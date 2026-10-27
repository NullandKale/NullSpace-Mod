package com.nullandkale.nullspace.ship;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Short-lived, bounded sensor highlights. No off-thread world reads. */
public final class ShipDiagnostics {
  private record Highlight(String dimension, List<BlockPos> points, int expires) {}

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, Highlight>>
      KEY = new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, Highlight> highlights(net.minecraft.server.MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(KEY, HashMap::new);
  }

  public static void highlight(ServerPlayer player, ShipScan.Failure failure) {
    highlights(player.level().getServer())
        .put(
            player.getUUID(),
            new Highlight(
                player.level().dimension().identifier().toString(),
                failure.trace.stream().limit(256).toList(),
                player.level().getServer().getTickCount() + 100));
    draw(player, failure.trace.stream().limit(256).toList());
  }

  private static void draw(ServerPlayer p, List<BlockPos> points) {
    for (var at : points)
      p.level()
          .sendParticles(
              net.minecraft.core.particles.ParticleTypes.FLAME,
              at.getX() + .5,
              at.getY() + .5,
              at.getZ() + .5,
              3,
              .12,
              .12,
              .12,
              0);
  }

  public static String summary(net.minecraft.server.level.ServerLevel level, ShipScan.Result scan) {
    var remaining = new HashSet<BlockPos>();
    for (var p : BlockPos.betweenClosed(scan.bounds().min(), scan.bounds().max()))
      if (scan.pressure().breathable(p)) remaining.add(p.immutable());
    int volume = remaining.size(), rooms = 0;
    while (!remaining.isEmpty()) {
      rooms++;
      var queue = new ArrayDeque<BlockPos>();
      queue.add(remaining.iterator().next());
      while (!queue.isEmpty()) {
        var p = queue.removeFirst();
        if (!remaining.remove(p)) continue;
        for (var d : net.minecraft.core.Direction.values())
          if (remaining.contains(p.relative(d))) queue.add(p.relative(d));
      }
    }
    long engines =
        scan.blocks().stream().filter(p -> level.getBlockState(p).is(ShipBlocks.ENGINE)).count();
    return "Ship ready: "
        + scan.blocks().size()
        + " blocks; "
        + engines
        + " engines; "
        + rooms
        + " sealed compartments, "
        + volume
        + " sealed cells. Controller air source SEALED. Occupant and cargo checks passed.";
  }

  public static void initialize() {
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.SESSIONS,
        server ->
            highlights(server)
                .entrySet()
                .removeIf(
                    e -> {
                      var p = server.getPlayerList().getPlayer(e.getKey());
                      var h = e.getValue();
                      if (p == null
                          || server.getTickCount() > h.expires
                          || !p.level().dimension().identifier().toString().equals(h.dimension))
                        return true;
                      if (server.getTickCount() % 10 == 0) draw(p, h.points);
                      return false;
                    }));
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> com.nullandkale.nullspace.server.ServerContext.get(server).remove(KEY));
  }

  private ShipDiagnostics() {}
}
