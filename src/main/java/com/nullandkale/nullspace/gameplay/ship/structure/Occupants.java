package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.gameplay.ship.ShipMessages;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;

/**
 * Shared by sensors and the final transfer: inspection must not promise readiness for unsupported
 * cargo.
 */
public final class Occupants
{
    public record Contents(List<Entity> cargo, List<ServerPlayer> crew)
    {}

    public static Contents inspect(ServerLevel level, HullScan.Result scan)
    {
        return inspect(level, scan, false);
    }

    public static Contents inspect(ServerLevel level, HullScan.Result scan, boolean recall)
    {
        return inspect(level, scan, recall, false);
    }

    /** As above; {@code anywhere} takes living occupants wherever they stand in the hull, for a docking move. */
    public static Contents inspect(ServerLevel level, HullScan.Result scan, boolean recall, boolean anywhere)
    {
        HullEntities.requireLoaded(level, scan.bounds());
        var contents = HullEntities.inBounds(level, scan.bounds());
        List<Entity> cargo = new ArrayList<>();
        List<ServerPlayer> crew = new ArrayList<>();
        for (Entity entity : contents)
        {
            if (recall && entity instanceof ServerPlayer p && !p.isAlive())
                continue;
            // Every entity fully inside the ship travels: mobs, vehicles, decorations, projectiles,
            // items. Only living occupants must be inside the sealed cabin.
            if (!recall && !anywhere && entity instanceof LivingEntity && !scan.pressure().breathable(BlockPos.containing(entity.getEyePosition())))
                throw ShipMessages.entityProblem(entity, "Move fully inside a sealed cabin before launch; this occupant is outside the safe" + " cabin area.");
            if (!scan.bounds().box().contains(entity.getBoundingBox().minX, entity.getBoundingBox().minY, entity.getBoundingBox().minZ) || !scan.bounds().box().contains(entity.getBoundingBox().maxX - 0.001, entity.getBoundingBox().maxY - 0.001, entity.getBoundingBox().maxZ - 0.001))
                throw ShipMessages.entityProblem(entity, "Move completely inside the ship; part of this occupant crosses the ship boundary.");
            // Riders travel with their mount and leashes are kept; the transfer handles both.
            if (entity instanceof ServerPlayer player)
                crew.add(player);
            else
                cargo.add(entity);
        }
        return new Contents(List.copyOf(cargo), List.copyOf(crew));
    }

    private Occupants()
    {
    }
}
