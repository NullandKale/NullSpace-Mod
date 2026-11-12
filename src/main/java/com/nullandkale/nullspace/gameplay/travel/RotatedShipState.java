package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.WorldStore;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

/** Saved ship-local state follows the same transform as the hull, without adding air or energy. */
final class RotatedShipState
{
    static void crew(CompoundTag member, ShipTransform transform)
    {
        var at = transform.local(new Vec3(member.getDoubleOr("x", 0), member.getDoubleOr("y", 0), member.getDoubleOr("z", 0)));
        member.putDouble("x", at.x);
        member.putDouble("y", at.y);
        member.putDouble("z", at.z);
        member.putFloat("yaw", transform.yaw(member.getFloatOr("yaw", 0)));
    }

    static void ship(CompoundTag ship, ShipTransform transform)
    {
        if (transform.turns() != 0)
        {
            if (ship.contains("mainExit"))
                ship.putLong("mainExit", transform.local(WorldStore.pos(ship, "mainExit")).asLong());
            for (var field : java.util.List.of("doorNodes", "computers", "monitorChannels"))
            {
                if (!ship.contains(field))
                    continue;
                var nodes = ship.getCompoundOrEmpty(field);
                var movedNodes = new CompoundTag();
                for (var key : nodes.keySet())
                    movedNodes.put(Long.toString(transform.local(BlockPos.of(Long.parseLong(key))).asLong()), nodes.get(key).copy());
                ship.put(field, movedNodes);
            }
            var systems = ship.getCompoundOrEmpty("systems");
            for (var tag : systems.getListOrEmpty("devices"))
            {
                var device = (CompoundTag) tag;
                device.putLong("position", transform.local(WorldStore.pos(device, "position")).asLong());
            }
            systems.getIntArray("air").ifPresent(air ->
            {
                if (air.length != 32 * 32 * 32)
                    throw new IllegalArgumentException("Ship air grid has an invalid size");
                int[] moved = new int[air.length];
                java.util.Arrays.fill(moved, -1);
                for (int i = 0; i < air.length; i++)
                {
                    var at = transform.local(new BlockPos(i % 32, i / 1024, i / 32 % 32));
                    if (at.getX() >= 0 && at.getX() < 32 && at.getY() >= 0 && at.getY() < 32 && at.getZ() >= 0 && at.getZ() < 32)
                        moved[at.getX() + at.getZ() * 32 + at.getY() * 1024] = air[i];
                    else if (air[i] > 0)
                        throw new IllegalArgumentException("Rotation would lose saved air outside the captured hull");
                }
                systems.putIntArray("air", moved);
            });
        }
        var systems = ship.getCompoundOrEmpty("systems");
        if (systems.contains("powerDebt"))
        {
            var debt = systems.getCompoundOrEmpty("powerDebt");
            var moved = new CompoundTag();
            for (var key : debt.keySet())
                moved.put(Long.toString(transform.apply(BlockPos.of(Long.parseLong(key))).asLong()), debt.get(key).copy());
            systems.put("powerDebt", moved);
        }
    }

    static void beds(CompoundTag store, String shipId, ShipTransform transform)
    {
        if (transform.turns() == 0)
            return;
        var placements = store.getCompoundOrEmpty("bedPlacements");
        var moved = new CompoundTag();
        String prefix = shipId + "@";
        for (var key : java.util.List.copyOf(placements.keySet()))
            if (key.startsWith(prefix))
            {
                var at = transform.local(BlockPos.of(Long.parseLong(key.substring(prefix.length()))));
                moved.put(prefix + at.asLong(), placements.get(key).copy());
                placements.remove(key);
            }
        for (var key : moved.keySet())
            placements.put(key, moved.get(key));
        var bindings = store.getCompoundOrEmpty("vesselBeds");
        for (var key : bindings.keySet())
        {
            var binding = bindings.getCompoundOrEmpty(key);
            if (binding.getStringOr("ship", "").equals(shipId))
            {
                binding.putLong("bed", transform.local(WorldStore.pos(binding, "bed")).asLong());
                binding.putFloat("yaw", transform.yaw(binding.getFloatOr("yaw", 0)));
            }
        }
    }

    private RotatedShipState()
    {
    }
}
