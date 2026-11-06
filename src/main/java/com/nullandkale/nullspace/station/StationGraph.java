package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Door nodes are discovered from real blocks. Route edges never substitute for hull seals. */
public final class StationGraph {
  public record Node(
      String id,
      BlockPos controller,
      BlockPos door,
      Direction facing,
      boolean dock,
      Set<BlockPos> back,
      Set<BlockPos> front) {}

  public record Graph(List<Node> nodes, Map<String, Set<String>> routes) {
    public List<String> route(String from, String to) {
      var parents = new HashMap<String, String>();
      var q = new ArrayDeque<String>();
      q.add(from);
      parents.put(from, "");
      while (!q.isEmpty()) {
        var at = q.removeFirst();
        if (at.equals(to)) break;
        for (var next : routes.getOrDefault(at, Set.of()))
          if (!parents.containsKey(next)) {
            parents.put(next, at);
            q.add(next);
          }
      }
      if (!parents.containsKey(to)) return List.of();
      var out = new ArrayList<String>();
      for (String at = to; !at.isEmpty(); at = parents.get(at)) out.add(at);
      Collections.reverse(out);
      return List.copyOf(out);
    }
  }

  /** Walkable standing positions, treating all closed doors as chamber boundaries. */
  public static Set<BlockPos> compartment(
      ServerLevel level, ShipScan.Bounds bounds, BlockPos start, int limit) {
    var seen = new HashSet<BlockPos>();
    var q = new ArrayDeque<BlockPos>();
    q.add(start);
    while (!q.isEmpty()) {
      var p = q.removeFirst();
      if (seen.contains(p) || !bounds.contains(p) || !standing(level, p)) continue;
      if (seen.size() >= limit)
        throw new IllegalArgumentException("Chamber exceeds bounded scan; seal it from the cabin");
      seen.add(p.immutable());
      for (var d : Direction.Plane.HORIZONTAL) q.add(p.relative(d));
      // Standing routes can step one block without assuming a room is flat.
      for (var d : Direction.Plane.HORIZONTAL)
        for (int dy : new int[] {-1, 1}) q.add(p.relative(d).above(dy));
    }
    return Set.copyOf(seen);
  }

  public static boolean standing(ServerLevel level, BlockPos p) {
    return level.hasChunkAt(p)
        && level.getBlockState(p).isAir()
        && level.getBlockState(p.above()).isAir()
        && level.getBlockState(p.below()).isCollisionShapeFullBlock(level, p.below());
  }

  public static List<Node> nodes(ServerLevel level, CompoundTag station) {
    var out = new ArrayList<Node>();
    var base = ShipStore.pos(station, "base");
    var bounds = ShipStore.bounds(station);
    var identities = station.getCompoundOrEmpty("doorNodes");
    if (!station.contains("doorNodes")) station.put("doorNodes", identities);
    for (var p : com.nullandkale.nullspace.systems.ShipSystems.connected(level, station)) {
      var state = level.getBlockState(p);
      boolean ordinary =
          state.getBlock() instanceof DoorBlock
              && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
      if (ordinary
          && (level.getBlockState(p.below()).is(ShipBlocks.DOCKING_BASE)
              || level.getBlockState(p.above(2)).is(ShipBlocks.DOOR_CONNECTOR)
              || level.getBlockState(p.above(2)).is(ShipBlocks.DOCKING_PORT))) continue;
      boolean dock = state.is(ShipBlocks.DOCKING_PORT) || state.is(ShipBlocks.DOCKING_BASE);
      boolean compact = state.is(ShipBlocks.DOCKING_BASE);
      if (!dock && !state.is(ShipBlocks.DOOR_CONNECTOR) && !ordinary) continue;
      var at = p.immutable();
      var door = ordinary ? at : compact ? at.above() : at.below(2);
      var ds = level.getBlockState(door);
      if (!(ds.getBlock() instanceof DoorBlock)
          || ds.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
          || !ShipDoors.framed(level, door)) continue;
      var direction =
          ordinary
              ? ds.getValue(DoorBlock.FACING)
              : state.getValue(HorizontalDirectionalBlock.FACING);
      var key = Long.toString(at.subtract(base).asLong());
      String id = identities.getStringOr(key, "");
      if (id.isEmpty()) {
        id = UUID.randomUUID().toString();
        identities.putString(key, id);
      }
      out.add(
          new Node(
              id,
              at,
              door,
              direction,
              dock,
              compartment(level, bounds, door.relative(direction.getOpposite()), 16384),
              dock ? Set.of() : compartment(level, bounds, door.relative(direction), 16384)));
    }
    return List.copyOf(out);
  }

  public static Graph inspect(ServerLevel level, CompoundTag station) {
    var nodes = nodes(level, station);
    var edges = new LinkedHashMap<String, Set<String>>();
    for (var n : nodes) edges.put(n.id(), new LinkedHashSet<>());
    for (var a : nodes)
      for (var b : nodes)
        if (a != b) {
          boolean same =
              intersects(a.back(), b.back())
                  || intersects(a.back(), b.front())
                  || intersects(a.front(), b.back())
                  || intersects(a.front(), b.front());
          if (same) edges.get(a.id()).add(b.id());
        }
    return new Graph(nodes, Collections.unmodifiableMap(edges));
  }

  private static boolean intersects(Set<BlockPos> a, Set<BlockPos> b) {
    return a.stream().anyMatch(b::contains);
  }

  private StationGraph() {}
}
