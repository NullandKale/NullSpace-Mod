package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.RoomKit.Side;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;

/**
 * The rooms of the endgame testbed ship (see {@code docs/design/endgame-testbed.md}): a farm, a pen, a lava farm and a vault. Each is built for
 * a small ship's side room (about 7 across and 11 deep), and each marks its chests as {@code stock} points, which the shipyard fills with
 * endgame items when the ship is delivered ({@code TestbedStock}).
 */
final class RoomsTestbed
{
    /** A bed of crops: soil with a crop standing on each cell and one water cell, which reaches four cells every way. */
    private static void bed(RoomKit k, int u0, int v0, int width, int length, BlockState crop, int waterU, int waterV)
    {
        var soil = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7);
        for (int u = u0; u < u0 + width; u++)
            for (int v = v0; v < v0 + length; v++)
            {
                boolean water = u == waterU && v == waterV;
                k.floor(u, v, water ? Blocks.WATER.defaultBlockState() : soil);
                if (!water)
                    k.put(u, v, 0, crop);
            }
    }

    private static BlockState grown(net.minecraft.world.level.block.Block crop)
    {
        return crop.defaultBlockState().setValue(CropBlock.AGE, 7);
    }

    /**
     * The food farm: wheat and carrots in beds on one side; on the other, cocoa pods ripe on a wall of jungle logs and sugar cane on sand beside
     * water. A chest of seeds and bone meal is at the back, with composters beside it.
     */
    static void farm(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.MOSS_BLOCK);
        bed(k, 0, 2, 3, 4, grown(Blocks.WHEAT), 1, 3);
        bed(k, 0, 6, 3, 4, grown(Blocks.CARROTS), 1, 7);
        var pod = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2).setValue(CocoaBlock.FACING, k.right);
        for (int v = 2; v <= 5; v++)
            for (int row = 0; row <= 2; row++)
            {
                k.put(k.w - 1, v, row, Blocks.JUNGLE_LOG.defaultBlockState());
                k.put(k.w - 2, v, row, pod);
            }
        for (int v = 6; v <= 9; v++)
        {
            k.floor(k.w - 2, v, Blocks.SAND);
            k.floor(k.w - 1, v, Blocks.WATER.defaultBlockState());
            for (int row = 0; row <= 2; row++)
                k.put(k.w - 2, v, row, Blocks.SUGAR_CANE.defaultBlockState());
        }
        if (k.put(c, k.d - 1, 0, Fx.CHEST.get(k.back)))
            k.mark(c, k.d - 1, 0, "stock", "seeds");
        k.run(Side.BACK, 1, k.w - 2, Fx.COMPOSTER);
        k.frame(Side.DOOR, c + 2, 1, "minecraft:iron_hoe");
    }

    /**
     * The pen: one fenced pen with a gate toward the path, hay and a water trough, and inside it six chickens, two cows, two sheep and two pigs.
     * A chest of food is by the door.
     */
    static void pen(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.GRASS_BLOCK);
        int u0 = 1, u1 = k.w - 2, v0 = 3, v1 = k.d - 2;
        for (int u = u0; u <= u1; u++)
            for (int v = v0; v <= v1; v++)
                if (u == u0 || u == u1 || v == v0 || v == v1)
                    k.put(u, v, 0, k.fence.defaultBlockState());
        k.put(c, v0, 0, k.gate.defaultBlockState().setValue(FenceGateBlock.FACING, k.fwd));
        k.put(u1 - 1, v1 - 1, 0, Blocks.HAY_BLOCK.defaultBlockState());
        k.put(u0 + 1, v1 - 1, 0, Fx.waterCauldron().get(k.fwd));
        String[] kinds = {"chicken", "chicken", "chicken", "chicken", "chicken", "chicken", "cow", "cow", "sheep", "sheep", "pig", "pig"};
        int i = 0;
        for (int v = v0 + 1; v < v1 - 1 && i < kinds.length; v++)
            for (int u = u0 + 1; u < u1 && i < kinds.length; u++)
            {
                k.animal(u, v, kinds[i++]);
                k.keep(u, v, 0);
            }
        if (k.put(k.w - 1, 2, 0, Fx.CHEST.get(k.left)))
            k.mark(k.w - 1, 2, 0, "stock", "food");
        k.frame(Side.DOOR, c + 2, 1, "minecraft:egg");
    }

    /**
     * The lava farm, the ship's fuel: three columns at the back, each a source of lava over a block of dripstone with a pointed dripstone
     * hanging from it over a cauldron. The dripstone drips lava into the cauldron, which fills again after every bucket taken; a bucket of lava
     * burns in a generator for a hundred items' worth. The lava is walled in stone. A chest of buckets, lava and coal blocks is by the door.
     */
    static void lavaFarm(RoomKit k)
    {
        k.air();
        int c = k.doorU;
        k.floorFill(Blocks.STONE_BRICKS);
        k.floorBorder(Blocks.BLACKSTONE);
        int v = k.d - 3;
        int[] columns = {1, k.w / 2, k.w - 2};
        var tip = Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN).setValue(PointedDripstoneBlock.THICKNESS, SpeleothemThickness.TIP);
        for (int u : columns)
        {
            k.put(u, v, 4, Blocks.LAVA.defaultBlockState());
            k.put(u, v, 3, Blocks.DRIPSTONE_BLOCK.defaultBlockState());
            k.put(u, v, 2, tip);
            k.put(u, v, 0, Blocks.LAVA_CAULDRON.defaultBlockState());
        }
        // The lava is in a trough in the stone at the top of the room: the stone goes round the lava, which is already in place.
        for (int u = 0; u < k.w; u++)
            for (int vv = v - 1; vv <= v + 1; vv++)
                k.put(u, vv, 4, Blocks.STONE_BRICKS.defaultBlockState());
        if (k.put(0, 2, 0, Fx.CHEST.get(k.right)))
            k.mark(0, 2, 0, "stock", "fuel");
        k.frame(Side.DOOR, c + 2, 1, "minecraft:lava_bucket");
    }

    /**
     * The vault: an enchanting table with a bank of bookshelves two cells away on two sides (the table has power from fifteen), smithing, anvil,
     * grindstone, stonecutter and two brewing stands down the right wall, and a row of chests down the left that the shipyard fills with gear,
     * magic, materials and food; a chest of this mod's own space items and one of treasure from the loot tables are on the right.
     */
    static void vault(RoomKit k)
    {
        int c = k.doorU;
        k.floorFill(k.planks);
        // The air supply first, so that the cell it breathes into is kept clear of the bookshelves.
        k.air();
        k.put(2, k.d - 3, 0, Blocks.ENCHANTING_TABLE.defaultBlockState());
        for (int row = 0; row <= 1; row++)
        {
            for (int v = k.d - 5; v <= k.d - 1; v++)
                k.put(0, v, row, Blocks.BOOKSHELF.defaultBlockState());
            for (int u = 1; u <= 4; u++)
                k.put(u, k.d - 1, row, Blocks.BOOKSHELF.defaultBlockState());
        }
        int right = k.w - 1;
        k.put(right, 3, 0, Fx.SMITH.get(k.left));
        k.put(right, 4, 0, Fx.ANVIL.get(k.left));
        k.put(right, 5, 0, Fx.GRIND.get(k.left));
        k.put(right, 6, 0, Fx.CUTTER.get(k.left));
        k.put(right, 7, 0, Fx.BREW.get(k.left));
        k.put(right, 8, 0, Fx.BREW.get(k.left));
        String[] left = {"gear", "magic", "materials", "food"};
        for (int i = 0; i < left.length; i++)
            if (k.put(0, 2 + i, 0, Fx.CHEST.get(k.right)))
                k.mark(0, 2 + i, 0, "stock", left[i]);
        if (k.put(right, 9, 0, Fx.CHEST.get(k.left)))
            k.mark(right, 9, 0, "stock", "space");
        if (k.put(right, 2, 0, Fx.CHEST.get(k.left)))
            k.mark(right, 2, 0, "stock", "treasure");
        k.frame(Side.DOOR, c + 2, 1, "minecraft:enchanted_book");
    }

    private RoomsTestbed()
    {
    }
}
