package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

/** The rooms of a ship and how they join, before any block is placed. Coordinates are unshifted; the ship is symmetric about {@code x = cx}. */
final class Layout
{
    /** A solid block of hull mass, inclusive on every side. */
    record Box(int x0, int x1, int y0, int y1, int z0, int z1)
    {}

    /** A straight flight of stairs rising toward {@code up}, {@code steps} blocks, starting at {@code foot}. */
    record Flight(BlockPos foot, Direction up, int steps)
    {}

    final List<Space> spaces = new ArrayList<>();
    final List<Link> links = new ArrayList<>();
    final List<Box> solids = new ArrayList<>();
    final List<Flight> flights = new ArrayList<>();
    /** Doorway cells that only keep furniture clear (a station tile's edge doorways are cut later, when a neighbour joins). */
    final List<Link> reserved = new ArrayList<>();
    /** Set for a station tile: the kit that furnishes it. */
    StationGenerator.Kit station;
    /** Open cells that belong to the interior without being part of a room's box: stairwell holes and top steps. */
    final Set<BlockPos> extra = new HashSet<>();
    /** The room the docking door opens into, and the wall cell (at floor row) the door stands in; it faces north. */
    Space dockSpace;
    BlockPos dockWall;
    int cx;
    /** Horizontal and vertical hull thickness around the rooms. */
    int hullH = 1, hullV = 1;

    Space add(Space s)
    {
        spaces.add(s);
        return s;
    }

    /** Spaces that share a wall with {@code s}: links whose wall cell touches its interior. */
    List<Link> linksOf(Space s)
    {
        var out = new ArrayList<Link>();
        var all = new ArrayList<>(links);
        all.addAll(reserved);
        for (var link : all)
            if (s.cells().contains(link.at().relative(link.across())) || s.cells().contains(link.at().relative(link.across().getOpposite())))
                out.add(link);
        return out;
    }
}
