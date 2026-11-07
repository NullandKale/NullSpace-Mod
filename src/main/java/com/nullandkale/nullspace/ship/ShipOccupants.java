package com.nullandkale.nullspace.ship;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Shared by sensors and the final transfer: inspection must not promise readiness for unsupported
 * cargo.
 */
public final class ShipOccupants {
  public record Contents(List<Entity> cargo, List<ServerPlayer> crew) {}

  public static Contents inspect(ServerLevel level, ShipScan.Result scan) {
    return inspect(level, scan, false);
  }

  static Contents inspect(ServerLevel level, ShipScan.Result scan, boolean recall) {
    ShipEntities.requireLoaded(level, scan.bounds());
    var contents = ShipEntities.inBounds(level, scan.bounds());
    List<Entity> cargo = new ArrayList<>();
    List<ServerPlayer> crew = new ArrayList<>();
    for (Entity entity : contents) {
      if (recall && entity instanceof ServerPlayer p && !p.isAlive()) continue;
      // Every entity fully inside the ship travels: mobs, vehicles, decorations, projectiles,
      // items. Only living occupants must be inside the sealed cabin.
      if (!recall
          && entity instanceof LivingEntity
          && !scan.pressure().breathable(BlockPos.containing(entity.getEyePosition())))
        throw ShipMessages.entityProblem(
            entity,
            "Move fully inside a sealed cabin before launch; this occupant is outside the safe"
                + " cabin area.");
      if (!scan.bounds()
              .box()
              .contains(
                  entity.getBoundingBox().minX,
                  entity.getBoundingBox().minY,
                  entity.getBoundingBox().minZ)
          || !scan.bounds()
              .box()
              .contains(
                  entity.getBoundingBox().maxX - 0.001,
                  entity.getBoundingBox().maxY - 0.001,
                  entity.getBoundingBox().maxZ - 0.001))
        throw ShipMessages.entityProblem(
            entity,
            "Move completely inside the ship; part of this occupant crosses the ship boundary.");
      // Riders travel with their mount and leashes are kept; the transfer handles both.
      if (entity instanceof ServerPlayer player) crew.add(player);
      else cargo.add(entity);
    }
    return new Contents(List.copyOf(cargo), List.copyOf(crew));
  }

  private ShipOccupants() {}
}
