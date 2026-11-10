package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

import static com.nullandkale.nullspace.worldgen.shipgen.RoomUse.*;

/**
 * The silhouette families. Each lays rooms out symmetric about {@code x = cx}, with straight
 * walkways and openings centred on the walls they pass through.
 */
final class Shapes
{
    static final int LIMIT = 32;

    /** Blocks the dock's ramp adds in front of the ship. */
    static final int RAMP = 1;

    static Layout layout(Spec spec)
    {
        return switch (spec.shape())
        {
            case SPINE -> spine(spec, false);
            case TWIN -> spine(spec, true);
            case DOME -> dome(spec);
        };
    }

    private static Link opening(int x, int y, int z, Direction across, int height)
    {
        return new Link(new BlockPos(x, y, z), across, 3, Math.min(3, height));
    }

    /** One corridor (or two, for the twin hull) from the bridge to engineering, with rooms either side. */
    private static Layout spine(Spec spec, boolean twin)
    {
        var t = spec.tier();
        int h = t.height, decks = twin ? 1 : t.decks, pitch = h + 1;
        int wr = twin ? Math.min(t.cell, 7) : t.cell;
        int gap = twin ? (wr >= 7 ? 3 : 5) : 0;
        int cx = twin ? wr + (9 + gap) / 2 : wr + 2;
        int[] corridors = twin ? new int[]{wr + 1, wr + 3, 2 * cx - wr - 3, 2 * cx - wr - 1} : new int[]{cx - 1, cx + 1};
        int leftWall = corridors[0] - 1, rightWall = corridors[corridors.length - 1] + 1;
        int frontHalf = twin ? cx - wr - 1 : new int[]{3, 4, 5}[t.ordinal()], rearHalf = twin ? frontHalf : new int[]{5, 6, 7}[t.ordinal()];
        int len = twin ? Math.min(t.length, 5) : t.length;
        int frontDepth = 5, rearDepth = decks > 1 ? len - 1 : len;
        var out = new Layout();
        out.cx = cx;
        // Slots along the ship: pairs of rooms, with a stairwell slot between decks. Trim to the build volume.
        int wanted = decks > 1 ? 2 : t == Spec.Tier.SMALL ? 2 : 3;
        var kinds = new ArrayList<Boolean>();
        for (int i = 0; i < wanted; i++)
            kinds.add(decks > 1 && i == 1);
        int minimum = decks > 1 ? 2 : 1;
        for (int rh = t == Spec.Tier.SMALL ? 1 : 2; rh >= 1; rh--)
        {
            out.hullH = rh;
            if (2 * cx + 3 + 2 * rh > LIMIT - RAMP)
                continue;
            var trial = new ArrayList<>(kinds);
            while (trial.size() > minimum && length(trial, frontDepth, rearDepth, len, pitch) + 2 * rh > LIMIT - RAMP)
                trial.removeLast();
            if (length(trial, frontDepth, rearDepth, len, pitch) + 2 * rh <= LIMIT - RAMP)
            {
                kinds = trial;
                break;
            }
        }
        int[] starts = new int[kinds.size()], depths = new int[kinds.size()];
        int z = frontDepth + 2;
        for (int i = 0; i < kinds.size(); i++)
        {
            depths[i] = kinds.get(i) ? pitch + 1 : len;
            starts[i] = z;
            z += depths[i] + 1;
        }
        int rearStart = z;
        int lastEnd = starts[kinds.size() - 1] + depths[kinds.size() - 1] - 1;
        int sideCount = (int) kinds.stream().filter(stairs -> !stairs).count();

        for (int level = 0; level < decks; level++)
        {
            int floorY = level * pitch, row0 = floorY + 1;
            var sideUses = new ArrayDeque<>(Program.sides(spec, level, sideCount, len, wr));
            boolean top = level == decks - 1;
            // A tall ship steps in at the top: the bridge deck has no side rooms, so the decks below show their roofs.
            boolean wide = !(decks >= 3 && top);
            var front = level == 0 && decks > 1 ? ENTRY : top ? BRIDGE : CABIN;
            var rear = level == 0 ? ENGINEERING : top ? VIEWING : LOUNGE;
            var frontSpace = out.add(Space.box("front" + level, front, level, floorY, h, cx - frontHalf, cx + frontHalf, 1, frontDepth, Direction.NORTH, Program.windows(front)));
            out.add(Space.box("rear" + level, rear, level, floorY, h, cx - rearHalf, cx + rearHalf, rearStart, rearStart + rearDepth - 1, Direction.SOUTH, Program.windows(rear)));
            if (level == 0)
            {
                out.dockSpace = frontSpace;
                out.dockWall = new BlockPos(cx, row0, 0);
            }
            for (int i = 0; i < corridors.length; i += 2)
            {
                int mid = (corridors[i] + corridors[i + 1]) / 2;
                out.add(Space.box("corridor" + level + "_" + i, CORRIDOR, level, floorY, h, corridors[i], corridors[i + 1], starts[0], lastEnd, Direction.NORTH, 0));
                out.links.add(opening(mid, row0, frontDepth + 1, Direction.NORTH, h));
                out.links.add(opening(mid, row0, rearStart - 1, Direction.SOUTH, h));
            }
            for (int i = 0; i < kinds.size(); i++)
            {
                int z0 = starts[i], z1 = z0 + depths[i] - 1, zMid = (z0 + z1) / 2;
                if (kinds.get(i))
                {
                    // The corridor swells to seven wide here, with a flight up in each outer lane and a clear walkway between.
                    for (int dx : new int[]{-3, -2, 2, 3})
                        for (int zz = z0; zz <= z1; zz++)
                            for (int y = 0; y < h; y++)
                                out.extra.add(new BlockPos(cx + dx, floorY + 1 + y, zz));
                    if (wide)
                    {
                        out.solids.add(new Layout.Box(0, cx - 4, floorY, floorY + h + 1, z0 - 1, z1 + 1));
                        out.solids.add(new Layout.Box(cx + 4, 2 * cx, floorY, floorY + h + 1, z0 - 1, z1 + 1));
                    }
                    if (level < decks - 1)
                        for (int lane : new int[]{cx - 3, cx + 3})
                            stairs(out, lane, floorY, z1 - 1, pitch);
                    continue;
                }
                if (!wide)
                    continue;
                var use = sideUses.isEmpty() ? STORAGE : sideUses.removeFirst();
                // Rooms either side, mirror images: entered through the wall that faces the corridor.
                out.add(Space.box("left" + level + "_" + i, use, level, floorY, h, 0, leftWall - 1, z0, z1, Direction.WEST, Program.windows(use)));
                out.add(Space.box("right" + level + "_" + i, use, level, floorY, h, rightWall + 1, 2 * cx, z0, z1, Direction.EAST, Program.windows(use)));
                out.links.add(opening(leftWall, row0, zMid, Direction.EAST, h));
                out.links.add(opening(rightWall, row0, zMid, Direction.WEST, h));
            }
        }
        return out;
    }

    private static int length(List<Boolean> kinds, int frontDepth, int rearDepth, int length, int pitch)
    {
        int z = frontDepth + 2;
        for (boolean stairs : kinds)
            z += (stairs ? pitch + 1 : length) + 1;
        return z + rearDepth + 1;
    }

    /**
     * A straight flight up toward the nose in one lane, with a landing cell in front of the first
     * step (the stair slot is one cell deeper than the climb) so a person walks in line with it and
     * never has to step sideways onto a stair, which takes a jump. The floor above is opened along the whole
     * flight: a person raises one block before stepping onto the next stair, so they need two
     * blocks of clear air over each step.
     */
    private static void stairs(Layout out, int lane, int floorY, int footZ, int pitch)
    {
        var foot = new BlockPos(lane, floorY + 1, footZ);
        out.flights.add(new Layout.Flight(foot, Direction.NORTH, pitch));
        int slab = floorY + pitch;
        for (int k = 0; k <= pitch - 1; k++)
            out.extra.add(new BlockPos(lane, slab, footZ - k));
    }

    /** A round commons under a glass dome, with the bridge, engineering and a pair of rooms off its four arms. */
    private static Layout dome(Spec spec)
    {
        var t = spec.tier();
        int i = t.ordinal();
        int h = t.height, radius = new int[]{4, 5, 6}[i], depth = new int[]{5, 6, 5}[i], length = new int[]{5, 7, 9}[i];
        int bridgeHalf = new int[]{3, 4, 4}[i], bridgeDepth = 5, engineHalf = new int[]{4, 5, 6}[i], engineDepth = 5;
        int cx = 40, cz = 40;
        var out = new Layout();
        out.cx = cx;
        out.hullH = i == 0 ? 1 : 1;
        int row0 = 1;

        var cells = new HashSet<BlockPos>();
        for (int dx = -(radius + 1); dx <= radius + 1; dx++)
            for (int dz = -(radius + 1); dz <= radius + 1; dz++)
            {
                int d2 = dx * dx + dz * dz;
                boolean arm = Math.abs(dx) <= 1 && Math.abs(dz) <= radius + 1 || Math.abs(dz) <= 1 && Math.abs(dx) <= radius + 1;
                if (d2 > radius * radius && !arm)
                    continue;
                int rows = h + (arm ? 0 : d2 <= (radius - 3) * (radius - 3) ? 2 : d2 <= (radius - 1) * (radius - 1) ? 1 : 0);
                for (int y = 0; y < rows; y++)
                    cells.add(new BlockPos(cx + dx, row0 + y, cz + dz));
            }
        out.add(Space.custom("hub", HUB, 0, 0, h + 2, Direction.NORTH, 2 * radius + 1, 2 * radius + 1, new BlockPos(cx - radius, row0, cz + radius), cells, 2));

        int wallN = cz - radius - 2, wallS = cz + radius + 2, wallE = cx + radius + 2, wallW = cx - radius - 2;
        var bridge = out.add(Space.box("bridge", BRIDGE, 0, 0, h, cx - bridgeHalf, cx + bridgeHalf, wallN - bridgeDepth, wallN - 1, Direction.NORTH, 2));
        out.add(Space.box("engineering", ENGINEERING, 0, 0, h, cx - engineHalf, cx + engineHalf, wallS + 1, wallS + engineDepth, Direction.SOUTH, 0));
        var use = Program.sides(spec, 0, 1, length, depth).getFirst();
        out.add(Space.box("east", use, 0, 0, h, wallE + 1, wallE + depth, cz - length / 2, cz + length / 2, Direction.EAST, Program.windows(use)));
        out.add(Space.box("west", use, 0, 0, h, wallW - depth, wallW - 1, cz - length / 2, cz + length / 2, Direction.WEST, Program.windows(use)));
        out.links.add(opening(cx, row0, wallN, Direction.NORTH, h));
        out.links.add(opening(cx, row0, wallS, Direction.SOUTH, h));
        out.links.add(opening(wallE, row0, cz, Direction.EAST, h));
        out.links.add(opening(wallW, row0, cz, Direction.WEST, h));
        out.dockSpace = bridge;
        out.dockWall = new BlockPos(cx, row0, wallN - bridgeDepth - 1);
        return out;
    }

    private Shapes()
    {
    }
}
