package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

/** Where a person can walk: standing cells, one step at a time, up and down stairs. */
public final class Walk
{
    /** A view of a ship's cells. */
    public interface Cells
    {
        Kind kind(BlockPos at);

        boolean interior(BlockPos at);
    }

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static boolean solid(Cells cells, BlockPos at)
    {
        var k = cells.kind(at);
        return k != null && k.solid();
    }

    /** A cell a person can stand in: open, with head room, on something solid or a stair. */
    public static boolean standable(Cells cells, BlockPos at)
    {
        if (!cells.interior(at) || solid(cells, at) || solid(cells, at.above()))
            return false;
        var below = cells.kind(at.below());
        return below != null && (below.solid() || below == Kind.STAIR);
    }

    public static List<BlockPos> neighbours(Cells cells, BlockPos at)
    {
        var out = new ArrayList<BlockPos>();
        for (var d : SIDES)
            for (int dy : new int[]{0, 1, -1})
            {
                var q = at.relative(d).above(dy);
                if (!standable(cells, q))
                    continue;
                // Only stairs change the level.
                if (dy == 1 && cells.kind(q.below()) != Kind.STAIR && cells.kind(at.below()) != Kind.STAIR)
                    continue;
                if (dy == -1 && cells.kind(at.below()) != Kind.STAIR && cells.kind(q.below()) != Kind.STAIR)
                    continue;
                out.add(q);
            }
        return out;
    }

    /** Every standing cell reachable from {@code start}; empty if {@code start} is not standable. */
    public static Set<BlockPos> reachable(Cells cells, BlockPos start)
    {
        var seen = new HashSet<BlockPos>();
        if (!standable(cells, start))
            return seen;
        var queue = new ArrayDeque<BlockPos>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty())
            for (var n : neighbours(cells, queue.poll()))
                if (seen.add(n))
                    queue.add(n);
        return seen;
    }

    /** True if a person standing somewhere reachable can use a fixture at {@code at}: it touches a reachable cell, or one up to two below. */
    public static boolean usable(Set<BlockPos> reach, BlockPos at)
    {
        // A sunken fixture such as farmland is used from the standing cell above it.
        if (reach.contains(at.above()))
            return true;
        for (var d : SIDES)
        {
            var n = at.relative(d);
            if (reach.contains(n) || reach.contains(n.below()) || reach.contains(n.below(2)))
                return true;
        }
        return false;
    }

    private Walk()
    {
    }
}
