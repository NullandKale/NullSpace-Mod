package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

/**
 * A room's furnishing helper. Everything is placed in the room's own frame ({@code u} across, {@code
 * v} from the entry, {@code row} above the floor), in mirrored pairs, so every room is symmetric
 * about the line from its entry to its far wall. Cells in front of an opening stay clear.
 */
final class Frame
{
    static final int DEEPER = 0, TOWARD_ENTRY = 1, RIGHT = 2, LEFT = 3;

    final Space s;
    final Grid g;
    final int c, w, d, h;
    private final Set<BlockPos> reserved = new HashSet<>();

    Frame(Space s, Grid g, List<Link> links)
    {
        this.s = s;
        this.g = g;
        this.c = s.centre();
        this.w = s.w;
        this.d = s.d;
        this.h = s.height;
        var floorRow = s.floorY + 1;
        for (var link : links)
        {
            for (var into : new Direction[]{link.across(), link.across().getOpposite()})
            {
                if (!s.cells().contains(link.at().relative(into)))
                    continue;
                var perpendicular = into.getClockWise();
                for (int depth = 1; depth <= 2; depth++)
                    for (int k = -link.width() / 2; k <= link.width() / 2; k++)
                    {
                        var at = link.at().relative(into, depth).relative(perpendicular, k);
                        reserved.add(new BlockPos(at.getX(), floorRow, at.getZ()));
                    }
            }
        }
    }

    /** Keeps a cell clear (the docking door's approach, for one). */
    void reserve(int u, int v)
    {
        var at = s.at(u, v, 0);
        reserved.add(new BlockPos(at.getX(), s.floorY + 1, at.getZ()));
    }

    void reserve(BlockPos at)
    {
        // Reserve the column on both decks joined by a stair flight.
        if (Math.abs(at.getY() - (s.floorY + 1)) <= s.height + 1)
            reserved.add(new BlockPos(at.getX(), s.floorY + 1, at.getZ()));
    }

    boolean floorFree(BlockPos at)
    {
        return !reserved.contains(new BlockPos(at.getX(), s.floorY + 1, at.getZ())) && !g.has(at);
    }

    static int mirror(int local)
    {
        return local == RIGHT ? LEFT : local == LEFT ? RIGHT : local;
    }

    boolean free(int u, int v, int row)
    {
        if (u < 0 || u >= w || v < 0 || v >= d || row < 0 || row >= h)
            return false;
        var at = s.at(u, v, row);
        return s.cells().contains(at) && !reserved.contains(new BlockPos(at.getX(), s.floorY + 1, at.getZ())) && !g.has(at);
    }

    boolean put(int u, int v, int row, Kind kind, int facing)
    {
        if (!free(u, v, row))
            return false;
        g.set(s.at(u, v, row), kind, s.dir(facing));
        return true;
    }

    /**
     * Air vents: a grille set high into each side wall, in a pair that mirrors, found by moving
     * outward from the middle of the wall past any window.
     */
    void vents()
    {
        int row = Math.min(2, h - 1);
        for (int step = 0; step < d; step++)
            for (int sign : new int[]{1, -1})
            {
                int v = d / 2 + sign * step;
                if (v < 0 || v >= d)
                    continue;
                var left = s.at(-1, v, row);
                var right = s.at(w, v, row);
                if (g.kind(left) == Kind.WALL && g.kind(right) == Kind.WALL)
                {
                    g.set(left, Kind.VENT, s.dir(RIGHT));
                    g.set(right, Kind.VENT, s.dir(LEFT));
                    return;
                }
            }
    }

    /** Mounts a block in the far wall itself, in mirrored pairs (engines: their exhaust opens through the hull). */
    void wallPair(int du, Kind kind, int facing)
    {
        g.set(s.at(c - du, d, 0), kind, s.dir(facing));
        if (du != 0)
            g.set(s.at(c + du, d, 0), kind, s.dir(mirror(facing)));
    }

    /**
     * A double chest on two neighbouring cells, {@code u0, v} and the next along {@code v} (or along
     * {@code u} if {@code alongV} is false), both facing {@code facing}. Does nothing if either is taken.
     */
    boolean doubleChest(int u0, int v, int row, int facing, boolean alongV)
    {
        int u1 = alongV ? u0 : u0 + 1, v1 = alongV ? v + 1 : v;
        if (!free(u0, v, row) || !free(u1, v1, row))
            return false;
        var face = s.dir(facing);
        var a = s.at(u0, v, row);
        var bAt = s.at(u1, v1, row);
        // A RIGHT half's partner is counter-clockwise of the facing, a LEFT half's clockwise.
        boolean aRight = bAt.equals(a.relative(face.getCounterClockWise()));
        g.set(a, aRight ? Kind.CHEST_RIGHT : Kind.CHEST_LEFT, face);
        g.set(bAt, aRight ? Kind.CHEST_LEFT : Kind.CHEST_RIGHT, face);
        return true;
    }

    /** Replaces the floor itself at {@code u, v} (planter beds are sunk into it). */
    void floor(int u, int v, Kind kind)
    {
        var at = s.at(u, v, -1);
        if (s.cells().contains(s.at(u, v, 0)) && g.kind(at) == Kind.FLOOR && !reserved.contains(new BlockPos(at.getX(), s.floorY + 1, at.getZ())))
            g.set(at, kind);
    }

    /** A single item against the left wall (as you walk in), facing the aisle: for things a room has one of. */
    boolean left(int v, int row, Kind kind)
    {
        return put(0, v, row, kind, RIGHT);
    }

    /** A single item against the right wall, facing the aisle. */
    boolean right(int v, int row, Kind kind)
    {
        return put(w - 1, v, row, kind, LEFT);
    }

    /** Places at {@code c - du} and, mirrored, at {@code c + du}. */
    void pair(int du, int v, int row, Kind kind, int facing)
    {
        put(c - du, v, row, kind, facing);
        if (du != 0)
            put(c + du, v, row, kind, mirror(facing));
    }

    void stack(int du, int v, Kind kind, int facing, int rows)
    {
        for (int row = 0; row < rows; row++)
            pair(du, v, row, kind, facing);
    }

    /** Ceiling lights down the middle, and in pairs further out in wide rooms. */
    void lights(int spacing)
    {
        for (int v = Math.min(1, d - 1); v < d; v += spacing)
        {
            light(c, v);
            // Also near each wall, so corners are as bright as the middle.
            light(1, v);
            light(w - 2, v);
            for (int du = 3; du < c; du += 3)
            {
                light(c - du, v);
                light(c + du, v);
            }
        }
    }

    /** A light set flush into the ceiling, or into a glass roof (a station dome keeps its lantern lattice in the glass). */
    void roofLight(int u, int v)
    {
        if (u < 0 || u >= w || v < 0 || v >= d)
            return;
        var at = s.at(u, v, h);
        if (g.kind(at) == Kind.CEILING || g.kind(at) == Kind.FLOOR || g.kind(at) == Kind.GLASS)
            g.set(at, Kind.LIGHT);
    }

    void light(int u, int v)
    {
        if (u < 0 || u >= w || v < 0 || v >= d)
            return;
        var at = s.at(u, v, h);
        // On the lower deck the ceiling is the deck above's floor, so a light sits flush in that floor.
        if (g.kind(at) == Kind.CEILING || g.kind(at) == Kind.FLOOR)
            g.set(at, Kind.LIGHT);
    }

    /** A floor stripe of accent blocks down the aisle from the entry. */
    void stripe(int fromV, int toV)
    {
        // Only walkways get an accent path; rooms keep their own floor.
        if (s.use != RoomUse.CORRIDOR)
            return;
        for (int v = fromV; v <= toV && v < d; v++)
        {
            var at = s.at(c, v, -1);
            if (g.kind(at) == Kind.FLOOR)
                g.set(at, Kind.ACCENT);
        }
    }
}
