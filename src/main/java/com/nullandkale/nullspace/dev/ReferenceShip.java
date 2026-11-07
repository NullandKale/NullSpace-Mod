package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.*;

public final class ReferenceShip {
  public static BlockPos build(ServerLevel level, BlockPos base) {
    for (int x = -1; x <= 8; x++)
      for (int z = -1; z <= 8; z++) {
        for (int y = 1; y <= 10; y++)
          level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 18);
        level.setBlock(
            base.offset(x, 0, z),
            (x == -1 || x == 8 || z == -1 || z == 8 ? Blocks.COBBLESTONE : Blocks.GRASS_BLOCK)
                .defaultBlockState(),
            18);
      }
    level.setBlock(
        base.offset(-1, 1, 3),
        PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.EAST),
        18);
    // 7x7 hull, its own floor, and a one-block air gap above the pad.
    for (int x = 0; x <= 6; x++)
      for (int z = 0; z <= 6; z++)
        for (int y = 2; y <= 6; y++)
          if (x == 0 || x == 6 || z == 0 || z == 6 || y == 2 || y == 6)
            level.setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), 18);
    BlockPos controller = base.offset(6, 3, 3);
    level.setBlock(
        controller,
        ModBlocks.COMPUTER
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST),
        18);
    level.setBlock(
        base.offset(0, 3, 3),
        ModBlocks.ENGINE
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST),
        18);
    var door =
        Blocks.OAK_DOOR
            .defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.NORTH)
            .setValue(DoorBlock.OPEN, false);
    level.setBlock(base.offset(0, 3, 1), ModBlocks.GENERATOR.defaultBlockState(), 18);
    FuelSystem.provision(level, base.offset(0, 3, 1), 64);
    level.setBlock(
        base.offset(6, 3, 1),
        ModBlocks.AIR_SUPPLY
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST),
        18);
    level.setBlock(base.offset(3, 3, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 18);
    level.setBlock(base.offset(3, 4, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
    level.setBlock(base.offset(3, 4, 6), Blocks.GLASS.defaultBlockState(), 18);
    level.setBlock(base.offset(2, 3, 2), Blocks.CHEST.defaultBlockState(), 18);
    var chest = (ChestBlockEntity) level.getBlockEntity(base.offset(2, 3, 2));
    chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
    return controller;
  }

  private ReferenceShip() {}
}
