package com.nullandkale.nullspace.systems;

import java.util.*;

/**
 * Bounded cell quantities. Interior history keeps a breached room finite instead of mixing with
 * infinite vacuum.
 */
public final class Atmosphere {
  public static final int SIZE = 32, CELLS = 32768, FULL = 1000, BREATHABLE = 250;
  private static final int[] OFFSETS = {-1, 1, -1024, 1024, -32, 32};
  private static final byte[] NEIGHBORS = neighborMasks();

  public record Room(int[] cells, int leaks) {}

  /** roomAt uses -1 for exterior/solid cells. Arrays belong to this bounded server topology. */
  public record Topology(List<Room> rooms, int[] roomAt, long displaced, long vented) {}

  public static int index(int x, int y, int z) {
    return x + z * 32 + y * 1024;
  }

  public static List<Room> rooms(boolean[] seals, int[] previous, boolean initialize) {
    return rebuild(seals, previous, initialize).rooms();
  }

  public static Topology rebuild(boolean[] seals, int[] previous, boolean initialize) {
    if (seals.length != CELLS || previous.length != CELLS)
      throw new IllegalArgumentException("Invalid atmosphere grid");
    var outside = new BitSet(CELLS);
    int[] queue = new int[CELLS];
    int head = 0, tail = 0;
    for (int i = 0; i < CELLS; i++)
      if (!seals[i] && NEIGHBORS[i] != 63) {
        outside.set(i);
        queue[tail++] = i;
      }
    while (head < tail) {
      int i = queue[head++], mask = NEIGHBORS[i];
      for (int d = 0; d < 6; d++)
        if ((mask & (1 << d)) != 0) {
          int n = i + OFFSETS[d];
          if (!seals[n] && !outside.get(n)) {
            outside.set(n);
            queue[tail++] = n;
          }
        }
    }
    var interior = new BitSet(CELLS);
    for (int i = 0; i < CELLS; i++)
      if (!seals[i] && (!outside.get(i) || previous[i] >= 0)) {
        interior.set(i);
        if (previous[i] < 0) previous[i] = initialize ? FULL : 0;
      }
    // Follow whole clusters of newly filled old air cells. Thick walls must not
    // silently delete the gas in their middle. Old solid cells remain barriers.
    long displaced = 0, vented = 0;
    var filled = new BitSet(CELLS);
    int[] destinations = null, marked = null;
    int stamp = 0;
    for (int start = 0; start < CELLS; start++)
      if (seals[start] && previous[start] >= 0 && !filled.get(start)) {
        if (destinations == null) {
          destinations = new int[CELLS];
          marked = new int[CELLS];
        }
        int count = 0;
        long quantity = 0;
        head = 0;
        tail = 0;
        stamp++;
        filled.set(start);
        queue[tail++] = start;
        while (head < tail) {
          int i = queue[head++], mask = NEIGHBORS[i];
          quantity += previous[i];
          for (int d = 0; d < 6; d++)
            if ((mask & (1 << d)) != 0) {
              int n = i + OFFSETS[d];
              if (seals[n] && previous[n] >= 0 && !filled.get(n)) {
                filled.set(n);
                queue[tail++] = n;
              } else if (interior.get(n) && marked[n] != stamp) {
                marked[n] = stamp;
                destinations[count++] = n;
              }
            }
        }
        if (count == 0) vented += quantity;
        else {
          displaced += quantity;
          long share = quantity / count, extra = quantity % count;
          for (int n = 0; n < count; n++)
            previous[destinations[n]] =
                Math.toIntExact(previous[destinations[n]] + share + (n < extra ? 1 : 0));
        }
      }
    for (int i = 0; i < CELLS; i++) if (!interior.get(i)) previous[i] = -1;
    var result = new ArrayList<Room>();
    int[] roomAt = new int[CELLS];
    Arrays.fill(roomAt, -1);
    for (int start = interior.nextSetBit(0); start >= 0; start = interior.nextSetBit(start + 1)) {
      if (roomAt[start] >= 0) continue;
      head = 0;
      tail = 0;
      int room = result.size(), leaks = 0;
      roomAt[start] = room;
      queue[tail++] = start;
      while (head < tail) {
        int i = queue[head++], mask = NEIGHBORS[i];
        leaks += 6 - Integer.bitCount(mask);
        for (int d = 0; d < 6; d++)
          if ((mask & (1 << d)) != 0) {
            int n = i + OFFSETS[d];
            if (interior.get(n)) {
              if (roomAt[n] < 0) {
                roomAt[n] = room;
                queue[tail++] = n;
              }
            } else if (!seals[n]) leaks++;
          }
      }
      result.add(new Room(Arrays.copyOf(queue, tail), leaks));
    }
    return new Topology(List.copyOf(result), roomAt, displaced, vented);
  }

  public static void tick(
      int[] air,
      List<Room> rooms,
      Map<Integer, Integer> supply,
      Map<Integer, Integer> occupants,
      int ticks) {
    int[] roomAt = new int[CELLS];
    Arrays.fill(roomAt, -1);
    for (int r = 0; r < rooms.size(); r++) for (int cell : rooms.get(r).cells()) roomAt[cell] = r;
    tick(air, new Topology(rooms, roomAt, 0, 0), supply, occupants, ticks, true);
  }

  public static void tick(
      int[] air,
      Topology topology,
      Map<Integer, Integer> supply,
      Map<Integer, Integer> occupants,
      int ticks,
      boolean leaks) {
    if (air.length != CELLS || topology.roomAt.length != CELLS || ticks < 0)
      throw new IllegalArgumentException("Invalid atmosphere tick");
    if (ticks == 0) return;
    long[] added = amounts(topology, supply, ticks), used = amounts(topology, occupants, ticks);
    for (int r = 0; r < topology.rooms.size(); r++) {
      var room = topology.rooms.get(r);
      long quantity = 0;
      for (int i : room.cells) quantity += Math.max(0, air[i]);
      // Closing a bulkhead can compress existing gas above nominal pressure. Pumps never
      // add beyond nominal capacity, but must not delete the gas displaced by that door.
      long capacity = room.cells.length * (long) FULL;
      quantity =
          Math.max(
              0,
              Math.max(quantity, Math.min(capacity, quantity + added[r]))
                  - used[r]
                  - (leaks ? room.leaks * 3000L * ticks : 0));
      int share = Math.toIntExact(quantity / room.cells.length),
          extra = (int) (quantity % room.cells.length);
      for (int n = 0; n < room.cells.length; n++) air[room.cells[n]] = share + (n < extra ? 1 : 0);
    }
  }

  private static long[] amounts(Topology topology, Map<Integer, Integer> rates, int ticks) {
    long[] result = new long[topology.rooms.size()];
    for (var entry : rates.entrySet()) {
      int cell = entry.getKey(), rate = entry.getValue();
      if (cell < 0 || cell >= CELLS || rate < 0)
        throw new IllegalArgumentException("Invalid atmosphere source");
      int room = topology.roomAt[cell];
      if (room >= 0) result[room] += rate * (long) ticks;
    }
    return result;
  }

  private static byte[] neighborMasks() {
    byte[] result = new byte[CELLS];
    for (int i = 0; i < CELLS; i++) {
      int x = i % 32, y = i / 1024, z = i / 32 % 32;
      result[i] =
          (byte)
              ((x > 0 ? 1 : 0)
                  | (x < 31 ? 2 : 0)
                  | (y > 0 ? 4 : 0)
                  | (y < 31 ? 8 : 0)
                  | (z > 0 ? 16 : 0)
                  | (z < 31 ? 32 : 0));
    }
    return result;
  }

  private Atmosphere() {}
}
