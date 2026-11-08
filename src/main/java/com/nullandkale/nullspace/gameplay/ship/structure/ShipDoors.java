package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;

/** Ordinary doors and horizontal hatches share pressure, discovery and powered control rules. */
public final class ShipDoors {
  public static boolean isDoor(BlockState state) {
    return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock;
  }

  public static boolean root(BlockState state) {
    return state.getBlock() instanceof TrapDoorBlock
        || state.getBlock() instanceof DoorBlock
            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
  }

  public static boolean open(BlockState state) {
    return isDoor(state) && state.getValue(DoorBlock.OPEN);
  }

  public static boolean framed(ServerLevel level, BlockPos at) {
    return level.getBlockState(at).getBlock() instanceof TrapDoorBlock
        ? framedHatch(level, at)
        : HullScan.framedDoor(level, at);
  }

  public static boolean framedHatch(ServerLevel level, BlockPos at) {
    if (!(level.getBlockState(at).getBlock() instanceof TrapDoorBlock)) return false;
    for (var direction : Direction.Plane.HORIZONTAL) {
      var p = at.relative(direction);
      var s = level.getBlockState(p);
      if (!s.isCollisionShapeFullBlock(level, p) || s.getBlock() instanceof LeavesBlock)
        return false;
    }
    return true;
  }

  public static boolean sealed(ServerLevel level, BlockPos at) {
    if (DockingPorts.sealedMate(level, at)) return true;
    var s = level.getBlockState(at);
    return s.getBlock() instanceof TrapDoorBlock
        ? framedHatch(level, at) && !open(s) && s.getFluidState().isEmpty()
        : HullScan.validDoor(level, at);
  }

  public static boolean powered(ServerLevel level, BlockPos at) {
    return level.hasNeighborSignal(at)
        || level.getBlockState(at).getBlock() instanceof DoorBlock
            && level.hasNeighborSignal(at.above());
  }

  public static void setOpen(ServerLevel level, BlockPos at, boolean open) {
    var s = level.getBlockState(at);
    if (s.getBlock() instanceof DoorBlock door) {
      door.setOpen(null, level, s, at, open);
      return;
    }
    if (!(s.getBlock() instanceof TrapDoorBlock) || open(s) == open) return;
    level.setBlock(at, s.setValue(TrapDoorBlock.OPEN, open), Block.UPDATE_ALL);
    boolean iron = s.is(Blocks.IRON_TRAPDOOR);
    level.playSound(
        null,
        at,
        iron
            ? (open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE)
            : (open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE),
        SoundSource.BLOCKS,
        1,
        1);
    level.gameEvent(null, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, at);
  }

  private ShipDoors() {}
}
