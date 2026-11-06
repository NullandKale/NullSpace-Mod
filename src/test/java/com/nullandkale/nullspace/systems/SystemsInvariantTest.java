package com.nullandkale.nullspace.systems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class SystemsInvariantTest {
  private static boolean[] solid() {
    var a = new boolean[Atmosphere.CELLS];
    Arrays.fill(a, true);
    return a;
  }

  private static int[] empty() {
    var a = new int[Atmosphere.CELLS];
    Arrays.fill(a, -1);
    return a;
  }

  private static long mass(int[] a) {
    return Arrays.stream(a).filter(n -> n >= 0).asLongStream().sum();
  }

  @TestFactory
  Stream<DynamicTest> doorCyclesDoNotCreateOrDeleteGas() {
    return IntStream.range(0, 6)
        .mapToObj(
            axis ->
                DynamicTest.dynamicTest(
                    "bulkhead cycle on face " + axis,
                    () -> {
                      var seals = solid();
                      var air = empty();
                      int step = new int[] {1, -1, 32, -32, 1024, -1024}[axis],
                          mid = Atmosphere.index(16, 16, 16);
                      for (int n = -3; n <= 3; n++) seals[mid + n * step] = false;
                      var topology = Atmosphere.rebuild(seals, air, true);
                      long original = mass(air);
                      for (int cycle = 0; cycle < 25; cycle++) {
                        int displaced = air[mid];
                        seals[mid] = true;
                        topology = Atmosphere.rebuild(seals, air, false);
                        assertEquals(2, topology.rooms().size());
                        assertEquals(original, mass(air));
                        assertEquals(displaced, topology.displaced());
                        assertEquals(0, topology.vented());
                        SpatialAir.step(air, topology, seals, Map.of(), Map.of(), 3, 0);
                        assertEquals(original, mass(air));
                        seals[mid] = false;
                        topology = Atmosphere.rebuild(seals, air, false);
                        assertEquals(1, topology.rooms().size());
                        assertEquals(0, air[mid]);
                        SpatialAir.step(air, topology, seals, Map.of(), Map.of(), 20, 0);
                        assertEquals(original, mass(air));
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> sixExteriorFacesHaveTheSameVacuumLoss() {
    return IntStream.range(0, 6)
        .mapToObj(
            face ->
                DynamicTest.dynamicTest(
                    "vacuum boundary " + face,
                    () -> {
                      var seals = solid();
                      var air = empty();
                      int[][] positions = {
                        {0, 16, 16},
                        {31, 16, 16},
                        {16, 0, 16},
                        {16, 31, 16},
                        {16, 16, 0},
                        {16, 16, 31}
                      };
                      int[] xyz = positions[face];
                      int cell = Atmosphere.index(xyz[0], xyz[1], xyz[2]);
                      seals[cell] = false;
                      air[cell] = 1000;
                      var topology = Atmosphere.rebuild(seals, air, false);
                      var result = SpatialAir.step(air, topology, seals, Map.of(), Map.of(), 1, 0);
                      // One open face moves FULL / HOLE_TICKS per tick from a one-block room.
                      long perFace = Atmosphere.FULL / SpatialAir.HOLE_TICKS;
                      assertEquals(perFace, result.escaped());
                      assertEquals(Atmosphere.FULL - perFace, air[cell]);
                      assertEquals(1, topology.rooms().getFirst().leaks());
                      assertTrue(result.flows().stream().allMatch(SpatialAir.Flow::exterior));
                    }));
  }

  @Test
  void sourceMapsIgnoreSolidCellsAndZeroTimeHasNoSideEffects() {
    var seals = solid();
    var air = empty();
    int room = Atmosphere.index(8, 8, 8);
    seals[room] = false;
    var topology = Atmosphere.rebuild(seals, air, false);
    var before = air.clone();
    var result = SpatialAir.step(air, topology, seals, Map.of(room, 100), Map.of(room, 10), 0, 0);
    assertArrayEquals(before, air);
    assertEquals(0, result.supplied());
    assertTrue(result.flows().isEmpty());
    result =
        SpatialAir.step(air, topology, seals, Map.of(room + 1, 100), Map.of(room + 1, 10), 1, 0);
    assertEquals(0, result.supplied());
    assertEquals(0, result.consumed());
    assertEquals(0, mass(air));
  }

  @Test
  void overpressureEqualizesWithoutSilentDeletionOrPumpOverfill() {
    var seals = solid();
    var air = empty();
    int a = Atmosphere.index(8, 8, 8);
    seals[a] = seals[a + 1] = false;
    var topology = Atmosphere.rebuild(seals, air, false);
    air[a] = 8000;
    air[a + 1] = 0;
    for (int i = 0; i < 100; i++) {
      var result = SpatialAir.step(air, topology, seals, Map.of(a, 100000), Map.of(), 5, 0);
      assertEquals(0, result.supplied());
      assertEquals(8000, mass(air));
      assertTrue(air[a] >= 0 && air[a + 1] >= 0);
    }
  }

  @Test
  void disconnectedRoomsCannotBorrowAnotherRoomsPumpCapacity() {
    var seals = solid();
    var air = empty();
    int a = Atmosphere.index(8, 8, 8), b = a + 2;
    seals[a] = seals[b] = false;
    var topology = Atmosphere.rebuild(seals, air, false);
    air[a] = 1000;
    var result = SpatialAir.step(air, topology, seals, Map.of(a, 100000), Map.of(), 20, 0);
    assertEquals(0, result.supplied());
    assertEquals(0, air[b]);
    assertTrue(result.flows().isEmpty());
  }

  @TestFactory
  Stream<DynamicTest> invalidAirSourcesAreRejected() {
    return Stream.of(Map.of(-1, 1), Map.of(Atmosphere.CELLS, 1), Map.of(0, -1), Map.of(0, 100001))
        .map(
            source ->
                DynamicTest.dynamicTest(
                    "reject pump " + source,
                    () -> {
                      var seals = solid();
                      var air = empty();
                      var t = Atmosphere.rebuild(seals, air, false);
                      assertThrows(
                          IllegalArgumentException.class,
                          () -> SpatialAir.step(air, t, seals, source, Map.of(), 1, 0));
                      assertThrows(
                          IllegalArgumentException.class,
                          () -> SpatialAir.step(air, t, seals, Map.of(), source, 1, 0));
                    }));
  }

  @TestFactory
  Stream<DynamicTest> powerDeliveryIsCappedByEachPhysicalLimit() {
    return IntStream.range(0, 6)
        .mapToObj(
            limit ->
                DynamicTest.dynamicTest(
                    "power limit " + limit,
                    () -> {
                      var result =
                          PowerBus.tick(
                              20,
                              0,
                              100,
                              100,
                              100,
                              List.of(new PowerBus.Load(PowerBus.System.AIR, 8, limit, 8)));
                      assertEquals(limit, result.delivered().get(PowerBus.System.AIR));
                      assertEquals(limit, result.consumed());
                      assertEquals(20 - limit, result.charged());
                      var requested =
                          PowerBus.tick(
                              20,
                              0,
                              100,
                              100,
                              100,
                              List.of(new PowerBus.Load(PowerBus.System.AIR, limit, 8, 8)));
                      assertEquals(result, requested);
                    }));
  }

  @Test
  void duplicateLoadsAndInvalidSuppliesCannotCreatePower() {
    var load = new PowerBus.Load(PowerBus.System.AIR, 1, 1, 1);
    assertThrows(
        IllegalArgumentException.class, () -> PowerBus.tick(1, 0, 0, 0, 0, List.of(load, load)));
    for (var supply :
        List.of(
            new int[] {-1, 0, 0, 0, 0},
            new int[] {0, -1, 0, 0, 0},
            new int[] {0, 2, 1, 0, 0},
            new int[] {0, 0, 0, -1, 0},
            new int[] {0, 0, 0, 0, -1}))
      assertThrows(
          IllegalArgumentException.class,
          () -> PowerBus.tick(supply[0], supply[1], supply[2], supply[3], supply[4], List.of()));
    assertThrows(
        ArithmeticException.class, () -> PowerBus.tick(Integer.MAX_VALUE, 1, 1, 0, 1, List.of()));
  }
}
