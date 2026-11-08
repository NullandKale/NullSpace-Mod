package com.nullandkale.nullspace.gameplay.pads;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

public final class PadLifecycleGameTest
{
    @GameTest
    public void missingComputerAndChangedFloorReturnPadStatus(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var base = ctx.absolutePos(new BlockPos(96000, 85, 96000));
        var registered = pad(level, base, 8);
        ctx.assertTrue(PadRegistry.check(level, registered).state() == PadRegistry.State.READY, "Valid pad produces a ready result");
        var state = level.getBlockState(registered.computer());
        level.setBlock(registered.computer(), Blocks.AIR.defaultBlockState(), 18);
        var missing = PadRegistry.check(level, registered);
        ctx.assertTrue(missing.state() == PadRegistry.State.UNAVAILABLE && missing.detail().equals("Pad computer is missing: Lifecycle") && PadRegistry.inspectState(level.getServer(), registered).state() == PadRegistry.State.UNAVAILABLE, "Missing controller is a returned status for both validation and destination inspection");
        level.setBlock(registered.computer(), state, 18);
        level.setBlock(base.offset(3, 0, 3), Blocks.AIR.defaultBlockState(), 18);
        var changed = PadRegistry.check(level, registered);
        ctx.assertTrue(changed.state() == PadRegistry.State.UNAVAILABLE && changed.detail().startsWith("Pad changed:"), "An incomplete floor returns a diagnostic without throwing");
        level.setBlock(base.offset(3, 0, 3), Blocks.DIRT.defaultBlockState(), 18);
        ctx.assertTrue(PadRegistry.check(level, registered).state() == PadRegistry.State.READY, "Repair restores the normal ready result");
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void completedRingExpansionAutomaticallyRetainsRegistration(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var repo = PadRepository.get(level.getServer());
        var base = ctx.absolutePos(new BlockPos(93000, 85, 93000));
        var original = pad(level, base, 8);
        var registered = new PadRegistry.Pad(original.id(), "Auto " + original.id().substring(0, 8), original.dimension(), original.computer(), original.bounds());
        var next = new ArrayList<>(repo.all());
        next.add(registered);
        repo.replace(next);
        var helm = base.offset(2, 1, 2);
        level.setBlock(helm, ModBlocks.COMPUTER.defaultBlockState(), 18);
        var vessel = ShipTopology.resolve(level, helm);
        // Extend the north/west edges and build a larger outer ring; keep the old east/south ring.
        for (int x = -1; x <= 12; x++)
            for (int z = -1; z <= 12; z++)
                if (x > 8 || z > 8)
                    level.setBlock(base.offset(x, 0, z), (x == -1 || z == -1 || x == 12 || z == 12 ? Blocks.COBBLESTONE : Blocks.DIRT).defaultBlockState(), 18);
        ctx.runAfterDelay(12, () ->
        {
            ctx.assertTrue(repo.id(registered.id()).bounds().equals(registered.bounds()), "A surviving complete inner ring still defines the original pad");
            for (int x = 0; x < 12; x++)
                for (int z = 0; z < 12; z++)
                    if (x == 8 || z == 8)
                        level.setBlock(base.offset(x, 0, z), Blocks.DIRT.defaultBlockState(), 18);
            ctx.runAfterDelay(12, () ->
            {
                var expanded = repo.id(registered.id());
                ctx.assertTrue(expanded.bounds().width() == 12 && expanded.bounds().depth() == 12 && expanded.id().equals(registered.id()) && expanded.name().equals(registered.name()) && expanded.computer().equals(registered.computer()), "Removing the inner ring publishes the expanded footprint without manual" + " registration");
                ctx.assertTrue(PadRegistry.below(level, base.offset(10, 2, 10)).orElseThrow().equals(expanded), "The ship computer resolves the newly added area");
                PadRegistry.validate(level, expanded);
                var store = WorldStore.get(level.getServer());
                var currentVessel = store.repository().find(vessel.getStringOr("id", ""));
                ctx.assertTrue(currentVessel != null && currentVessel.getIntOr("width", 0) == 12 && currentVessel.getIntOr("depth", 0) == 12 && store.at(level, base.offset(10, 2, 10)) == currentVessel, "The existing landed ship allocation grows without losing its identity");
                repo.reload();
                ctx.assertTrue(repo.id(expanded.id()).equals(expanded), "Automatic resize survives registry reload");
                level.setBlock(base.offset(12, 0, 12), Blocks.AIR.defaultBlockState(), 18);
                ctx.runAfterDelay(12, () ->
                {
                    ctx.assertTrue(repo.id(expanded.id()).equals(expanded), "Incomplete construction cannot overwrite the last valid registration");
                    ctx.succeed();
                });
            });
        });
    }

    @GameTest
    public void interactionRepairsASavedStaleFootprintWithoutReregistering(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var repo = PadRepository.get(level.getServer());
        var base = ctx.absolutePos(new BlockPos(94000, 85, 94000));
        var actual = pad(level, base, 12);
        var stale = new PadRegistry.Pad(actual.id(), "Stale " + actual.id().substring(0, 8), actual.dimension(), actual.computer(), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7));
        var next = new ArrayList<>(repo.all());
        next.add(stale);
        repo.replace(next);
        var found = PadRegistry.below(level, base.offset(10, 2, 10)).orElseThrow();
        ctx.assertTrue(found.id().equals(stale.id()) && found.bounds().equals(actual.bounds()), "Opening a ship computer immediately repairs a pre-existing stale registration");
        ctx.succeed();
    }

    private static PadRegistry.Pad pad(ServerLevel level, BlockPos base, int size)
    {
        for (int x = -1; x <= size; x++)
            for (int z = -1; z <= size; z++)
                level.setBlock(base.offset(x, 0, z), (x == -1 || z == -1 || x == size || z == size ? Blocks.COBBLESTONE : Blocks.DIRT).defaultBlockState(), 18);
        for (int x = (base.getX() - 1) >> 4; x <= (base.getX() + size) >> 4; x++)
            for (int z = (base.getZ() - 1) >> 4; z <= (base.getZ() + size) >> 4; z++)
                level.setChunkForced(x, z, true);
        var controller = base.offset(-1, 1, 2);
        level.setBlock(controller, PadComputers.BLOCK.defaultBlockState(), 18);
        return new PadRegistry.Pad(UUID.randomUUID().toString(), "Lifecycle", level.dimension().identifier().toString(), controller, new PadScanner.Pad(base.getX(), base.getX() + size - 1, base.getY(), base.getZ(), base.getZ() + size - 1));
    }

    @GameTest
    public void rejectedRegistryWritesAndMalformedReloadsPreserveEveryIndex(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var repo = PadRepository.get(server);
        var p = pad(ctx.getLevel(), ctx.absolutePos(new BlockPos(90000, 85, 90000)), 8);
        var next = new ArrayList<>(repo.all());
        next.add(p);
        repo.replace(next);
        long revision = repo.revision();
        var before = repo.all();
        var file = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-pads.json");
        byte[] durable = Files.readAllBytes(file);
        for (var invalid : List.of(p, new PadRegistry.Pad(UUID.randomUUID().toString(), "Duplicate controller", p.dimension(), p.computer(), p.bounds())))
        {
            var duplicate = new ArrayList<>(before);
            duplicate.add(invalid);
            boolean rejected = false;
            try
            {
                repo.replace(duplicate);
            }
            catch (RuntimeException expected)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected && Arrays.equals(durable, Files.readAllBytes(file)) && repo.revision() == revision && repo.id(p.id()).equals(p) && repo.controller(p.dimension(), p.computer()).equals(p) && repo.dimension(p.dimension()).contains(p), "Rejected registration preserves disk, revision and all indexes");
        }
        try
        {
            Files.writeString(file, "[broken json");
            boolean rejected = false;
            try
            {
                repo.reload();
            }
            catch (IllegalStateException expected)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected && repo.all().equals(before) && repo.revision() == revision && repo.id(p.id()).equals(p) && repo.controller(p.dimension(), p.computer()).equals(p), "Malformed reload cannot publish partial state");
        }
        finally
        {
            Files.write(file, durable);
        }
        var temporary = file.resolveSibling("nullspace-pads.json.tmp");
        Files.deleteIfExists(temporary);
        Files.createDirectory(temporary);
        try
        {
            boolean rejected = false;
            try
            {
                repo.replace(List.of());
            }
            catch (java.io.IOException expected)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected && repo.all().equals(before) && repo.revision() == revision && Arrays.equals(durable, Files.readAllBytes(file)), "A real temporary-file I/O failure cannot change published indexes");
        }
        finally
        {
            Files.delete(temporary);
        }
        repo.reload();
        ctx.assertTrue(repo.revision() == revision + 1 && repo.all().equals(before), "A valid reload publishes one new registry generation");
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:pad_lifecycle", maxTicks = 160)
    public void registeredPadsRefreshThroughEditsExpiryReloadAndMissingControllers(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var repo = PadRepository.get(server);
        var base = ctx.absolutePos(new BlockPos(91000, 85, 91000));
        var large = pad(level, base, 32);
        var upper = pad(level, base.above(48), 8);
        var other = pad(level, base.offset(64, 0, 0), 8);
        var next = new ArrayList<>(repo.all());
        next.addAll(List.of(large, upper, other));
        repo.replace(next);
        for (var p : List.of(large, upper, other))
            ctx.assertTrue(PadRegistry.state(server, p).state() == PadRegistry.State.UNKNOWN, "New registered pads queue inspection");
        boolean[] awaitingInitialInspection = {true};
        ctx.onEachTick(() ->
        {
            // Keep advisory inspection jobs requested until they finish under the global read budget.
            // This environment isolates timed expiry/reload assertions from other registry writers.
            if (awaitingInitialInspection[0])
                for (var p : List.of(large, upper, other))
                    PadRegistry.state(server, p);
            ctx.assertTrue(PadStatus.lastReads(server) <= PadStatus.READS_PER_TICK, "All registered inspections respect the global budget");
        });
        ctx.runAfterDelay(2, () ->
        {
            for (var p : List.of(large, upper, other))
                PadRegistry.state(server, p);
        });
        ctx.runAfterDelay(28, () ->
        {
            awaitingInitialInspection[0] = false;
            ctx.assertTrue(PadRegistry.state(server, large).state() == PadRegistry.State.OCCUPIED && PadRegistry.state(server, upper).state() == PadRegistry.State.READY && PadRegistry.state(server, other).state() == PadRegistry.State.READY, "Distinct pads and stacked launch columns inspect independently: " + PadRegistry.state(server, large) + " / " + PadRegistry.state(server, upper) + " / " + PadRegistry.state(server, other));
            var obstruction = base.offset(67, 10, 3);
            level.setBlock(obstruction, Blocks.GLASS.defaultBlockState(), 18);
            ctx.assertTrue(PadRegistry.state(server, other).state() == PadRegistry.State.UNKNOWN, "The real block-change hook invalidates a ready pad");
            ctx.assertTrue(PadRegistry.state(server, upper).state() == PadRegistry.State.READY, "Edits outside another pad cannot invalidate it");
            ctx.runAfterDelay(8, () ->
            {
                ctx.assertTrue(PadRegistry.state(server, other).state() == PadRegistry.State.OCCUPIED, "Reinspection sees the new obstruction");
                level.setBlock(obstruction, Blocks.AIR.defaultBlockState(), 18);
                PadRegistry.state(server, other);
                ctx.runAfterDelay(8, () ->
                {
                    ctx.assertTrue(PadRegistry.state(server, other).state() == PadRegistry.State.READY, "Removing the obstruction restores readiness");
                    ctx.runAfterDelay(42, () ->
                    {
                        ctx.assertTrue(PadRegistry.state(server, other).state() == PadRegistry.State.UNKNOWN, "Expired advisory results must be inspected again");
                        ctx.runAfterDelay(8, () ->
                        {
                            ctx.assertTrue(PadRegistry.state(server, other).state() == PadRegistry.State.READY, "Expired work eventually refreshes");
                            try
                            {
                                repo.reload();
                            }
                            catch (RuntimeException failure)
                            {
                                throw failure;
                            }
                            ctx.assertTrue(PadRegistry.state(server, other).state() == PadRegistry.State.UNKNOWN, "Registry reload invalidates advisory snapshots");
                            level.setBlock(other.computer(), Blocks.AIR.defaultBlockState(), 18);
                            PadRegistry.state(server, other);
                            ctx.runAfterDelay(8, () ->
                            {
                                ctx.assertTrue(PadRegistry.state(server, other).state() == PadRegistry.State.UNAVAILABLE, "Removed controllers cannot remain ready");
                                ctx.succeed();
                            });
                        });
                    });
                });
            });
        });
    }

    @GameTest(maxTicks = 40)
    public void queuedColdPadRemainsUnknownAndDoesNotLoadTerrain(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        var base = new BlockPos(7600000, 100, 7600000);
        var controller = base.offset(-1, 1, 2);
        var p = new PadRegistry.Pad(UUID.randomUUID().toString(), "Cold", TravelService.SPACE.identifier().toString(), controller, new PadScanner.Pad(base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31));
        var next = new ArrayList<>(PadRepository.get(server).all());
        next.add(p);
        PadRepository.get(server).replace(next);
        PadRegistry.state(server, p);
        ctx.runAfterDelay(8, () ->
        {
            ctx.assertTrue(PadRegistry.state(server, p).state() == PadRegistry.State.UNKNOWN && !level.hasChunkAt(controller) && !level.hasChunkAt(base.offset(31, 0, 31)), "Queued inspection of remote terrain remains UNKNOWN without generating chunks");
            ctx.succeed();
        });
    }
}
