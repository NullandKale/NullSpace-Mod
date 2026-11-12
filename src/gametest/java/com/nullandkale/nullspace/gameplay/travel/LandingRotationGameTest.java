package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.save.Journals;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.pads.*;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.*;
import com.nullandkale.nullspace.model.flight.ShipHeading;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

public final class LandingRotationGameTest
{
    static PadRegistry.Pad pad(ServerLevel level, BlockPos base, int width, int depth, Direction side, String name) throws java.io.IOException
    {
        EntityTravelGameTest.hold(level, base.getX() - 2, base.getX() + width + 2, base.getZ() - 2, base.getZ() + depth + 2, true);
        for (int x = -1; x <= width; x++)
            for (int z = -1; z <= depth; z++)
                level.setBlock(base.offset(x, 0, z), (x == -1 || x == width || z == -1 || z == depth ? Blocks.COBBLESTONE : Blocks.DIRT).defaultBlockState(), 18);
        var at = switch (side)
        {
            case WEST -> base.offset(-1, 1, depth / 2);
            case EAST -> base.offset(width, 1, depth / 2);
            case NORTH -> base.offset(width / 2, 1, -1);
            default -> base.offset(width / 2, 1, depth);
        };
        // Deliberately point the computer independently of its ring edge.
        level.setBlock(at, PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.NORTH), 18);
        PadComputers.register(level, at, new PadScanner.Pad(base.getX(), base.getX() + width - 1, base.getY(), base.getZ(), base.getZ() + depth - 1), name, false);
        return PadRegistry.read(level.getServer()).stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static void completed(GameTestHelper ctx, TravelService.Result result)
    {
        ctx.assertTrue(result.outcome() == TravelService.Outcome.COMPLETED, result.message());
    }

    @GameTest(maxTicks = 500)
    public void northEdgeFacesTheEntranceNorth(GameTestHelper ctx) throws Exception
    {
        edge(ctx, Direction.NORTH, 110);
    }

    @GameTest(maxTicks = 500)
    public void eastEdgeFacesTheEntranceEast(GameTestHelper ctx) throws Exception
    {
        edge(ctx, Direction.EAST, 111);
    }

    @GameTest(maxTicks = 500)
    public void southEdgeFacesTheEntranceSouth(GameTestHelper ctx) throws Exception
    {
        edge(ctx, Direction.SOUTH, 112);
    }

    @GameTest(maxTicks = 500)
    public void westEdgeFacesTheEntranceWest(GameTestHelper ctx) throws Exception
    {
        edge(ctx, Direction.WEST, 113);
    }

    private static void edge(GameTestHelper ctx, Direction side, int slot) throws Exception
    {
        var source = EntityTravelGameTest.referenceBed(ctx, slot);
        var destination = pad(ctx.getLevel(), new BlockPos(370000 + slot * 64, 80, 360000), 8, 8, side, "Rotate " + side);
        ctx.runAfterDelay(20, () ->
        {
            var bed = source.get();
            var ship = bed.ship();
            var base = WorldStore.pos(ship, "base");
            var dock = base.offset(3, 1, 0);
            bed.level().setBlock(dock, ModBlocks.DOCKING_BASE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 18);
            bed.level().setBlock(base.offset(5, 2, 4), ModBlocks.BATTERY.defaultBlockState(), 18);
            SystemsUpgrade.upgrade(bed.level(), ship);
            ShipSystems.tick(bed.level(), ship);
            ctx.assertTrue(ShipHeading.forward(bed.level(), ship) == Direction.EAST, "Fixture bow differs from its north entrance");
            completed(ctx, TravelService.transfer(bed.player(), bed.helm(), "", ShipService.preflight(bed.level(), bed.helm())));
            ship = bed.ship();
            var orbitBase = WorldStore.pos(ship, "base");
            var scan = ShipService.preflight(bed.space(), bed.helm()).scan();
            var blocks = new LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
            scan.blocks().forEach(p -> blocks.put(p, bed.space().getBlockState(p)));
            var oldPlayer = bed.player().position();
            float oldYaw = bed.player().getYRot();
            var offline = new CompoundTag();
            var offlineId = UUID.randomUUID();
            offline.putString("id", offlineId.toString());
            offline.putDouble("x", oldPlayer.x - orbitBase.getX());
            offline.putDouble("y", oldPlayer.y - orbitBase.getY());
            offline.putDouble("z", oldPlayer.z - orbitBase.getZ());
            offline.putFloat("yaw", 19);
            ship.getListOrEmpty("crew").add(offline);
            for (var t : ship.getCompoundOrEmpty("systems").getListOrEmpty("devices"))
                if (((CompoundTag) t).getStringOr("type", "").contains("battery"))
                    ((CompoundTag) t).putInt("energy", 37);
            var ticksAt = scan.blocks().iterator().next();
            bed.space().scheduleTick(ticksAt, bed.space().getBlockState(ticksAt).getBlock(), 1000);
            completed(ctx, TravelService.transfer(bed.player(), bed.helm(), "id:" + destination.id(), ShipService.preflight(bed.space(), bed.helm())));
            var arrived = bed.ship();
            var ports = DockingPorts.ports(bed.level(), arrived);
            ctx.assertTrue(ports.size() == 1 && ports.getFirst().facing() == side, "Entrance faces the computer's ring edge, independently of bow and computer facing");
            CompoundTag journal = null;
            try
            {
                for (var f : Journals.list(WorldStore.get(bed.level().getServer()).directory))
                {
                    var tag = WorldStore.read(f);
                    if (tag.getStringOr("operation", "").equals("landing") && tag.getListOrEmpty("ships").stream().anyMatch(t -> t.asString().orElse("").equals(bed.shipId())))
                        journal = tag;
                }
            }
            catch (java.io.IOException e)
            {
                throw new java.io.UncheckedIOException(e);
            }
            ctx.assertTrue(journal != null, "The MOVE retains its transform for recovery");
            var transform = ShipTransform.fromJournal(journal);
            for (var entry : blocks.entrySet())
                ctx.assertTrue(bed.level().getBlockState(transform.apply(entry.getKey())).equals(entry.getValue().rotate(transform.rotation())), "Every hull block rotates with its state: " + entry.getKey());
            ctx.assertTrue(bed.player().position().distanceTo(transform.apply(oldPlayer)) < .001, "Player follows the hull without a cell offset");
            ctx.assertTrue(Math.abs(bed.player().getYRot() - transform.yaw(oldYaw)) < .001, "Player heading follows the quarter turn");
            var target = WorldStore.get(bed.level().getServer()).list("targets").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(offlineId.toString())).findFirst().orElseThrow();
            ctx.assertTrue(Math.abs(target.getDoubleOr("x", 0) - transform.apply(oldPlayer).x) < .001 && target.getFloatOr("yaw", 0) == transform.yaw(19), "Offline arrival and facing follow the hull");
            var chestOld = blocks.keySet().stream().filter(p -> blocks.get(p).is(Blocks.CHEST)).findFirst().orElseThrow();
            var chest = (ChestBlockEntity) bed.level().getBlockEntity(transform.apply(chestOld));
            ctx.assertTrue(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7, "Chest cargo is unchanged");
            ctx.assertTrue(bed.level().getBlockTicks().hasScheduledTick(transform.apply(ticksAt), blocks.get(ticksAt).getBlock()), "Scheduled work follows rotated coordinates");
            var afterBlocks = new LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
            blocks.keySet().forEach(p -> afterBlocks.put(transform.apply(p), bed.level().getBlockState(transform.apply(p))));
            completed(ctx, TravelService.transfer(bed.player(), bed.helm(), "", ShipService.preflight(bed.level(), bed.helm())));
            completed(ctx, TravelService.transfer(bed.player(), bed.helm(), "id:" + destination.id(), ShipService.preflight(bed.space(), bed.helm())));
            afterBlocks.forEach((at, state) -> ctx.assertTrue(bed.level().getBlockState(at).equals(state), "Repeated landing has no orientation or placement drift"));
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:landing_rotation_recovery", maxTicks = 500)
    public void interruptedRotatedLandingCanBeRestoredWithoutLeavingItsStagedHull(GameTestHelper ctx) throws Exception
    {
        var fixture = EntityTravelGameTest.referenceBed(ctx, 176);
        var target = pad(ctx.getLevel(), new BlockPos(398000, 80, 360000), 8, 8, Direction.EAST, "Rotated rescue target");
        ctx.runAfterDelay(20, () ->
        {
            var bed = fixture.get();
            var server = bed.level().getServer();
            var store = WorldStore.get(server);
            completed(ctx, TravelService.transfer(bed.player(), bed.helm(), "", ShipService.preflight(bed.level(), bed.helm())));
            EntityTravelGameTest.holdOrbit(bed.space(), WorldStore.pos(bed.ship(), "base").getX() / TravelService.SPACE_SPACING, true);
            ctx.runAfterDelay(10, () -> restoreLanding(ctx, bed, target));
        });
    }

    private static void restoreLanding(GameTestHelper ctx, EntityTravelGameTest.Bed bed, PadRegistry.Pad target)
    {
        var server = bed.level().getServer();
        var store = WorldStore.get(server);
            var oldController = bed.helm();
            var previous = TravelService.failureInjector;
            boolean[] injected = {false};
            try
            {
                TravelService.failureInjector = stage ->
                {
                    if (stage.equals("SOURCE_CLEARED"))
                    {
                        injected[0] = true;
                        throw new IllegalStateException("rotated landing interrupted");
                    }
                };
                TravelService.transfer(bed.player(), bed.helm(), "id:" + target.id(), ShipService.preflight(bed.space(), bed.helm()));
                ctx.fail("The interruption must stop the MOVE");
            }
            catch (IllegalStateException expected)
            {
                ctx.assertTrue(expected.getMessage().equals("rotated landing interrupted"), "Only the intended failure is accepted");
            }
            finally
            {
                TravelService.failureInjector = previous;
            }
            ctx.assertTrue(injected[0], "The rotated hull was staged and the source cleared");
            try
            {
                java.nio.file.Path pending = null;
                CompoundTag journal = null;
                for (var file : Journals.list(store.directory))
                {
                    var tag = WorldStore.read(file);
                    if (tag.getStringOr("stage", "").equals("SOURCE_CLEARED") && tag.getListOrEmpty("ships").stream().anyMatch(t -> t.asString().orElse("").equals(bed.shipId())))
                    {
                        pending = file;
                        journal = tag;
                    }
                }
                ctx.assertTrue(pending != null, "The interrupted MOVE is retained");
                var moved = ShipTransform.fromJournal(journal).apply(oldController);
                ctx.assertTrue(bed.level().getBlockState(moved).is(ModBlocks.COMPUTER), "The rotated destination is present before rescue");
                TransferRecovery.setAside(server, pending, new IllegalStateException("operator restore fixture"));
                var entry = UnresolvedJournals.get(server, journal.getStringOr("id", ""));
                ctx.assertTrue(UnresolvedJournals.areaBusy(bed.level(), ShipTransform.destinationBounds(journal)), "The exact rotated destination is reserved in quarantine");
                JournalRestore.restore(server, entry, "space");
                var restored = bed.ship();
                ctx.assertTrue(bed.space().getBlockState(WorldStore.pos(restored, "controller")).is(ModBlocks.COMPUTER), "Operator restore establishes one authoritative source-oriented hull");
                ctx.assertTrue(bed.level().getBlockState(moved).isAir(), "The rotated staged copy is removed");
                ctx.assertTrue(UnresolvedJournals.get(server, entry.id()) == null, "Successful rescue releases the original quarantine");
            }
            catch (java.io.IOException failure)
            {
                throw new java.io.UncheckedIOException(failure);
            }
            ctx.succeed();
    }

    @GameTest(maxTicks = 500)
    public void rectangularHullFitsAfterTurningAndWrongOrientationRefusesBeforeMutation(GameTestHelper ctx) throws Exception
    {
        var fixture = EntityTravelGameTest.catalogBed(ctx, 175, "rivet_bolt");
        var preset = ShipBlueprints.get("rivet_bolt");
        var wrong = pad(ctx.getLevel(), new BlockPos(390000, 80, 360000), preset.depth(), preset.width(), Direction.NORTH, "Wrong rotation footprint");
        var right = pad(ctx.getLevel(), new BlockPos(390128, 80, 360000), preset.depth(), preset.width(), Direction.EAST, "Swapped rotation footprint");
        ctx.runAfterDelay(20, () ->
        {
            var bed = fixture.get();
            var stand = bed.floor().getFirst();
            bed.player().teleportTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5);
            completed(ctx, TravelService.transfer(bed.player(), bed.helm(), "", ShipService.preflight(bed.level(), bed.helm())));
            var sourceController = bed.helm();
            var no = TravelService.transfer(bed.player(), sourceController, "id:" + wrong.id(), ShipService.preflight(bed.space(), sourceController));
            ctx.assertTrue(no.outcome() == TravelService.Outcome.REJECTED && no.message().contains("fit"), "A non-fitting rotated footprint is rejected");
            ctx.assertTrue(bed.space().getBlockState(sourceController).is(ModBlocks.COMPUTER), "Refusal leaves the source intact");
            completed(ctx, TravelService.transfer(bed.player(), bed.helm(), "id:" + right.id(), ShipService.preflight(bed.space(), bed.helm())));
            ctx.assertTrue(DockingPorts.ports(bed.level(), bed.ship()).getFirst().facing() == Direction.EAST, "A rectangular hull lands on swapped pad dimensions");
            ctx.succeed();
        });
    }
}
