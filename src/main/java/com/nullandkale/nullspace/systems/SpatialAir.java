package com.nullandkale.nullspace.systems;

import java.util.*;

/**
 * Room air on the ship's 32-cube lattice, tuned for play rather than fluid accuracy.
 *
 * <p>Air is 0-1000 per block ({@link Atmosphere#FULL}; shown to players as 0-100) and is shared
 * evenly within a sealed room. Rates are relative to the room, so a cabin and a station behave the
 * same (player's balance spec, playtest 2):
 *
 * <ul>
 *   <li>Each open face of a room (its leak area) moves {@code cells * FULL / 600} per tick toward
 *       the outside pressure: one hole empties a full room in 30 s, a 5x5 breach in about 1.2 s.
 *   <li>An Air Supply at normal power (4 units) adds {@code cells * FULL / 1200} per tick, so two of
 *       them exactly hold one hole and a large breach stays empty until it is patched.
 *   <li>Overdriven to 8 units, one Air Supply gives 150% of normal output: three quarters of a
 *       hole. Power above 4 buys half as much air per unit (player's balance spec, November 7).
 * </ul>
 */
public final class SpatialAir {
  public record Flow(int cell, int dx, int dy, int dz, int amount, boolean exterior) {}

  public record Result(
      long supplied, long consumed, long escaped, long entered, List<Flow> flows) {}

  /** Ticks for one open face to empty a full room. */
  public static final int HOLE_TICKS = 30 * 20;

  /** Ticks for one full-power Air Supply to fill an empty sealed room. */
  public static final int SUPPLY_TICKS = 60 * 20;

  /** Power units an Air Supply draws at normal (100%) output. */
  public static final int SUPPLY_POWER = 4;

  /** Most power one Air Supply can use, overdriven to 150% output. */
  public static final int MAX_SUPPLY_POWER = 8;

  /** Output of one Air Supply at normal power, in the units {@link #step} takes. */
  public static final int FULL_OUTPUT = 1000;

  /** One Air Supply's output for the power it receives: linear to 4, then half as steep to 8. */
  public static int output(int power) {
    int p = Math.clamp(power, 0, MAX_SUPPLY_POWER);
    return p <= SUPPLY_POWER
        ? p * FULL_OUTPUT / SUPPLY_POWER
        : FULL_OUTPUT + (p - SUPPLY_POWER) * FULL_OUTPUT / (2 * SUPPLY_POWER);
  }

  private static final int[] DX = {-1, 1, 0, 0, 0, 0},
      DY = {0, 0, -1, 1, 0, 0},
      DZ = {0, 0, 0, 0, -1, 1};

  /**
   * @param pumps outlet cell to the Air Supply output feeding it ({@link #output}; one supply at
   *     normal power is {@link #FULL_OUTPUT})
   * @param consumers cell to air used per tick (crew breathing)
   */
  public static Result step(
      int[] air,
      Atmosphere.Topology topology,
      boolean[] seals,
      Map<Integer, Integer> pumps,
      Map<Integer, Integer> consumers,
      int ticks,
      int exteriorPressure) {
    var grid = topology.grid();
    if (air.length != grid.cells()
        || seals.length != grid.cells()
        || topology.roomAt().length != grid.cells()
        || ticks < 0
        || ticks > 20
        || exteriorPressure < 0
        || exteriorPressure > Atmosphere.FULL)
      throw new IllegalArgumentException("Invalid spatial air step");
    int rooms = topology.rooms().size();
    long[] power = new long[rooms], breathing = new long[rooms];
    for (var entry : pumps.entrySet()) {
      check(entry.getKey(), entry.getValue(), grid.cells());
      int room = topology.roomAt()[entry.getKey()];
      if (room >= 0) power[room] += entry.getValue();
    }
    for (var entry : consumers.entrySet()) {
      check(entry.getKey(), entry.getValue(), grid.cells());
      int room = topology.roomAt()[entry.getKey()];
      if (room >= 0) breathing[room] += entry.getValue();
    }
    long supplied = 0, consumed = 0, escaped = 0, entered = 0;
    long[] pumped = new long[rooms], leaked = new long[rooms];
    for (int r = 0; r < rooms; r++) {
      var room = topology.rooms().get(r);
      long cells = room.cells().length, capacity = cells * Atmosphere.FULL;
      long outside = cells * exteriorPressure;
      long quantity = 0;
      for (int cell : room.cells()) quantity += Math.max(0, air[cell]);
      for (int tick = 0; tick < ticks; tick++) {
        long leak = capacity * room.leaks() / HOLE_TICKS;
        if (quantity > outside) {
          long out = Math.min(leak, quantity - outside);
          quantity -= out;
          escaped += out;
          leaked[r] += out;
        } else if (quantity < outside) {
          long in = Math.min(leak, outside - quantity);
          quantity += in;
          entered += in;
          leaked[r] -= in;
        }
        long add =
            Math.min(
                capacity * power[r] / (FULL_OUTPUT * (long) SUPPLY_TICKS),
                Math.max(0, capacity - quantity));
        quantity += add;
        supplied += add;
        pumped[r] += add;
        long used = Math.min(breathing[r], quantity);
        quantity -= used;
        consumed += used;
      }
      int share = Math.toIntExact(quantity / cells), extra = (int) (quantity % cells);
      for (int n = 0; n < cells; n++) air[room.cells()[n]] = share + (n < extra ? 1 : 0);
    }
    return new Result(supplied, consumed, escaped, entered, flows(topology, seals, pumps, pumped, leaked, ticks));
  }

  /** Particles for the visualization: outlets that are filling and the faces air escapes through. */
  private static List<Flow> flows(
      Atmosphere.Topology topology,
      boolean[] seals,
      Map<Integer, Integer> pumps,
      long[] pumped,
      long[] leaked,
      int ticks) {
    var grid = topology.grid();
    var flows = new ArrayList<Flow>();
    for (var entry : pumps.entrySet()) {
      int room = topology.roomAt()[entry.getKey()];
      if (room >= 0 && pumped[room] > 0 && flows.size() < 128)
        flows.add(new Flow(entry.getKey(), 0, 0, 0, (int) Math.min(Integer.MAX_VALUE, pumped[room] / Math.max(1, ticks)), false));
    }
    for (int r = 0; r < topology.rooms().size(); r++) {
      var room = topology.rooms().get(r);
      if (leaked[r] == 0 || room.leaks() == 0) continue;
      int perFace = (int) Math.min(Integer.MAX_VALUE, Math.abs(leaked[r]) / Math.max(1, ticks) / room.leaks());
      for (int cell : room.cells()) {
        int x = grid.x(cell), y = grid.y(cell), z = grid.z(cell);
        for (int d = 0; d < 6 && flows.size() < 128; d++) {
          boolean inside = grid.contains(x + DX[d], y + DY[d], z + DZ[d]);
          int other = inside ? cell + grid.offsets()[d] : -1;
          if (inside && (seals[other] || topology.roomAt()[other] >= 0)) continue;
          flows.add(
              leaked[r] > 0
                  ? new Flow(cell, DX[d], DY[d], DZ[d], perFace, true)
                  : new Flow(cell, -DX[d], -DY[d], -DZ[d], perFace, true));
        }
      }
    }
    return List.copyOf(flows);
  }

  /** Air as players see it: 0-100. */
  public static int percent(int cellAir) {
    return Math.clamp(Math.max(0, cellAir) / 10, 0, 100);
  }

  private static void check(int cell, int rate, int cells) {
    if (cell < 0 || cell >= cells || rate < 0 || rate > 100000)
      throw new IllegalArgumentException("Invalid air source");
  }

  private SpatialAir() {}
}
