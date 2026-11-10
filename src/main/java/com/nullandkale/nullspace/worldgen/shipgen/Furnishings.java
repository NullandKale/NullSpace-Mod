package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

import static com.nullandkale.nullspace.worldgen.shipgen.Frame.*;
import static com.nullandkale.nullspace.worldgen.shipgen.Kind.*;

/**
 * What goes in each kind of room. Every room is laid out in its own frame as mirrored pairs along
 * its walls, with a three-wide aisle from the entry to the far wall left clear. A room holds
 * everything its task needs and nothing from another task.
 */
final class Furnishings
{
    /** Furnishes {@code s} in {@code g}. {@code dock} is true for the room the docking door opens into. */
    static void furnish(Space s, Grid g, List<Link> links, boolean dock)
    {
        var f = new Frame(s, g, links);
        if (dock)
        {
            f.reserve(f.c, f.d - 1);
            f.reserve(f.c, f.d - 2);
        }
        switch (s.use)
        {
            case BRIDGE -> bridge(f);
            case ENTRY -> entry(f);
            case ENGINEERING -> engineering(f);
            case CARGO -> cargo(f, false);
            case STORAGE -> cargo(f, true);
            case CREW -> crew(f);
            case CABIN -> cabin(f);
            case CRAFTING -> crafting(f);
            case SMELTING -> smelting(f, false);
            case MINING -> smelting(f, true);
            case GALLEY -> galley(f);
            case VIEWING -> viewing(f);
            case ENCHANTING -> enchanting(f);
            case BREWING -> brewing(f);
            case HYDROPONICS -> hydroponics(f);
            case LOUNGE -> lounge(f);
            case LAB -> lab(f);
            case HUB -> hub(f);
            case CORRIDOR ->
            {
                f.stripe(0, f.d - 1);
                f.lights(2);
                return;
            }
            default ->
            {
                f.lights(2);
                return;
            }
        }
        f.vents();
        f.lights(s.use == RoomUse.HYDROPONICS ? 2 : 3);
    }

    private static void bridge(Frame f)
    {
        // The helm in front of the docking door, with lanes either side. One terminal and one scanner, one each side, a row before it.
        f.put(f.c, f.d - 3, 0, COMPUTER, TOWARD_ENTRY);
        // Wherever there is room against a side wall: a row before the helm, then back toward the door, then beside the helm if the room is wide.
        var rows = new ArrayList<Integer>();
        for (int v = Math.max(1, f.d - 4); v >= 1; v--)
            rows.add(v);
        if (f.w >= 7)
        {
            rows.add(f.d - 2);
            rows.add(f.d - 3);
        }
        for (int v : rows)
            if (f.left(v, 0, TERMINAL))
                break;
        for (int v : rows)
            if (f.right(v, 0, SCANNER))
                break;
        // Forward screens either side of the docking door.
        for (int du = 2; du < f.c; du++)
            for (int row = 0; row < Math.min(2, f.h - 1); row++)
                f.pair(du, f.d - 1, row, MONITOR, TOWARD_ENTRY);
        f.stripe(0, f.d - 4);
    }

    private static void entry(Frame f)
    {
        // Lockers for suits and spare air along the walls, one map table by the door, and the air supply.
        for (int v = 1; v <= f.d - 3; v++)
            f.stack(f.c, v, BARREL, RIGHT, 2);
        f.left(f.d - 2, 0, CARTOGRAPHY_TABLE);
        f.pair(f.c, 0, 0, AIR_SUPPLY, RIGHT);
        f.stripe(0, f.d - 1);
    }

    private static void engineering(Frame f)
    {
        // Banks stand against the walls, leaving two clear rows before the engines at the stern.
        int n = Math.min(f.d - 3, 3);
        for (int i = 0; i < n; i++)
        {
            // Against the wall where it is free, or as near it as it can be.
            int du = f.c;
            while (du > 2 && !f.free(f.c - du, i, 0))
                du--;
            f.pair(du, i, 0, GENERATOR, TOWARD_ENTRY);
            f.pair(du, i, 1, BATTERY, TOWARD_ENTRY);
            if (du >= 4)
                f.stack(du - 1, i, BARREL, TOWARD_ENTRY, 2);
        }
        f.pair(f.c, f.d - 1, 0, AIR_SUPPLY, RIGHT);
        // A fuel locker by the engines, whatever the room's size.
        f.stack(f.c, f.d - 2, BARREL, RIGHT, 2);
        for (int du = 3; du < f.c; du += 3)
            f.wallPair(du, ENGINE, DEEPER);
        f.stripe(0, f.d - 2);
    }

    /**
     * A workstation unit against a side wall, anchored in the back corner: a chest first (single when
     * the unit is three cells, double when longer), then the machines that use it, working toward
     * the door. The wall is {@code d - 1} cells long and the cells nearest the door stay open, more
     * of them in a deeper room, so the unit grows with the room and leaves floor beside it.
     */
    static void unit(Frame f, boolean left, Kind... machines)
    {
        unit(f, left, false, 3, machines);
    }

    /** A unit with its size choices: {@code singleChest} keeps the chest to one block to leave room for more machines, up to {@code cap}. */
    static void unit(Frame f, boolean left, boolean singleChest, int cap, Kind... machines)
    {
        int open = Math.max(1, (f.d - 3) / 2);
        int length = f.d - 1 - open;
        int u = left ? 0 : f.w - 1, facing = left ? RIGHT : LEFT;
        int chest = length >= 4 && !singleChest ? 2 : 1;
        if (chest == 2)
            f.doubleChest(u, f.d - 2, 0, facing, true);
        else
            f.put(u, f.d - 1, 0, CHEST, facing);
        int v = f.d - 1 - chest;
        for (int i = 0; i < machines.length && i < length - chest && i < cap; i++, v--)
            f.put(u, v, 0, machines[i], facing);
    }

    /** Stock along a side wall from the back corner: {@code kind} in every cell of the same length as a unit, {@code rows} high (chests as doubles). */
    static void stockWall(Frame f, boolean left, Kind kind, int rows)
    {
        int open = Math.max(1, (f.d - 3) / 2);
        int length = f.d - 1 - open;
        int u = left ? 0 : f.w - 1, facing = left ? RIGHT : LEFT;
        for (int row = 0; row < rows; row++)
        {
            int v = f.d - 1;
            int left_ = length;
            while (left_ > 0)
            {
                if (kind == CHEST && left_ >= 2)
                {
                    f.doubleChest(u, v - 1, row, facing, true);
                    v -= 2;
                    left_ -= 2;
                }
                else
                {
                    f.put(u, v, row, kind, facing);
                    v--;
                    left_--;
                }
            }
        }
    }

    /** A little storage by the door for a room that works with items: a stack of {@code kind} either side. */
    static void doorStore(Frame f, Kind kind)
    {
        for (int row = 0; row < 2; row++)
        {
            f.left(0, row, kind);
            f.right(0, row, kind);
        }
    }

    private static void cargo(Frame f, boolean sorted)
    {
        // Storage: double chests stacked two high along both walls from the back corner. The hold: the same with barrels. The middle and the door end stay open.
        stockWall(f, true, sorted ? CHEST : BARREL, 2);
        stockWall(f, false, sorted ? CHEST : BARREL, 2);
    }

    private static void crew(Frame f)
    {
        // A footlocker by the door, whatever the size of the room.
        f.pair(f.c, 0, 0, CHEST, RIGHT);
        // Each bed has its own stretch of the room, with open floor between beds, and a locker by the pillow where the room is wide.
        for (int head = f.d - 1; head - 1 >= 1; head -= 3)
        {
            f.pair(f.c, head, 0, BED, DEEPER);
            f.pair(f.c, head - 1, 0, BED_FOOT, DEEPER);
            if (f.w >= 7)
                f.pair(f.c - 1, head, 0, CHEST, RIGHT);
        }
    }

    private static void cabin(Frame f)
    {
        // The bed on the centre line with clear floor either side. A store down the left wall, and down the right a terminal, a screen and a desk with its chair and bookshelf.
        // A small cabin has the bed against the left wall and no store, so no strip beside it is a single cell wide.
        boolean roomy = f.w >= 7;
        int bed = roomy ? f.c : 0;
        f.put(bed, f.d - 1, 0, BED, DEEPER);
        f.put(bed, f.d - 2, 0, BED_FOOT, DEEPER);
        if (roomy)
            unit(f, true, BARREL);
        else
            f.put(1, f.d - 1, 0, CHEST, RIGHT);
        f.right(1, 0, TERMINAL);
        for (int v = 3; v <= Math.min(4, f.d - 4); v++)
            for (int row = 0; row < 2; row++)
                f.right(v, row, MONITOR);
        for (int row = 0; row < 2; row++)
            f.right(f.d - 2, row, BOOKSHELF);
        f.right(f.d - 1, 0, TABLE);
        f.put(f.w - 2, f.d - 1, 0, SEAT, LEFT);
    }

    private static void crafting(Frame f)
    {
        // One of every bench, in units: a chest and benches down each side wall, three a side in a medium room and four in a large one. Each bench is used on its own, so none is repeated.
        unit(f, true, true, 4, CRAFTING_TABLE, SMITHING_TABLE, STONECUTTER, ANVIL);
        unit(f, false, true, 4, LOOM, GRINDSTONE, FLETCHING_TABLE, CARTOGRAPHY_TABLE);
    }

    private static void smelting(Frame f, boolean ore)
    {
        // A chest of fuel and stock with furnaces on one wall and blast furnaces on the other: two a side in a small or medium room, three in a large one. The mining bay has blast furnaces and a grindstone on one wall and ore bins on the other.
        if (ore)
        {
            unit(f, true, BLAST_FURNACE, BLAST_FURNACE, GRINDSTONE);
            unit(f, false, BARREL, BARREL, BARREL);
        }
        else
        {
            unit(f, true, FURNACE, FURNACE, FURNACE);
            unit(f, false, BLAST_FURNACE, BLAST_FURNACE, BLAST_FURNACE);
        }
    }

    private static void galley(Frame f)
    {
        // Storage: a chest and barrels down the left wall. Prep: a chest of food with smokers and a cauldron for water down the right wall. A large room also has a dining table, two long with a chair either side, by the door.
        unit(f, true, BARREL, BARREL);
        unit(f, false, true, 3, SMOKER, CAULDRON, SMOKER);
        if (f.d >= 9)
            for (int k = 0; k < 2; k++)
            {
                f.put(f.w - 2, 2 + k, 0, TABLE, LEFT);
                f.put(f.w - 1, 2 + k, 0, SEAT, RIGHT);
                f.put(f.w - 3, 2 + k, 0, SEAT, LEFT);
            }
    }

    private static void viewing(Frame f)
    {
        // One monitor wall is at most eight wide, so the giant screen is seven across.
        int half = Math.min(3, f.c - 1);
        for (int du = 0; du <= half; du++)
            for (int row = 0; row < Math.min(3, f.h - 1); row++)
                f.pair(du, f.d - 1, row, MONITOR, TOWARD_ENTRY);
        // Rows of seats facing the screen with a clear step between, a media terminal to choose what to watch, and a plant in each corner by the door.
        for (int v = 0; v <= f.d - 4; v += 2)
            for (int du = 1; du < f.c; du++)
                f.pair(du, v, 0, SEAT, TOWARD_ENTRY);
        f.right(f.d - 3, 0, TERMINAL);
        f.stripe(0, f.d - 2);
    }

    private static void enchanting(Frame f)
    {
        int tv = f.d - 3;
        f.put(f.c, tv, 0, ENCHANTING_TABLE, TOWARD_ENTRY);
        for (int dv = -2; dv <= 2; dv++)
            for (int du = -2; du <= 2; du++)
            {
                if (Math.max(Math.abs(du), Math.abs(dv)) != 2 || du == 0 && dv == -2)
                    continue;
                for (int row = 0; row < Math.min(2, f.h); row++)
                    f.put(f.c + du, tv + dv, row, BOOKSHELF, TOWARD_ENTRY);
            }
        f.left(1, 0, ANVIL);
        f.right(1, 0, LECTERN);
        // Lapis and books by the door.
        doorStore(f, CHEST);
        f.stripe(0, 1);
    }

    private static void brewing(Frame f)
    {
        // Ingredient chests with brewing stands on one wall, and chests with cauldrons for water on the other.
        unit(f, true, BREWING_STAND, BREWING_STAND, BREWING_STAND);
        unit(f, false, CAULDRON, CAULDRON, CAULDRON);
    }

    private static void hydroponics(Frame f)
    {
        // Beds of farmland sunk into the floor down both sides, three crops in bands, and a water channel sunk into the floor down the middle (the farmland drinks from it) between two moss paths. Crops are walked through.
        var crops = new Kind[]{CROP, CROP2, CROP3};
        for (int v = 1; v <= f.d - 2; v++)
            for (int u = 0; u <= f.c - 2; u++)
            {
                var crop = crops[(v + u) % 3];
                f.floor(u, v, FARMLAND);
                f.put(u, v, 0, crop, RIGHT);
                f.floor(f.w - 1 - u, v, FARMLAND);
                f.put(f.w - 1 - u, v, 0, crop, LEFT);
            }
        for (int v = 0; v <= f.d - 1; v++)
        {
            f.floor(f.c - 1, v, MOSS);
            f.floor(f.c + 1, v, MOSS);
            // The channel runs between the paths, closed at both ends by the floor, so it cannot spill.
            f.floor(f.c, v, v >= 1 && v <= f.d - 2 ? WATER : MOSS);
        }
        // Grow lights over every bed, as well as the room's own lights.
        for (int v = 1; v <= f.d - 2; v += 2)
            for (int u = 0; u <= f.c - 2; u += 2)
            {
                f.light(u, v);
                f.light(f.w - 1 - u, v);
            }
        // Composters along the back wall, and seeds and bone meal by the door.
        for (int du = 1; du <= f.c; du++)
            f.pair(du, f.d - 1, 0, COMPOSTER, TOWARD_ENTRY);
        doorStore(f, BARREL);
    }

    private static void lounge(Frame f)
    {
        for (int v = 1; v <= f.d - 2; v++)
        {
            f.stack(f.c, v, BOOKSHELF, RIGHT, Math.min(2, f.h));
            if (f.w >= 7)
                f.pair(f.c - 1, v, 0, SEAT, LEFT);
        }
        for (int du = 2; du < f.c; du++)
            f.stack(du, f.d - 1, BOOKSHELF, TOWARD_ENTRY, Math.min(2, f.h));
        f.stripe(0, f.d - 2);
    }

    private static void lab(Frame f)
    {
        // Analysis: charts, a lectern and a terminal down one wall, sample storage down the other, screens at the back.
        var left = new Kind[]{CARTOGRAPHY_TABLE, LECTERN, TERMINAL};
        for (int i = 0; i < left.length && 1 + i <= f.d - 2; i++)
            f.left(1 + i, 0, left[i]);
        for (int v = 1; v <= f.d - 2; v++)
        {
            f.right(v, 0, BARREL);
            f.right(v, 1, BARREL);
        }
        for (int du = 2; du < f.c; du++)
        {
            f.pair(du, f.d - 1, 0, MONITOR, TOWARD_ENTRY);
            f.pair(du, f.d - 1, 1, MONITOR, TOWARD_ENTRY);
        }
        f.stripe(0, f.d - 2);
    }

    /** The round commons: crossing aisles stay clear, with seats and tables in each quarter and planters at the wall. */
    private static void hub(Frame f)
    {
        var s = f.s;
        int r = (s.w - 1) / 2;
        double cx = 0, cz = 0;
        int n = 0;
        for (var at : s.cells())
            if (at.getY() == s.floorY + 1)
            {
                cx += at.getX();
                cz += at.getZ();
                n++;
            }
        int mx = (int) Math.round(cx / n), mz = (int) Math.round(cz / n);
        for (var at : new ArrayList<>(s.cells()))
        {
            if (at.getY() != s.floorY + 1)
                continue;
            int dx = at.getX() - mx, dz = at.getZ() - mz;
            if (Math.abs(dx) <= 1 || Math.abs(dz) <= 1)
                continue;
            int d2 = dx * dx + dz * dz, ax = Math.abs(dx), az = Math.abs(dz);
            // A round table in each quarter, seats around it, and planters against the wall.
            // A lamp on each table, and one lamp in every third planter at the wall.
            if (ax <= 3 && az <= 3)
                f.g.set(at, ax == 2 && az == 2 ? LIGHT : TABLE);
            else if (d2 >= (r - 1) * (r - 1))
                f.g.set(at, (ax + az) % 3 == 0 ? LIGHT : PLANT);
            else if (ax == 4 && az <= 3 || az == 4 && ax <= 3)
            {
                var toward = ax == 4 ? (dx < 0 ? Direction.EAST : Direction.WEST) : (dz < 0 ? Direction.SOUTH : Direction.NORTH);
                f.g.set(at, SEAT, toward.getOpposite());
            }
        }
    }

    private Furnishings()
    {
    }
}
