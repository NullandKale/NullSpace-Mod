package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.RoomKit.Side;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Rooms where people live, eat, grow things and recover: warm, with soft edges and somewhere to sit. */
final class RoomsLiving
{
    private static BlockState pot()
    {
        return Blocks.POTTED_FERN.defaultBlockState();
    }

    /** A bed with its foot at {@code u, v} and its head one cell toward {@code head} (a local direction). */
    static void bed(RoomKit k, int u, int v, int row, Direction head)
    {
        int du = head == k.right ? 1 : head == k.left ? -1 : 0, dv = head == k.fwd ? 1 : head == k.back ? -1 : 0;
        if (!k.free(u, v, row) || !k.free(u + du, v + dv, row))
            return;
        var foot = k.palette.bed().defaultBlockState().setValue(BedBlock.FACING, head).setValue(BedBlock.PART, BedPart.FOOT);
        k.put(u, v, row, foot);
        k.put(u + du, v + dv, row, foot.setValue(BedBlock.PART, BedPart.HEAD));
    }

    /** A small tree on a grass patch: a trunk three high and a blob of leaves that never decay. */
    static void tree(RoomKit k, int u, int v, Block trunk, Block leaves)
    {
        for (int du = -1; du <= 1; du++)
            for (int dv = -1; dv <= 1; dv++)
                k.floor(u + du, v + dv, Blocks.GRASS_BLOCK);
        for (int r = 0; r < 3; r++)
            k.put(u, v, r, trunk.defaultBlockState());
        for (int r = 2; r <= 3; r++)
            for (int du = -1; du <= 1; du++)
                for (int dv = -1; dv <= 1; dv++)
                    if (du != 0 || dv != 0 || r == 3)
                        k.put(u + du, v + dv, r, Fx.leaves(leaves));
        k.put(u, v, 4, Fx.leaves(leaves));
    }

    /** A fenced grass pen with hay, a water trough and animals. {@code u, v} is its lowest corner; it is {@code pw} by {@code pd}. */
    private static void pen(RoomKit k, int u, int v, int pw, int pd, String animal, int count)
    {
        for (int du = 0; du < pw; du++)
            for (int dv = 0; dv < pd; dv++)
            {
                boolean edge = du == 0 || dv == 0 || du == pw - 1 || dv == pd - 1;
                k.floor(u + du, v + dv, Blocks.GRASS_BLOCK);
                if (edge)
                    k.put(u + du, v + dv, 0, k.fence.defaultBlockState());
            }
        k.put(u + pw - 2, v + pd - 2, 0, Blocks.HAY_BLOCK.defaultBlockState());
        k.put(u + 1, v + pd - 2, 0, Fx.waterCauldron().get(k.fwd));
        for (int i = 0; i < count; i++)
        {
            k.animal(u + 1 + i % (pw - 2), v + 1 + i / (pw - 2), animal);
            k.keep(u + 1 + i % (pw - 2), v + 1 + i / (pw - 2), 0);
        }
    }

    /** A room to meet in: seating along one wall and tables you can walk round; a large one adds a stage and a counter. */
    static void commons(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(k.planks);
        for (int v = 2; v <= k.d - 3; v++)
            k.put(0, v, 0, k.seatAgainst(), k.right);
        k.put(k.w - 1, 2, 0, pot());
        k.put(k.w - 1, k.d - 3, 0, pot());
        if (k.big())
        {
            // A stage at the back with steps, a counter along the right wall, and table clusters.
            for (int u = c - 2; u <= c + 2; u++)
                for (int v = k.d - 3; v < k.d; v++)
                    k.put(u, v, 0, k.planks.defaultBlockState());
            for (int u = c - 1; u <= c + 1; u++)
                k.put(u, k.d - 4, 0, k.stairs.defaultBlockState().setValue(StairBlock.FACING, k.fwd));
            k.put(c, k.d - 2, 1, Fx.LECTERN.get(k.back));
            for (int v = 3; v <= k.d - 6; v++)
            {
                k.put(k.w - 1, v, 0, Fx.BARREL.get(k.left));
                k.put(k.w - 2, v, 0, k.slabTop());
            }
            for (int i = 0; i < 3; i++)
                k.tableSet(2, 2, true, k.lantern());
            k.painting(Side.LEFT, 4, 1, 2, 1);
        }
        else
        {
            k.tableSet(3, 2, true, k.lantern());
            k.painting(Side.RIGHT, 3, 1, 2, 1);
        }
        k.frame(Side.BACK, c, 1, "minecraft:clock");
    }

    /** A stove wall, a counter, a pantry and a table you can walk round; a large kitchen adds a serving line and a mess. */
    static void kitchen(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorChecker(k.style.floorA, k.style.floorB);
        for (int u = 1; u <= k.w - 2; u++)
            k.column(u, k.d - 1, k.back, u % 2 == 1 ? Fx.SMOKER : Fx.waterCauldron());
        k.run(Side.RIGHT, 2, k.d - 3, Fx.BARREL);
        if (k.big())
        {
            for (int v = 2; v <= k.d - 3; v++)
                k.put(0, v, 0, v % 2 == 0 ? k.slabTop() : Fx.CHEST.get(k.right));
            // A serving counter across the room with a staff gap, and a mess of tables.
            for (int u = 1; u <= k.w - 2; u++)
                if (Math.abs(u - c) > 1)
                    k.put(u, k.d - 5, 0, k.slabTop());
            for (int i = 0; i < 3; i++)
                k.tableSet(3, 1, true, k.lantern());
        }
        else
            k.tableSet(2, 1, true, k.lantern());
        k.frame(Side.RIGHT, k.d / 2, 2, "minecraft:clock");
    }

    /** A bed on the centre line, a desk, a wardrobe of chests and a rug; a large cabin is a suite with a sitting area. */
    static void cabin(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(k.planks);
        bed(k, c, k.d - 2, 0, k.fwd);
        for (int side : new int[]{-1, 1})
        {
            k.put(c + side, k.d - 1, 0, k.slabTop());
            k.put(c + side, k.d - 1, 1, k.lantern());
        }
        // Desk and chair on the right, bookshelves behind it.
        k.put(k.w - 1, 2, 0, k.slabTop());
        k.put(k.w - 1, 3, 0, k.slabTop());
        k.chair(k.w - 2, 2, k.right);
        k.column(k.w - 1, 4, k.left, Fx.SHELF, Fx.SHELF);
        // The wardrobe on the left: chests in a row.
        k.chestRun(Side.LEFT, 2, 3);
        var rug = k.palette.carpet().defaultBlockState();
        for (int u = c - 1; u <= c + 1; u++)
            for (int v = k.d - 4; v <= k.d - 3; v++)
                k.put(u, v, 0, rug);
        if (k.big())
        {
            // A partition with a gap divides sleeping from sitting; the front is a sitting area.
            int wallV = k.d - 5;
            for (int u = 0; u < k.w; u++)
                if (Math.abs(u - c) > 1)
                {
                    k.put(u, wallV, 0, k.fence.defaultBlockState());
                    k.put(u, wallV, 1, k.style.rail.defaultBlockState());
                }
            k.tableSet(3, 1, true, k.lantern());
            k.put(1, 2, 0, pot());
            k.put(k.w - 2, 2, 0, pot());
        }
        k.painting(Side.LEFT, k.d - 3, 1, 2, 1);
        k.frame(Side.RIGHT, 2, 2, "minecraft:clock");
    }

    /** Single beds, each in its own bay with a chest at the pillow and a partition between bays, down both walls. */
    static void bunkRoom(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(k.planks);
        for (int v = 2; v + 2 <= k.d - 2; v += 3)
        {
            bed(k, 0, v, 0, k.fwd);
            bed(k, k.w - 1, v, 0, k.fwd);
            k.put(0, v + 2, 0, Fx.CHEST.get(k.right));
            k.put(k.w - 1, v + 2, 0, Fx.CHEST.get(k.left));
            // The partition that makes each bed its own region.
            if (v + 2 < k.d - 2)
            {
                k.put(1, v + 2, 0, k.fence.defaultBlockState());
                k.put(1, v + 2, 1, k.style.rail.defaultBlockState());
                k.put(k.w - 2, v + 2, 0, k.fence.defaultBlockState());
                k.put(k.w - 2, v + 2, 1, k.style.rail.defaultBlockState());
            }
            k.put(1, v, 0, k.lantern());
            k.put(k.w - 2, v, 0, k.lantern());
        }
        k.run(Side.BACK, 1, k.w - 2, Fx.BARREL);
        if (k.big())
            for (int i = 0; i < 2; i++)
                k.tableSet(3, 2, true, k.lantern());
        k.frame(Side.BACK, c, 1, "minecraft:clock");
    }

    private static boolean has(int[] values, int value)
    {
        for (int x : values)
            if (x == value)
                return true;
        return false;
    }

    private static Block crop(int i)
    {
        return switch (Math.floorMod(i, 4))
        {
            case 0 -> Blocks.WHEAT;
            case 1 -> Blocks.CARROTS;
            case 2 -> Blocks.POTATOES;
            default -> Blocks.BEETROOTS;
        };
    }

    private static BlockState planted(Block crop)
    {
        var s = crop.defaultBlockState();
        return crop == Blocks.BEETROOTS ? s.setValue(BeetrootBlock.AGE, 3) : s.setValue(CropBlock.AGE, 7);
    }

    /**
     * Beds sunk into the floor: soil flush with the path, crops standing on it, and one water cell in
     * each bed (farmland drinks from water up to four cells away, so a bed nine long needs only one).
     * A lamp post stands at each end. The beds sit either side of a clear central path.
     */
    static void growingBay(RoomKit k)
    {
        if (k.variant == 1)
        {
            orchard(k);
            return;
        }
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.MOSS_BLOCK);
        var soil = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7);
        int[] starts = k.w >= 15 ? new int[]{0, 4, k.w - 7, k.w - 3} : new int[]{0, k.w - 3};
        int v0 = 2, v1 = k.d - 5, which = k.rng.nextInt(4);
        // One water cell reaches four cells either way; a longer bed gets a second.
        int[] waters = v1 - v0 + 1 <= 9 ? new int[]{(v0 + v1) / 2} : new int[]{v0 + 4, v1 - 4};
        for (int s = 0; s < starts.length; s++)
        {
            int u0 = starts[s];
            var seeded = planted(crop(which + s));
            for (int v = v0; v <= v1; v++)
                for (int u = u0; u <= u0 + 2; u++)
                {
                    boolean water = u == u0 + 1 && has(waters, v);
                    k.floor(u, v, water ? Blocks.WATER.defaultBlockState() : soil);
                    if (!water)
                        k.put(u, v, 0, seeded);
                }
            for (int v : new int[]{v0 - 1, v1 + 1})
            {
                k.put(u0 + 1, v, 0, k.fence.defaultBlockState());
                k.put(u0 + 1, v, 1, k.lantern());
            }
        }
        // Composters and seed barrels along the back.
        int back = k.w >= 15 ? 4 : 1;
        for (int u = back; u <= k.w - 1 - back; u++)
            k.put(u, k.d - 1, 0, (u % 2 == 0 ? Fx.COMPOSTER : Fx.BARREL).get(k.back));
        if (k.big() && k.w < 15)
        {
            // An orchard in the middle: two trees on grass patches either side of the path.
            tree(k, c - 1, 4, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
            tree(k, c + 1, k.d - 7, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        }
        k.frame(Side.BACK, c, 1, "minecraft:iron_hoe");
        k.frame(Side.BACK, c + 2, 1, "minecraft:water_bucket");
        k.frame(Side.BACK, c - 2, 1, "minecraft:shears");
    }

    /**
     * The orchard variant of the growing bay: rows of trees of a few species on grass patches, either
     * side of a clear central path, with composters and seed barrels along the back. The leaves never decay.
     */
    private static void orchard(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.MOSS_BLOCK);
        Block[][] species = {{Blocks.OAK_LOG, Blocks.OAK_LEAVES}, {Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES}, {Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES}, {Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES}};
        int[] columns = k.w >= 15 ? new int[]{1, 5, k.w - 6, k.w - 2} : new int[]{1, k.w - 2};
        int rows = k.big() ? 3 : 1, which = k.rng.nextInt(species.length);
        for (int r = 0; r < rows; r++)
        {
            int v = 3 + r * 4;
            if (v + 1 > k.d - 2)
                break;
            for (int i = 0; i < columns.length; i++)
            {
                var kind = species[(which + r + i) % species.length];
                tree(k, columns[i], v, kind[0], kind[1]);
            }
        }
        int back = k.w >= 15 ? 4 : 1;
        for (int u = back; u <= k.w - 1 - back; u++)
            k.put(u, k.d - 1, 0, (u % 2 == 0 ? Fx.COMPOSTER : Fx.BARREL).get(k.back));
        k.frame(Side.BACK, c, 1, "minecraft:iron_axe");
        k.frame(Side.BACK, c + 2, 1, "minecraft:shears");
    }

    /**
     * One big pen with every animal two by two, like an ark: a fence all round with a gate on the side
     * toward the path, grass inside and nothing else, since animals jump onto any block.
     */
    private static void ark(RoomKit k, int u, int v, int pw, int pd)
    {
        int gateV = v + pd / 2;
        for (int du = 0; du < pw; du++)
            for (int dv = 0; dv < pd; dv++)
            {
                k.floor(u + du, v + dv, Blocks.GRASS_BLOCK);
                boolean edge = du == 0 || dv == 0 || du == pw - 1 || dv == pd - 1;
                if (edge && !(du == 0 && v + dv == gateV))
                    k.put(u + du, v + dv, 0, k.fence.defaultBlockState());
            }
        k.put(u, gateV, 0, k.gate.defaultBlockState().setValue(FenceGateBlock.FACING, k.right));
        String[] kinds = {"sheep", "cow", "pig", "chicken"};
        int iw = pw - 2, ih = pd - 2;
        for (int i = 0; i < 8; i++)
        {
            int n = i * (iw * ih) / 8;
            int au = u + 1 + n % iw, av = v + 1 + n / iw;
            k.animal(au, av, kinds[i / 2]);
            k.keep(au, av, 0);
        }
    }

    /**
     * A walk-through garden: a path down the middle to a sunken pond, flower beds either side, trees in
     * the corners and a bench; a large one has one big pen with a pair of every animal.
     */
    static void garden(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        for (int u = 0; u < k.w; u++)
            for (int v = 0; v < k.d; v++)
                k.floor(u, v, u == c ? Blocks.DIRT_PATH : Blocks.GRASS_BLOCK);
        // The pen first, so nothing else claims its cells.
        if (k.big() && k.variant == 0)
            ark(k, c + 2, 3, k.w - c - 3, k.d - 6);
        // Flower beds in mirrored rows, a colour to a row.
        Block[] flowers = {Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.CORNFLOWER};
        int bedU = Math.max(1, c - 2);
        for (int v = 2; v <= k.d - 5; v++)
        {
            var flower = flowers[v % flowers.length].defaultBlockState();
            k.put(bedU, v, 0, flower);
            k.put(k.w - 1 - bedU, v, 0, flower);
        }
        // The pond at the back is sunk into the floor, in a rim of stone brick.
        int pondW = k.big() ? 5 : 3;
        for (int du = -pondW / 2; du <= pondW / 2; du++)
            for (int v = k.d - 3; v < k.d; v++)
            {
                boolean rim = Math.abs(du) == pondW / 2 || v == k.d - 3 || v == k.d - 1;
                k.floor(c + du, v, rim ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        k.put(0, 2, 0, k.seatAgainst(), k.right);
        tree(k, 1, k.d - 2, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        tree(k, k.w - 2, k.d - 2, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        if (k.big() && k.variant == 1)
        {
            // The grove: trees in mirrored rows down both sides instead of a pen.
            for (int v = 4; v + 1 <= k.d - 5; v += 4)
            {
                tree(k, 1, v, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
                tree(k, k.w - 2, v, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
            }
            k.put(k.w - 1, 2, 0, k.seatAgainst(), k.left);
        }
        else if (k.big())
            tree(k, 1, k.d / 2, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
        else
            k.put(k.w - 1, 2, 0, k.seatAgainst(), k.left);
        k.frame(Side.BACK, c, 1, "minecraft:shears");
        k.painting(Side.LEFT, 4, 1, 1, 1);
    }

    /** Beds in bays with a bedside table, one brewing stand in the medicine cabinet, a white floor and a red cross. */
    static void infirmary(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.CONCRETE.white());
        var red = Blocks.CONCRETE.red();
        for (int d = -1; d <= 1; d++)
        {
            k.floor(c + d, k.d / 2, red);
            k.floor(c, k.d / 2 + d, red);
        }
        for (int v = 2; v + 1 <= k.d - 3; v += 3)
        {
            bed(k, 1, v, 0, k.left);
            bed(k, k.w - 2, v, 0, k.right);
            for (int side : new int[]{0, 1})
            {
                int u = side == 0 ? 0 : k.w - 1;
                k.put(u, v + 1, 0, k.slabTop());
                k.put(u, v + 1, 1, k.lantern());
            }
            k.put(1, v + 2, 0, k.style.rail.defaultBlockState());
            k.put(k.w - 2, v + 2, 0, k.style.rail.defaultBlockState());
        }
        k.frame(Side.LEFT, 2, 1, "minecraft:potion");
        k.run(Side.BACK, 1, k.w - 2, Fx.BARREL);
        k.put(k.w / 2, k.d - 1, 1, Fx.BREW.get(k.back));
        if (k.big())
        {
            k.tableSet(3, 2, true, k.lantern());
            k.column(k.w - 3, k.d - 2, k.back, Fx.SCANNER);
        }
    }

    private RoomsLiving()
    {
    }
}
