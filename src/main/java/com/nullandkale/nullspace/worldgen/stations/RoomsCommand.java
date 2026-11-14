package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.RoomKit.Side;
import com.nullandkale.nullspace.worldgen.stations.RoomKit.Spec;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/**
 * Rooms for study, command and security: tiers, consoles and racks, arranged to face something.
 * Costly blocks (scanners, brewing stands, the enchanting table, screens) appear once, or as one
 * deliberate group, never scattered.
 */
final class RoomsCommand
{
    private static BlockState bars()
    {
        return Blocks.IRON_BARS.defaultBlockState();
    }

    /** One bench with the room's single brewing stand, a sample wall, a console and a scanner; a large lab adds a second stand. */
    static void lab(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorChecker(Blocks.SMOOTH_QUARTZ, Blocks.CONCRETE.lightGray());
        int last = k.big() ? k.d - 3 : 4;
        for (int v = 2; v <= last; v++)
        {
            Spec spec = v == 3 || k.big() && v == 7 ? Fx.BREW : v % 2 == 0 ? Fx.waterCauldron() : Fx.LECTERN;
            k.column(0, v, k.right, spec);
        }
        // The sample wall: pots of specimens on shelves.
        for (int v = 2; v <= last; v++)
            k.column(k.w - 1, v, k.left, Fx.SHELF, v % 2 == 0 ? RoomKit.plain(Blocks.DECORATED_POT) : Fx.SHELF);
        k.frame(Side.RIGHT, 3, 1, "minecraft:potion");
        k.frame(Side.RIGHT, 5, 1, "minecraft:spyglass");
        k.column(c, k.d - 1, k.back, Fx.TERMINAL, Fx.MONITOR);
        k.column(c - 2, k.d - 1, k.back, Fx.CHARTS);
        k.column(c + 2, k.d - 1, k.back, Fx.SCANNER);
        k.tableSet(3, k.big() ? 2 : 1, false, k.lantern());
        if (k.big())
            k.tableSet(3, 1, false, null);
    }

    /** Tall stacks of shelves, a reading nook and an enchanting table with its ring of shelves in a back corner. */
    static void archive(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(k.planks);
        var runner = k.palette.carpet().defaultBlockState();
        for (int v = 0; v <= k.d - 6; v++)
            k.put(c, v, 0, runner);
        // The enchanting table and its ring in the back-left corner, so the rest of the room stays open.
        int cu = k.big() ? 3 : 2, cv = k.d - 3;
        k.put(cu, cv, 0, Blocks.ENCHANTING_TABLE.defaultBlockState());
        for (int du = -2; du <= 2; du++)
            for (int dv = -2; dv <= 2; dv++)
                if (Math.max(Math.abs(du), Math.abs(dv)) == 2 && !(du == 0 && dv == -2))
                    for (int r = 0; r < 2; r++)
                        k.put(cu + du, cv + dv, r, Fx.SHELF.get(k.fwd));
        Spec[] stack = {Fx.SHELF, Fx.SHELF, Fx.SHELF};
        for (int v = 2; v <= k.d - 3; v++)
            k.column(k.w - 1, v, k.left, stack);
        // A reading nook against the shelves, behind the walkway.
        k.put(k.w - 1, k.d - 2, 0, Fx.LECTERN.get(k.left));
        k.chair(k.w - 2, k.d - 2, k.right);
        if (k.big())
            for (int v = 5; v <= k.d - 8; v++)
                if ((v - 5) % 4 != 3)
                    k.column(c + 3, v, k.fwd, stack);
        k.frame(Side.RIGHT, 5, 1, "minecraft:writable_book");
    }

    /**
     * Loungers facing the window, one pair in a small room and three rows in a large one, with a low sill
     * under the glass and a chart table to one side. (Rows of stair-block chairs read as steps, so the
     * seating here is long and low.)
     */
    static void observation(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorBorder(k.style.floorB);
        for (int u = 1; u <= k.w - 2; u++)
            k.put(u, k.d - 1, 0, k.slabBottom());
        int rows = k.big() ? 3 : 1;
        int[] columns = k.big() ? new int[]{c - 3, c + 3} : new int[]{c - 2, c + 2};
        for (int i = 0; i < rows; i++)
        {
            int v = k.d - 3 - i * 3;
            if (v < 2)
                break;
            for (int u : columns)
                RoomsLiving.bed(k, u, v, 0, k.fwd);
        }
        k.column(0, 2, k.right, Fx.CHARTS);
        k.column(0, 3, k.right, Fx.LECTERN);
        k.put(k.w - 1, 2, 0, pot());
        k.frame(Side.RIGHT, 3, 1, "minecraft:spyglass");
        k.frame(Side.LEFT, 5, 1, "minecraft:compass");
        if (k.big())
            k.painting(Side.RIGHT, 5, 1, 2, 1);
    }

    private static BlockState pot()
    {
        return Blocks.POTTED_FERN.defaultBlockState();
    }

    /** A raised watch platform behind a rail, with a giant screen on the wall, one scanner and a seat. */
    static void lookout(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorBorder(k.style.floorB);
        int top = k.d - 3;
        for (int u = 1; u <= k.w - 2; u++)
            for (int v = top; v <= k.d - 1; v++)
                k.put(u, v, 0, k.planks.defaultBlockState());
        // Steps up in the middle, a rail along the rest of the edge.
        k.put(c, top - 1, 0, k.stairs.defaultBlockState().setValue(StairBlock.FACING, k.fwd));
        for (int u = 1; u <= k.w - 2; u++)
            if (u != c)
                k.put(u, top, 1, k.style.rail.defaultBlockState());
        // One giant view screen filling the wall above the platform, and the room's one scanner.
        int half = k.big() ? 2 : 1;
        for (int u = c - half; u <= c + half; u++)
            for (int r = 1; r <= 2; r++)
                k.put(u, k.d - 1, r, Fx.MONITOR.get(k.back));
        k.put(c + half + 1, k.d - 2, 1, Fx.SCANNER.get(k.back));
        for (int u = c - (k.big() ? 2 : 0); u <= c + (k.big() ? 2 : 0); u += 2)
            k.put(u, k.d - 2, 1, k.stairs.defaultBlockState().setValue(StairBlock.FACING, k.back));
        k.frame(Side.LEFT, 2, 1, "minecraft:bow");
        k.frame(Side.RIGHT, 2, 1, "nullspace:air_canister");
        k.frame(Side.LEFT, 4, 1, "minecraft:spyglass");
    }

    /**
     * A ship's bridge: the helm a few steps in from the docking door in the nose wall, with a walkway either side of it,
     * forward screens over a counter either side of the door, a terminal and a scanner against the side walls, and two pilot seats.
     */
    static void bridge(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorBorder(k.style.accent);
        k.floorStripe(c, k.d - 4, k.style.accent);
        // The helm faces back into the room, so the crew stands on the walkway side of it; two cells before the door stay clear.
        k.put(c, k.d - 3, 0, Fx.COMPUTER, k.back);
        for (int u = 1; u <= k.w - 2; u++)
            if (Math.abs(u - c) >= 2)
            {
                k.put(u, k.d - 1, 0, k.slabTop());
                for (int r = 1; r <= 2; r++)
                    k.put(u, k.d - 1, r, Fx.MONITOR.get(k.back));
            }
        k.column(0, k.d - 3, k.right, Fx.TERMINAL);
        k.column(k.w - 1, k.d - 3, k.left, Fx.SCANNER);
        k.chair(c - 2, k.d - 3, k.fwd);
        k.chair(c + 2, k.d - 3, k.fwd);
        k.column(0, 2, k.right, Fx.CHARTS);
        k.frame(Side.RIGHT, 2, 1, "minecraft:clock");
        k.frame(Side.LEFT, 4, 1, "minecraft:compass");
    }

    /** A giant screen over a long console desk, two operator terminals, and for a large room rows of them and a map table. */
    static void operations(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorBorder(k.style.accent);
        k.floorStripe(c, k.d - 5, k.style.accent);
        for (int u = 1; u <= k.w - 2; u++)
            k.put(u, k.d - 1, 0, k.slabTop());
        int half = k.big() ? 2 : 1;
        for (int u = c - half; u <= c + half; u++)
            for (int r = 1; r <= 2; r++)
                k.put(u, k.d - 1, r, Fx.MONITOR.get(k.back));
        int rows = k.big() ? 2 : 1;
        for (int i = 0; i < rows; i++)
        {
            int v = k.d - 3 - i * 3;
            if (v < 3)
                break;
            for (int u : k.big() ? new int[]{c - 3, c, c + 3} : new int[]{c - 2, c + 2})
            {
                k.put(u, v, 0, Fx.TERMINAL.get(k.back));
                k.chair(u, v - 1, k.fwd);
            }
        }
        if (k.big())
        {
            k.column(c, k.d / 2 - 2, k.fwd, Fx.CHARTS);
            for (int v = 3; v <= 5; v++)
            {
                k.put(0, v, 0, k.slabTop());
                if (v % 2 == 1)
                    k.chair(1, v, k.left);
            }
        }
        else
            k.column(0, 2, k.right, Fx.CHARTS);
        k.frame(Side.LEFT, 2, 1, "minecraft:clock");
        k.frame(Side.RIGHT, 2, 1, "minecraft:compass");
    }

    /** Weapon racks down both walls, an issue counter with lockers behind it, a repair bench and an armored suit on show. */
    static void armory(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.STONE_BRICKS);
        String[] arms = {"minecraft:iron_sword", "minecraft:bow", "minecraft:shield", "minecraft:stone_sword"};
        k.chestRun(Side.LEFT, 2, k.d - 4);
        k.chestRun(Side.RIGHT, 2, k.d - 4);
        for (int v = 2; v <= k.d - 4; v += 2)
        {
            k.frame(Side.LEFT, v, 1, arms[(v / 2) % arms.length]);
            k.frame(Side.RIGHT, v, 1, arms[(v / 2 + 2) % arms.length]);
        }
        // The issue counter, with a gap, and lockers behind it.
        for (int u = 1; u <= k.w - 2; u++)
            if (Math.abs(u - c) > 1)
                k.put(u, k.d - 4, 0, k.slabTop());
        k.run(Side.BACK, 1, k.w - 2, Fx.BARREL);
        k.column(c, k.d - 3, k.fwd, Fx.LECTERN);
        k.column(k.w - 2, k.d - 2, k.back, Fx.GRIND);
        k.column(1, k.d - 2, k.fwd, Fx.ANVIL);
        k.stand(c, k.d - 2, k.back, "armored");
        if (k.big())
        {
            k.stand(c - 3, k.d - 2, k.back, "armored");
            for (int v = 3; v <= k.d - 6; v += 3)
                k.put(c - 3, v, 0, Fx.TARGET.get(k.fwd));
        }
    }

    /** Suits on stands in niches along the back wall, lockers and benches to the sides, and a canister by every stand. */
    static void evaPrep(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorBorder(Blocks.POLISHED_ANDESITE);
        k.floorStripe(c, k.d - 2, k.style.accent);
        int[] stands = k.big() ? new int[]{1, 3, k.w - 4, k.w - 2} : new int[]{1, k.w - 2};
        // Suits are valuable: three at most in a room, and empty stands elsewhere.
        int suits = 0;
        for (int u : stands)
        {
            boolean suited = suits++ < 3;
            k.stand(u, k.d - 1, k.back, suited ? "plain" : "empty");
            if (suited)
                k.frame(Side.BACK, u, 1, "nullspace:air_canister");
        }
        // Posts between the niches.
        for (int u = 1; u <= k.w - 2; u++)
            if (Math.abs(u - c) > 1 && !contains(stands, u))
            {
                k.put(u, k.d - 1, 0, k.log.defaultBlockState());
                k.put(u, k.d - 1, 1, k.log.defaultBlockState());
            }
        for (int v = 2; v <= k.d - 3; v++)
        {
            k.put(0, v, 0, v % 2 == 0 ? k.seatAgainst().get(k.right) : Fx.BARREL.get(k.right));
            k.put(k.w - 1, v, 0, v % 2 == 0 ? k.seatAgainst().get(k.left) : Fx.BARREL.get(k.left));
        }
        if (k.big())
        {
            for (int v = 3; v <= k.d - 5; v += 3)
            {
                k.stand(1, v, k.right, "empty");
                k.stand(k.w - 2, v, k.left, "empty");
            }
            k.column(c, k.d - 3, k.fwd, Fx.ANVIL);
        }
        k.frame(Side.DOOR, c + 2, 1, "nullspace:air_canister");
    }

    private static boolean contains(int[] values, int value)
    {
        for (int v : values)
            if (v == value)
                return true;
        return false;
    }

    /**
     * A cell is two cells wide behind iron bars the full height of the room. The gate is an iron door
     * between two solid posts under a lintel, so the bars meet solid blocks and the door looks framed;
     * the gate's button is on the guard side, on the post beside it.
     */
    private static void cell(RoomKit k, int u0)
    {
        int front = k.d - 3;
        var post = k.planks.defaultBlockState();
        for (int r = 0; r < k.h; r++)
        {
            k.put(u0, front, r, post);
            k.put(u0 + 2, front, r, post);
            // The divider between this cell and the next, and bars across the rest of the front.
            if (r < k.h)
                for (int v = front + 1; v <= k.d - 1; v++)
                    k.put(u0 + 2, v, r, bars());
        }
        // The gate: an iron door under a lintel, between the posts.
        var door = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, k.fwd);
        k.put(u0 + 1, front, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        k.put(u0 + 1, front, 1, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        for (int r = 2; r < k.h; r++)
            k.put(u0 + 1, front, r, post);
        var button = Blocks.STONE_BUTTON.defaultBlockState().setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL).setValue(HorizontalDirectionalBlock.FACING, k.back);
        k.put(u0 + 2, front - 1, 1, button);
        // The bunk against the back wall, and a washbasin.
        var foot = k.palette.bed().defaultBlockState().setValue(BedBlock.FACING, k.fwd).setValue(BedBlock.PART, BedPart.FOOT);
        k.put(u0, k.d - 2, 0, foot);
        k.put(u0, k.d - 1, 0, foot.setValue(BedBlock.PART, BedPart.HEAD));
        k.put(u0 + 1, k.d - 1, 0, Blocks.CAULDRON.defaultBlockState());
    }

    /** A row of cells across the back, a guard desk and a rack of leads by the door. */
    static void brig(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.STONE_BRICKS);
        int cells = 0;
        for (int u0 = 0; u0 + 2 < k.w && cells < (k.big() ? 5 : 2); u0 += 3)
        {
            cell(k, u0);
            cells++;
        }
        if (k.big())
        {
            int deskV = k.d - 7;
            k.table(c - 2, deskV, 3, 1, null);
            k.put(c, deskV, 1, Fx.TERMINAL.get(k.back));
            k.chair(c, deskV - 1, k.fwd);
        }
        else
        {
            // A small brig keeps the walkway in front of the gates clear: the guard's desk is against the wall.
            k.put(k.w - 1, 2, 0, k.slabTop());
            k.put(k.w - 1, 2, 1, Fx.TERMINAL.get(k.left));
        }
        k.frame(Side.LEFT, 2, 1, "minecraft:lead");
        k.frame(Side.RIGHT, 3, 1, "minecraft:lead");
    }

    private RoomsCommand()
    {
    }
}
