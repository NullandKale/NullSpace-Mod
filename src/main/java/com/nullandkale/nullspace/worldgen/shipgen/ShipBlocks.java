package com.nullandkale.nullspace.worldgen.shipgen;

import com.nullandkale.nullspace.content.ModBlocks;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Turns a generated ship's kinds into real blocks, in its maker's materials. */
public final class ShipBlocks
{
    /** One maker's materials: three or four cheap blocks and one accent colour. */
    private record Palette(Block hull, Block frame, Block wall, Block floor, Block ceiling, Block trim, Block accent, Block stairs, Block bed, Block carpet)
    {}

    private static Palette palette(Spec.Maker maker)
    {
        return switch (maker)
        {
            case RIVET -> new Palette(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.CONCRETE.orange(), Blocks.STONE_BRICK_STAIRS, Blocks.BED.orange(), Blocks.CARPET.orange());
            case HALCYON -> new Palette(Blocks.QUARTZ_BLOCK, Blocks.DIORITE, Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_STONE, Blocks.SMOOTH_QUARTZ, Blocks.CHISELED_QUARTZ_BLOCK, Blocks.CONCRETE.cyan(), Blocks.QUARTZ_STAIRS, Blocks.BED.cyan(), Blocks.CARPET.cyan());
            case BASTION -> new Palette(Blocks.POLISHED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.SMOOTH_STONE, Blocks.DEEPSLATE_TILES, Blocks.CHISELED_DEEPSLATE, Blocks.CONCRETE.red(), Blocks.POLISHED_DEEPSLATE_STAIRS, Blocks.BED.red(), Blocks.CARPET.red());
        };
    }

    /** The ship's blocks, without air, keyed by their position in the ship. */
    public static Map<BlockPos, BlockState> blocks(ShipGenerator.Ship ship)
    {
        var palette = palette(ship.spec().maker());
        var out = new LinkedHashMap<BlockPos, BlockState>();
        for (var e : ship.voxels().entrySet())
            out.put(e.getKey(), state(e.getValue(), palette));
        return out;
    }

    private static BlockState facing(Block block, Direction d)
    {
        return block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, d);
    }

    private static BlockState state(Voxel v, Palette p)
    {
        var d = v.facing();
        return switch (v.kind())
        {
            case HULL -> p.hull.defaultBlockState();
            case FRAME -> p.frame.defaultBlockState();
            case WALL -> p.wall.defaultBlockState();
            case FLOOR -> p.floor.defaultBlockState();
            case CEILING -> p.ceiling.defaultBlockState();
            case TRIM -> p.trim.defaultBlockState();
            case VENT -> Blocks.COPPER_GRATE.waxed().unaffected().defaultBlockState();
            case ACCENT -> p.accent.defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
            case LIGHT -> Blocks.SEA_LANTERN.defaultBlockState();
            case DOOR_LOWER -> Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, d).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            case DOOR_UPPER -> Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, d).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            case DOCK_BASE -> facing(ModBlocks.DOCKING_BASE, d);
            case STAIR, SEAT -> p.stairs.defaultBlockState().setValue(StairBlock.FACING, d);
            case BARREL -> Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, d);
            case CHEST -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, d);
            case CHEST_LEFT -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, d).setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
            case CHEST_RIGHT -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, d).setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
            case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE.defaultBlockState();
            case FURNACE -> Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, d);
            case BLAST_FURNACE -> Blocks.BLAST_FURNACE.defaultBlockState().setValue(BlastFurnaceBlock.FACING, d);
            case SMOKER -> Blocks.SMOKER.defaultBlockState().setValue(SmokerBlock.FACING, d);
            case ANVIL -> Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, d.getClockWise());
            case ENCHANTING_TABLE -> Blocks.ENCHANTING_TABLE.defaultBlockState();
            case BOOKSHELF -> Blocks.BOOKSHELF.defaultBlockState();
            case BED -> p.bed.defaultBlockState().setValue(BedBlock.FACING, d).setValue(AbstractBedBlock.PART, BedPart.HEAD);
            case BED_FOOT -> p.bed.defaultBlockState().setValue(BedBlock.FACING, d).setValue(AbstractBedBlock.PART, BedPart.FOOT);
            case LECTERN -> Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, d);
            case BREWING_STAND -> Blocks.BREWING_STAND.defaultBlockState();
            case CAULDRON -> Blocks.CAULDRON.defaultBlockState();
            case SMITHING_TABLE -> Blocks.SMITHING_TABLE.defaultBlockState();
            case STONECUTTER -> Blocks.STONECUTTER.defaultBlockState().setValue(StonecutterBlock.FACING, d);
            case LOOM -> Blocks.LOOM.defaultBlockState().setValue(LoomBlock.FACING, d);
            case CARTOGRAPHY_TABLE -> Blocks.CARTOGRAPHY_TABLE.defaultBlockState();
            case GRINDSTONE -> Blocks.GRINDSTONE.defaultBlockState().setValue(GrindstoneBlock.FACING, d);
            case FLETCHING_TABLE -> Blocks.FLETCHING_TABLE.defaultBlockState();
            case COMPOSTER -> Blocks.COMPOSTER.defaultBlockState();
            case FARMLAND -> Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7);
            case CROP -> Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
            case CROP2 -> Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7);
            case CROP3 -> Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 3);
            case WATER -> Blocks.WATER.defaultBlockState();
            case MOSS -> Blocks.MOSS_BLOCK.defaultBlockState();
            case TABLE -> Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
            case CARPET -> p.carpet.defaultBlockState();
            case PLANT -> Blocks.POTTED_FERN.defaultBlockState();
            case COMPUTER -> facing(ModBlocks.COMPUTER, d);
            case TERMINAL -> facing(ModBlocks.TERMINAL, d);
            case SCANNER -> facing(ModBlocks.LOCAL_SCANNER, d);
            case MONITOR -> facing(ModBlocks.MONITOR, d);
            case CAMERA -> facing(ModBlocks.CAMERA, d);
            case GENERATOR -> facing(ModBlocks.GENERATOR, d);
            case BATTERY -> facing(ModBlocks.BATTERY, d);
            case ENGINE -> facing(ModBlocks.ENGINE, d);
            case AIR_SUPPLY -> facing(ModBlocks.AIR_SUPPLY, d);
            case STATION_CORE -> facing(ModBlocks.STATION_CORE, d);
        };
    }

    private ShipBlocks()
    {
    }
}
