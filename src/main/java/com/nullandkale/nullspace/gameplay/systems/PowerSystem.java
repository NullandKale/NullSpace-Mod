package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

final class PowerSystem
{
    private static List<CompoundTag> batteries(CompoundTag ship, ShipRuntime r)
    {
        return r.batteryDevices.stream().filter(d -> d.getBooleanOr("enabled", true)).toList();
    }

    record Allocation(PowerBus.Result result, List<CompoundTag> batteries, int stored, int demand, int capacity)
    {}

    static Allocation allocate(ServerLevel level, CompoundTag ship, ShipRuntime r)
    {
        var s = state(ship);
        int[] requests = s.getIntArray("requests").orElse(ShipSystems.defaultRequests());
        if (requests.length != 6)
            throw new IllegalStateException("Invalid saved allocations");
        var counts = new HashMap<Block, Integer>();
        for (var p : r.equipment)
            if (active(r, p))
                counts.merge(level.getBlockState(p).getBlock(), 1, Integer::sum);
        int generators = counts.getOrDefault(ModBlocks.GENERATOR, 0), air = counts.getOrDefault(ModBlocks.AIR_SUPPLY, 0), cameras = counts.getOrDefault(ModBlocks.CAMERA, 0) + counts.getOrDefault(ModBlocks.LOCAL_SCANNER, 0);
        var batteries = batteries(ship, r);
        int energy = batteries.stream().mapToInt(b -> b.getIntOr("energy", 0)).sum();
        int controls = counts.getOrDefault(ModBlocks.COMPUTER, 0) + counts.getOrDefault(ModBlocks.STATION_CORE, 0) + counts.getOrDefault(ModBlocks.TERMINAL, 0) > 0 ? 1 : 0;
        int doors = counts.getOrDefault(ModBlocks.COMPUTER, 0) + counts.getOrDefault(ModBlocks.DOCKING_BASE, 0) > 0 ? 1 : 0;
        int engines = r.equipment.stream().filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE) && active(r, p)).mapToInt(p -> 3 + 2 * FuelSystem.tier(level, p)).sum();
        int weapons = counts.getOrDefault(ModBlocks.WEAPON, 0) * 2;
        // What the installed hardware can take, in the order of PowerBus.System: controls, air, doors, engines, cameras, weapons.
        int[] caps = {controls, air * SpatialAir.MAX_SUPPLY_POWER, doors, engines, cameras, weapons};
        var loads = new ArrayList<PowerBus.Load>();
        int[] order = s.getIntArray("order").orElse(s.getBooleanOr("airFirst", false) ? new int[]{1, 0, 2, 4, 3, 5} : ship.getBooleanOr("generatedRooms", false) ? new int[]{0, 2, 1, 4, 3, 5} : new int[]{0, 1, 2, 4, 3, 5});
        for (int i : order)
            loads.add(new PowerBus.Load(PowerBus.System.values()[i], i == 3 && requests[i] > 0
                ? requests[i] + r.equipment.stream().filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE) && active(r, p)).mapToInt(p -> 2 * FuelSystem.tier(level, p)).sum()
                // The air request is power per Air Supply: 4 is normal output, 8 is 150%.
                : i == 1 ? requests[i] * air : requests[i], caps[i], i == 2 ? (r.doors.isEmpty() ? 0 : 1) : i == 3 ? Math.max(0, ship.getIntOr("thrustDemand", 0)) : caps[i]));
        int capacity = batteries.stream().mapToInt(b -> FuelSystem.capacity(level, batteryPos(ship, b))).sum();
        int chargeRate = batteries.stream().mapToInt(b -> Math.min(chargeStep(level, ship, b), FuelSystem.capacity(level, batteryPos(ship, b)) - b.getIntOr("energy", 0))).sum();
        int dischargeRate = batteries.stream().mapToInt(b -> Math.min(chargeStep(level, ship, b), b.getIntOr("energy", 0))).sum();
        int output = r.equipment.stream().filter(p -> level.getBlockState(p).is(ModBlocks.GENERATOR) && active(r, p)).mapToInt(p -> FuelSystem.generatorOutput(FuelSystem.tier(level, p))).sum();
        int supply = s.getBooleanOr("supplyDisabled", false) ? 0 : Math.min(output, FuelSystem.available(level, ship));
        int served = loads.stream().mapToInt(l -> Math.min(l.requested(), Math.min(l.capacity(), l.demand()))).sum();
        return new Allocation(PowerBus.tick(supply, energy, Math.max(capacity, energy), chargeRate, dischargeRate, loads), batteries, energy, served, output);
    }

    static void tick(ServerLevel level, CompoundTag ship, ShipRuntime r)
    {
        var allocation = allocate(level, ship, r);
        r.power = allocation.result();
        int used = r.power.generated() - r.power.curtailed();
        for (var position : r.equipment)
            if (used > 0 && level.getBlockState(position).is(ModBlocks.GENERATOR) && active(r, position))
            {
                int efficiency = FuelSystem.tier(level, position), work = Math.min(used, FuelSystem.generatorOutput(efficiency));
                // P7 balance: POWER_PER_FUEL_UNIT power units per fuel unit, upgrades cheaper still.
                // Fractions carry over per generator so small steady loads are charged exactly.
                var s = ShipSystems.state(ship);
                var debts = s.getCompoundOrEmpty("powerDebt");
                String key = Long.toString(position.asLong());
                long debt = debts.getLongOr(key, 0) + work * 4L;
                long divisor = FuelSystem.POWER_PER_FUEL_UNIT * (4L + efficiency);
                int units = (int) (debt / divisor);
                if (units > 0)
                    FuelSystem.spend(level, ship, units);
                debts.putLong(key, debt % divisor);
                s.put("powerDebt", debts);
                used -= work;
            }
        int difference = r.power.energy() - allocation.stored();
        for (var b : allocation.batteries())
        {
            int old = b.getIntOr("energy", 0);
            int step = chargeStep(level, ship, b);
            int change = difference >= 0 ? Math.min(step, Math.min(FuelSystem.capacity(level, batteryPos(ship, b)) - old, difference)) : -Math.min(step, Math.min(old, -difference));
            b.putInt("energy", old + change);
            b.putInt("flow", change);
            difference -= change;
        }
    }

    /** The block of a battery record, in world coordinates. */
    private static BlockPos batteryPos(CompoundTag ship, CompoundTag battery)
    {
        return WorldStore.pos(ship, "base").offset(BlockPos.of(battery.getLongOr("position", 0)));
    }

    /** What a battery can move in or out in one tick: four units for the first tier, four more for each tier above it. */
    private static int chargeStep(ServerLevel level, CompoundTag ship, CompoundTag battery)
    {
        return 4 * (1 + FuelSystem.tier(level, batteryPos(ship, battery)));
    }
}
