package com.nullandkale.nullspace.model.air;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AtmosphereTest {
  @Test
  void fillingAThickWallMovesGasFromItsMiddleIntoSurvivingRooms() {
    boolean[] seals = solid();
    int[] air = empty();
    for (int x = 3; x <= 15; x++) seals[Atmosphere.index(x, 5, 5)] = false;
    Atmosphere.rooms(seals, air, true);
    for (int x = 6; x <= 12; x++) seals[Atmosphere.index(x, 5, 5)] = true;
    var topology = Atmosphere.rebuild(seals, air, false);
    assertEquals(2, topology.rooms().size());
    assertEquals(13000, total(air));
    assertEquals(7000, topology.displaced());
    assertEquals(0, topology.vented());
  }

  @Test
  void removingEveryAirCellAccountsForConstructionLossAndDoesNotRefillOnReopening() {
    boolean[] seals = solid();
    int[] air = empty();
    int a = Atmosphere.index(6, 6, 6), b = a + 1;
    seals[a] = seals[b] = false;
    Atmosphere.rooms(seals, air, true);
    seals[a] = seals[b] = true;
    var filled = Atmosphere.rebuild(seals, air, false);
    assertTrue(filled.rooms().isEmpty());
    assertEquals(2000, filled.vented());
    assertEquals(0, total(air));
    seals[a] = seals[b] = false;
    var reopened = Atmosphere.rebuild(seals, air, false);
    assertEquals(1, reopened.rooms().size());
    assertEquals(0, total(air));
    assertEquals(0, reopened.vented());
  }

  @Test
  void displacedGasDoesNotTeleportThroughAnExistingBulkhead() {
    boolean[] seals = solid();
    int[] air = empty();
    int a = Atmosphere.index(6, 6, 6), b = a + 2;
    seals[a] = seals[b] = false;
    Atmosphere.rooms(seals, air, true);
    air[a] = 600;
    air[b] = 100;
    seals[a] = true;
    var topology = Atmosphere.rebuild(seals, air, false);
    assertEquals(600, topology.vented());
    assertEquals(100, air[b]);
    assertEquals(100, total(air));
  }

  @Test
  void gridEdgesNeverWrapIntoAnotherRow() {
    boolean[] seals = solid();
    int[] air = empty();
    int a = Atmosphere.index(31, 4, 5), b = Atmosphere.index(0, 4, 6);
    seals[a] = seals[b] = false;
    air[a] = air[b] = 1000;
    var topology = Atmosphere.rebuild(seals, air, false);
    assertEquals(2, topology.rooms().size());
    assertNotEquals(topology.roomAt()[a], topology.roomAt()[b]);
    assertEquals(1, topology.rooms().getFirst().leaks());
    assertEquals(1, topology.rooms().getLast().leaks());
  }

  @Test
  void openVacuumAndSolidVolumesDoNotInventRooms() {
    assertTrue(Atmosphere.rebuild(new boolean[Atmosphere.CELLS], empty(), true).rooms().isEmpty());
    assertTrue(Atmosphere.rebuild(solid(), empty(), true).rooms().isEmpty());
  }

  @Test
  void roomLookupIsCompleteAndLeakFacesAreCountedOnce() {
    boolean[] seals = solid();
    int[] air = empty();
    int a = Atmosphere.index(0, 0, 0), b = a + 1;
    seals[a] = seals[b] = false;
    air[a] = air[b] = 1000;
    var topology = Atmosphere.rebuild(seals, air, false);
    assertEquals(1, topology.rooms().size());
    assertEquals(5, topology.rooms().getFirst().leaks());
    assertEquals(topology.roomAt()[a], topology.roomAt()[b]);
    assertEquals(-1, topology.roomAt()[b + 1]);
    for (int r = 0; r < topology.rooms().size(); r++)
      for (int cell : topology.rooms().get(r).cells()) assertEquals(r, topology.roomAt()[cell]);
  }

  @Test
  void mismatchedGridSizesAreRejected() {
    boolean[] seals = solid();
    int[] air = empty();
    int cell = Atmosphere.index(5, 5, 5);
    seals[cell] = false;
    var topology = Atmosphere.rebuild(seals, air, false);
    assertThrows(
        IllegalArgumentException.class, () -> Atmosphere.rebuild(new boolean[1], air, false));
  }

  @Test
  void randomConstructionEditsConserveGasOrAccountForLostVolume() {
    boolean[] seals = solid();
    int[] air = empty();
    for (int x = 8; x < 24; x++)
      for (int y = 8; y < 24; y++)
        for (int z = 8; z < 24; z++) seals[Atmosphere.index(x, y, z)] = false;
    Atmosphere.rebuild(seals, air, true);
    var random = new Random(84923651);
    for (int step = 0; step < 250; step++) {
      long before = total(air);
      for (int edit = 0; edit < 12; edit++) {
        int cell =
            Atmosphere.index(
                1 + random.nextInt(30), 1 + random.nextInt(30), 1 + random.nextInt(30));
        seals[cell] = !seals[cell];
      }
      var topology = Atmosphere.rebuild(seals, air, false);
      assertEquals(before, total(air) + topology.vented(), "Construction step " + step);
      for (int value : air) assertTrue(value >= -1);
    }
  }

  private static boolean[] solid() {
    boolean[] seals = new boolean[Atmosphere.CELLS];
    Arrays.fill(seals, true);
    return seals;
  }

  private static int[] empty() {
    int[] air = new int[Atmosphere.CELLS];
    Arrays.fill(air, -1);
    return air;
  }

  private static long total(int[] a) {
    return Arrays.stream(a).mapToLong(i -> Math.max(0, i)).sum();
  }
}
