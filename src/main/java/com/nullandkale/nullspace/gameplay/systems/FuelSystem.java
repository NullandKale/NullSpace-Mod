package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** One shared store across connected generators. Burn units are conserved between all consumers. */
public final class FuelSystem {
  public static List<MachineBlockEntity> generators(ServerLevel level, CompoundTag ship) {
    return ShipSystems.equipment(level, ship).stream()
        .filter(p -> level.getBlockState(p).is(ModBlocks.GENERATOR))
        .map(level::getBlockEntity)
        .filter(MachineBlockEntity.class::isInstance)
        .map(MachineBlockEntity.class::cast)
        .toList();
  }

  public static int available(ServerLevel level, CompoundTag ship) {
    return (int)
        Math.min(
            Integer.MAX_VALUE,
            generators(level, ship).stream().mapToLong(MachineBlockEntity::available).sum());
  }

  public static boolean spend(ServerLevel level, CompoundTag ship, int amount) {
    if (amount <= 0) return true;
    if (available(level, ship) < amount) return false;
    // Spread the draw so generators empty together (playtest 3), not one after another.
    var generators = generators(level, ship);
    long total = generators.stream().mapToLong(MachineBlockEntity::available).sum();
    int remaining = amount;
    for (var generator : generators) {
      int share =
          (int) Math.min(remaining, (long) amount * generator.available() / Math.max(1, total));
      remaining -= generator.draw(share);
    }
    // Rounding leftovers come from whoever still has fuel.
    for (var generator : generators) {
      if (remaining == 0) break;
      remaining -= generator.draw(remaining);
    }
    return remaining == 0;
  }

  public static int tier(ServerLevel level, BlockPos at) {
    return level.getBlockEntity(at) instanceof MachineBlockEntity machine ? machine.tier() : 0;
  }

  public static int capacity(ServerLevel level, BlockPos at) {
    return 1200 * (1 + tier(level, at));
  }

  /**
   * Internal fuel is furnace burn time x 8, so one coal is 12,800 units. Players see fuel where one
   * coal is 1.00 (playtest 2 decision); internal amounts stay integers.
   */
  public static final int UNITS_PER_FUEL = 1600 * 8;

  /** Fuel as players see it: coal-equivalents with two decimals, e.g. "1.25". */
  public static String display(long units) {
    return String.format(java.util.Locale.ROOT, "%.2f", units / (double) UNITS_PER_FUEL);
  }

  /** P7 balance: a jump within a star system costs 0.50 fuel before engine upgrades. */
  public static final int LOCAL_JUMP = UNITS_PER_FUEL / 2;

  /** P7 balance: an interstellar jump costs 2.00 fuel (P9 will scale this with distance). */
  public static final int INTERSTELLAR_JUMP = UNITS_PER_FUEL * 2;

  /** Launch or landing base cost: about 0.25 fuel for a typical 1,500-block ship. */
  public static int transfer(int blocks) {
    return (400 + blocks) * 2;
  }

  /**
   * P7 balance: generators deliver this many power units per fuel unit. An idle starter ship
   * drawing 8 power (controls 1, one Air Supply at 4, doors 1, scanner and camera 2) burns about 4
   * fuel an hour.
   */
  public static final int POWER_PER_FUEL_UNIT = 11;

  /**
   * Power one generator can deliver: 16, plus 8 per upgrade tier. Doubled with the Air Supply's
   * move from 2 to 4 power (November 7), so ships keep the same headroom for everything else.
   */
  public static int generatorOutput(int tier) {
    return 16 + 8 * tier;
  }

  /** Fuel (coal) per hour for a steady draw from tier-0 generators. */
  public static double fuelPerHour(int power) {
    return power * 72000.0 / POWER_PER_FUEL_UNIT / UNITS_PER_FUEL;
  }

  public static int travelCost(ServerLevel level, CompoundTag ship, int base) {
    int best =
        ShipSystems.equipment(level, ship).stream()
            .filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE))
            .filter(p -> ShipSystems.active(ship, p))
            .mapToInt(p -> tier(level, p))
            .max()
            .orElse(0);
    return Math.max(1, (base * 4 + 3 + best) / (4 + best));
  }

  public static void provision(ServerLevel level, BlockPos at, int coal) {
    if (level.getBlockEntity(at) instanceof MachineBlockEntity machine
        && machine.generator()
        && machine.isEmpty()
        && machine.burn() == 0)
      machine.setItem(
          3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, coal));
  }

  private FuelSystem() {}
}
