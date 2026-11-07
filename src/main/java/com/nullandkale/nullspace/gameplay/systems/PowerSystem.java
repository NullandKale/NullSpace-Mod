package com.nullandkale.nullspace.gameplay.systems;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

final class PowerSystem {
  private static List<CompoundTag> batteries(CompoundTag ship, ShipRuntime r) {
    return r.batteryDevices.stream().filter(d -> d.getBooleanOr("enabled", true)).toList();
  }

  record Allocation(
      PowerBus.Result result, List<CompoundTag> batteries, int stored, int demand, int capacity) {}

  static Allocation allocate(ServerLevel level, CompoundTag ship, ShipRuntime r) {
    var s = state(ship);
    int[] requests = s.getIntArray("requests").orElse(ShipSystems.defaultRequests());
    if (requests.length != 6) throw new IllegalStateException("Invalid saved allocations");
    var counts = new HashMap<Block, Integer>();
    for (var p : r.equipment)
      if (active(r, p)) counts.merge(level.getBlockState(p).getBlock(), 1, Integer::sum);
    int generators = counts.getOrDefault(ModBlocks.GENERATOR, 0),
        air = counts.getOrDefault(ModBlocks.AIR_SUPPLY, 0),
        cameras =
            counts.getOrDefault(ModBlocks.CAMERA, 0)
                + counts.getOrDefault(ModBlocks.LOCAL_SCANNER, 0);
    var batteries = batteries(ship, r);
    int energy = batteries.stream().mapToInt(b -> b.getIntOr("energy", 0)).sum();
    int[] caps = {
      counts.getOrDefault(ModBlocks.COMPUTER, 0)
                  + counts.getOrDefault(ModBlocks.STATION_CORE, 0)
                  + counts.getOrDefault(ModBlocks.TERMINAL, 0)
              > 0
          ? 1
          : 0,
      air * SpatialAir.MAX_SUPPLY_POWER,
      counts.getOrDefault(ModBlocks.COMPUTER, 0) + counts.getOrDefault(ModBlocks.DOCKING_BASE, 0)
              > 0
          ? 1
          : 0,
      r.equipment.stream()
          .filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE) && active(r, p))
          .mapToInt(p -> 3 + 2 * FuelSystem.tier(level, p))
          .sum(),
      cameras,
      counts.getOrDefault(ModBlocks.WEAPON, 0) * 2
    };
    var loads = new ArrayList<PowerBus.Load>();
    int[] order =
        s.getIntArray("order")
            .orElse(
                s.getBooleanOr("airFirst", false)
                    ? new int[] {1, 0, 2, 4, 3, 5}
                    : new int[] {0, 1, 2, 4, 3, 5});
    for (int i : order)
      loads.add(
          new PowerBus.Load(
              PowerBus.System.values()[i],
              i == 3 && requests[i] > 0
                  ? requests[i]
                      + r.equipment.stream()
                          .filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE) && active(r, p))
                          .mapToInt(p -> 2 * FuelSystem.tier(level, p))
                          .sum()
                  // The air request is power per Air Supply: 4 is normal output, 8 is 150%.
                  : i == 1 ? requests[i] * air : requests[i],
              caps[i],
              i == 2
                  ? (r.doors.isEmpty() ? 0 : 1)
                  : i == 3 ? Math.max(0, ship.getIntOr("thrustDemand", 0)) : caps[i]));
    int capacity =
        batteries.stream()
            .mapToInt(
                b ->
                    FuelSystem.capacity(
                        level,
                        WorldStore.pos(ship, "base")
                            .offset(BlockPos.of(b.getLongOr("position", 0)))))
            .sum();
    int chargeRate =
        batteries.stream()
            .mapToInt(
                b ->
                    Math.min(
                        4
                            * (1
                                + FuelSystem.tier(
                                    level,
                                    WorldStore.pos(ship, "base")
                                        .offset(BlockPos.of(b.getLongOr("position", 0))))),
                        FuelSystem.capacity(
                                level,
                                WorldStore.pos(ship, "base")
                                    .offset(BlockPos.of(b.getLongOr("position", 0))))
                            - b.getIntOr("energy", 0)))
            .sum();
    int dischargeRate =
        batteries.stream()
            .mapToInt(
                b ->
                    Math.min(
                        4
                            * (1
                                + FuelSystem.tier(
                                    level,
                                    WorldStore.pos(ship, "base")
                                        .offset(BlockPos.of(b.getLongOr("position", 0))))),
                        b.getIntOr("energy", 0)))
            .sum();
    int output =
        r.equipment.stream()
            .filter(p -> level.getBlockState(p).is(ModBlocks.GENERATOR) && active(r, p))
            .mapToInt(p -> FuelSystem.generatorOutput(FuelSystem.tier(level, p)))
            .sum();
    return new Allocation(
        PowerBus.tick(
            s.getBooleanOr("supplyDisabled", false)
                ? 0
                : Math.min(output, FuelSystem.available(level, ship)),
            energy,
            Math.max(capacity, energy),
            chargeRate,
            dischargeRate,
            loads),
        batteries,
        energy,
        loads.stream()
            .mapToInt(l -> Math.min(l.requested(), Math.min(l.capacity(), l.demand())))
            .sum(),
        output);
  }

  static void tick(ServerLevel level, CompoundTag ship, ShipRuntime r) {
    var allocation = allocate(level, ship, r);
    r.power = allocation.result();
    int used = r.power.generated() - r.power.curtailed();
    for (var position : r.equipment)
      if (used > 0
          && level.getBlockState(position).is(ModBlocks.GENERATOR)
          && active(r, position)) {
        int efficiency = FuelSystem.tier(level, position),
            work = Math.min(used, FuelSystem.generatorOutput(efficiency));
        // P7 balance: POWER_PER_FUEL_UNIT power units per fuel unit, upgrades cheaper still.
        // Fractions carry over per generator so small steady loads are charged exactly.
        var s = ShipSystems.state(ship);
        var debts = s.getCompoundOrEmpty("powerDebt");
        String key = Long.toString(position.asLong());
        long debt = debts.getLongOr(key, 0) + work * 4L;
        long divisor = FuelSystem.POWER_PER_FUEL_UNIT * (4L + efficiency);
        int units = (int) (debt / divisor);
        if (units > 0) FuelSystem.spend(level, ship, units);
        debts.putLong(key, debt % divisor);
        s.put("powerDebt", debts);
        used -= work;
      }
    int difference = r.power.energy() - allocation.stored();
    for (var b : allocation.batteries()) {
      int old = b.getIntOr("energy", 0),
          change =
              difference >= 0
                  ? Math.min(
                      4
                          * (1
                              + FuelSystem.tier(
                                  level,
                                  WorldStore.pos(ship, "base")
                                      .offset(BlockPos.of(b.getLongOr("position", 0))))),
                      Math.min(
                          FuelSystem.capacity(
                                  level,
                                  WorldStore.pos(ship, "base")
                                      .offset(BlockPos.of(b.getLongOr("position", 0))))
                              - old,
                          difference))
                  : -Math.min(
                      4
                          * (1
                              + FuelSystem.tier(
                                  level,
                                  WorldStore.pos(ship, "base")
                                      .offset(BlockPos.of(b.getLongOr("position", 0))))),
                      Math.min(old, -difference));
      b.putInt("energy", old + change);
      b.putInt("flow", change);
      difference -= change;
    }
  }
}
