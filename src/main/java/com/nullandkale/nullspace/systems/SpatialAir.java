package com.nullandkale.nullspace.systems;

import java.util.*;

/**
 * Conservative finite-volume diffusion on the ship's 32-cube lattice. One update exchanges face
 * fluxes.
 */
public final class SpatialAir {
  public record Flow(int cell, int dx, int dy, int dz, int amount, boolean exterior) {}

  public record Result(
      long supplied, long consumed, long escaped, long entered, List<Flow> flows) {}

  private static final int[] DX = {-1, 1, 0, 0, 0, 0},
      DY = {0, 0, -1, 1, 0, 0},
      DZ = {0, 0, 0, 0, -1, 1};

  public static Result step(
      int[] air,
      Atmosphere.Topology topology,
      boolean[] seals,
      Map<Integer, Integer> pumps,
      Map<Integer, Integer> consumers,
      int ticks,
      int exteriorPressure) {
    var grid = topology.grid();
    var offsets = grid.offsets();
    if (air.length != grid.cells()
        || seals.length != grid.cells()
        || topology.roomAt().length != grid.cells()
        || ticks < 0
        || ticks > 20
        || exteriorPressure < 0
        || exteriorPressure > Atmosphere.FULL)
      throw new IllegalArgumentException("Invalid spatial air step");
    long supplied = 0, consumed = 0, escaped = 0, entered = 0;
    var flows = new ArrayList<Flow>();
    int[] delta = new int[grid.cells()];
    boolean[] visible = new boolean[topology.rooms().size()];
    for (int tick = 0; tick < ticks; tick++) {
      long[] capacity = new long[topology.rooms().size()];
      for (int r = 0; r < capacity.length; r++) {
        var room = topology.rooms().get(r);
        long total = quantity(air, room);
        capacity[r] = Math.max(0, room.cells().length * (long) Atmosphere.FULL - total);
        visible[r] =
            room.leaks() > 0 && exteriorPressure < Atmosphere.FULL
                || total < room.cells().length * (long) Atmosphere.FULL * 99 / 100;
      }
      for (var entry : pumps.entrySet()) {
        int cell = entry.getKey(), rate = entry.getValue();
        check(cell, rate, grid.cells());
        int room = topology.roomAt()[cell];
        if (room < 0) continue;
        int add =
            (int)
                Math.min(
                    Math.min(rate, capacity[room]), Math.max(0, Atmosphere.FULL * 4 - air[cell]));
        air[cell] += add;
        capacity[room] -= add;
        supplied += add;
        if (tick == ticks - 1 && add > 0 && visible[room] && flows.size() < 128)
          flows.add(new Flow(cell, 0, 0, 0, add, false));
      }
      for (var entry : consumers.entrySet()) {
        int cell = entry.getKey(), rate = entry.getValue();
        check(cell, rate, grid.cells());
        if (air[cell] >= 0) {
          int used = Math.min(rate, air[cell]);
          air[cell] -= used;
          consumed += used;
        }
      }
      Arrays.fill(delta, 0);
      for (var room : topology.rooms())
        for (int cell : room.cells()) {
          int x = grid.x(cell), y = grid.y(cell), z = grid.z(cell);
          for (int d = 0; d < 6; d++) {
            boolean inside = grid.contains(x + DX[d], y + DY[d], z + DZ[d]);
            int other = inside ? cell + offsets[d] : -1;
            if (inside && seals[other]) continue;
            boolean exterior = !inside || topology.roomAt()[other] < 0;
            if (!exterior && cell > other) continue;
            int wanted =
                (int)
                    (((long) Math.max(0, air[cell])
                            - (exterior ? exteriorPressure : Math.max(0, air[other])))
                        / 12);
            if (wanted == 0) continue;
            delta[cell] -= wanted;
            if (exterior) {
              if (wanted > 0) escaped += wanted;
              else entered -= wanted;
            } else delta[other] += wanted;
            // Crew consumption creates small local gradients even in underfilled rooms.
            // Keep those invisible, while retaining faint exterior leak effects.
            if (tick == ticks - 1
                && Math.abs(wanted) >= (exterior ? 4 : 25)
                && (exterior || visible[topology.roomAt()[cell]])
                && flows.size() < 128)
              flows.add(
                  wanted > 0
                      ? new Flow(cell, DX[d], DY[d], DZ[d], wanted, exterior)
                      : new Flow(
                          exterior ? cell : other, -DX[d], -DY[d], -DZ[d], -wanted, exterior));
          }
        }
      for (var room : topology.rooms())
        for (int cell : room.cells()) {
          long next = (long) Math.max(0, air[cell]) + delta[cell];
          if (next < 0 || next > Integer.MAX_VALUE)
            throw new IllegalStateException("Air solver conservation invariant failed");
          air[cell] = (int) next;
        }
    }
    for (int r = 0; r < visible.length; r++) {
      var room = topology.rooms().get(r);
      visible[r] =
          room.leaks() > 0 && exteriorPressure < Atmosphere.FULL
              || quantity(air, room) < room.cells().length * (long) Atmosphere.FULL * 99 / 100;
    }
    flows.removeIf(f -> !f.exterior() && !visible[topology.roomAt()[f.cell()]]);
    return new Result(supplied, consumed, escaped, entered, List.copyOf(flows));
  }

  private static long quantity(int[] air, Atmosphere.Room room) {
    long total = 0;
    for (int cell : room.cells()) total += Math.max(0, air[cell]);
    return total;
  }

  private static void check(int cell, int rate, int cells) {
    if (cell < 0 || cell >= cells || rate < 0 || rate > 100000)
      throw new IllegalArgumentException("Invalid air source");
  }

  private SpatialAir() {}
}
