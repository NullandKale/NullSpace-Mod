package com.nullandkale.nullspace.compat;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.EntityTravelGameTest;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What another mod's blocks do once they have arrived, and what its containers hold. The block sweep in
 * {@link ModCompatGameTest} checks that things come back the same; this checks that they keep acting the same.
 */
public final class ModBehaviorGameTest
{
    // ------------------------------------------------------------------ containers

    /** Containers of every kind, vanilla and modded, filled with awkward and modded items, flown out and back. */
    @GameTest(maxTicks = 1500)
    public void everyContainerKeepsItsItemsAcrossTheTrip(GameTestHelper ctx)
    {
        var spaces = ModCompatGameTest.namespaces();
        if (spaces.isEmpty())
        {
            ctx.succeed();
            return;
        }
        var make = EntityTravelGameTest.catalogBed(ctx, 70, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var start = bed.floor().getFirst();
            bed.player().teleportTo(start.getX() + .5, start.getY(), start.getZ() + .5);
            var cells = ModCompatGameTest.spareCells(bed.floor(), start);
            var states = new ArrayList<BlockState>();
            for (var block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND, Blocks.SHULKER_BOX, Blocks.CRAFTER, Blocks.DECORATED_POT, Blocks.CHISELED_BOOKSHELF, Blocks.JUKEBOX, Blocks.LECTERN))
                states.add(block.defaultBlockState());
            for (var block : BuiltInRegistries.BLOCK)
                if (spaces.contains(BuiltInRegistries.BLOCK.getKey(block).getNamespace()) && !(block instanceof DoorBlock) && block.defaultBlockState().hasBlockEntity() && !ModCompatGameTest.skip(block.defaultBlockState()))
                    states.add(block.defaultBlockState());
            var anomalies = new TreeMap<String, String>();
            int[] filled = {0};
            int offset = 0;
            while (offset < states.size())
            {
                var batch = new ArrayList<ModCompatGameTest.Placed>();
                for (int i = 0; i < cells.size() && offset < states.size(); i++)
                    batch.add(new ModCompatGameTest.Placed(cells.get(i), states.get(offset++)));
                ModCompatGameTest.runBatch(bed, batch, anomalies, placed ->
                {
                    for (var p : placed)
                        if (level.getBlockEntity(p.cell()) instanceof Container container)
                            filled[0] += ContainerFill.fill(container, level, spaces);
                });
            }
            var notes = List.of(states.size() + " container candidates, " + filled[0] + " slots filled with " + ContainerFill.pool(spaces).size() + " kinds of item");
            level.getServer().getPlayerList().remove(bed.player());
            WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(bed.shipId()));
            ModCompatGameTest.conclude(ctx, "containers", notes, anomalies);
        });
    }

    // ------------------------------------------------------------------ ticking

    private record Slot(BlockPos pos, BlockState state)
    {}

    /** Glass in every empty cell around a block, so a fluid or a spreading block cannot leave its cell or reach another. */
    private static final BlockState WALL = Blocks.STAINED_GLASS.red().defaultBlockState();

    private static void place(ServerLevel level, net.minecraft.server.level.ServerPlayer player, List<Slot> slots)
    {
        for (var slot : slots)
        {
            for (var direction : net.minecraft.core.Direction.values())
            {
                var next = slot.pos().relative(direction);
                if (level.getBlockState(next).isAir())
                    level.setBlock(next, WALL, 18);
            }
            ModCompatGameTest.place(level, slot.pos(), slot.state(), player);
        }
    }

    private static void reset(ServerLevel level, List<Slot> slots)
    {
        for (var slot : slots)
        {
            level.removeBlockEntity(slot.pos());
            level.setBlock(slot.pos(), Blocks.AIR.defaultBlockState(), 18);
            for (var direction : net.minecraft.core.Direction.values())
            {
                var next = slot.pos().relative(direction);
                if (level.getBlockState(next).equals(WALL))
                    level.setBlock(next, Blocks.AIR.defaultBlockState(), 18);
            }
        }
    }

    /** Ticks every block with the same seeds each time, and writes down what each one and its neighbours became. */
    private static List<String> outcome(ServerLevel level, List<Slot> slots)
    {
        var results = new ArrayList<String>();
        int index = 0;
        for (var slot : slots)
        {
            var random = RandomSource.create(1000L + index++);
            for (int k = 0; k < 16; k++)
            {
                var state = level.getBlockState(slot.pos());
                if (state.isRandomlyTicking())
                    state.randomTick(level, slot.pos(), random);
            }
            for (int k = 0; k < 4; k++)
                level.getBlockState(slot.pos()).tick(level, slot.pos(), random);
            var text = new StringBuilder(ModCompatGameTest.describe(level.getBlockState(slot.pos())));
            var data = ModCompatGameTest.data(level, slot.pos());
            if (data != null)
                text.append(' ').append(data);
            for (var direction : net.minecraft.core.Direction.values())
                text.append(' ').append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(slot.pos().relative(direction)).getBlock()).getPath());
            results.add(text.toString());
        }
        return results;
    }

    /** The air cell directly under the ceiling above a floor cell, for things that hang. */
    private static BlockPos ceilingOf(ServerLevel level, BlockPos floor)
    {
        var at = floor;
        for (int step = 0; step < 6 && level.getBlockState(at.above()).isAir(); step++)
            at = at.above();
        return at;
    }

    /**
     * For each block that moves, grows, spreads or decays: tick it with fixed random numbers before the trip and after it,
     * and again let real game time pass, and report anything that acts differently afterwards, or that lasts without a
     * trip and not with one. Each block goes where it can stand: on a floor, or hanging from a ceiling.
     */
    @GameTest(maxTicks = 6000)
    public void blocksKeepActingTheSameAfterTheTrip(GameTestHelper ctx)
    {
        var spaces = ModCompatGameTest.namespaces();
        if (spaces.isEmpty())
        {
            ctx.succeed();
            return;
        }
        var make = EntityTravelGameTest.catalogBed(ctx, 71, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var start = bed.floor().getFirst();
            bed.player().teleportTo(start.getX() + .5, start.getY(), start.getZ() + .5);
            var columns = EntityTravelGameTest.spread(ModCompatGameTest.spareCells(bed.floor(), start));
            var anomalies = new TreeMap<String, String>();
            var batches = new ArrayList<List<Slot>>();
            var current = new ArrayList<Slot>();
            int considered = 0, unsupported = 0;
            var used = new HashSet<BlockPos>();
            for (var block : BuiltInRegistries.BLOCK)
            {
                if (!spaces.contains(BuiltInRegistries.BLOCK.getKey(block).getNamespace()) || block instanceof DoorBlock || ModCompatGameTest.skip(block.defaultBlockState()))
                    continue;
                for (var state : ModCompatGameTest.statesToTry(block.defaultBlockState()))
                {
                    if (!(state.isRandomlyTicking() || state.hasBlockEntity() || !state.getFluidState().isEmpty()))
                        continue;
                    // A trapdoor framed on four sides must be shut and dry to count as sealed, so open and waterlogged ones are refused, as with any trapdoor.
                    if (state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock && (state.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN) || state.getValue(net.minecraft.world.level.block.TrapDoorBlock.WATERLOGGED)))
                        continue;
                    considered++;
                    BlockPos spot = null;
                    for (var column : columns)
                    {
                        if (used.contains(column))
                            continue;
                        var ceiling = ceilingOf(level, column);
                        if (state.canSurvive(level, column))
                            spot = column;
                        else if (state.canSurvive(level, ceiling))
                            spot = ceiling;
                        if (spot != null)
                        {
                            used.add(column);
                            break;
                        }
                    }
                    if (spot == null)
                    {
                        unsupported++;
                        continue;
                    }
                    current.add(new Slot(spot, state));
                    if (used.size() >= columns.size())
                    {
                        batches.add(current);
                        current = new ArrayList<>();
                        used.clear();
                    }
                }
            }
            if (!current.isEmpty())
                batches.add(current);
            var notes = new ArrayList<String>();
            notes.add(spaces + ": " + considered + " states that tick, hold data or flow; " + unsupported + " could not stand anywhere in the test ship (they need a wall); " + batches.size() + " batches");
            behave(ctx, bed, batches, 0, anomalies, notes);
        });
    }

    /**
     * The pad under the ship is plain grass and dirt, and a ship will not launch from anything else. If a block's ticks turn
     * pad floor into something else, name it and the batch it came from, then mend the floor so the other batches still run.
     */
    private static void checkPad(ServerLevel level, EntityTravelGameTest.Bed bed, List<Slot> slots, int index, String when, Map<String, String> anomalies)
    {
        var bounds = WorldStore.bounds(bed.ship());
        int y = bounds.min().getY() - 1;
        for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++)
            for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++)
            {
                var at = new BlockPos(x, y, z);
                var state = level.getBlockState(at);
                if (com.nullandkale.nullspace.gameplay.pads.PadFloor.is(state))
                    continue;
                var names = new TreeSet<String>();
                for (var slot : slots)
                    names.add(ModCompatGameTest.describe(slot.state()));
                anomalies.put("PAD DAMAGE (batch " + index + ", " + when + ")", "the pad floor at " + at.toShortString() + " became " + ModCompatGameTest.describe(state) + "; the batch held " + names);
                level.setBlock(at, Blocks.GRASS_BLOCK.defaultBlockState(), 18);
            }
    }

    /** One batch: control ticks, a settle, then the trip, the same ticks, and a second settle. Then the next batch. */
    private static void behave(GameTestHelper ctx, EntityTravelGameTest.Bed bed, List<List<Slot>> batches, int index, Map<String, String> anomalies, List<String> notes)
    {
        var level = bed.level();
        if (index == batches.size())
        {
            level.getServer().getPlayerList().remove(bed.player());
            WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(bed.shipId()));
            ModCompatGameTest.conclude(ctx, "behavior", notes, anomalies);
            return;
        }
        var slots = batches.get(index);
        place(level, bed.player(), slots);
        var control = outcome(level, slots);
        checkPad(level, bed, slots, index, "control ticks", anomalies);
        reset(level, slots);
        place(level, bed.player(), slots);
        var again = outcome(level, slots);
        reset(level, slots);
        // A block whose own ticks give different answers twice in a row has nothing to compare.
        var stable = new HashSet<Integer>();
        for (int i = 0; i < slots.size(); i++)
            if (control.get(i).equals(again.get(i)))
                stable.add(i);
        place(level, bed.player(), slots);
        ctx.runAfterDelay(40, () ->
        {
            checkPad(level, bed, slots, index, "40 ticks in place", anomalies);
            var survivedAlone = new HashSet<Integer>();
            for (int i = 0; i < slots.size(); i++)
                // Leaves that are not persistent are meant to decay, at random, so lasting 40 ticks says nothing about them.
                if (level.getBlockState(slots.get(i).pos()).is(slots.get(i).state().getBlock()) && !(slots.get(i).state().hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) && !slots.get(i).state().getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)))
                    survivedAlone.add(i);
            reset(level, slots);
            place(level, bed.player(), slots);
            try
            {
                for (var problem : EntityTravelGameTest.roundTrip(bed, List.of(), Set.of()))
                    anomalies.put("(batch " + index + ") " + (problem.length() > 80 ? problem.substring(0, 80) : problem), problem);
            }
            catch (RuntimeException refused)
            {
                anomalies.put("(batch " + index + ")", refused instanceof IllegalArgumentException ? "REFUSED: " + refused.getMessage().replaceAll("\\s+", " ") : "THREW: " + ModCompatGameTest.trace(refused));
                reset(level, slots);
                behave(ctx, bed, batches, index + 1, anomalies, notes);
                return;
            }
            var trial = outcome(level, slots);
            checkPad(level, bed, slots, index, "ticks after the trip", anomalies);
            for (int i : stable)
                if (!control.get(i).equals(trial.get(i)))
                    anomalies.put(ModCompatGameTest.describe(slots.get(i).state()), "TICKS DIFFERENTLY after the trip: " + control.get(i) + " became " + trial.get(i));
            // The ticks above changed those blocks, so the blocks that are timed on arrival come from a second trip.
            reset(level, slots);
            place(level, bed.player(), slots);
            try
            {
                for (var problem : EntityTravelGameTest.roundTrip(bed, List.of(), Set.of()))
                    anomalies.put("(second trip, batch " + index + ") " + (problem.length() > 80 ? problem.substring(0, 80) : problem), problem);
            }
            catch (RuntimeException refused)
            {
                anomalies.put("(second trip, batch " + index + ")", refused instanceof IllegalArgumentException ? "REFUSED: " + refused.getMessage().replaceAll("\\s+", " ") : "THREW: " + ModCompatGameTest.trace(refused));
                reset(level, slots);
                behave(ctx, bed, batches, index + 1, anomalies, notes);
                return;
            }
            ctx.runAfterDelay(40, () ->
            {
                checkPad(level, bed, slots, index, "40 ticks after the trip", anomalies);
                for (int i : survivedAlone)
                    if (!level.getBlockState(slots.get(i).pos()).is(slots.get(i).state().getBlock()))
                        anomalies.put(ModCompatGameTest.describe(slots.get(i).state()), "LOST after the trip: it lasts 40 ticks where it was placed, but did not after travelling");
                reset(level, slots);
                behave(ctx, bed, batches, index + 1, anomalies, notes);
            });
        });
    }
}
