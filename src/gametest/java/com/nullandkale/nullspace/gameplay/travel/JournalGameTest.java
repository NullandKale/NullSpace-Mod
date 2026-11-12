package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.Journals;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.testing.ShipTestFixture;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Each test runs in its own environment, so its own batch: they use the global failure hook and recover every journal in
 * the world, which would disturb tests running at the same time.
 *
 * <p>The journal lifecycle on a real server: finished journals are pruned, a rollback touches only its own records, and a
 * journal that cannot be recovered is set aside, locked and listed for an operator.
 */
public final class JournalGameTest
{
    /** A registered ship on its own pad: the reference build with machinery, as the crash tests use. */
    private static BlockPos ship(GameTestHelper ctx, BlockPos base, String pad) throws java.io.IOException
    {
        var planet = ctx.getLevel().getServer().overworld();
        for (int x = base.getX() >> 4; x <= (base.getX() >> 4) + 2; x++)
            for (int z = base.getZ() >> 4; z <= (base.getZ() >> 4) + 2; z++)
                planet.setChunkForced(x, z, true);
        ShipTestFixture.build(planet, base);
        PadComputers.register(planet, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), pad, false);
        for (int x = 2; x <= 5; x++)
            planet.setBlock(base.offset(x, 3, 5), new Block[]{ModBlocks.GENERATOR, ModBlocks.BATTERY, ModBlocks.AIR_SUPPLY, ModBlocks.LOCAL_SCANNER}[x - 2].defaultBlockState(), 18);
        var controller = base.offset(6, 3, 3);
        SystemsUpgrade.upgrade(planet, ShipTopology.resolve(planet, controller));
        return controller;
    }

    /** Takes a ship out of the world and the store, so the space slot it held is free again for other tests. */
    private static void retire(net.minecraft.server.MinecraftServer server, String shipId)
    {
        var store = WorldStore.get(server);
        var record = store.repository().find(shipId);
        if (record == null)
            return;
        var bounds = WorldStore.bounds(record);
        var level = WorldStore.level(server, record.getStringOr("dimension", ""));
        for (var entity : com.nullandkale.nullspace.gameplay.ship.structure.HullEntities.inBounds(level, bounds))
            if (!(entity instanceof net.minecraft.world.entity.player.Player))
                entity.discard();
        TransferRecovery.clear(level, bounds.min(), bounds.max());
        store.ships().remove(record);
        store.requestSave();
    }

    /** Journals of this ship or operation that are not finished. Other tests in the same world leave their own. */
    private static boolean unfinished(WorldStore store, String ship, String operation) throws java.io.IOException
    {
        for (var file : Journals.list(store.directory))
        {
            var name = Journals.parse(file);
            if (name == null || name.finished())
                continue;
            var journal = WorldStore.read(file);
            if (journal.getStringOr("operation", "").equals(operation) && (ship.isEmpty() || journal.getListOrEmpty("ships").stream().anyMatch(t -> t.asString().orElse("").equals(ship))))
                return true;
        }
        return false;
    }

    @GameTest(environment = "nullspace:journals_prune", maxTicks = 900)
    public void finishedJournalsArePrunedOnceBothStoreCopiesIncludeThem(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var planet = server.overworld();
        var base = new BlockPos(8192, 96, 8192);
        var controller = ship(ctx, base, "Journal Prune");
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
        var store = WorldStore.get(server);
        var shipId = new String[]{store.at(planet, controller).getStringOr("id", "")};
        var started = new boolean[1];
        ctx.onEachTick(() ->
        {
            if (started[0])
                return;
            try
            {
                var result = TravelService.transfer(player, controller, "", ShipService.preflight(planet, controller));
                started[0] = result.outcome() == TravelService.Outcome.COMPLETED;
                if (!started[0])
                    ctx.assertTrue(result.message().contains("loading"), "Launch must complete: " + result.message());
            }
            catch (IllegalArgumentException loading)
            {
                ctx.assertTrue(loading.getMessage().contains("loading"), "Launch must complete: " + loading.getMessage());
                return;
            }
            if (!started[0])
                return;
            try
            {
                var finished = new ArrayList<Path>();
                for (var candidate : Journals.list(store.directory))
                    if (Journals.parse(candidate) != null && Journals.parse(candidate).finished() && WorldStore.read(candidate).getListOrEmpty("ships").stream().anyMatch(t -> t.asString().orElse("").equals(shipId[0])))
                        finished.add(candidate);
                ctx.assertTrue(!finished.isEmpty(), "A committed move leaves a finished journal, named with its save number");
                ctx.assertTrue(!unfinished(store, shipId[0], "launch"), "No unfinished journal of this launch remains after a commit");
                var file = finished.getLast();
                long covers = Journals.parse(file).saveNumber();
                ctx.assertTrue(covers <= store.saveNumber(), "The commit was saved before it was marked finished");
                var journal = WorldStore.read(file);
                ctx.assertTrue(journal.getIntOr("version", 0) == Journals.VERSION && journal.getStringOr("kind", "").equals("MOVE") && journal.getStringOr("operation", "").equals("launch"), "The header says what the operation was");
                ctx.assertTrue(!journal.contains("before") && !journal.contains("after"), "A journal no longer holds two whole copies of the store");
                ctx.assertTrue(!journal.getListOrEmpty("records").isEmpty(), "It holds the records the move changed");
                ctx.assertTrue(journal.getCompoundOrEmpty("template").getListOrEmpty("blocks").size() < 32 * 32 * 32 / 4, "It holds the hull, not the whole box");
                // One more save makes both store copies include it, so it is deleted.
                WorldStore.worldSaved(server, true);
                ctx.assertTrue(!Files.exists(file), "Once both copies include it the finished journal is deleted");
                store.document("journalPruneProbe", null);
                store.save();
                retire(server, shipId[0]);
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:journals_rollback", maxTicks = 900)
    public void aRollbackKeepsEveryChangeTheMoveDidNotMake(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var planet = server.overworld();
        var space = server.getLevel(TravelService.SPACE);
        var base = new BlockPos(8192, 96, 8448);
        var otherBase = new BlockPos(8192, 96, 8704);
        var controller = ship(ctx, base, "Journal Mover");
        var otherController = ship(ctx, otherBase, "Journal Bystander");
        var store = WorldStore.get(server);
        var bystander = store.at(planet, otherController).getStringOr("id", "");
        var moverId = store.at(planet, controller).getStringOr("id", "");
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
        var done = new boolean[1];
        ctx.onEachTick(() ->
        {
            if (done[0])
                return;
            // The journal is written before the first world change. A failure after the source is cleared and before the
            // commit is rolled back, and a change made to another ship in between must survive that rollback.
            TravelService.failureInjector = stage ->
            {
                if (stage.equals("SOURCE_CLEARED"))
                {
                    store.repository().find(bystander).putString("marker", "made-during-the-move");
                    throw new IllegalStateException("injected failure");
                }
            };
            try
            {
                var result = TravelService.transfer(player, controller, "", ShipService.preflight(planet, controller));
                if (result.outcome() == TravelService.Outcome.WAITING)
                    return; // Passengers are still loading: try again next tick.
                ctx.fail("The injected failure must stop the move, but it returned " + result.outcome() + ": " + result.message());
            }
            catch (IllegalArgumentException loading)
            {
                TravelService.failureInjector = stage ->
                {};
                return;
            }
            catch (IllegalStateException expected)
            {
                done[0] = true;
            }
            finally
            {
                TravelService.failureInjector = stage ->
                {};
            }
            try
            {
                // Where the copy was staged is in the journal: the controller's place plus the move's offset.
                BlockPos staged = null;
                for (var file : Journals.list(store.directory))
                {
                    var open = Journals.parse(file);
                    var journal = open == null || open.finished() ? null : WorldStore.read(file);
                    if (journal != null && journal.getListOrEmpty("ships").stream().anyMatch(t -> t.asString().orElse("").equals(moverId)))
                        staged = controller.offset(BlockPos.of(journal.getLongOr("delta", 0)));
                }
                ctx.assertTrue(staged != null && space.getBlockState(staged).is(ModBlocks.COMPUTER), "The copy was staged at the destination before the failure");
                TransferRecovery.recover(server);
                ctx.assertTrue(planet.getBlockState(controller).is(ModBlocks.COMPUTER), "The rolled-back ship is back at its source");
                ctx.assertTrue(space.getBlockState(staged).isAir(), "The destination copy is gone");
                ctx.assertTrue(store.at(planet, controller) != null, "The ship's record is back where it started");
                ctx.assertTrue(store.repository().find(bystander).getStringOr("marker", "").equals("made-during-the-move"), "A rollback restores the journal's own records only; another ship's change survives");
                ctx.assertTrue(!unfinished(store, moverId, "launch"), "The journal is finished, not left pending");
                ctx.assertTrue(UnresolvedJournals.all(server).stream().noneMatch(e -> e.ships().contains(moverId)), "Nothing about this ship is left unresolved");
                store.repository().find(bystander).remove("marker");
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
            ctx.succeed();
        });
    }

    private static CompoundTag fake(Path storeDirectory, String operation, List<String> ships, String dimension, BlockPos min, BlockPos max) throws java.io.IOException
    {
        var journal = Journals.create(storeDirectory, Journals.Kind.MOVE, operation, ships);
        journal.putString("source", dimension);
        journal.putString("target", dimension);
        journal.putLong("min", min.asLong());
        journal.putLong("max", max.asLong());
        journal.putLong("delta", BlockPos.ZERO.asLong());
        journal.put("template", new CompoundTag());
        journal.put("records", new ListTag());
        journal.putString("stage", "PREPARED");
        return journal;
    }

    @GameTest(environment = "nullspace:journals_set_aside", maxTicks = 900)
    public void journalsThatCannotBeRecoveredAreSetAsideLockedAndListed(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var planet = server.overworld();
        var store = WorldStore.get(server);
        var live = Journals.directory(store.directory);
        // 1. A dimension that is not loaded, as after a mod was removed: retried on every start, never fatal.
        var missing = fake(store.directory, "launch", List.of("ship-missing-dimension"), "nullspace:no_such_level", new BlockPos(0, 96, 0), new BlockPos(31, 127, 31));
        var missingFile = Journals.file(store.directory, missing);
        WorldStore.write(missingFile, missing);
        // 2. A file that cannot be read at all.
        long sequence = Journals.nextSequence(store.directory) + 1;
        var garbageFile = live.resolve(String.format("%08d-%s.nbt", sequence, UUID.randomUUID()));
        Files.write(garbageFile, new byte[]{1, 2, 3, 4});
        // 3. A journal from a newer version of the mod.
        var newer = fake(store.directory, "launch", List.of("ship-newer-version"), "minecraft:overworld", new BlockPos(0, 96, 0), new BlockPos(1, 97, 1));
        newer.putLong("sequence", sequence + 1);
        newer.putInt("version", 3);
        var newerFile = Journals.file(store.directory, newer);
        WorldStore.write(newerFile, newer);
        // 4. A journal whose hull cannot be restored: it waits for entities, then fails, and its area stays locked throughout.
        var min = new BlockPos(9300, 96, 9300);
        var broken = fake(store.directory, "landing", List.of("ship-broken-hull"), "minecraft:overworld", min, min.offset(31, 31, 31));
        broken.putLong("sequence", sequence + 2);
        var template = new CompoundTag();
        template.putString("not", "a template");
        var blocks = new ListTag();
        var block = new CompoundTag();
        block.putInt("state", 99);
        var pos = new ListTag();
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        block.put("pos", pos);
        blocks.add(block);
        template.put("blocks", blocks);
        broken.put("template", template);
        var brokenFile = Journals.file(store.directory, broken);
        WorldStore.write(brokenFile, broken);
        var brokenId = broken.getStringOr("id", "");

        TransferRecovery.recover(server); // Must not throw, whatever the journals hold.

        var missingEntry = UnresolvedJournals.get(server, missing.getStringOr("id", ""));
        ctx.assertTrue(missingEntry != null && missingEntry.state() == UnresolvedJournals.State.MISSING_DIMENSION && Files.exists(missingFile), "A missing dimension keeps the journal pending where it is");
        ctx.assertTrue(UnresolvedJournals.shipBusy(server, "ship-missing-dimension"), "Its ship stays locked");
        var quarantine = Journals.quarantine(store.directory);
        ctx.assertTrue(!Files.exists(garbageFile) && Files.exists(quarantine.resolve(garbageFile.getFileName())) && Files.exists(quarantine.resolve(garbageFile.getFileName() + ".reason.txt")), "An unreadable journal is moved to quarantine with a reason file");
        ctx.assertTrue(!Files.exists(newerFile) && Files.exists(quarantine.resolve(newerFile.getFileName())), "A journal from a newer mod version is set aside whole");
        var newerEntry = UnresolvedJournals.all(server).stream().filter(e -> e.ships().contains("ship-newer-version")).findFirst().orElse(null);
        ctx.assertTrue(newerEntry != null && newerEntry.state() == UnresolvedJournals.State.SET_ASIDE && newerEntry.reason().contains("newer"), "The reason names the cause");
        var area = new HullScan.Bounds(min.offset(5, 5, 5), min.offset(6, 6, 6));
        ctx.assertTrue(UnresolvedJournals.get(server, brokenId) != null && TransferLocks.areaBusy(planet, area), "A journal that is waiting or failing keeps its area locked");

        // The waiting journal is retried by itself every few ticks and fails once its entities are in.
        ctx.onEachTick(() ->
        {
            var entry = UnresolvedJournals.get(server, brokenId);
            if (entry == null || entry.state() != UnresolvedJournals.State.SET_ASIDE)
                return;
            try
            {
                ctx.assertTrue(Files.exists(quarantine.resolve(brokenFile.getFileName())), "The journal that could not restore its hull is in quarantine");
                ctx.assertTrue(TransferLocks.areaBusy(planet, area), "A journal set aside keeps its area locked");
                var source = server.createCommandSourceStack();
                server.getCommands().performPrefixedCommand(source, "nullspace recovery list");
                for (var each : UnresolvedJournals.all(server).stream().filter(e -> e.ships().stream().anyMatch(s -> s.startsWith("ship-"))).toList())
                    server.getCommands().performPrefixedCommand(source, "nullspace recovery discard " + each.id());
                ctx.assertTrue(UnresolvedJournals.all(server).stream().noneMatch(e -> e.ships().stream().anyMatch(s -> s.startsWith("ship-"))), "discard resolves every journal and releases its locks");
                ctx.assertTrue(!TransferLocks.areaBusy(planet, area), "The area is free again");
                ctx.assertTrue(!Files.exists(missingFile) && Files.exists(quarantine.resolve(brokenFile.getFileName() + ".discarded")), "A discarded journal is kept under another name");
                ctx.assertTrue(Journals.nextSequence(store.directory) > sequence + 2, "A discarded journal keeps its sequence number, so a new one never reuses it");
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:journals_build", maxTicks = 100)
    public void buildJournalsCommitOrRollBackBlocksAndRecordsTogether(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var store = WorldStore.get(server);
        var origin = new BlockPos(9600, 96, 9600);
        var region = new HullScan.Bounds(origin, origin.offset(3, 3, 3));
        for (int x = origin.getX() >> 4; x <= (origin.getX() + 3) >> 4; x++)
            for (int z = origin.getZ() >> 4; z <= (origin.getZ() + 3) >> 4; z++)
                level.getChunk(x, z);
        level.setBlock(origin, Blocks.GLASS.defaultBlockState(), 18);
        store.document("buildJournalProbe", StringTag.valueOf("before"));
        store.save();

        var intermediate = new CompoundTag[1];
        BuildJournal.run(level, "test-commit", List.of(), region, () ->
        {
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 18);
            level.setBlock(origin.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), 18);
            store.document("buildJournalProbe", StringTag.valueOf("committed"));
            var provisional = new CompoundTag();
            provisional.putString("id", "provisional-build-order");
            store.list("journalBuildOrders").add(provisional);
            store.save();
            intermediate[0] = store.snapshot();
            store.list("journalBuildOrders").remove(provisional);
            return null;
        });
        ctx.assertTrue(level.getBlockState(origin).is(Blocks.STONE) && level.getBlockState(origin.offset(1, 0, 0)).is(Blocks.STONE), "A committed build keeps its blocks");
        BuildJournal.flushPending(server);
        ctx.assertTrue(store.snapshot().getStringOr("buildJournalProbe", "").equals("committed"), "and its records");
        ctx.assertTrue(!unfinished(store, "", "test-commit"), "It leaves no unfinished journal");
        var committedFile = Journals.list(store.directory).stream().filter(p ->
        {
            try
            {
                return WorldStore.read(p).getStringOr("operation", "").equals("test-commit");
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
        }).findFirst().orElseThrow();
        var committedJournal = WorldStore.read(committedFile);
        com.nullandkale.nullspace.core.save.StoreRecords.apply(intermediate[0], com.nullandkale.nullspace.core.save.StoreRecords.decode(committedJournal.getListOrEmpty("records")), true);
        ctx.assertTrue(intermediate[0].getListOrEmpty("journalBuildOrders").isEmpty(), "Replay removes an order added and removed within one step, even if the fallback copy saved it");

        boolean failed = false;
        level.scheduleTick(origin, Blocks.STONE, 400, net.minecraft.world.ticks.TickPriority.HIGH);
        level.scheduleTick(origin, net.minecraft.world.level.material.Fluids.WATER, 400);
        try
        {
            BuildJournal.run(level, "test-failure", List.of(), region, () ->
            {
                level.setBlock(origin, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
                level.setBlock(origin.offset(2, 0, 0), Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
                store.document("buildJournalProbe", StringTag.valueOf("half-done"));
                store.save();
                try
                {
                    var pending = Journals.list(store.directory).stream().filter(p -> Journals.parse(p) != null && !Journals.parse(p).finished()).filter(p ->
                    {
                        try
                        {
                            return WorldStore.read(p).getStringOr("operation", "").equals("test-failure");
                        }
                        catch (java.io.IOException error)
                        {
                            throw new java.io.UncheckedIOException(error);
                        }
                    }).findFirst().orElseThrow();
                    var saved = WorldStore.read(pending);
                    ctx.assertTrue(saved.getListOrEmpty("records").stream().anyMatch(t -> ((CompoundTag) t).getStringOr("top", "").equals("buildJournalProbe") && ((CompoundTag) t).getStringOr("before", "").equals("committed")), "Rollback records are durable before an intermediate store save");
                }
                catch (java.io.IOException error)
                {
                    throw new java.io.UncheckedIOException(error);
                }
                throw new IllegalStateException("injected build failure");
            });
        }
        catch (IllegalStateException expected)
        {
            failed = true;
        }
        ctx.assertTrue(failed, "The failure reaches the caller");
        ctx.assertTrue(level.getBlockState(origin).is(Blocks.STONE) && level.getBlockState(origin.offset(2, 0, 0)).isAir() && level.getBlockState(origin.offset(1, 0, 0)).is(Blocks.STONE), "A failed build puts the blocks back exactly");
        ctx.assertTrue(store.snapshot().getStringOr("buildJournalProbe", "").equals("committed"), "and the records");
        ctx.assertTrue(!unfinished(store, "", "test-failure"), "and nothing is left unresolved");
        ctx.assertTrue(level.getBlockTicks().hasScheduledTick(origin, Blocks.STONE) && level.getFluidTicks().hasScheduledTick(origin, net.minecraft.world.level.material.Fluids.WATER), "Rollback restores scheduled block and fluid work after clearing the build footprint");
        var distant = origin.offset(64, 0, 0);
        level.getChunk(distant);
        var unrelated = origin.offset(65, 0, 0);
        level.setBlock(unrelated, Blocks.EMERALD_BLOCK.defaultBlockState(), 18);
        boolean nestedFailed = false;
        try
        {
            BuildJournal.run(level, "test-nested-build", List.of(), List.of(origin), () ->
            {
                level.setBlock(origin, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
                BuildJournal.run(level, "test-child-build", List.of(), List.of(origin, distant), () ->
                {
                    level.setBlock(origin, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
                    level.setBlock(distant, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
                    store.document("buildJournalProbe", StringTag.valueOf("nested"));
                    return null;
                });
                throw new IllegalStateException("outer build failed");
            });
        }
        catch (IllegalStateException expected)
        {
            nestedFailed = true;
        }
        ctx.assertTrue(nestedFailed && level.getBlockState(origin).is(Blocks.STONE) && level.getBlockState(distant).isAir(), "A nested build shares its parent's transaction, including overlapping and distant positions");
        ctx.assertTrue(level.getBlockState(unrelated).is(Blocks.EMERALD_BLOCK) && store.snapshot().getStringOr("buildJournalProbe", "").equals("committed"), "Rollback preserves unlisted blocks and restores the parent's original records");
        level.setBlock(unrelated, Blocks.AIR.defaultBlockState(), 18);
        store.document("buildJournalProbe", null);
        store.save();
        for (var at : BlockPos.betweenClosed(origin, origin.offset(3, 3, 3)))
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:journals_record")
    public void recordEditsReplayWithoutAWorldSaveAndNestedEditsShareOneJournal(GameTestHelper ctx) throws Exception
    {
        var store = WorldStore.get(ctx.getLevel().getServer());
        var before = store.snapshot();
        long save = store.saveNumber();
        RecordJournal.run(ctx.getLevel().getServer(), "test-record", () ->
        {
            store.document("recordJournalProbe", StringTag.valueOf("after"));
            return RecordJournal.run(ctx.getLevel().getServer(), "test-nested-record", () ->
            {
                store.document("recordJournalNestedProbe", StringTag.valueOf("nested"));
                return null;
            });
        });
        ctx.assertTrue(store.saveNumber() == save, "RECORD commits do not force a metadata or world save");
        var files = new ArrayList<Path>();
        for (var file : Journals.list(store.directory))
            if (WorldStore.read(file).getStringOr("operation", "").startsWith("test-record") || WorldStore.read(file).getStringOr("operation", "").equals("test-nested-record"))
                files.add(file);
        ctx.assertTrue(files.size() == 1, "Nested record edits use one journal");
        com.nullandkale.nullspace.core.save.JournalReplay.apply(store.directory, before, save);
        ctx.assertTrue(before.getStringOr("recordJournalProbe", "").equals("after") && before.getStringOr("recordJournalNestedProbe", "").equals("nested"), "Both edits replay onto the saved copy");
        var previousWriter = WorldStore.writeObserver;
        boolean refused = false;
        try
        {
            WorldStore.writeObserver = progress ->
            {
                if (progress.file().getParent().getFileName().toString().equals("transfers") && progress.stage().equals("COMMITTED"))
                    throw new IllegalStateException("injected RECORD write failure");
            };
            RecordJournal.run(ctx.getLevel().getServer(), "test-record-failure", () ->
            {
                store.document("recordJournalProbe", StringTag.valueOf("must-not-publish"));
                return null;
            });
        }
        catch (IllegalStateException expected)
        {
            refused = true;
        }
        finally
        {
            WorldStore.writeObserver = previousWriter;
        }
        ctx.assertTrue(refused && store.snapshot().getStringOr("recordJournalProbe", "").equals("after"), "An uncommitted RECORD write failure rolls back its in-memory metadata");
        store.document("recordJournalProbe", null);
        store.document("recordJournalNestedProbe", null);
        store.save();
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:journals_restore", maxTicks = 300)
    public void operatorRestorePreservesObstructionsAndRollsBackEntitiesBeforeACleanRetry(GameTestHelper ctx) throws Exception
    {
        operatorRestore(ctx, false);
    }

    @GameTest(environment = "nullspace:journals_restore_space", maxTicks = 300)
    public void operatorRestoreToSpaceSkipsOccupiedSlotsAndMovesPassengerAuthority(GameTestHelper ctx) throws Exception
    {
        operatorRestore(ctx, true);
    }

    private void operatorRestore(GameTestHelper ctx, boolean toSpace) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var sourceFloor = new BlockPos(11264, 96, 11264);
        var controller = ship(ctx, sourceFloor, "Restore Source");
        var store = WorldStore.get(server);
        var record = store.at(level, controller);
        String id = record.getStringOr("id", "");
        var fence = sourceFloor.offset(1, 3, 3);
        level.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState(), 18);
        var scan = ShipService.inspect(level, controller).scan();
        var min = scan.bounds().min();
        var max = scan.bounds().max();
        var template = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
        template.fillFromWorld(level, min, new net.minecraft.core.Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1), false, List.of());
        var hull = template.save(new CompoundTag());
        hull.getListOrEmpty("blocks").removeIf(t ->
        {
            var at = ((CompoundTag) t).getListOrEmpty("pos");
            return !scan.blocks().contains(min.offset(at.getIntOr(0, 0), at.getIntOr(1, 0), at.getIntOr(2, 0)));
        });
        var pig = net.minecraft.world.entity.EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        pig.setPos(sourceFloor.getX() + 3.5, sourceFloor.getY() + 3, sourceFloor.getZ() + 3.5);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        pig.setLeashedTo(net.minecraft.world.entity.decoration.LeashFenceKnotEntity.getOrCreateKnot(level, fence), true);
        var uuid = pig.getUUID();
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        ctx.assertTrue(pig.save(output), "The passenger is saved with the hull");
        var passenger = new CompoundTag();
        passenger.putString("id", uuid.toString());
        passenger.put("data", output.buildResult());
        passenger.put("riders", new ListTag());
        var entities = new ListTag();
        entities.add(passenger);
        var journal = Journals.create(store.directory, Journals.Kind.MOVE, "test-rescue", List.of(id));
        journal.putString("source", level.dimension().identifier().toString());
        journal.putString("target", "nullspace:removed_for_restore_test");
        journal.putLong("min", min.asLong());
        journal.putLong("max", max.asLong());
        journal.putLong("delta", BlockPos.ZERO.asLong());
        journal.put("template", hull);
        journal.put("entities", entities);
        var offline = UUID.randomUUID();
        var players = new ListTag();
        var offlinePassenger = new CompoundTag();
        offlinePassenger.putString("id", offline.toString());
        offlinePassenger.putDouble("x", sourceFloor.getX() + 3.5);
        offlinePassenger.putDouble("y", sourceFloor.getY() + 3);
        offlinePassenger.putDouble("z", sourceFloor.getZ() + 3.5);
        players.add(offlinePassenger);
        journal.put("players", players);
        var before = store.snapshot();
        var after = before.copy();
        after.getListOrEmpty("ships").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(id)).findFirst().orElseThrow().putString("dimension", "nullspace:removed_for_restore_test");
        journal.put("records", com.nullandkale.nullspace.core.save.StoreRecords.encode(com.nullandkale.nullspace.core.save.StoreRecords.diff(before, after)));
        var file = Journals.file(store.directory, journal);
        TransferRecovery.checkpoint(file, journal, "SOURCE_CLEARED");
        TransferRecovery.setAside(server, file, new IllegalStateException("operator rescue fixture"));
        var targetFloor = sourceFloor.offset(128, 0, 0);
        com.nullandkale.nullspace.gameplay.shipyard.ShipYard.buildPad(level, targetFloor, 8, 8, "Restore Target");
        var targetBase = targetFloor.above();
        var blocked = targetBase.offset(min.subtract(WorldStore.pos(record, "base")));
        level.setBlock(blocked, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        var entry = UnresolvedJournals.get(server, journal.getStringOr("id", ""));
        boolean refused = false;
        try
        {
            JournalRestore.restore(server, entry, "Restore Target");
        }
        catch (IllegalArgumentException expected)
        {
            refused = true;
        }
        ctx.assertTrue(refused && level.getBlockState(blocked).is(Blocks.DIAMOND_BLOCK) && UnresolvedJournals.get(server, entry.id()) != null, "An occupied rescue pad is preserved and the original stays locked");
        level.setBlock(blocked, Blocks.AIR.defaultBlockState(), 18);
        var rescueLevel = toSpace ? server.getLevel(TravelService.SPACE) : level;
        String destination = toSpace ? "space" : "Restore Target";
        var occupiedSlot = new BlockPos(0, 96, 0);
        var occupiedBlock = occupiedSlot.offset(min.subtract(WorldStore.pos(record, "base")));
        if (toSpace)
            rescueLevel.setBlock(occupiedBlock, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        var completed = new boolean[1];
        var interruptedOnce = new boolean[1];
        ctx.onEachTick(() ->
        {
            if (completed[0])
                return;
            try
            {
                if (!interruptedOnce[0])
                {
                    var previousHook = TravelService.failureInjector;
                    try
                    {
                        TravelService.failureInjector = stage ->
                        {
                            if (stage.equals("PLACED"))
                                throw new IllegalStateException("rescue commit interrupted");
                        };
                        JournalRestore.restore(server, UnresolvedJournals.get(server, entry.id()), destination);
                        ctx.fail("The rescue commit must be interrupted");
                    }
                    catch (IllegalStateException interrupted)
                    {
                        ctx.assertTrue(interrupted.getMessage().contains("rescue commit interrupted"), "The intended commit seam was reached");
                    }
                    finally
                    {
                        TravelService.failureInjector = previousHook;
                    }
                    ctx.assertTrue(level.getBlockState(controller).is(ModBlocks.COMPUTER) && level.getEntity(uuid) != null, "Rollback restores the original hull and passenger");
                    ctx.assertTrue(level.getBlockState(blocked).isAir(), "Rollback leaves the rescue destination empty");
                    interruptedOnce[0] = true;
                }
                var source = server.createCommandSourceStack();
                server.getCommands().performPrefixedCommand(source, "nullspace recovery show " + entry.id());
                server.getCommands().performPrefixedCommand(source, "nullspace recovery export " + entry.id());
                server.getCommands().performPrefixedCommand(source, "nullspace recovery restore " + entry.id() + " " + destination);
                var restored = store.repository().find(id);
                var rescuedBase = WorldStore.pos(restored, "base");
                if (toSpace ? !restored.getStringOr("dimension", "").equals(rescueLevel.dimension().identifier().toString()) : !rescuedBase.equals(targetBase))
                    return; // The command reports ordinary saved-entity loading; retry on another tick.
                completed[0] = true;
                ctx.assertTrue(restored.getStringOr("dimension", "").equals(rescueLevel.dimension().identifier().toString()) && (toSpace ? !rescuedBase.equals(occupiedSlot) : rescuedBase.equals(targetBase)), "The operator command chooses the requested pad or a clear space slot");
                ctx.assertTrue(level.getBlockState(controller).isAir() && rescueLevel.getEntity(uuid) != null && rescueLevel.getEntity(uuid).getX() >= rescuedBase.getX(), "The old hull is cleared and one authoritative passenger is at the destination");
                if (toSpace)
                    ctx.assertTrue((level.getEntity(uuid) == null || level.getEntity(uuid).isRemoved()) && rescueLevel.getBlockState(occupiedBlock).is(Blocks.DIAMOND_BLOCK), "Cross-dimension rescue removes the old entity and preserves the occupied slot");
                ctx.assertTrue(store.list("targets").stream().anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(offline.toString()) && ((CompoundTag) t).getStringOr("dimension", "").equals(rescueLevel.dimension().identifier().toString()) && ((CompoundTag) t).getDoubleOr("x", 0) >= rescuedBase.getX()), "Offline passengers receive saved arrival targets at the rescued ship");
                ctx.assertTrue(UnresolvedJournals.get(server, entry.id()) == null && !TransferLocks.busy(server, restored), "A successful rescue archives the original and releases its locks");
                ctx.assertTrue(Files.exists(store.directory.resolve("rescue").resolve(entry.id()).resolve("hull.nbt")), "Export retains a vanilla structure for manual recovery");
                completed[0] = true;
                ctx.runAfterDelay(3, () ->
                {
                    var restoredPig = (net.minecraft.world.entity.animal.pig.Pig) rescueLevel.getEntity(uuid);
                    var expectedFence = fence.offset(rescuedBase.subtract(WorldStore.pos(record, "base")));
                    ctx.assertTrue(restoredPig != null && restoredPig.getLeashHolder() instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity knot && knot.getPos().equals(expectedFence), "The rescued passenger's saved fence leash follows its hull");
                    retire(server, id);
                    if (toSpace)
                        rescueLevel.setBlock(occupiedBlock, Blocks.AIR.defaultBlockState(), 18);
                    ctx.succeed();
                });
            }
            catch (IllegalArgumentException loading)
            {
                ctx.assertTrue(loading.getMessage().contains("loading"), "Only saved entity loading can delay rescue: " + loading.getMessage());
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }

}
