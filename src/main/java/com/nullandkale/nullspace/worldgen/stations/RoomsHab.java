package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.RoomKit.Side;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;

/**
 * The rooms only a hab has (see {@code docs/design/hab-room-catalog.md}): the homes of the crew, their shrine, the power plant, the shaft
 * to the seam, the bees and the room the whole hab is run from. They are as large as a hab's rooms are, so each repeats its bays.
 */
final class RoomsHab
{
    private static final String[] KEEPSAKES = {"minecraft:book", "minecraft:clock", "minecraft:compass", "minecraft:map", "minecraft:bread", "minecraft:paper", "minecraft:honeycomb", "minecraft:apple"};

    /**
     * Homes: units of a bed or two, a chest at the pillow and a keepsake on the wall, in bays down both side walls with a partition between
     * them and a carpet down the aisle. A lectern and shelves at the back hold the family's log.
     */
    static void familyQuarters(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(k.planks);
        var rug = k.palette.carpet().defaultBlockState();
        for (int v = 2; v < k.d - 1; v++)
            k.put(c, v, 0, rug);
        for (int s = 0; s < 2; s++)
        {
            boolean left = s == 0;
            int u0 = left ? 0 : k.w - 1, step = left ? 1 : -1;
            var toward = left ? k.right : k.left;
            var wall = left ? Side.LEFT : Side.RIGHT;
            for (int v = 2; v + 3 <= k.d - 2; v += 4)
            {
                int kind = k.rng.nextInt(3);
                RoomsLiving.bed(k, u0, v, 0, k.fwd);
                if (kind >= 1)
                    RoomsLiving.bed(k, u0 + step, v, 0, k.fwd);
                k.put(u0, v + 2, 0, Fx.CHEST.get(toward));
                // A child's corner in the third kind of home: a toy chest and a small table.
                if (kind == 2)
                {
                    k.put(u0 + step, v + 2, 0, Fx.BARREL.get(toward));
                    k.put(u0 + 2 * step, v + 1, 0, k.slabBottom());
                }
                k.put(u0 + 2 * step, v, 0, k.lantern());
                if (v + 3 <= k.d - 3)
                    for (int i = 0; i < 2; i++)
                    {
                        k.put(u0 + i * step, v + 3, 0, k.fence.defaultBlockState());
                        k.put(u0 + i * step, v + 3, 1, k.style.rail.defaultBlockState());
                    }
                k.frame(wall, v + 1, 2, KEEPSAKES[k.rng.nextInt(KEEPSAKES.length)]);
            }
        }
        k.run(Side.BACK, c - 1, c + 1, Fx.SHELF, Fx.SHELF);
        if (k.put(c, k.d - 2, 0, Fx.LECTERN.get(k.back)))
            k.mark(c, k.d - 2, 0, "log", "family " + k.rng.nextInt(1000));
    }

    /**
     * A shrine: benches in rows facing a stone altar with a soul lantern, a floor of two stones, and a wall of name tags in frames, one for each
     * of the crew. One book hangs over the altar.
     */
    static void chapel(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorChecker(Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS);
        for (int v = 3; v <= k.d - 5; v += 2)
        {
            for (int u = Math.max(1, c - 4); u <= c - 2; u++)
                k.chair(u, v, k.fwd);
            for (int u = c + 2; u <= Math.min(k.w - 2, c + 4); u++)
                k.chair(u, v, k.fwd);
        }
        k.put(c, k.d - 2, 0, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        k.put(c, k.d - 2, 1, Blocks.SOUL_LANTERN.defaultBlockState());
        k.put(c - 1, k.d - 2, 0, k.slabBottom());
        k.put(c + 1, k.d - 2, 0, k.slabBottom());
        k.frame(Side.BACK, c, 2, "minecraft:book");
        if (k.put(c + 2, k.d - 2, 0, Fx.LECTERN.get(k.back)))
            k.mark(c + 2, k.d - 2, 0, "log", "chapel " + k.rng.nextInt(1000));
        int names = 6 + k.rng.nextInt(8);
        for (int a = 2; a <= k.d - 3 && names > 0; a += 2)
        {
            k.frame(Side.LEFT, a, 1, "minecraft:name_tag");
            k.frame(Side.RIGHT, a, 1, "minecraft:name_tag");
            names -= 2;
        }
    }

    /**
     * The power plant: generators, each with its battery, down both side walls and across the back, a console at each end of the back wall,
     * fuel barrels, and pipes overhead, on a floor striped to the machines. The machines are the real ones and start with no fuel, so the
     * hab is dark and dead until someone feeds them coal. Variant 1 is a plant that failed with a leak: cracked floor and webs.
     */
    static void machineHall(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        for (int u = 0; u < k.w; u++)
            for (int v = k.d - 3; v < k.d; v++)
                k.floor(u, v, Blocks.BLACKSTONE);
        k.floorStripe(c, k.d - 4, k.style.accent);
        // The consoles first, so the back bank of generators leaves them their places.
        k.column(c - 3 >= 1 ? c - 3 : 1, k.d - 1, k.back, Fx.TERMINAL, Fx.MONITOR);
        k.column(c + 3 <= k.w - 2 ? c + 3 : k.w - 2, k.d - 1, k.back, Fx.TERMINAL, Fx.MONITOR);
        k.bank(Side.LEFT, 2, k.d - 3, 3, Fx.GENERATOR, Fx.BATTERY);
        k.bank(Side.RIGHT, 2, k.d - 3, 3, Fx.GENERATOR, Fx.BATTERY);
        k.bank(Side.BACK, 2, k.w - 3, 3, Fx.GENERATOR, Fx.BATTERY);
        k.run(Side.LEFT, 1, 1, Fx.BARREL);
        k.run(Side.RIGHT, 1, 1, Fx.BARREL);
        for (int v : new int[]{k.d / 3 + 1, 2 * k.d / 3})
            for (int u = 0; u < k.w; u++)
                k.put(u, v, k.h - 1, Blocks.IRON_BARS.defaultBlockState());
        if (k.variant == 1)
        {
            for (int u = 0; u < k.w; u++)
                for (int v = 3; v < k.d - 3; v++)
                    if (k.rng.nextInt(4) == 0 && u != c)
                        k.floor(u, v, Blocks.CRACKED_STONE_BRICKS);
            for (int[] corner : new int[][]{{0, k.d - 1}, {k.w - 1, k.d - 1}, {0, 2}, {k.w - 1, 2}})
                k.put(corner[0], corner[1], k.h - 1, Blocks.COBWEB.defaultBlockState());
        }
        k.frame(Side.DOOR, c - 2, 1, "nullspace:air_canister");
        k.frame(Side.DOOR, c + 2, 1, "nullspace:circuit_board");
        if (k.put(c, 2, 0, Fx.LECTERN.get(k.fwd)))
            k.mark(c, 2, 0, "log", "machine " + k.rng.nextInt(1000));
    }

    /**
     * The shaft to the seam: a square opening in the floor with a rail round three sides, a ladder down, a cart and a lamp. Variant 1 (a room
     * on the deepest floor) is open to a real cave under the hab, which the hab's build digs where the {@code mineshaft} point is; any other
     * floor has a grating over a shaft that goes nowhere.
     */
    static void mineHead(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.STONE_BRICKS);
        k.floorBorder(Blocks.COBBLESTONE);
        int v0 = k.d - 4, v1 = k.d - 2;
        var open = k.variant == 1;
        for (int u = c - 1; u <= c + 1; u++)
            for (int v = v0; v <= v1; v++)
            {
                if (open)
                {
                    k.out.put(k.at(u, v, -2), Blocks.AIR.defaultBlockState());
                    k.out.put(k.at(u, v, -1), Blocks.AIR.defaultBlockState());
                }
                else
                    k.floor(u, v, Blocks.IRON_BARS);
            }
        // A rail round the sides and back of the opening, open at the front.
        for (int v = v0; v <= v1; v++)
        {
            k.put(c - 2, v, 0, k.fence.defaultBlockState());
            k.put(c + 2, v, 0, k.fence.defaultBlockState());
        }
        for (int u = c - 2; u <= c + 2; u++)
            k.put(u, v1 + 1 < k.d ? v1 + 1 : v1, 0, k.fence.defaultBlockState());
        k.put(c - 2, v0 - 1, 0, k.lantern());
        k.put(c + 2, v0 - 1, 0, k.lantern());
        // A track along one wall with a bin of rubble at its end, and barrels of tools down the other.
        for (int v = 2; v <= k.d - 3; v++)
            k.put(1, v, 0, Blocks.RAIL.defaultBlockState());
        k.put(1, k.d - 2, 0, Blocks.COBBLESTONE.defaultBlockState());
        k.run(Side.RIGHT, 2, 4, Fx.BARREL);
        k.frame(Side.DOOR, c + 2, 1, "minecraft:iron_pickaxe");
        k.mark(c, (v0 + v1) / 2, 0, "mineshaft", open ? "open" : "capped");
    }

    /**
     * The bees: hives on posts along both walls, flower beds either side of a path, composters across the back, a pair of bees and a frame of
     * honeycomb. A calm room with a sting in it for anyone who disturbs the hives.
     */
    static void apiary(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.GRASS_BLOCK);
        for (int v = 0; v < k.d; v++)
            k.floor(c, v, Blocks.DIRT_PATH);
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};
        for (int v = 3; v <= k.d - 4; v++)
        {
            for (int u : new int[]{3, 4, k.w - 5, k.w - 4})
                if (Math.abs(u - c) > 1)
                    k.put(u, v, 0, flowers[(u + v) % flowers.length].defaultBlockState());
        }
        k.bank(Side.LEFT, 2, k.d - 3, 3, RoomKit.of(Blocks.BEEHIVE));
        k.bank(Side.RIGHT, 2, k.d - 3, 3, RoomKit.of(Blocks.BEEHIVE));
        k.run(Side.BACK, 1, k.w - 2, Fx.COMPOSTER);
        k.animal(c - 2 >= 1 ? c - 2 : 1, k.d / 2, "minecraft:bee");
        k.animal(c + 2 <= k.w - 2 ? c + 2 : k.w - 2, k.d / 2, "minecraft:bee");
        k.frame(Side.DOOR, c + 2, 1, "minecraft:honeycomb");
    }

    /**
     * The room the hab is run from: a raised dais of polished blackstone at the back with the controller on it (a console and screen until
     * the hab controller block exists), a wall of screens behind, a lantern at each corner of the dais and steps up its front.
     */
    static void controllerRoom(RoomKit k)
    {
        k.air();
        // A room that is walked through has a clear strip down its middle, so the dais stands to one side of it.
        int c = k.doorU;
        if (k.passedThrough())
            c = c + 5 <= k.w - 1 ? c + 3 : c - 3;
        k.floorFill(Blocks.POLISHED_BLACKSTONE_BRICKS);
        k.floorBorder(k.style.accent);
        int front = k.d - 5;
        for (int u = c - 2; u <= c + 2; u++)
            for (int v = front + 1; v <= k.d - 2; v++)
                k.put(u, v, 0, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        for (int u = c - 1; u <= c + 1; u++)
            k.put(u, front, 0, k.stairs.defaultBlockState().setValue(StairBlock.FACING, k.fwd));
        // The hab's controller: a real Station Core, standing on the dais (the hab is registered as a station, so it works).
        k.put(c, k.d - 3, 1, Fx.CORE.get(k.back));
        for (int u : new int[]{c - 1, c + 1})
            k.column(u, k.d - 3, k.back, null, Fx.MONITOR);
        k.put(c - 2, front + 1, 1, k.lantern());
        k.put(c + 2, front + 1, 1, k.lantern());
        k.run(Side.BACK, Math.max(1, c - 3), Math.min(k.w - 2, c + 3), null, Fx.MONITOR, Fx.MONITOR);
        k.frame(Side.LEFT, 3, 1, "minecraft:clock");
        k.frame(Side.RIGHT, 3, 1, "nullspace:circuit_board");
        if (k.put(c + 3 <= k.w - 2 ? c + 3 : k.w - 2, 2, 0, Fx.LECTERN.get(k.fwd)))
            k.mark(c + 3 <= k.w - 2 ? c + 3 : k.w - 2, 2, 0, "log", "controller " + k.rng.nextInt(1000));
    }

    /**
     * The crew's eyes on the hab: a giant wall of screens across the back, and a desk of consoles with a chair at each in front of it. The
     * screens show the nearest camera until someone chooses one; cameras hang in the machine hall, the controller room, the commons, the plazas, the
     * apiary and the grove.
     */
    static void cameraWall(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.POLISHED_BLACKSTONE_BRICKS);
        k.floorStripe(c, k.d - 4, k.style.accent);
        k.run(Side.BACK, 1, k.w - 2, Fx.MONITOR, Fx.MONITOR, Fx.MONITOR);
        for (int u = 2; u <= k.w - 3; u += 2)
            if (Math.abs(u - c) > 1)
            {
                k.column(u, k.d - 4, k.back, Fx.TERMINAL);
                k.chair(u, k.d - 5, k.fwd);
            }
        k.run(Side.LEFT, 2, 3, Fx.BARREL);
        k.frame(Side.DOOR, c + 2, 1, "minecraft:clock");
    }

    /**
     * A quiet room with a glass of screens: rows of benches facing a giant wall of monitors that shows the nearest camera (the grove's, from
     * the deepest floor), and plants down the sides.
     */
    static void gallery(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(k.planks);
        k.run(Side.BACK, 1, k.w - 2, Fx.MONITOR, Fx.MONITOR, Fx.MONITOR);
        for (int v = 3; v <= k.d - 5; v += 2)
        {
            for (int u = Math.max(1, c - 4); u <= c - 2; u++)
                k.chair(u, v, k.fwd);
            for (int u = c + 2; u <= Math.min(k.w - 2, c + 4); u++)
                k.chair(u, v, k.fwd);
        }
        for (int v = 2; v <= k.d - 3; v += 3)
        {
            k.put(0, v, 0, RoomsLiving.pot());
            k.put(k.w - 1, v, 0, RoomsLiving.pot());
        }
        k.frame(Side.DOOR, c - 2, 1, "minecraft:spyglass");
    }

    /**
     * The sealed beds of the infirmary's quarantine ward: each bed in a cell of iron bars with solid posts and no door, down both walls, cobwebs
     * in the corners, and the ward's supplies in chests in the aisle. The first hint of what went wrong.
     */
    static void quarantineWard(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.STONE_BRICKS);
        for (int s = 0; s < 2; s++)
        {
            boolean left = s == 0;
            int u0 = left ? 0 : k.w - 1, step = left ? 1 : -1;
            for (int v = 2; v + 2 <= k.d - 3; v += 3)
            {
                RoomsLiving.bed(k, u0, v, 0, k.fwd);
                for (int dv = 0; dv < 3; dv++)
                    for (int r = 0; r < 2; r++)
                        k.put(u0 + step, v + dv, r, Blocks.IRON_BARS.defaultBlockState());
                for (int r = 0; r < 2; r++)
                    k.put(u0, v + 2, r, Blocks.STONE_BRICKS.defaultBlockState());
                k.put(u0 + 2 * step, v, 0, k.lantern());
            }
        }
        k.run(Side.BACK, 1, k.w - 2, Fx.CHEST);
        for (int[] corner : new int[][]{{0, k.d - 1}, {k.w - 1, k.d - 1}, {c - 2, 2}, {c + 2, 2}})
            k.put(corner[0], corner[1], k.h - 1, Blocks.COBWEB.defaultBlockState());
        k.frame(Side.DOOR, c + 2, 1, "minecraft:potion");
    }

    /**
     * Where the seam's ore was kept: piles of raw ore down one side beside a track, double chests down the other, and a hopper and chest at
     * the back. What it holds is the planet's own ore.
     */
    static void oreStore(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.COBBLESTONE);
        k.floorBorder(Blocks.STONE_BRICKS);
        Block[] ores = {Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.COAL_ORE};
        int bin = 0;
        for (int v = 2; v + 1 <= k.d - 3; v += 3, bin++)
            for (int du = 0; du < 2; du++)
                for (int dv = 0; dv < 2; dv++)
                    k.put(du, v + dv, 0, ores[bin % ores.length].defaultBlockState());
        for (int v = 2; v <= k.d - 3; v++)
            k.put(2, v, 0, Blocks.RAIL.defaultBlockState());
        k.chestRun(Side.RIGHT, 2, k.d - 3);
        k.put(c, k.d - 1, 0, Blocks.HOPPER.defaultBlockState());
        k.put(c + 1, k.d - 1, 0, Fx.CHEST.get(k.back));
        k.frame(Side.DOOR, c - 2, 1, "minecraft:raw_iron");
    }

    private RoomsHab()
    {
    }
}
