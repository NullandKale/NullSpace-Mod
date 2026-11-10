package com.nullandkale.nullspace.compat;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.EntityTravelGameTest;

import java.util.*;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.material.Fluid;

import biomesoplenty.api.block.BOPBlocks;
import biomesoplenty.api.block.BOPFluids;
import biomesoplenty.block.entity.AnomalyBlockEntity;

/**
 * Biomes O' Plenty, the part of its content that the registry sweeps cannot set up for themselves: two fluids that
 * must stay in their basin, leaves that must stay on their tree, grass that spreads, and the anomaly block.
 * Everything here compiles against the mod, and every case passes at once when the mod is not loaded.
 */
public final class BiomesOPlentyCompatGameTest
{
    private static boolean loaded()
    {
        return FabricLoader.getInstance().isModLoaded("biomesoplenty");
    }

    private record Site(EntityTravelGameTest.Bed bed, ServerLevel level, List<BlockPos> areas)
    {}

    /** Centres of open 3 x 3 patches of floor, kept three cells apart so patches never touch. */
    private static List<BlockPos> openAreas(List<BlockPos> floor)
    {
        var all = new HashSet<>(floor);
        var areas = new ArrayList<BlockPos>();
        for (var at : floor)
        {
            boolean open = true;
            for (int dx = -1; dx <= 1 && open; dx++)
                for (int dz = -1; dz <= 1 && open; dz++)
                    open = all.contains(at.offset(dx, 0, dz));
            if (open && areas.stream().noneMatch(a -> Math.abs(a.getX() - at.getX()) < 4 && Math.abs(a.getZ() - at.getZ()) < 4 && a.getY() == at.getY()))
                areas.add(at);
        }
        return areas;
    }

    private static void withShip(GameTestHelper ctx, int slot, Consumer<Site> test)
    {
        if (!loaded())
        {
            ctx.succeed();
            return;
        }
        var make = EntityTravelGameTest.catalogBed(ctx, slot, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var start = bed.floor().getFirst();
            bed.player().teleportTo(start.getX() + .5, start.getY(), start.getZ() + .5);
            // Keep the player's own patch of floor out of the test areas.
            var areas = new ArrayList<>(openAreas(bed.floor()));
            areas.removeIf(a -> Math.abs(a.getX() - start.getX()) < 4 && Math.abs(a.getZ() - start.getZ()) < 4 && a.getY() == start.getY());
            test.accept(new Site(bed, bed.level(), areas));
        });
    }

    private static void end(GameTestHelper ctx, Site site, List<String> notes, Map<String, String> anomalies, String name)
    {
        var level = site.level();
        level.getServer().getPlayerList().remove(site.bed().player());
        WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(site.bed().shipId()));
        ModCompatGameTest.conclude(ctx, name, notes, anomalies);
    }

    private static void trip(Site site, Map<String, String> anomalies, String label)
    {
        try
        {
            for (var problem : EntityTravelGameTest.roundTrip(site.bed(), List.of(), Set.of()))
                anomalies.put(label + ": " + (problem.length() > 80 ? problem.substring(0, 80) : problem), problem);
        }
        catch (IllegalArgumentException refused)
        {
            anomalies.put(label + ": REFUSED", refused.getMessage().replace('\n', ' '));
        }
    }

    // ------------------------------------------------------------------ fluids

    /** How many blocks of a fluid are in the 7 x 3 x 7 box around a basin, and whether the middle one is still a source. */
    private static String fluidAround(ServerLevel level, BlockPos center, Fluid fluid)
    {
        int count = 0;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    if (level.getFluidState(center.offset(dx, dy, dz)).getType().isSame(fluid))
                        count++;
        return count + " blocks, middle " + (level.getFluidState(center).isSource() && level.getFluidState(center).getType().isSame(fluid) ? "is a source" : "is " + level.getFluidState(center));
    }

    private static boolean ringIntact(ServerLevel level, BlockPos center)
    {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if ((dx != 0 || dz != 0) && !level.getBlockState(center.offset(dx, 0, dz)).is(Blocks.STONE))
                    return false;
        return true;
    }

    /** Blood and Liquid Null, each in a stone basin: still one source in an intact basin after the trip, and after time passes. */
    @GameTest(maxTicks = 600)
    public void bloodAndLiquidNullStayInTheirBasins(GameTestHelper ctx)
    {
        withShip(ctx, 80, site ->
        {
            var level = site.level();
            var fluids = List.of(BOPFluids.BLOOD, BOPFluids.LIQUID_NULL);
            var anomalies = new TreeMap<String, String>();
            var notes = new ArrayList<String>();
            if (site.areas().size() < fluids.size())
            {
                anomalies.put("(ship)", "The test ship has only " + site.areas().size() + " open 3 x 3 patches of floor");
                end(ctx, site, notes, anomalies, "biomesoplenty-fluids");
                return;
            }
            var before = new ArrayList<String>();
            for (int i = 0; i < fluids.size(); i++)
            {
                var center = site.areas().get(i);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        if (dx != 0 || dz != 0)
                            level.setBlock(center.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 18);
                level.setBlock(center, fluids.get(i).defaultFluidState().createLegacyBlock(), 18);
                level.scheduleTick(center, fluids.get(i), fluids.get(i).getTickDelay(level));
                before.add(fluidAround(level, center, fluids.get(i)));
            }
            trip(site, anomalies, "Fluids");
            for (int i = 0; i < fluids.size(); i++)
            {
                var center = site.areas().get(i);
                String name = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluids.get(i)).toString();
                if (!fluidAround(level, center, fluids.get(i)).equals(before.get(i)))
                    anomalies.put(name, "after the trip: " + fluidAround(level, center, fluids.get(i)) + " (before: " + before.get(i) + ")");
                if (!ringIntact(level, center))
                    anomalies.put(name + " basin", "the basin walls changed");
            }
            ctx.runAfterDelay(60, () ->
            {
                for (int i = 0; i < fluids.size(); i++)
                {
                    var center = site.areas().get(i);
                    String name = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluids.get(i)).toString();
                    if (!fluidAround(level, center, fluids.get(i)).equals(before.get(i)))
                        anomalies.put(name + " after 60 ticks", fluidAround(level, center, fluids.get(i)) + " (before: " + before.get(i) + ")");
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++)
                            level.setBlock(center.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 18);
                }
                notes.add("fluids checked: " + before);
                end(ctx, site, notes, anomalies, "biomesoplenty-fluids");
            });
        });
    }

    // ------------------------------------------------------------------ plants

    private static int count(ServerLevel level, List<BlockPos> cells, Block block)
    {
        int n = 0;
        for (var at : cells)
            if (level.getBlockState(at).is(block))
                n++;
        return n;
    }

    /** Runs the scheduled tick of each block a few times, which is when leaves work out their distance from a trunk. */
    private static void settle(ServerLevel level, List<BlockPos> cells, long seed)
    {
        var random = RandomSource.create(seed);
        for (int round = 0; round < 8; round++)
            for (var at : cells)
                level.getBlockState(at).tick(level, at, random);
    }

    private static void tickAll(ServerLevel level, List<BlockPos> cells, int rounds, long seed)
    {
        var random = RandomSource.create(seed);
        for (int round = 0; round < rounds; round++)
            for (var at : cells)
            {
                var state = level.getBlockState(at);
                if (state.isRandomlyTicking())
                    state.randomTick(level, at, random);
            }
    }

    /** A trunk with leaves against it keeps every leaf under random ticks, and does so after the trip as before. */
    @GameTest(maxTicks = 300)
    public void leavesOnATreeDoNotDecayAfterTheTrip(GameTestHelper ctx)
    {
        withShip(ctx, 81, site ->
        {
            var level = site.level();
            var anomalies = new TreeMap<String, String>();
            var notes = new ArrayList<String>();
            if (site.areas().isEmpty())
            {
                anomalies.put("(ship)", "No open 3 x 3 patch of floor in the test ship");
                end(ctx, site, notes, anomalies, "biomesoplenty-trees");
                return;
            }
            var c = site.areas().getFirst();
            var leafCells = new ArrayList<BlockPos>();
            for (var base : List.of(c, c.above()))
                for (var offset : List.of(new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)))
                    leafCells.add(base.offset(offset));
            Runnable build = () ->
            {
                level.setBlock(c, BOPBlocks.FIR_LOG.defaultBlockState(), 18);
                level.setBlock(c.above(), BOPBlocks.FIR_LOG.defaultBlockState(), 18);
                var leaf = BOPBlocks.FIR_LEAVES.defaultBlockState();
                if (leaf.hasProperty(LeavesBlock.PERSISTENT))
                    leaf = leaf.setValue(LeavesBlock.PERSISTENT, false);
                // Neighbour updates on, so each leaf works out its own distance from the trunk.
                for (var at : leafCells)
                    level.setBlock(at, leaf, 3);
                settle(level, leafCells, 7);
            };
            build.run();
            tickAll(level, leafCells, 200, 4242);
            int control = count(level, leafCells, BOPBlocks.FIR_LEAVES);
            for (var at : leafCells)
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(c, Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(c.above(), Blocks.AIR.defaultBlockState(), 18);
            build.run();
            trip(site, anomalies, "Tree");
            int arrivedWith = count(level, leafCells, BOPBlocks.FIR_LEAVES);
            tickAll(level, leafCells, 200, 4242);
            int after = count(level, leafCells, BOPBlocks.FIR_LEAVES);
            notes.add("leaves against a trunk: " + leafCells.size() + " placed, " + control + " left after 200 ticks without a trip, " + arrivedWith + " on arrival, " + after + " after 200 ticks following the trip");
            if (arrivedWith != leafCells.size())
                anomalies.put("biomesoplenty:fir_leaves", "arrived with " + arrivedWith + " of " + leafCells.size() + " leaves");
            if (after != control)
                anomalies.put("biomesoplenty:fir_leaves decay", "without a trip " + control + " leaves are left after 200 ticks, after a trip " + after);
            for (var at : leafCells)
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(c, Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(c.above(), Blocks.AIR.defaultBlockState(), 18);
            end(ctx, site, notes, anomalies, "biomesoplenty-trees");
        });
    }

    /** Origin grass among dirt spreads the same way before and after the trip, from the same random numbers. */
    @GameTest(maxTicks = 300)
    public void originGrassSpreadsTheSameAfterTheTrip(GameTestHelper ctx)
    {
        withShip(ctx, 82, site ->
        {
            var level = site.level();
            var anomalies = new TreeMap<String, String>();
            var notes = new ArrayList<String>();
            if (site.areas().isEmpty())
            {
                anomalies.put("(ship)", "No open 3 x 3 patch of floor in the test ship");
                end(ctx, site, notes, anomalies, "biomesoplenty-grass");
                return;
            }
            var c = site.areas().getFirst();
            var patch = new ArrayList<BlockPos>();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    patch.add(c.offset(dx, 0, dz));
            Runnable build = () ->
            {
                for (var at : patch)
                    level.setBlock(at, Blocks.DIRT.defaultBlockState(), 18);
                level.setBlock(c, BOPBlocks.ORIGIN_GRASS_BLOCK.defaultBlockState(), 18);
            };
            build.run();
            tickAll(level, patch, 400, 99);
            int control = count(level, patch, BOPBlocks.ORIGIN_GRASS_BLOCK);
            build.run();
            trip(site, anomalies, "Grass");
            tickAll(level, patch, 400, 99);
            int after = count(level, patch, BOPBlocks.ORIGIN_GRASS_BLOCK);
            notes.add("origin grass in a 3 x 3 dirt patch after 400 random ticks per cell: " + control + " cells without a trip, " + after + " after a trip");
            if (control != after)
                anomalies.put("biomesoplenty:origin_grass_block", "spreads to " + control + " cells without a trip and " + after + " after one");
            for (var at : patch)
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
            end(ctx, site, notes, anomalies, "biomesoplenty-grass");
        });
    }

    // ------------------------------------------------------------------ pads

    /** A landing pad whose floor is Origin Grass, as in the Origin Valley, is a pad: it scans valid and keeps reading valid. */
    @GameTest(maxTicks = 100)
    public void aPadWithAnOriginGrassFloorIsAValidPad(GameTestHelper ctx)
    {
        if (!loaded())
        {
            ctx.succeed();
            return;
        }
        var level = ctx.getLevel();
        var base = new BlockPos(330000, 70, 270000);
        for (int x = (base.getX() - 2) >> 4; x <= (base.getX() + 10) >> 4; x++)
            for (int z = (base.getZ() - 2) >> 4; z <= (base.getZ() + 10) >> 4; z++)
                level.getChunk(x, z);
        try
        {
            com.nullandkale.nullspace.gameplay.shipyard.ShipYard.buildPad(level, base, 8, 8, "OriginGrassPad " + UUID.randomUUID().toString().substring(0, 6));
        }
        catch (java.io.IOException failure)
        {
            throw new java.io.UncheckedIOException(failure);
        }
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++)
                level.setBlock(base.offset(x, 0, z), BOPBlocks.ORIGIN_GRASS_BLOCK.defaultBlockState(), 18);
        var computer = base.offset(-1, 1, 3);
        level.setBlock(computer, com.nullandkale.nullspace.gameplay.pads.PadComputers.BLOCK.defaultBlockState(), 18);
        var result = com.nullandkale.nullspace.gameplay.pads.PadComputers.inspect(level, computer);
        ctx.assertTrue(result.valid(), "A pad floored with Origin Grass scans as a valid pad: " + result.message());
        ctx.succeed();
    }

    // ------------------------------------------------------------------ the anomaly

    /** The anomaly block keeps its own block entity through the trip and while time passes afterwards. */
    @GameTest(maxTicks = 400)
    public void theAnomalyBlockKeepsItsBlockEntity(GameTestHelper ctx)
    {
        withShip(ctx, 83, site ->
        {
            var level = site.level();
            var anomalies = new TreeMap<String, String>();
            var notes = new ArrayList<String>();
            var at = site.areas().isEmpty() ? site.bed().floor().get(site.bed().floor().size() / 2) : site.areas().getFirst();
            level.setBlock(at, BOPBlocks.ANOMALY.defaultBlockState(), 18);
            if (!(level.getBlockEntity(at) instanceof AnomalyBlockEntity))
            {
                anomalies.put("biomesoplenty:anomaly", "placed without its block entity");
                end(ctx, site, notes, anomalies, "biomesoplenty-anomaly");
                return;
            }
            trip(site, anomalies, "Anomaly");
            if (!(level.getBlockEntity(at) instanceof AnomalyBlockEntity))
                anomalies.put("biomesoplenty:anomaly", "lost its block entity in the trip");
            ctx.runAfterDelay(60, () ->
            {
                if (!(level.getBlockEntity(at) instanceof AnomalyBlockEntity anomaly) || anomaly.getRenderState() == null)
                    anomalies.put("biomesoplenty:anomaly after 60 ticks", "no block entity or no render state");
                else
                    notes.add("anomaly render state after the trip: " + anomaly.getRenderState());
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
                end(ctx, site, notes, anomalies, "biomesoplenty-anomaly");
            });
        });
    }
}
