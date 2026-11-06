package com.nullandkale.nullspace.systems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class StationAtmosphereTest {
  @Test
  void airCrossesRenderBoundariesWithoutLossOrCoordinateAliasing() {
    var grid = new Atmosphere.Grid(128, 32, 128);
    boolean[] seals = new boolean[grid.cells()];
    Arrays.fill(seals, true);
    int[] air = new int[grid.cells()];
    Arrays.fill(air, -1);
    for (int x = 29; x <= 35; x++)
      for (int z = 29; z <= 35; z++) seals[grid.index(x, 3, z)] = false;
    var topology = Atmosphere.rebuild(seals, air, false, grid);
    assertEquals(1, topology.rooms().size());
    int source = grid.index(31, 3, 31), destination = grid.index(32, 3, 32);
    air[source] = 1000;
    var result = SpatialAir.step(air, topology, seals, Map.of(), Map.of(), 20, 0);
    assertEquals(0, result.escaped());
    assertEquals(
        1000,
        topology.rooms().stream()
            .flatMapToInt(r -> Arrays.stream(r.cells()))
            .mapToLong(i -> air[i])
            .sum());
    assertTrue(air[destination] > 0);
    assertNotEquals(grid.index(96, 3, 96), grid.index(0, 3, 0));
  }

  @Test
  void outerStationBoundaryLeaksButSectionEdgesDoNot() {
    var grid = new Atmosphere.Grid(64, 32, 64);
    boolean[] seals = new boolean[grid.cells()];
    Arrays.fill(seals, true);
    int[] air = new int[grid.cells()];
    Arrays.fill(air, -1);
    for (int x = 30; x < 64; x++) {
      int i = grid.index(x, 3, 40);
      seals[i] = false;
      air[i] = 1000;
    }
    var topology = Atmosphere.rebuild(seals, air, false, grid);
    assertEquals(1, topology.rooms().size());
    assertEquals(1, topology.rooms().getFirst().leaks());
    var result = SpatialAir.step(air, topology, seals, Map.of(), Map.of(), 1, 0);
    assertTrue(result.escaped() > 0);
    assertEquals(1000, air[grid.index(31, 3, 40)]);
  }

  @Test
  void shipCoordinatesAndEnvelopeRemainCompatible() {
    assertEquals(Atmosphere.index(31, 31, 31), Atmosphere.SHIP.index(31, 31, 31));
    assertThrows(IllegalArgumentException.class, () -> new Atmosphere.Grid(129, 32, 128));
    assertThrows(IllegalArgumentException.class, () -> new Atmosphere.Grid(128, 33, 128));
  }
}
