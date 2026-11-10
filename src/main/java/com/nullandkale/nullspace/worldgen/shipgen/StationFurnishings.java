package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.BlockPos;

import static com.nullandkale.nullspace.worldgen.shipgen.Frame.*;
import static com.nullandkale.nullspace.worldgen.shipgen.Kind.*;
import static com.nullandkale.nullspace.worldgen.shipgen.StationGenerator.Kit;

/**
 * What goes in a station tile. Each tile is a pair of big halls, one per story, furnished by the
 * ship's rules: units against the walls with open cells at either end, open floor between, and
 * somewhere in the middle for people to gather. The hall is fourteen wide, so it does not mirror
 * about a centre cell: everything is placed by explicit cells, and the shell's pairs are
 * mirrored about the line between the middle two.
 *
 * <p>The walls have their doorway slots at cells 2 and 11 (kept clear in front of each). A flight
 * of stairs runs along the south wall and another along the north, so the west and east walls
 * hold the furnishing, each in a zone of cells 3 to 10 with the door approaches at either end.
 */
final class StationFurnishings
{
    /** The walls, by the side of the hall they are on. */
    private static final int SOUTH = 0, NORTH = 1, WEST = 2, EAST = 3;

    static void furnish(Kit kit, Space s, Grid g, List<Link> links)
    {
        var f = new Frame(s, g, links);
        // The stairs along the south and north walls, their landings and the shaft over them: nothing stands there.
        for (int u = 3; u <= 11; u++)
        {
            f.reserve(u, 0);
            f.reserve(u, f.d - 1);
        }
        boolean upper = s.level == 1;
        switch (kit)
        {
            case ENTRANCE ->
            {
                if (upper)
                    gallery(f);
                else
                    concourse(f);
            }
            case WORKSHOP ->
            {
                if (upper)
                    stores(f);
                else
                    workshop(f);
            }
            case HABITATION ->
            {
                if (upper)
                    dormitory(f);
                else
                    mess(f);
            }
            case POWER ->
            {
                if (upper)
                    control(f);
                else
                    plant(f);
            }
            case PROMENADE -> promenade(f);
            case GARDEN ->
            {
                if (upper)
                    gallery(f);
                else
                    garden(f);
            }
            case OBSERVATORY -> observatory(f, upper);
        }
        // One air supply for each tile's air, at the end of the west wall below (engineering has its pair).
        if (!upper && kit != Kit.POWER)
            f.put(0, 10, 0, AIR_SUPPLY, RIGHT);
        f.vents();
        for (int u : new int[]{3, 6, 7, 10})
            for (int v : new int[]{3, 6, 7, 10})
                f.roofLight(u, v);
        // The rows along the stairs, which the grid does not reach.
        for (int u : new int[]{3, 6, 7, 10})
        {
            f.roofLight(u, 0);
            f.roofLight(u, f.d - 1);
        }
        for (int u : new int[]{1, 12})
        {
            f.roofLight(u, 0);
            f.roofLight(u, f.d - 1);
        }
        wallLights(f);
        // Four lanterns set flush into the floor light the middle, which the ceiling is too far above to reach.
        for (int u : new int[]{4, 9})
            for (int v : new int[]{4, 9})
                f.floor(u, v, LIGHT);
    }

    /** The halls are six high, so the ceiling lanterns are far from the floor: lanterns set into the walls at head height keep the edges bright. */
    private static void wallLights(Frame f)
    {
        int row = Math.min(3, f.h - 1);
        for (int k : new int[]{3, 6, 7, 10})
        {
            for (var at : new BlockPos[]{f.s.at(-1, k, row), f.s.at(f.w, k, row), f.s.at(k, -1, row), f.s.at(k, f.d, row)})
                if (f.g.kind(at) == WALL)
                    f.g.set(at, LIGHT);
        }
    }

    // ---- The halls ----

    /** The ground floor of the entrance: lockers down the west wall, a wall of screens down the east with a reception desk and its terminal in front, and a table to meet at. */
    private static void concourse(Frame f)
    {
        segment(f, WEST, 1, true, BARREL, BARREL, BARREL);
        screens(f, EAST, 4, 9);
        // The station core stands in the counter beside the terminal.
        f.put(f.w - 4, 7, 0, STATION_CORE, LEFT);
        desk(f, 4, 9);
        table(f, 6, 5, 2, 2);
    }

    /** A quiet upper floor: shelves down both walls, and a long table to sit at. */
    private static void gallery(Frame f)
    {
        shelves(f, WEST, 4, 9);
        shelves(f, EAST, 4, 9);
        table(f, 6, 4, 2, 4);
    }

    private static void workshop(Frame f)
    {
        segment(f, WEST, 1, true, CRAFTING_TABLE, SMITHING_TABLE, STONECUTTER, ANVIL);
        segment(f, EAST, 1, true, LOOM, GRINDSTONE, FLETCHING_TABLE, CARTOGRAPHY_TABLE);
        table(f, 6, 5, 2, 2);
    }

    /** The smelting floor above the workshop: furnaces down one wall and blast furnaces down the other, each with its chest of fuel. */
    private static void stores(Frame f)
    {
        segment(f, WEST, 1, true, FURNACE, FURNACE, FURNACE);
        segment(f, EAST, 1, true, BLAST_FURNACE, BLAST_FURNACE, BLAST_FURNACE);
        table(f, 6, 5, 2, 2);
    }

    /** Food stores down one wall and prep down the other, with a long table to eat at between. */
    private static void mess(Frame f)
    {
        segment(f, WEST, 1, true, BARREL, BARREL, BARREL);
        segment(f, EAST, 1, true, SMOKER, CAULDRON, SMOKER);
        table(f, 6, 4, 2, 5);
    }

    /** Beds in their own bays down both walls, a locker by each pillow, and a table between. */
    private static void dormitory(Frame f)
    {
        for (int v : new int[]{9, 5})
        {
            f.put(0, v, 0, BED, LEFT);
            f.put(1, v, 0, BED_FOOT, LEFT);
            f.put(0, v + 1, 0, CHEST, RIGHT);
            f.put(f.w - 1, v, 0, BED, RIGHT);
            f.put(f.w - 2, v, 0, BED_FOOT, RIGHT);
            f.put(f.w - 1, v + 1, 0, CHEST, LEFT);
        }
        table(f, 6, 5, 2, 2);
    }

    /** Generators with batteries stacked on them against both walls, an air supply at the end of each, a table in the middle. */
    private static void plant(Frame f)
    {
        for (int v : new int[]{4, 6, 8})
        {
            both(f, 0, v, GENERATOR);
            both(f, 0, v, 1, BATTERY);
        }
        both(f, 0, 10, 0, AIR_SUPPLY);
        table(f, 6, 5, 2, 2);
    }

    /** The control room above: a wall of screens, a desk with the one terminal, and the one scanner on the far wall. */
    private static void control(Frame f)
    {
        screens(f, WEST, 4, 9);
        desk(f, 4, 9, true);
        shelves(f, EAST, 4, 5);
        f.put(f.w - 1, 7, 0, SCANNER, LEFT);
        table(f, 6, 5, 2, 2);
    }

    /** The promenade: a planter in the middle ringed with benches, shelves down both walls. */
    private static void promenade(Frame f)
    {
        for (int u = 6; u <= 7; u++)
            for (int v = 6; v <= 7; v++)
                f.put(u, v, 0, PLANT, DEEPER);
        for (int k = 5; k <= 8; k++)
        {
            f.put(5, k, 0, SEAT, RIGHT);
            f.put(8, k, 0, SEAT, LEFT);
        }
        for (int k = 6; k <= 7; k++)
        {
            f.put(k, 5, 0, SEAT, DEEPER);
            f.put(k, 8, 0, SEAT, TOWARD_ENTRY);
        }
        shelves(f, WEST, 4, 9);
        shelves(f, EAST, 4, 9);
    }

    /** A pond of real water between two beds of crops sunk into the floor, mossy paths either side, grow lights over the beds and composters down a wall. */
    private static void garden(Frame f)
    {
        var crops = new Kind[]{CROP, CROP2, CROP3};
        for (int v = 3; v <= 10; v++)
        {
            for (int u : new int[]{4, 5})
            {
                f.floor(u, v, FARMLAND);
                f.floor(f.w - 1 - u, v, FARMLAND);
                var crop = crops[(u + v) % 3];
                f.put(u, v, 0, crop, RIGHT);
                f.put(f.w - 1 - u, v, 0, crop, LEFT);
            }
            f.floor(6, v, WATER);
            f.floor(7, v, WATER);
            // Grow lights flush in the path beside the beds, every other cell.
            for (int u : new int[]{1, 2, 3, 10, 11, 12})
                f.floor(u, v, (u == 3 || u == 10) && v % 2 == 0 ? LIGHT : MOSS);
        }
        for (int v = 3; v <= 10; v += 2)
            for (int u : new int[]{4, 5})
            {
                f.roofLight(u, v);
                f.roofLight(f.w - 1 - u, v);
            }
        segment(f, WEST, 1, true, COMPOSTER, COMPOSTER, COMPOSTER, COMPOSTER);
        bench(f, EAST, 4, 9);
    }

    /** The glass dome: a ring of seats looking out with a gap on each side, and a scanner and a terminal at the middle. */
    private static void observatory(Frame f, boolean upper)
    {
        for (int k = 4; k <= 9; k++)
        {
            if (k == 6 || k == 7)
                continue;
            f.put(3, k, 0, SEAT, RIGHT);
            f.put(f.w - 4, k, 0, SEAT, LEFT);
            f.put(k, 3, 0, SEAT, DEEPER);
            f.put(k, f.d - 4, 0, SEAT, TOWARD_ENTRY);
        }
        if (upper)
        {
            table(f, 6, 6, 2, 2);
            return;
        }
        for (int u = 6; u <= 7; u++)
            for (int v = 6; v <= 7; v++)
                f.put(u, v, 0, TABLE, DEEPER);
        f.put(6, 6, 1, SCANNER, DEEPER);
        f.put(7, 7, 1, TERMINAL, DEEPER);
    }

    // ---- Pieces ----

    /** Which way a thing against {@code side} faces: into the hall. */
    private static int into(int side)
    {
        return switch (side)
        {
            case SOUTH -> DEEPER;
            case NORTH -> TOWARD_ENTRY;
            case WEST -> RIGHT;
            default -> LEFT;
        };
    }

    /** The cell at position {@code p} along {@code side}: u for the south and north walls, v for the west and east. */
    private static int[] cell(Frame f, int side, int p)
    {
        return switch (side)
        {
            case SOUTH -> new int[]{p, 0};
            case NORTH -> new int[]{p, f.d - 1};
            case WEST -> new int[]{0, p};
            default -> new int[]{f.w - 1, p};
        };
    }

    /**
     * A unit against a wall: a double chest and then the machines that use it, one cell each.
     * The wall's zone is cells 3 to 10; {@code start} counts from 3, so a six-cell unit at
     * start 1 leaves a free cell at each end.
     */
    private static void segment(Frame f, int side, int start, boolean twin, Kind... machines)
    {
        int p = 3 + start;
        if (twin)
        {
            var at = cell(f, side, p);
            f.doubleChest(at[0], at[1], 0, into(side), side >= WEST);
            p += 2;
        }
        for (var kind : machines)
        {
            var at = cell(f, side, p++);
            f.put(at[0], at[1], 0, kind, into(side));
        }
    }

    /** Double chests, {@code rows} high, {@code pairs} of them along a wall's zone. */
    private static void stock(Frame f, int side, int start, int pairs, int rows)
    {
        for (int row = 0; row < rows; row++)
            for (int i = 0; i < pairs; i++)
            {
                var at = cell(f, side, 3 + start + 2 * i);
                int u = at[0], v = at[1];
                // Stacked rows: place a double chest at this height.
                var face = f.s.dir(into(side));
                var a = f.s.at(u, v, row);
                var b = side >= WEST ? f.s.at(u, v + 1, row) : f.s.at(u + 1, v, row);
                if (f.free(u, v, row) && f.free(side >= WEST ? u : u + 1, side >= WEST ? v + 1 : v, row))
                {
                    boolean aRight = b.equals(a.relative(face.getCounterClockWise()));
                    f.g.set(a, aRight ? CHEST_RIGHT : CHEST_LEFT, face);
                    f.g.set(b, aRight ? CHEST_LEFT : CHEST_RIGHT, face);
                }
            }
    }

    /** Bookshelves two high along a wall, cells {@code from} to {@code to}. */
    private static void shelves(Frame f, int side, int from, int to)
    {
        for (int p = from; p <= to; p++)
        {
            var at = cell(f, side, p);
            for (int row = 0; row < Math.min(2, f.h); row++)
                f.put(at[0], at[1], row, BOOKSHELF, into(side));
        }
    }

    /** A bench of stair seats against a wall, backs to it, cells {@code from} to {@code to}. */
    private static void bench(Frame f, int side, int from, int to)
    {
        int back = switch (side)
        {
            case SOUTH -> TOWARD_ENTRY;
            case NORTH -> DEEPER;
            case WEST -> LEFT;
            default -> RIGHT;
        };
        for (int p = from; p <= to; p++)
        {
            var at = cell(f, side, p);
            f.put(at[0], at[1], 0, SEAT, back);
        }
    }

    /** A table {@code wide} by {@code long} with a seat at every cell round it, each looking in. */
    private static void table(Frame f, int u0, int v0, int wide, int len)
    {
        for (int u = u0; u < u0 + wide; u++)
            for (int v = v0; v < v0 + len; v++)
                f.put(u, v, 0, TABLE, DEEPER);
        for (int v = v0; v < v0 + len; v++)
        {
            f.put(u0 - 1, v, 0, SEAT, LEFT);
            f.put(u0 + wide, v, 0, SEAT, RIGHT);
        }
        for (int u = u0; u < u0 + wide; u++)
        {
            f.put(u, v0 - 1, 0, SEAT, TOWARD_ENTRY);
            f.put(u, v0 + len, 0, SEAT, DEEPER);
        }
    }

    /** A wall of screens against a side wall, two high, cells {@code from} to {@code to} along it. */
    private static void screens(Frame f, int side, int from, int to)
    {
        for (int p = from; p <= to; p++)
        {
            var at = cell(f, side, p);
            for (int row = 0; row < 2; row++)
                f.put(at[0], at[1], row, MONITOR, into(side));
        }
    }

    /** A counter of table slabs two cells in front of a screen wall, with the room's one terminal set into it; the staff side is two wide. */
    private static void desk(Frame f, int from, int to)
    {
        desk(f, from, to, false);
    }

    private static void desk(Frame f, int from, int to, boolean west)
    {
        int deskU = west ? 3 : f.w - 4;
        for (int v = from; v <= to; v++)
            f.put(deskU, v, 0, v == (from + to) / 2 ? TERMINAL : TABLE, west ? RIGHT : LEFT);
    }

    /** One machine at cell {@code u}, row 0, and its mirror: {@code kind} against both side walls at {@code v}. */
    private static void both(Frame f, int u, int v, Kind kind)
    {
        both(f, u, v, 0, kind);
    }

    private static void both(Frame f, int u, int v, int row, Kind kind)
    {
        f.put(u, v, row, kind, RIGHT);
        f.put(f.w - 1 - u, v, row, kind, LEFT);
    }

    private static void both(Frame f, int u, int v, int row, Kind kind, int facing)
    {
        f.put(u, v, row, kind, facing);
        f.put(f.w - 1 - u, v, row, kind, Frame.mirror(facing));
    }

    private StationFurnishings()
    {
    }
}
