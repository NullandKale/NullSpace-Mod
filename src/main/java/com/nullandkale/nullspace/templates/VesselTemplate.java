package com.nullandkale.nullspace.templates;

import com.nullandkale.nullspace.ship.ShipBlocks;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Shared ordinary-block pieces for hand-authored manufacturer templates. Coordinates are local. */
public final class VesselTemplate {
  private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

  public void put(int x, int y, int z, Block block) {
    blocks.put(new BlockPos(x, y, z), block.defaultBlockState());
  }

  public void state(BlockPos at, BlockState state) {
    blocks.put(at, state);
  }

  public void clear(BlockPos at) {
    blocks.remove(at);
  }

  public static void rectangle(Set<BlockPos> footprint, int x0, int z0, int x1, int z1) {
    for (int x = x0; x <= x1; x++)
      for (int z = z0; z <= z1; z++) footprint.add(new BlockPos(x, 0, z));
  }

  public void shell(Set<BlockPos> footprint, int floor, int height, Block hull, Block deck) {
    shell(footprint, floor, p -> height - 1, hull, deck);
  }

  public void shell(
      Set<BlockPos> footprint,
      int floor,
      java.util.function.ToIntFunction<BlockPos> ceiling,
      Block hull,
      Block deck) {
    for (var at : footprint) {
      int x = at.getX(), z = at.getZ();
      put(x, floor, z, deck);
      int roof = ceiling.applyAsInt(at);
      put(x, floor + roof, z, hull);
      for (int y = 1; y < roof; y++) {
        boolean edge = false;
        for (var d : Direction.Plane.HORIZONTAL) {
          var neighbor = at.relative(d);
          if (!footprint.contains(neighbor) || ceiling.applyAsInt(neighbor) <= y) edge = true;
        }
        if (edge) {
          boolean window = (y == 2 || y == 3) && (x + z) % 5 != 0;
          put(x, floor + y, z, window ? Blocks.GLASS : hull);
        }
      }
    }
  }

  public void door(BlockPos at, Direction facing, Block frame) {
    var side = facing.getClockWise();
    for (var d : List.of(side, side.getOpposite()))
      for (int y = 0; y < 2; y++) blocks.put(at.relative(d).above(y), frame.defaultBlockState());
    blocks.put(at.above(2), frame.defaultBlockState());
    var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing);
    blocks.put(at, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
    blocks.put(at.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
  }

  public void dock(BlockPos door, Direction facing, Block frame) {
    door(door, facing, frame);
    machine(door.below(), ShipBlocks.DOCKING_BASE, facing);
    for (int d = 1; d <= 2; d++) {
      clear(door.relative(facing.getOpposite(), d));
      clear(door.relative(facing.getOpposite(), d).above());
    }
  }

  public void bulkhead(int z, int x0, int x1, int floor, int height, int doorX, Block hull) {
    for (int x = x0; x <= x1; x++) for (int y = 1; y < height - 1; y++) put(x, floor + y, z, hull);
    door(new BlockPos(doorX, floor + 1, z), Direction.NORTH, hull);
  }

  public void machine(BlockPos at, Block block, Direction facing) {
    var state = block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
    if (block == ShipBlocks.CAMERA || block == ShipBlocks.MONITOR) {
      state = state.setValue(ShipBlocks.InventoryMachine.LEGACY_HULL, false);
      var backing = at.relative(facing.getOpposite());
      var behind = blocks.get(backing);
      if (behind == null || behind.isAir())
        blocks.put(backing, Blocks.IRON_BLOCK.defaultBlockState());
    }
    blocks.put(at, state);
  }

  public void light(int x, int y, int z) {
    put(x, y, z, Blocks.SEA_LANTERN);
  }

  public Map<BlockPos, BlockState> finish(int width, int height, int depth) {
    for (var at : blocks.keySet())
      if (at.getX() < 0
          || at.getY() < 0
          || at.getZ() < 0
          || at.getX() >= width
          || at.getY() >= height
          || at.getZ() >= depth)
        throw new IllegalStateException("Template exceeds its declared bounds at " + at);
    return Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
  }
}
