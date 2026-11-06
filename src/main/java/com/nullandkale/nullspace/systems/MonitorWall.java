package com.nullandkale.nullspace.systems;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** Bounded coplanar rectangular groups. Irregular arrangements remain independent tiles. */
public final class MonitorWall {
  public static List<BlockPos> tiles(ServerLevel level, BlockPos at) {
    if (!level.getBlockState(at).is(ShipBlocks.MONITOR)) return List.of(at);
    var facing = level.getBlockState(at).getValue(HorizontalDirectionalBlock.FACING);
    boolean legacy = level.getBlockState(at).getValue(ShipBlocks.InventoryMachine.LEGACY_HULL);
    var right = facing.getClockWise();
    var seen = new HashSet<BlockPos>();
    var queue = new ArrayDeque<BlockPos>();
    queue.add(at);
    while (!queue.isEmpty() && seen.size() < 64) {
      var p = queue.removeFirst();
      if (!level.hasChunkAt(p)
          || !level.getBlockState(p).is(ShipBlocks.MONITOR)
          || level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING) != facing
          || level.getBlockState(p).getValue(ShipBlocks.InventoryMachine.LEGACY_HULL) != legacy
          || !seen.add(p)) continue;
      for (var dir : List.of(right, right.getOpposite(), Direction.UP, Direction.DOWN))
        queue.add(p.relative(dir));
    }
    int minY = seen.stream().mapToInt(BlockPos::getY).min().orElse(at.getY()),
        maxY = seen.stream().mapToInt(BlockPos::getY).max().orElse(at.getY());
    int min =
        seen.stream()
            .mapToInt(p -> p.getX() * right.getStepX() + p.getZ() * right.getStepZ())
            .min()
            .orElse(0);
    int max =
        seen.stream()
            .mapToInt(p -> p.getX() * right.getStepX() + p.getZ() * right.getStepZ())
            .max()
            .orElse(0);
    if ((max - min + 1) * (maxY - minY + 1) != seen.size() || max - min >= 8 || maxY - minY >= 8)
      return List.of(at);
    return seen.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
  }

  public static void select(ServerLevel level, CompoundTag ship, BlockPos at, String value) {
    long camera;
    try {
      camera = Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      return;
    }
    var cameras =
        ShipSystems.connected(level, ship).stream()
            .filter(p -> level.getBlockState(p).is(ShipBlocks.CAMERA))
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .toList();
    int index = cameras.indexOf(BlockPos.of(camera));
    if (index < 0) return;
    String feed = "id:" + ShipSystems.deviceId(ship, BlockPos.of(camera));
    var channels = ship.getCompoundOrEmpty("monitorChannels");
    if (!ship.contains("monitorChannels")) ship.put("monitorChannels", channels);
    for (var tile : tiles(level, at)) {
      channels.putInt(Long.toString(tile.subtract(ShipStore.pos(ship, "base")).asLong()), index);
      if (level.getBlockEntity(tile) instanceof MachineEntity e) {
        e.camera = feed;
        e.setChanged();
      }
    }
    ShipStore.get(level.getServer()).requestSave();
  }

  private MonitorWall() {}
}
