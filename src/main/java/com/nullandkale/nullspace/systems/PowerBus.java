package com.nullandkale.nullspace.systems;

import java.util.*;

/** Integer, deterministic allocation. One unit of power consumes one unit of energy per tick. */
public final class PowerBus {
  public enum System {
    CONTROLS,
    AIR,
    DOORS,
    ENGINES,
    CAMERAS,
    WEAPONS
  }

  public record Load(System system, int requested, int capacity, int demand) {
    public Load {
      if (requested < 0 || capacity < 0 || demand < 0)
        throw new IllegalArgumentException("Negative power load");
    }
  }

  public record Result(
      int energy,
      int generated,
      int discharged,
      int consumed,
      int charged,
      int curtailed,
      Map<System, Integer> delivered) {}

  public static Result tick(
      int generation,
      int energy,
      int storageCapacity,
      int chargeRate,
      int dischargeRate,
      List<Load> loads) {
    if (generation < 0
        || energy < 0
        || storageCapacity < energy
        || chargeRate < 0
        || dischargeRate < 0) throw new IllegalArgumentException("Invalid supply");
    int available = Math.addExact(generation, Math.min(energy, dischargeRate)), consumed = 0;
    var grants = new EnumMap<System, Integer>(System.class);
    for (var load : loads) {
      if (grants.containsKey(load.system))
        throw new IllegalArgumentException("Duplicate subsystem");
      int grant = Math.min(available, Math.min(load.requested, load.capacity));
      available -= grant;
      grants.put(load.system, grant);
      consumed += Math.min(grant, load.demand);
    }
    int discharge = Math.max(0, consumed - generation),
        surplus = Math.max(0, generation - consumed);
    int charge = Math.min(surplus, Math.min(chargeRate, storageCapacity - energy));
    return new Result(
        energy - discharge + charge,
        generation,
        discharge,
        consumed,
        charge,
        surplus - charge,
        Map.copyOf(grants));
  }

  private PowerBus() {}
}
