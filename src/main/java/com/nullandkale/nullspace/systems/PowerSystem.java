package com.nullandkale.nullspace.systems;

import static com.nullandkale.nullspace.systems.ShipSystems.*;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

final class PowerSystem {
  private static List<CompoundTag> batteries(CompoundTag ship, ShipRuntime r) {
    return r.batteryDevices.stream().filter(d -> d.getBooleanOr("enabled", true)).toList();
  }

  record Allocation(PowerBus.Result result, List<CompoundTag> batteries, int stored) {}

  static Allocation allocate(ServerLevel level, CompoundTag ship, ShipRuntime r) {
    var s = state(ship);
    int[] requests = s.getIntArray("requests").orElse(new int[] {1, 2, 1, 3, 1, 0});
    if (requests.length == 5) {
      requests = Arrays.copyOf(requests, 6);
      s.putIntArray("requests", requests);
    }
    if (requests.length != 6) throw new IllegalStateException("Invalid saved allocations");
    var counts = new HashMap<Block, Integer>();
    for (var p : r.equipment)
      if (active(r, p)) counts.merge(level.getBlockState(p).getBlock(), 1, Integer::sum);
    int generators = counts.getOrDefault(ShipBlocks.GENERATOR, 0),
        air = counts.getOrDefault(ShipBlocks.AIR_SUPPLY, 0),
        cameras =
            counts.getOrDefault(ShipBlocks.CAMERA, 0)
                + counts.getOrDefault(ShipBlocks.LOCAL_SCANNER, 0)
                + counts.getOrDefault(ShipBlocks.LONG_RANGE_SCANNER, 0);
    var batteries = batteries(ship, r);
    int energy = batteries.stream().mapToInt(b -> b.getIntOr("energy", 0)).sum();
    int[] caps = {
      counts.getOrDefault(ShipBlocks.COMPUTER, 0)
                  + counts.getOrDefault(ShipBlocks.HELM, 0)
                  + counts.getOrDefault(ShipBlocks.SYSTEMS_CONTROLLER, 0)
                  + counts.getOrDefault(ShipBlocks.TERMINAL, 0)
              > 0
          ? 1
          : 0,
      air * 2,
      counts.getOrDefault(ShipBlocks.DOOR_CONTROL, 0) > 0 ? 1 : 0,
      counts.getOrDefault(ShipBlocks.ENGINE, 0) * 3,
      cameras,
      counts.getOrDefault(ShipBlocks.WEAPON, 0) * 2
    };
    var loads = new ArrayList<PowerBus.Load>();
    int[] order =
        s.getIntArray("order")
            .orElse(
                s.getBooleanOr("airFirst", false)
                    ? new int[] {1, 0, 2, 3, 4, 5}
                    : new int[] {0, 1, 2, 3, 4, 5});
    for (int i : order)
      loads.add(
          new PowerBus.Load(
              PowerBus.System.values()[i],
              requests[i],
              caps[i],
              i == 2 ? (r.doors.isEmpty() ? 0 : 1) : caps[i]));
    int chargeRate =
        batteries.stream().mapToInt(b -> Math.min(4, 1200 - b.getIntOr("energy", 0))).sum();
    int dischargeRate =
        batteries.stream().mapToInt(b -> Math.min(4, b.getIntOr("energy", 0))).sum();
    return new Allocation(
        PowerBus.tick(
            s.getBooleanOr("supplyDisabled", false) ? 0 : generators * 8,
            energy,
            batteries.size() * 1200,
            chargeRate,
            dischargeRate,
            loads),
        batteries,
        energy);
  }

  static void tick(ServerLevel level, CompoundTag ship, ShipRuntime r) {
    var allocation = allocate(level, ship, r);
    r.power = allocation.result();
    int difference = r.power.energy() - allocation.stored();
    for (var b : allocation.batteries()) {
      int old = b.getIntOr("energy", 0),
          change =
              difference >= 0
                  ? Math.min(4, Math.min(1200 - old, difference))
                  : -Math.min(4, Math.min(old, -difference));
      b.putInt("energy", old + change);
      difference -= change;
    }
  }
}
