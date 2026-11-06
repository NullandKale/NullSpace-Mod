package com.nullandkale.nullspace.navigation;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.navigation.GalaxyPlan.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GalaxyPlanTest {
  private static final int SEEDS = 5000;

  @Test
  void everyPlaceIsReachableWithTheStartingEngineOnThousandsOfSeeds() {
    for (long seed = 0; seed < SEEDS; seed++) {
      var galaxy = GalaxyPlan.generate(seed * 7919 - 31);
      assertTrue(
          GalaxyPlan.connected(galaxy, GalaxyPlan.STARTING_RANGE), "seed " + seed + " is split");
      for (var place : galaxy.places()) {
        assertTrue(place.x() >= 0 && place.x() <= GalaxyPlan.SIZE, "on the chart");
        assertTrue(place.y() >= 0 && place.y() <= GalaxyPlan.SIZE, "on the chart");
      }
    }
  }

  @Test
  void chartHasFiveSystemsSpreadPlanetCountsAndFeatures() {
    var counts = new int[10];
    for (long seed = 0; seed < SEEDS; seed++) {
      var galaxy = GalaxyPlan.generate(seed);
      assertEquals(5, galaxy.systems().size());
      assertEquals(GalaxyPlan.HOME, galaxy.systems().getFirst().id());
      assertTrue(galaxy.features().size() >= 3, "at least three features");
      assertEquals(
          1,
          galaxy.features().stream()
              .filter(f -> f.type() == FeatureType.PULSAR || f.type() == FeatureType.BLACK_HOLE)
              .count(),
          "one rare oddity");
      var nonHome = galaxy.systems().subList(1, 5).stream().map(s -> s.planets().size()).sorted().toList();
      // One system from each band: 1-2, 3-4, 5-6, 7-9.
      assertTrue(nonHome.get(0) <= 2 && nonHome.get(1) >= 3 && nonHome.get(1) <= 4, nonHome.toString());
      assertTrue(nonHome.get(2) >= 5 && nonHome.get(2) <= 6 && nonHome.get(3) >= 7, nonHome.toString());
      for (var s : galaxy.systems()) counts[s.planets().size()]++;
      for (int i = 0; i < 5; i++)
        for (int j = i + 1; j < 5; j++)
          assertTrue(
              GalaxyPlan.distance(galaxy.systems().get(i), galaxy.systems().get(j))
                  >= GalaxyPlan.SYSTEM_SPACING,
              "systems are sparse");
    }
    for (int planets = 1; planets <= 9; planets++)
      assertTrue(counts[planets] > 0, "some system has " + planets + " planets");
  }

  @Test
  void zonesAreUniqueAndSurfacesStayInsideThePool() {
    long landable = 0, bodies = 0;
    for (long seed = 0; seed < SEEDS; seed++) {
      var galaxy = GalaxyPlan.generate(seed);
      var zones = galaxy.zones();
      assertEquals(zones.size(), new HashSet<>(zones).size(), "unique zone ids");
      for (var zone : zones) assertTrue(zone.matches("[a-z0-9_]+"), zone);
      var surfaces = new HashSet<String>();
      for (var s : galaxy.systems())
        for (var b : s.bodies()) {
          bodies++;
          if (!b.landable()) continue;
          landable++;
          assertTrue(b.type().surface, "only surface types can be landed on");
          assertTrue(surfaces.add(b.surface()), "a surface belongs to one body");
          if (b.moon())
            assertTrue(
                s.bodies().stream().anyMatch(p -> p.id().equals(b.parent()) && !p.moon()),
                "a moon orbits a planet of its system");
        }
      for (var type : GalaxyPlan.SURFACE_POOL.keySet()) {
        var used =
            surfaces.stream()
                .filter(x -> x.startsWith("nullspace:surface_" + type.name().toLowerCase(Locale.ROOT)))
                .count();
        assertTrue(used <= GalaxyPlan.SURFACE_POOL.get(type), type + " pool exceeded");
      }
    }
    double share = landable / (double) bodies;
    assertTrue(share > .25 && share < .55, "about 40% of bodies are landable, was " + share);
  }

  @Test
  void solKeepsTheHomeWorldsAndTheirOwnDimensions() {
    var sol = GalaxyPlan.generate(42).systems().getFirst();
    var byId = new HashMap<String, Body>();
    for (var b : sol.bodies()) byId.put(b.id(), b);
    assertEquals("minecraft:overworld", byId.get(LocalSpaces.HOME).surface());
    assertEquals("nullspace:sol_luna", byId.get("sol_luna").surface());
    assertEquals(LocalSpaces.HOME, byId.get("sol_luna").parent());
    assertEquals("nullspace:sol_ares", byId.get("sol_ares").surface());
    assertEquals(BodyType.GAS_GIANT, byId.get("sol_3").type());
  }

  @Test
  void sameSeedSameChartDifferentSeedDifferentChart() {
    assertEquals(GalaxyPlan.generate(7), GalaxyPlan.generate(7));
    var a = GalaxyPlan.generate(7).systems().get(2);
    var b = GalaxyPlan.generate(8).systems().get(2);
    assertNotEquals(a.x(), b.x());
  }

  @Test
  void routesHopWithinRangeAndBetterEnginesNeedFewerHops() {
    int shorter = 0;
    for (long seed = 0; seed < 500; seed++) {
      var galaxy = GalaxyPlan.generate(seed);
      for (var target : galaxy.places()) {
        var route = GalaxyPlan.route(galaxy, GalaxyPlan.HOME, target.id(), GalaxyPlan.STARTING_RANGE);
        assertFalse(route.isEmpty());
        assertEquals(GalaxyPlan.HOME, route.getFirst().id());
        assertEquals(target.id(), route.getLast().id());
        for (int i = 1; i < route.size(); i++)
          assertTrue(GalaxyPlan.distance(route.get(i - 1), route.get(i)) <= GalaxyPlan.STARTING_RANGE);
        var far = GalaxyPlan.route(galaxy, GalaxyPlan.HOME, target.id(), GalaxyPlan.RANGES[3]);
        assertTrue(far.size() <= route.size());
        if (far.size() < route.size()) shorter++;
      }
    }
    assertTrue(shorter > 0, "upgraded engines skip hops somewhere");
    assertEquals(3.5, GalaxyPlan.jumpFuel(25), 1e-9);
  }

  @Test
  void zonesMapBackToTheirChartPlace() {
    var galaxy = GalaxyPlan.generate(3);
    assertEquals(GalaxyPlan.HOME, galaxy.placeOfZone("sol_luna").id());
    var feature = galaxy.features().getFirst();
    assertSame(feature, galaxy.placeOfZone(feature.id()));
  }
}
