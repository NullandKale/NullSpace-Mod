package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.gameplay.defence.RepairBayBlockEntity;
import com.nullandkale.nullspace.gameplay.defence.RepairCost;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

/**
 * What the repair bays of a ship or station do each tick (chunk A4 of {@code docs/design/rewards-work-plan.md}). A bay works while
 * its vessel's controls have power. It takes the first damaged tool, pays the fuel for the whole job from the generators
 * ({@link RepairCost#fuelFor}: a stack of coal for a worn diamond pickaxe), then restores
 * {@link RepairCost#PER_SECOND} durability a second. It changes only the damage, so enchantments survive.
 */
public final class RepairBays
{
    public static final int INTERVAL = 10;

    static void tick(ServerLevel level, CompoundTag ship, ShipRuntime r)
    {
        if (r.bays.isEmpty() || level.getGameTime() % INTERVAL != 0)
            return;
        boolean powered = grant(r, PowerBus.System.CONTROLS) >= 1;
        for (var pos : r.bays)
            if (level.getBlockEntity(pos) instanceof RepairBayBlockEntity bay)
                work(level, ship, bay, powered);
    }

    private static void work(ServerLevel level, CompoundTag ship, RepairBayBlockEntity bay, boolean powered)
    {
        if (!powered)
        {
            bay.status("No power");
            return;
        }
        int slot = bay.working();
        if (slot < 0 || slot >= RepairBayBlockEntity.SIZE || !bay.getItem(slot).isDamaged())
        {
            slot = -1;
            for (int i = 0; i < RepairBayBlockEntity.SIZE && slot < 0; i++)
                if (bay.getItem(i).isDamaged())
                    slot = i;
            if (slot < 0)
            {
                bay.working(-1);
                bay.status("Ready");
                return;
            }
            double fuel = RepairCost.fuelFor(bay.getItem(slot).getDamageValue());
            if (!FuelSystem.spend(level, ship, RepairCost.unitsFor(bay.getItem(slot).getDamageValue())))
            {
                bay.working(-1);
                bay.status(String.format(Locale.ROOT, "Needs %.2f fuel in a generator", fuel));
                return;
            }
            bay.working(slot);
        }
        var tool = bay.getItem(slot);
        tool.setDamageValue(Math.max(0, tool.getDamageValue() - RepairCost.PER_SECOND * INTERVAL / 20));
        bay.setChanged();
        bay.status(tool.isDamaged() ? "Repairing" : "Done");
        if (!tool.isDamaged())
            bay.working(-1);
    }

    private RepairBays()
    {
    }
}
