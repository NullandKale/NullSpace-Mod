package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.stations.RoomKit.Spec;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** The fixtures room designs are made of, as placeable specs. */
final class Fx
{
    static final Spec CHEST = RoomKit.of(Blocks.CHEST), BARREL = RoomKit.of(Blocks.BARREL);
    static final Spec FURNACE = RoomKit.of(Blocks.FURNACE), BLAST = RoomKit.of(Blocks.BLAST_FURNACE), SMOKER = RoomKit.of(Blocks.SMOKER);
    static final Spec CRAFT = RoomKit.plain(Blocks.CRAFTING_TABLE), SMITH = RoomKit.plain(Blocks.SMITHING_TABLE), CUTTER = RoomKit.of(Blocks.STONECUTTER), LOOM = RoomKit.of(Blocks.LOOM);
    static final Spec FLETCH = RoomKit.plain(Blocks.FLETCHING_TABLE), CHARTS = RoomKit.plain(Blocks.CARTOGRAPHY_TABLE), GRIND = RoomKit.of(Blocks.GRINDSTONE), ANVIL = RoomKit.anvil();
    static final Spec SHELF = RoomKit.plain(Blocks.BOOKSHELF), LECTERN = RoomKit.of(Blocks.LECTERN), BREW = RoomKit.plain(Blocks.BREWING_STAND), CAULDRON = RoomKit.plain(Blocks.CAULDRON);
    static final Spec COMPOSTER = RoomKit.plain(Blocks.COMPOSTER), ENCHANT = RoomKit.plain(Blocks.ENCHANTING_TABLE), HOPPER = RoomKit.plain(Blocks.HOPPER), TARGET = RoomKit.plain(Blocks.TARGET);
    static final Spec BARS = RoomKit.plain(Blocks.IRON_BARS), IRON = RoomKit.plain(Blocks.IRON_BLOCK), HAY = RoomKit.plain(Blocks.HAY_BLOCK), BLACKSTONE = RoomKit.plain(Blocks.BLACKSTONE);
    static final Spec GENERATOR = RoomKit.of(ModBlocks.GENERATOR), BATTERY = RoomKit.of(ModBlocks.BATTERY), TERMINAL = RoomKit.of(ModBlocks.TERMINAL), MONITOR = RoomKit.of(ModBlocks.MONITOR);
    static final Spec SCANNER = RoomKit.of(ModBlocks.LOCAL_SCANNER), CAMERA = RoomKit.of(ModBlocks.CAMERA);
    static final Spec COMPUTER = RoomKit.of(ModBlocks.COMPUTER), ENGINE = RoomKit.of(ModBlocks.ENGINE);

    static Spec waterCauldron()
    {
        return f -> Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3);
    }

    static BlockState leaves(Block block)
    {
        return block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    private Fx()
    {
    }
}
