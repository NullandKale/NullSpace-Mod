package com.nullandkale.nullspace.systems;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/**
 * Equipment discovery works on damaged ships, independently of launch validation. Never loads
 * terrain.
 */
public final class ShipTopology {
  public static CompoundTag resolve(ServerLevel level, BlockPos controller) {
    var store = ShipStore.get(level.getServer());
    var selected =
        level.dimension().equals(TravelService.SPACE)
            ? java.util.Optional.<PadRegistry.Pad>empty()
            : PadRegistry.below(level, controller);
    var existing = store.at(level, controller);
    if (selected.isPresent()
        && existing != null
        && !existing.getStringOr("homeId", "").equals(selected.get().id())) existing = null;
    if (existing != null) {
      if (ShipBlocks.navigation(level.getBlockState(controller))
          && !controller.equals(ShipStore.pos(existing, "controller"))) {
        var connected = discover(level, existing);
        long helms =
            connected.stream().filter(p -> ShipBlocks.navigation(level.getBlockState(p))).count();
        if (!connected.contains(controller) || helms != 1)
          throw new IllegalArgumentException(
              "Resolve competing or disconnected helms before claiming this ship");
        existing.putLong("controller", controller.asLong());
        store.requestSave();
      }
      return existing;
    }
    for (var pad : selected.stream().toList()) {
      var b = pad.bounds();
      if (!pad.dimension().equals(level.dimension().identifier().toString())
          || controller.getX() < b.minX()
          || controller.getX() > b.maxX()
          || controller.getZ() < b.minZ()
          || controller.getZ() > b.maxZ()
          || controller.getY() <= b.y()
          || controller.getY() > b.y() + 32) continue;
      var probe = new CompoundTag();
      probe.putLong("base", new BlockPos(b.minX(), b.y() + 1, b.minZ()).asLong());
      probe.putLong("controller", controller.asLong());
      probe.putInt("width", b.width());
      probe.putInt("depth", b.depth());
      probe.putInt("height", PadRegistry.buildHeight(level, b));
      if (!ShipBlocks.navigation(level.getBlockState(controller))) {
        var queue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        queue.add(controller);
        BlockPos found = null;
        while (!queue.isEmpty()) {
          var p = queue.removeFirst();
          if (!ShipStore.bounds(probe).contains(p)
              || !visited.add(p)
              || !level.hasChunkAt(p)
              || level.getBlockState(p).isAir()) continue;
          if (ShipBlocks.navigation(level.getBlockState(p))) {
            if (found != null)
              throw new IllegalArgumentException("Multiple helms/computers in this build");
            found = p;
          }
          for (var d : Direction.values()) queue.add(p.relative(d));
        }
        if (found == null)
          throw new IllegalArgumentException("Connect this controller to a helm or ship computer");
        controller = found;
      }
      var tag = new CompoundTag();
      tag.putString("id", UUID.randomUUID().toString());
      tag.putString("dimension", pad.dimension());
      tag.putLong("base", new BlockPos(b.minX(), b.y() + 1, b.minZ()).asLong());
      tag.putLong("controller", controller.asLong());
      tag.putString("homeId", pad.id());
      tag.putString("home", pad.name());
      tag.put("crew", new ListTag());
      tag.putInt("width", b.width());
      tag.putInt("depth", b.depth());
      tag.putInt("height", PadRegistry.buildHeight(level, b));
      store.ships().add(tag);
      store.requestSave();
      return tag;
    }
    throw new IllegalArgumentException("Register the pad below this ship first");
  }

  /** Query availability only: automation must not complete a partially loaded hull. */
  public static boolean loaded(ServerLevel level, CompoundTag ship) {
    var bounds = ShipStore.bounds(ship);
    for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
      for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
        if (!level.hasChunk(x, z)) return false;
    return true;
  }

  public static Set<BlockPos> discover(ServerLevel level, CompoundTag ship) {
    var bounds = ShipStore.bounds(ship);
    var start = ShipStore.pos(ship, "controller");
    if (!level.hasChunkAt(start)) return Set.of();
    if (ShipBlocks.navigation(level.getBlockState(start))) return flood(level, bounds, start);
    // Keep one surviving bus: select the largest component containing actual supply/control
    // hardware. Never join disconnected wreck fragments through metadata alone.
    Set<BlockPos> best = Set.of();
    var visited = new HashSet<BlockPos>();
    boolean ambiguous = false;
    for (BlockPos p : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
      if (visited.contains(p) || !level.hasChunkAt(p)) continue;
      var state = level.getBlockState(p);
      if (!state.is(ShipBlocks.SYSTEMS_CONTROLLER)
          && !state.is(ShipBlocks.GENERATOR)
          && !state.is(ShipBlocks.BATTERY)) continue;
      var component = flood(level, bounds, p.immutable());
      visited.addAll(component);
      if (component.size() > best.size()) {
        best = component;
        ambiguous = false;
      } else if (component.size() == best.size() && !component.isEmpty()) ambiguous = true;
    }
    return ambiguous ? Set.of() : best;
  }

  private static Set<BlockPos> flood(ServerLevel level, ShipScan.Bounds bounds, BlockPos start) {
    var found = new HashSet<BlockPos>();
    var queue = new ArrayDeque<BlockPos>();
    queue.add(start);
    while (!queue.isEmpty()) {
      var p = queue.removeFirst();
      if (!bounds.contains(p) || found.contains(p) || !level.hasChunkAt(p)) continue;
      var state = level.getBlockState(p);
      if (state.isAir()) continue;
      found.add(p);
      for (var d : Direction.values()) queue.add(p.relative(d));
    }
    return Set.copyOf(found);
  }

  private ShipTopology() {}
}
