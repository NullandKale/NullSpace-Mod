package com.nullandkale.nullspace.systems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SpatialAirTest {
  static boolean[] solid() {
    var a = new boolean[Atmosphere.CELLS];
    Arrays.fill(a, true);
    return a;
  }

  static int[] empty() {
    var a = new int[Atmosphere.CELLS];
    Arrays.fill(a, -1);
    return a;
  }

  static long total(int[] a) {
    return Arrays.stream(a).filter(n -> n >= 0).asLongStream().sum();
  }

  @Test
  void gasMovesLocallyRatherThanInstantlyFillingTheRoom() {
    var s = solid();
    var a = empty();
    for (int x = 3; x < 14; x++) s[Atmosphere.index(x, 5, 5)] = false;
    var t = Atmosphere.rebuild(s, a, false);
    int source = Atmosphere.index(3, 5, 5);
    a[source] = 1000;
    var flow = SpatialAir.step(a, t, s, Map.of(), Map.of(), 1, 0);
    assertEquals(1000, total(a));
    assertTrue(a[source + 1] > 0);
    assertEquals(0, a[source + 4]);
    assertFalse(flow.flows().isEmpty());
  }

  @Test
  void randomClosedGridConservesGasWithoutNegativeCells() {
    var s = solid();
    var a = empty();
    var random = new Random(42);
    for (int y = 2; y < 12; y++)
      for (int z = 2; z < 12; z++)
        for (int x = 2; x < 12; x++) s[Atmosphere.index(x, y, z)] = false;
    var t = Atmosphere.rebuild(s, a, false);
    for (var r : t.rooms()) for (int i : r.cells()) a[i] = random.nextInt(5000);
    long expected = total(a);
    for (int n = 0; n < 100; n++) {
      SpatialAir.step(a, t, s, Map.of(), Map.of(), 5, 0);
      assertEquals(expected, total(a));
      for (var r : t.rooms()) for (int i : r.cells()) assertTrue(a[i] >= 0);
    }
  }

  @Test
  void refillMassAndCrewConsumptionAreAccounted() {
    var s = solid();
    var a = empty();
    int one = Atmosphere.index(5, 5, 5);
    s[one] = s[one + 1] = false;
    var t = Atmosphere.rebuild(s, a, false);
    var r = SpatialAir.step(a, t, s, Map.of(one, 100), Map.of(one + 1, 5), 5, 0);
    assertEquals(r.supplied() - r.consumed(), total(a));
    assertTrue(a[one] > a[one + 1]);
  }

  @Test
  void breachLossAndAmbientRefillHaveFiniteRates() {
    var s = solid();
    var a = empty();
    for (int x = 1; x < 10; x++) s[Atmosphere.index(x, 5, 5)] = false;
    var t = Atmosphere.rebuild(s, a, true);
    s[Atmosphere.index(0, 5, 5)] = false;
    t = Atmosphere.rebuild(s, a, false);
    long before = total(a);
    var r = SpatialAir.step(a, t, s, Map.of(), Map.of(), 1, 0);
    assertTrue(r.escaped() > 0);
    assertEquals(before - r.escaped(), total(a));
    assertTrue(total(a) > 0);
    for (var room : t.rooms()) for (int cell : room.cells()) a[cell] = 0;
    var fill = SpatialAir.step(a, t, s, Map.of(), Map.of(), 1, 1000);
    assertTrue(fill.entered() > 0);
    assertEquals(fill.entered(), total(a));
  }

  @Test
  void equilibriumDoesNotEmitFlowAndSolidWallsBlockIt() {
    var s = solid();
    var a = empty();
    int one = Atmosphere.index(4, 4, 4), two = one + 2;
    s[one] = s[two] = false;
    var t = Atmosphere.rebuild(s, a, true);
    a[two] = 0;
    var r = SpatialAir.step(a, t, s, Map.of(), Map.of(), 5, 0);
    assertEquals(1000, a[one]);
    assertEquals(0, a[two]);
    assertTrue(r.flows().isEmpty());
  }

  @Test
  void fullOccupiedRoomSuppressesMaintenanceHazeWithoutStoppingGas() {
    var s = solid();
    var a = empty();
    int one = Atmosphere.index(4, 4, 4);
    s[one] = s[one + 1] = false;
    var t = Atmosphere.rebuild(s, a, true);
    for (int i = 0; i < 30; i++) {
      var r = SpatialAir.step(a, t, s, Map.of(one, 100), Map.of(one + 1, 20), 5, 0);
      assertTrue(r.supplied() > 0);
      assertEquals(100, r.consumed());
      assertEquals(1980, total(a));
      assertTrue(r.flows().isEmpty(), "Routine crew top-ups in a full room should be invisible");
    }
  }

  @Test
  void breathingInAnUnderfilledRoomDoesNotProduceFollowingParticles() {
    var s = solid();
    var a = empty();
    for (int x = 4; x < 12; x++) s[Atmosphere.index(x, 4, 4)] = false;
    var t = Atmosphere.rebuild(s, a, false);
    for (var room : t.rooms()) for (int cell : room.cells()) a[cell] = 800;
    long expected = total(a);
    for (int i = 0; i < 30; i++) {
      int occupant = Atmosphere.index(4 + i % 8, 4, 4);
      var r = SpatialAir.step(a, t, s, Map.of(), Map.of(occupant, 20), 5, 0);
      expected -= r.consumed();
      assertEquals(100, r.consumed());
      assertEquals(expected, total(a));
      assertTrue(r.flows().isEmpty(), "Breathing should not draw clouds along the crew's path");
    }
  }

  @Test
  void significantInternalTransferStillProducesParticles() {
    var s = solid();
    var a = empty();
    int one = Atmosphere.index(4, 4, 4);
    s[one] = s[one + 1] = false;
    var t = Atmosphere.rebuild(s, a, false);
    a[one] = 900;
    a[one + 1] = 300;
    var r = SpatialAir.step(a, t, s, Map.of(), Map.of(), 1, 0);
    assertEquals(1200, total(a));
    assertTrue(r.flows().stream().anyMatch(f -> !f.exterior() && f.amount() == 50));
  }

  @Test
  void faintExteriorLeakStillProducesParticles() {
    var s = solid();
    var a = empty();
    int cell = Atmosphere.index(0, 4, 4);
    s[cell] = false;
    a[cell] = 120;
    var t = Atmosphere.rebuild(s, a, false);
    var r = SpatialAir.step(a, t, s, Map.of(), Map.of(), 1, 0);
    assertEquals(10, r.escaped());
    assertEquals(110, total(a));
    assertTrue(r.flows().stream().anyMatch(f -> f.exterior() && f.amount() == 10));
  }

  @Test
  void lastFillUpdateHasNoResidualPumpParticles() {
    var s = solid();
    var a = empty();
    int one = Atmosphere.index(4, 4, 4);
    s[one] = false;
    var t = Atmosphere.rebuild(s, a, false);
    var r = SpatialAir.step(a, t, s, Map.of(one, 1000), Map.of(), 1, 0);
    assertEquals(1000, r.supplied());
    assertEquals(1000, a[one]);
    assertTrue(r.flows().isEmpty());
  }

  @Test
  void fullRoomStillShowsRealVacuumEscape() {
    var s = solid();
    var a = empty();
    for (int x = 1; x < 10; x++) s[Atmosphere.index(x, 5, 5)] = false;
    var t = Atmosphere.rebuild(s, a, true);
    s[Atmosphere.index(0, 5, 5)] = false;
    t = Atmosphere.rebuild(s, a, false);
    var r = SpatialAir.step(a, t, s, Map.of(), Map.of(), 1, 0);
    assertTrue(r.escaped() > 0);
    assertTrue(r.flows().stream().anyMatch(SpatialAir.Flow::exterior));
  }
}
