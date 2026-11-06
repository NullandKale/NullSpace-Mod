package com.nullandkale.nullspace.station;

import java.util.*;
import net.minecraft.core.*;

/** Bounded room grammar. A room piece is smaller than the station's simulation section. */
public final class StationLayout {
  public static final int TILE = 16, SIZE = 32;

  public enum Role {
    ARRIVAL,
    WORKSHOP,
    HABITATION,
    ENGINEERING
  }

  public record Room(String id, Role role, int x, int z) {
    public BlockPos origin() {
      return new BlockPos(x * TILE, 0, z * TILE);
    }
  }

  public record Joint(String a, String b) {}

  public record Layout(List<Room> rooms, List<Joint> joints) {
    public Layout {
      rooms = List.copyOf(rooms);
      joints = List.copyOf(joints);
      var ids = new HashMap<String, Room>();
      var cells = new HashSet<Integer>();
      for (var room : rooms) {
        if (room.id().isBlank()
            || ids.put(room.id(), room) != null
            || room.x() < 0
            || room.x() >= StationSpace.TILES
            || room.z() < 0
            || room.z() >= StationSpace.TILES
            || !cells.add(room.x() + room.z() * StationSpace.TILES))
          throw new IllegalArgumentException("Invalid or overlapping station room");
      }
      if (rooms.isEmpty() || rooms.size() > StationSpace.TILES * StationSpace.TILES)
        throw new IllegalArgumentException("Station needs 1..64 room pieces");
      var edges = new HashSet<String>();
      for (var edge : joints) {
        var a = ids.get(edge.a());
        var b = ids.get(edge.b());
        if (a == null
            || b == null
            || Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) != 1
            || !edges.add(
                a.id().compareTo(b.id()) < 0 ? a.id() + ":" + b.id() : b.id() + ":" + a.id()))
          throw new IllegalArgumentException("Invalid station joint");
      }
      var reached = new HashSet<String>();
      reached.add(rooms.getFirst().id());
      for (int n = 0; n < rooms.size(); n++)
        for (var e : joints) {
          if (reached.contains(e.a())) reached.add(e.b());
          if (reached.contains(e.b())) reached.add(e.a());
        }
      if (reached.size() != rooms.size())
        throw new IllegalArgumentException("Station graph is disconnected");
    }

    public Room room(String id) {
      return rooms.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }
  }

  public static Layout waypoint() {
    return new Layout(
        List.of(
            new Room("arrival", Role.ARRIVAL, 0, 0),
            new Room("workshop", Role.WORKSHOP, 1, 0),
            new Room("habitation", Role.HABITATION, 0, 1),
            new Room("engineering", Role.ENGINEERING, 1, 1)),
        List.of(
            new Joint("arrival", "workshop"),
            new Joint("arrival", "habitation"),
            new Joint("workshop", "engineering"),
            new Joint("habitation", "engineering")));
  }

  /**
   * Bounded generation varies room roles and a redundant connection, never retries indefinitely.
   */
  public static Layout generate(long seed) {
    var random = new Random(seed);
    var roles = new ArrayList<>(List.of(Role.WORKSHOP, Role.HABITATION, Role.ENGINEERING));
    Collections.shuffle(roles, random);
    var rooms = new ArrayList<Room>();
    rooms.add(new Room("room0", Role.ARRIVAL, 0, 0));
    rooms.add(new Room("room1", roles.get(0), 1, 0));
    rooms.add(new Room("room2", roles.get(1), 0, 1));
    rooms.add(new Room("room3", roles.get(2), 1, 1));
    var edges =
        new ArrayList<>(
            List.of(
                new Joint("room0", "room1"),
                new Joint("room0", "room2"),
                new Joint("room1", "room3"),
                new Joint("room2", "room3")));
    if (random.nextBoolean()) edges.remove(random.nextInt(edges.size()));
    return new Layout(rooms, edges);
  }

  /** Eight widely separated berths on a walkable 128-block perimeter. */
  public static Layout harbor() {
    var rooms = new ArrayList<Room>();
    for (int z = 0; z < 8; z++)
      for (int x = 0; x < 8; x++) {
        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
        var role =
            x == 0 && z == 0
                ? Role.ARRIVAL
                : (x + z) % 4 == 0
                    ? Role.ENGINEERING
                    : (x + z) % 2 == 0 ? Role.WORKSHOP : Role.HABITATION;
        rooms.add(new Room(x + "_" + z, role, x, z));
      }
    var edges = new ArrayList<Joint>();
    for (var a : rooms)
      for (var b : rooms)
        if (a.id().compareTo(b.id()) < 0 && Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) == 1)
          edges.add(new Joint(a.id(), b.id()));
    return new Layout(rooms, edges);
  }

  public static Direction direction(Room a, Room b) {
    if (a.x() < b.x()) return Direction.EAST;
    if (a.x() > b.x()) return Direction.WEST;
    return a.z() < b.z() ? Direction.SOUTH : Direction.NORTH;
  }

  public static BlockPos doorway(Room room, Direction face) {
    return room.origin()
        .offset(
            face == Direction.WEST ? 0 : face == Direction.EAST ? 15 : 8,
            3,
            face == Direction.NORTH ? 0 : face == Direction.SOUTH ? 15 : 8);
  }

  private StationLayout() {}
}
