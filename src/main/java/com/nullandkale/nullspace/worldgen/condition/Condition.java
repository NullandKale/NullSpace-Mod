package com.nullandkale.nullspace.worldgen.condition;

import com.nullandkale.nullspace.worldgen.piece.Archetype;
import com.nullandkale.nullspace.worldgen.piece.Assembler;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wear and damage for generated builds, worse with danger (0-3). Deterministic for a seed.
 * <ul>
 * <li><b>Missing section</b> (danger 2-3, on an assembly): an optional end piece, such as a wing,
 * is left out and its joint stays open.</li>
 * <li><b>Breaches</b>: clusters of exterior hull removed, at {@code breach_ok} points first. They
 * leak air through the real air system.</li>
 * <li><b>Scorching</b>: exterior hull around breaches cracked or blackened.</li>
 * <li><b>Dead lights</b>: some lamps unlit or gone.</li>
 * </ul>
 * Machines, doors, docking bases with their approach, and loot spots are never damaged.
 */
public final class Condition
{
    public record Report(int breaches, int removed, int scorched, int darkened, String omitted)
    {
        public static final Report NONE = new Report(0, 0, 0, 0, "");
    }

    public record Result(StationBlueprint.Plan plan, Report report)
    {}

    private static final Set<Block> FULL_LIGHTS = Set.of(Blocks.SEA_LANTERN, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT);

    private static final Set<Block> SMALL_LIGHTS = Set.of(Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.END_ROD);

    private static final Map<Block, Block> CRACKED = Map.of(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS);

    /** Leaves out one optional end piece at danger 2 (half the time) or 3 (always), if there is one. */
    public static Assembler.Assembly omitSection(Assembler.Assembly assembly, Archetype archetype, int danger, long seed)
    {
        var random = new Random(seed ^ 0x5DEECE66DL);
        if (danger < 2 || danger == 2 && random.nextBoolean())
            return assembly;
        var optional = new HashSet<String>();
        for (var count : archetype.roles())
            if (count.min() == 0)
                optional.add(count.role());
        var candidates = new ArrayList<Assembler.Placed>();
        for (var placed : assembly.placed())
        {
            if (!optional.contains(placed.role()) || placed.cells().stream().anyMatch(c -> protectedBlock(assembly.blocks().get(c))))
                continue;
            if (touching(assembly, placed).stream().filter(p -> !p.role().equals(archetype.cap())).count() == 1)
                candidates.add(placed);
        }
        if (candidates.isEmpty())
            return assembly;
        var gone = candidates.get(random.nextInt(candidates.size()));
        var removed = new HashSet<>(gone.cells());
        var keep = new ArrayList<Assembler.Placed>();
        for (var placed : assembly.placed())
        {
            boolean cap = placed.role().equals(archetype.cap()) && touching(assembly, placed).stream().allMatch(p -> p == gone);
            if (placed == gone || cap)
                removed.addAll(placed.cells());
            else
                keep.add(placed);
        }
        var blocks = new LinkedHashMap<>(assembly.blocks());
        for (var at : removed)
            blocks.put(at, Blocks.AIR.defaultBlockState());
        var points = assembly.points().stream().filter(p -> !removed.contains(p.pos())).toList();
        return new Assembler.Assembly(Collections.unmodifiableMap(blocks), points, keep, assembly.open(), assembly.problems());
    }

    private static List<Assembler.Placed> touching(Assembler.Assembly assembly, Assembler.Placed piece)
    {
        var result = new ArrayList<Assembler.Placed>();
        for (var other : assembly.placed())
        {
            if (other == piece)
                continue;
            outer : for (var c : piece.cells())
                for (var d : Direction.values())
                    if (other.cells().contains(c.relative(d)))
                    {
                        result.add(other);
                        break outer;
                    }
        }
        return result;
    }

    /** Breaches, scorching and dead lights for {@code danger}; danger 0 returns the plan unchanged. */
    public static Result apply(StationBlueprint.Plan plan, int danger, long seed)
    {
        danger = Math.clamp(danger, 0, 3);
        if (danger == 0)
            return new Result(plan, Report.NONE);
        var random = new Random(seed);
        var blocks = new LinkedHashMap<>(plan.blocks());
        var safe = safeCells(plan);
        var exterior = exterior(blocks);
        exterior.removeIf(at -> safe.contains(at) || protectedBlock(blocks.get(at)));
        int count = danger == 1 ? 1 : danger == 2 ? 2 + random.nextInt(2) : 3 + random.nextInt(3);
        int radius = danger == 3 ? 2 : 1;
        var centers = new ArrayList<BlockPos>();
        var preferred = new ArrayList<>(plan.points("breach_ok"));
        Collections.shuffle(preferred, random);
        for (var point : preferred)
        {
            if (centers.size() >= count)
                break;
            exterior.stream().min(Comparator.comparingDouble(at -> at.distSqr(point.pos()))).ifPresent(centers::add);
        }
        var pool = new ArrayList<>(exterior);
        pool.sort(Comparator.comparingLong(BlockPos::asLong));
        while (centers.size() < count && !pool.isEmpty())
            centers.add(pool.remove(random.nextInt(pool.size())));
        int removed = 0, scorched = 0, darkened = 0;
        for (var center : centers)
            for (var at : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius)))
                if (at.distSqr(center) <= radius * radius && exterior.contains(at) && !blocks.get(at).isAir())
                {
                    blocks.put(at.immutable(), Blocks.AIR.defaultBlockState());
                    removed++;
                }
        int burn = radius + 2;
        for (var center : centers)
            for (var at : BlockPos.betweenClosed(center.offset(-burn, -burn, -burn), center.offset(burn, burn, burn)))
            {
                var state = blocks.get(at);
                if (state == null || state.isAir() || !exterior.contains(at) || !state.canOcclude() || FULL_LIGHTS.contains(state.getBlock()) || random.nextFloat() > 0.6f)
                    continue;
                var cracked = CRACKED.get(state.getBlock());
                blocks.put(at.immutable(), (cracked != null ? cracked : random.nextBoolean() ? Blocks.BLACKSTONE : Blocks.POLISHED_BLACKSTONE).defaultBlockState());
                scorched++;
            }
        float dark = danger * 0.25f;
        for (var e : blocks.entrySet())
        {
            var block = e.getValue().getBlock();
            if (FULL_LIGHTS.contains(block) && random.nextFloat() < dark)
            {
                e.setValue(Blocks.REDSTONE_LAMP.defaultBlockState());
                darkened++;
            }
            else if (SMALL_LIGHTS.contains(block) && random.nextFloat() < dark)
            {
                e.setValue(Blocks.AIR.defaultBlockState());
                darkened++;
            }
        }
        return new Result(new StationBlueprint.Plan(blocks, plan.core(), plan.docks(), plan.points()), new Report(centers.size(), removed, scorched, darkened, ""));
    }

    /** Machines (this mod's blocks) and doors are never damaged or removed. */
    static boolean protectedBlock(BlockState state)
    {
        if (state == null || state.isAir())
            return false;
        return state.getBlock() instanceof DoorBlock || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("nullspace");
    }

    /** Docking bases with two blocks around them, and every point, stay intact. */
    private static Set<BlockPos> safeCells(StationBlueprint.Plan plan)
    {
        var safe = new HashSet<BlockPos>();
        for (var dock : plan.docks())
            for (var at : BlockPos.betweenClosed(dock.offset(-2, -1, -2), dock.offset(2, 3, 2)))
                safe.add(at.immutable());
        for (var point : plan.points())
            safe.add(point.pos());
        return safe;
    }

    /** Solid blocks touching the open space around the build (flood fill from outside its box). */
    static Set<BlockPos> exterior(Map<BlockPos, BlockState> blocks)
    {
        BlockPos lo = null, hi = null;
        for (var at : blocks.keySet())
        {
            lo = lo == null ? at : BlockPos.min(lo, at);
            hi = hi == null ? at : BlockPos.max(hi, at);
        }
        var result = new HashSet<BlockPos>();
        if (lo == null)
            return result;
        lo = lo.offset(-1, -1, -1);
        hi = hi.offset(1, 1, 1);
        var outside = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(lo);
        outside.add(lo);
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (var d : Direction.values())
            {
                var next = at.relative(d);
                if (next.getX() < lo.getX() || next.getY() < lo.getY() || next.getZ() < lo.getZ() || next.getX() > hi.getX() || next.getY() > hi.getY() || next.getZ() > hi.getZ() || outside.contains(next))
                    continue;
                var state = blocks.get(next);
                if (state == null || state.isAir())
                {
                    outside.add(next);
                    queue.add(next);
                }
                else
                    result.add(next);
            }
        }
        return result;
    }

    private Condition()
    {
    }
}
