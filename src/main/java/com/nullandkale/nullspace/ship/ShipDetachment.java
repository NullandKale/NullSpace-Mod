package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.server.ServerContext;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit, short-lived permission to leave an exact set of loose blocks in place. */
public final class ShipDetachment {
  public record Review(
      String dimension,
      BlockPos controller,
      Map<BlockPos, BlockState> loose,
      Set<BlockPos> connected,
      int expires,
      int containers) {
    public String message() {
      return loose.size()
          + " loose blocks will stay here, including "
          + containers
          + " containers or machines and everything stored in them."
          + " Nothing is deleted. Confirm leave blocks, then choose your travel action.";
    }
  }

  private record Key(String dimension, BlockPos controller) {}

  private static final ServerContext.Key<Map<Key, Review>> KEY = new ServerContext.Key<>();

  private static Map<Key, Review> approvals(ServerLevel level) {
    var map = ServerContext.get(level.getServer()).service(KEY, LinkedHashMap::new);
    map.values().removeIf(r -> r.expires() < level.getServer().getTickCount());
    return map;
  }

  public static Set<BlockPos> approved(ServerLevel level, BlockPos controller) {
    var map = approvals(level);
    var key = new Key(level.dimension().identifier().toString(), controller);
    var review = map.get(key);
    if (review == null) return Set.of();
    if (review.loose().entrySet().stream()
        .anyMatch(e -> !level.getBlockState(e.getKey()).equals(e.getValue()))) {
      map.remove(key);
      return Set.of();
    }
    return review.loose().keySet();
  }

  public static Review review(ServerLevel level, BlockPos controller) {
    var inspected = ShipService.inspect(level, controller, true);
    ShipOccupants.inspect(level, inspected.scan());
    var base = inspected.base();
    int width = inspected.pad() == null ? 32 : inspected.pad().bounds().width();
    int depth = inspected.pad() == null ? 32 : inspected.pad().bounds().depth();
    int height = inspected.pad() == null ? 32 : level.getMaxY() - base.getY() + 1;
    var tunnel = com.nullandkale.nullspace.station.DockingTunnel.positions(level);
    Map<BlockPos, BlockState> loose = new LinkedHashMap<>();
    int containers = 0;
    for (var cursor : BlockPos.betweenClosed(base, base.offset(width - 1, height - 1, depth - 1))) {
      var p = cursor.immutable();
      var state = level.getBlockState(p);
      if (state.isAir()
          || ShipScan.cover(state)
          || inspected.scan().blocks().contains(p)
          || tunnel.contains(p)) continue;
      loose.put(p, state);
      if (level.getBlockEntity(p) != null) containers++;
    }
    return new Review(
        level.dimension().identifier().toString(),
        controller.immutable(),
        Map.copyOf(loose),
        inspected.scan().blocks(),
        level.getServer().getTickCount() + 1200,
        containers);
  }

  /** Returns a normal refusal for stale confirmation; no partial permission is installed. */
  public static String confirm(ServerPlayer player, Review review) {
    if (review == null
        || review.expires() < player.level().getServer().getTickCount()
        || !review.dimension().equals(player.level().dimension().identifier().toString()))
      return "Review loose blocks again before confirming";
    var current = review(player.level(), review.controller());
    if (current.loose().isEmpty()) return "No loose blocks remain";
    if (!review.loose().equals(current.loose()) || !review.connected().equals(current.connected()))
      return "The ship or loose blocks changed. Review them again before confirming";
    var map = approvals(player.level());
    while (map.size() >= 64) map.remove(map.keySet().iterator().next());
    var key = new Key(review.dimension(), review.controller());
    map.put(key, current);
    try {
      var inspected = ShipService.preflight(player.level(), review.controller());
      if (player.level().dimension().equals(TravelService.SPACE))
        TravelService.requestDetachment(player, review.controller(), inspected);
    } catch (RuntimeException error) {
      map.remove(key);
      throw error;
    }
    return "Loose blocks will stay here. Your connected ship still needs to pass all travel"
               + " checks.";
  }

  private ShipDetachment() {}
}
