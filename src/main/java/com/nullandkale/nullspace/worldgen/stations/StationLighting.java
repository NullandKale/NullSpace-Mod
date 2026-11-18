package com.nullandkale.nullspace.worldgen.stations;

import java.util.*;

import net.minecraft.core.*;

/**
 * The torch lighting pass. It runs in rounds over a finished structure: a regular, mirrored set of
 * wall torches first, then repeated simulate-and-fix rounds that add a torch wherever a standing
 * cell is still too dark. It treats torches already in the world as light sources, so it can be run
 * again after rooms are furnished and only adds what the furniture left dark.
 */
public final class StationLighting
{
    /** What a torch gives off, and the dimmest light a standing cell may have. */
    public static final int TORCH = 14, MINIMUM = 8;

    /** What the pass needs to know about the world. */
    public interface Space
    {
        /** True when block light passes through the cell (air, glass, doors, stairs, torches). */
        boolean transparent(BlockPos at);
    }

    /**
     * A torch: the cell it occupies and, for a wall torch, the direction from that cell to the wall
     * that holds it; {@code null} for a standing torch on the floor.
     */
    public record Torch(BlockPos at, Direction wall)
    {}

    /** The torches to add, how many rounds it took, and any standing cells it could not light. */
    public record Result(List<Torch> torches, int rounds, List<BlockPos> dark)
    {}

    private static final Comparator<BlockPos> ORDER = Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt((BlockPos p) -> p.getX()).thenComparingInt((BlockPos p) -> p.getZ());

    /** The eight-cell box a position is in, as one number, for finding the torches near a cell without looking at them all. */
    private static long box(int x, int y, int z)
    {
        return ((long) (x >> 3) & 0x1FFFFF) << 42 | ((long) (y >> 3) & 0x1FFFFF) << 21 | ((long) (z >> 3) & 0x1FFFFF);
    }

    /** Block light at every cell a torch reaches. */
    public static Map<BlockPos, Integer> levels(Space space, Collection<BlockPos> sources)
    {
        var level = new HashMap<BlockPos, Integer>();
        var queue = new ArrayDeque<BlockPos>();
        for (var source : sources)
        {
            level.put(source, TORCH);
            queue.add(source);
        }
        spread(space, level, queue);
        return level;
    }

    private static void spread(Space space, Map<BlockPos, Integer> level, ArrayDeque<BlockPos> queue)
    {
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            int next = level.get(at) - 1;
            if (next < 1)
                continue;
            for (var d : Direction.values())
            {
                var to = at.relative(d);
                if (space.transparent(to) && level.getOrDefault(to, 0) < next)
                {
                    level.put(to, next);
                    queue.add(to);
                }
            }
        }
    }

    /**
     * Lights {@code standing} cells to at least {@link #MINIMUM}. {@code existing} are torches already
     * placed; {@code baseline} go in first, as the regular pattern; {@code candidates} are the only
     * places a fixing round may add one, in preference order.
     */
    public static Result light(Space space, Collection<BlockPos> standing, Collection<BlockPos> existing, List<Torch> baseline, List<Torch> candidates, int maxRounds)
    {
        var sources = new LinkedHashSet<BlockPos>(existing);
        var added = new ArrayList<Torch>();
        for (var torch : baseline)
            if (sources.add(torch.at()))
                added.add(torch);
        var level = levels(space, sources);
        // The candidates by the eight-cell box they are in: a dark cell only needs the torches within eight cells, which are in the 27 boxes round it.
        var index = new HashMap<Long, ArrayList<Torch>>();
        for (var c : candidates)
            index.computeIfAbsent(box(c.at().getX(), c.at().getY(), c.at().getZ()), k -> new ArrayList<>()).add(c);
        var ordered = new ArrayList<>(standing);
        ordered.sort(ORDER);
        int rounds = 0;
        var dark = new ArrayList<BlockPos>();
        while (true)
        {
            dark.clear();
            for (var cell : ordered)
                if (level.getOrDefault(cell, 0) < MINIMUM)
                    dark.add(cell);
            if (dark.isEmpty() || rounds >= maxRounds)
                break;
            rounds++;
            boolean progress = false;
            // Darkest cells first; later fixes in the round may already have lit the rest.
            dark.sort(Comparator.comparingInt((BlockPos c) -> level.getOrDefault(c, 0)).thenComparing(ORDER));
            var darkSet = new HashSet<>(dark);
            for (var cell : List.copyOf(dark))
            {
                if (level.getOrDefault(cell, 0) >= MINIMUM)
                    continue;
                Torch best = null;
                int bestGain = 0;
                int checked = 0;
                var near = new ArrayList<Torch>();
                for (int bx = -1; bx <= 1; bx++)
                    for (int by = -1; by <= 1; by++)
                        for (int bz = -1; bz <= 1; bz++)
                        {
                            var here = index.get(box(cell.getX() + bx * 8, cell.getY() + by * 8, cell.getZ() + bz * 8));
                            if (here != null)
                                for (var c : here)
                                    if (!sources.contains(c.at()) && c.at().distManhattan(cell) <= TORCH - MINIMUM + 2)
                                        near.add(c);
                        }
                near.sort(Comparator.comparingInt((Torch c) -> c.at().distManhattan(cell)).thenComparing(c -> c.at(), ORDER));
                for (var c : near)
                {
                    if (checked++ >= 6)
                        break;
                    // A wall torch is worth a little more than a floor torch that lights the same cells.
                    int gain = gain(space, level, darkSet, c.at()) * 2 + (c.wall() != null ? 1 : 0);
                    if (gain > bestGain)
                    {
                        best = c;
                        bestGain = gain;
                    }
                }
                if (best == null || bestGain < 2)
                    continue;
                sources.add(best.at());
                added.add(best);
                level.put(best.at(), TORCH);
                var queue = new ArrayDeque<BlockPos>(List.of(best.at()));
                spread(space, level, queue);
                progress = true;
            }
            if (!progress)
                break;
        }
        dark.clear();
        for (var cell : ordered)
            if (level.getOrDefault(cell, 0) < MINIMUM)
                dark.add(cell);
        return new Result(List.copyOf(added), rounds, List.copyOf(dark));
    }

    /** How many currently dark cells a torch at {@code at} would bring up to the minimum. */
    private static int gain(Space space, Map<BlockPos, Integer> level, Set<BlockPos> dark, BlockPos at)
    {
        var local = new HashMap<BlockPos, Integer>(Map.of(at, TORCH));
        var queue = new ArrayDeque<BlockPos>(List.of(at));
        spread(space, local, queue);
        // Only the cells the torch reaches can gain, so these are looked at, and not every dark cell there is.
        int gain = 0;
        for (var entry : local.entrySet())
            if (entry.getValue() >= MINIMUM && dark.contains(entry.getKey()) && level.getOrDefault(entry.getKey(), 0) < MINIMUM)
                gain++;
        return gain;
    }

    private StationLighting()
    {
    }
}
