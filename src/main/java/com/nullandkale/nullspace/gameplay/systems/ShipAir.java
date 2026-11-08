package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

/**
 * Air aboard a ship as players and screens read it: rooms, breathability, pressure and Air Supply
 * output.
 */
public final class ShipAir {

  public static boolean breathable(ServerLevel level, CompoundTag ship, BlockPos pos) {
    if (DockingPorts.sealedMate(level, pos)) {
      var state = level.getBlockState(pos);
      var inside = pos.relative(state.getValue(DoorBlock.FACING).getOpposite());
      return breathable(level, ship, inside);
    }
    if (!WorldStore.bounds(ship).contains(pos)) return false;
    var r = ShipSystems.runtime(level, ship);
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    return r.air[ShipSystems.local(ship, pos)] >= Atmosphere.BREATHABLE;
  }

  /** Oxygen at a block, 0-100 as players see it; 0 outside the vessel's air volume. */
  public static int oxygen(ServerLevel level, CompoundTag ship, BlockPos pos) {
    if (DockingPorts.sealedMate(level, pos)) {
      var state = level.getBlockState(pos);
      return oxygen(level, ship, pos.relative(state.getValue(DoorBlock.FACING).getOpposite()));
    }
    if (!WorldStore.bounds(ship).contains(pos)) return 0;
    var r = ShipSystems.runtime(level, ship);
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    int cell = ShipSystems.local(ship, pos);
    if (r.atmosphere.roomAt()[cell] >= 0) return SpatialAir.percent(r.air[cell]);
    // A doorway (or any cell between rooms) belongs to no room: breathe the best adjoining room.
    int best = SpatialAir.percent(r.air[cell]);
    for (var d : Direction.values()) {
      var next = pos.relative(d);
      if (!WorldStore.bounds(ship).contains(next)) continue;
      int neighbour = ShipSystems.local(ship, next);
      if (r.atmosphere.roomAt()[neighbour] >= 0)
        best = Math.max(best, SpatialAir.percent(r.air[neighbour]));
    }
    return best;
  }

  public static int pressure(ServerLevel level, CompoundTag ship, int x, int y, int z) {
    if (!ShipSystems.grid(ship).contains(x, y, z))
      throw new IllegalArgumentException("Cell coordinates are outside this vessel");
    var r = ShipSystems.runtime(level, ship);
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    return Math.max(0, r.air[ShipSystems.grid(ship).index(x, y, z)]);
  }

  public static int[] interiorMask(ServerLevel level, CompoundTag ship, BlockPos offset) {
    var r = ShipSystems.runtime(level, ship);
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    var grid = ShipSystems.grid(ship);
    var result = new int[1024];
    for (var room : r.rooms)
      if (room.leaks() == 0)
        for (int cell : room.cells()) {
          int x = grid.x(cell) - offset.getX(),
              y = grid.y(cell) - offset.getY(),
              z = grid.z(cell) - offset.getZ();
          if (x >= 0 && x < 32 && y >= 0 && y < 32 && z >= 0 && z < 32) {
            int index = Atmosphere.index(x, y, z);
            result[index >>> 5] |= 1 << (index & 31);
          }
        }
    return result;
  }

  public static RoomReading roomAt(ServerLevel level, CompoundTag ship, BlockPos pos) {
    if (!ShipSystems.enabled(ship) || !WorldStore.bounds(ship).contains(pos)) return null;
    var r = ShipSystems.runtime(level, ship);
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    int number = r.atmosphere.roomAt()[ShipSystems.local(ship, pos)];
    return number < 0 ? null : rooms(level, ship).get(number);
  }

  /** How many block levels a room spans: an open stairwell joins floors into one room. */
  public static int roomFloors(ServerLevel level, CompoundTag ship, int room) {
    var r = ShipSystems.runtime(level, ship);
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    var grid = ShipSystems.grid(ship);
    var standing = new HashMap<Integer, Integer>();
    var rooms = r.atmosphere.roomAt();
    // Room numbers shown to players start at 1; the room map counts from 0. A level is a height
    // where at least six of the room's cells stand on a floor outside the room (so single stair
    // steps do not count as levels).
    int index = room - 1, below = grid.offsets()[2]; // offsets follow SpatialAir: [2] is one down
    for (int c = 0; c < rooms.length; c++)
      if (rooms[c] == index) {
        int y = grid.y(c);
        if (y == 0 || rooms[c + below] != index) standing.merge(y, 1, Integer::sum);
      }
    return (int) Math.max(1, standing.values().stream().filter(n -> n >= 6).count());
  }

  public record RoomReading(int number, int volume, int oxygenPercent, int leaks, long quantity) {
    public String description() {
      return String.format(
          Locale.ROOT,
          "R%02d Oxygen %d%% | %d cells | %s",
          number,
          oxygenPercent,
          volume,
          leaks == 0 ? "SEALED" : "LEAK " + leaks);
    }
  }

  public static List<RoomReading> rooms(ServerLevel level, CompoundTag ship) {
    if (!ShipSystems.enabled(ship)) return List.of();
    var r = ShipSystems.runtime(level, ship);
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    var result = new ArrayList<RoomReading>();
    for (var room : r.rooms) {
      long quantity = 0;
      for (int cell : room.cells()) quantity += Math.max(0, r.air[cell]);
      result.add(
          new RoomReading(
              result.size() + 1,
              room.cells().length,
              (int) Math.min(Integer.MAX_VALUE, quantity * 100 / (room.cells().length * 1000L)),
              room.leaks(),
              quantity));
    }
    return List.copyOf(result);
  }

  /** Air Supplies that feed a room, in the order the air bus is shared between them. */
  static List<BlockPos> airOutlets(ServerLevel level, CompoundTag ship, ShipRuntime r) {
    var outlets = new ArrayList<BlockPos>();
    for (var p : r.equipment)
      if (level.getBlockState(p).is(ModBlocks.AIR_SUPPLY) && ShipSystems.active(r, p)) {
        var front = p.relative(level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING));
        if (WorldStore.bounds(ship).contains(front)) outlets.add(p.immutable());
      }
    return outlets;
  }

  /** Power one Air Supply receives from the air bus, which is shared evenly. */
  static int supplyShare(
      ServerLevel level, CompoundTag ship, ShipRuntime r, BlockPos supply, int bus) {
    var outlets = airOutlets(level, ship, r);
    int i = outlets.indexOf(supply);
    if (i < 0) return 0;
    return bus / outlets.size() + (i < bus % outlets.size() ? 1 : 0);
  }

  /**
   * For the Air Supply screen: this pump's output as a percent of normal, and the share of its
   * room's leak that all pumps feeding the room cover (-1 when the room does not leak). One hole
   * needs two pumps at normal output.
   */
  public static int[] airSupplyReading(ServerLevel level, CompoundTag ship, BlockPos supply) {
    if (!ShipSystems.enabled(ship)) return new int[] {0, -1};
    var r = ShipSystems.runtime(level, ship);
    int bus = ShipSystems.grant(r, PowerBus.System.AIR);
    var outlets = airOutlets(level, ship, r);
    int index = outlets.indexOf(supply);
    if (index < 0) return new int[] {0, -1};
    int own = SpatialAir.output(supplyShare(level, ship, r, supply, bus));
    var front =
        supply.relative(level.getBlockState(supply).getValue(HorizontalDirectionalBlock.FACING));
    var room = roomAt(level, ship, front);
    if (room == null || room.leaks() == 0)
      return new int[] {own * 100 / SpatialAir.FULL_OUTPUT, -1};
    long total = 0;
    for (var other : outlets) {
      var out =
          other.relative(level.getBlockState(other).getValue(HorizontalDirectionalBlock.FACING));
      var otherRoom = roomAt(level, ship, out);
      if (otherRoom != null && otherRoom.number() == room.number())
        total += SpatialAir.output(supplyShare(level, ship, r, other, bus));
    }
    long need = (long) room.leaks() * 2 * SpatialAir.FULL_OUTPUT;
    return new int[] {own * 100 / SpatialAir.FULL_OUTPUT, (int) Math.min(999, total * 100 / need)};
  }

  public static boolean tether(ServerLevel level, CompoundTag ship) {
    var r = ShipSystems.runtime(level, ship);
    if (ShipSystems.grant(r, PowerBus.System.AIR) == 0) return false;
    var base = WorldStore.pos(ship, "base");
    for (var p : r.equipment)
      if (level.getBlockState(p).is(ModBlocks.AIR_SUPPLY) && ShipSystems.active(r, p)) {
        var at = p.relative(level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING));
        if (breathable(level, ship, at)) {
          int i = ShipSystems.local(ship, at);
          r.air[i] = Math.max(0, r.air[i] - 10);
          ShipSystems.state(ship).putIntArray("air", r.air);
          return true;
        }
      }
    return false;
  }

  private ShipAir() {}
}
