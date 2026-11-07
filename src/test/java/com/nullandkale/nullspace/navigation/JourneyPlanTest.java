package com.nullandkale.nullspace.navigation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class JourneyPlanTest {
  @Test
  void routesAreStableAndSharedInBothDirections() {
    var a = JourneyPlan.between(42, "sol_terra", "sol_luna", 0, 0);
    assertEquals(a, JourneyPlan.between(42, "sol_luna", "sol_terra", 0, 0));
    assertTrue(a.site().length() <= 32);
  }

  @Test
  void speedsDoNotRerollContacts() {
    var a = JourneyPlan.between(42, "a", "b", 0, 0);
    for (int tier = 1; tier <= 3; tier++) {
      var b = JourneyPlan.between(42, "a", "b", 0, tier);
      assertEquals(a.site(), b.site());
      assertEquals(a.kind(), b.kind());
      assertTrue(b.seconds() < a.seconds());
      assertTrue(b.contactSecond() < b.seconds());
    }
  }

  @Test
  void worldAndRouteChangeTheDiscoveries() {
    assertNotEquals(
        JourneyPlan.between(1, "a", "b", 0, 0).site(),
        JourneyPlan.between(2, "a", "b", 0, 0).site());
    assertNotEquals(
        JourneyPlan.between(1, "a", "b", 0, 0).site(),
        JourneyPlan.between(1, "a", "c", 0, 0).site());
  }

  @Test
  void allEncounterKindsOccurWithinTimingBounds() {
    var kinds = new HashSet<Integer>();
    for (int i = 0; i < 1000; i++) {
      var local = JourneyPlan.between(i, "a", "b", 0, 0);
      var far = JourneyPlan.between(i, "a", "b", 30, 0);
      assertTrue(local.seconds() >= 45 && local.seconds() <= 90);
      assertTrue(far.seconds() >= 180 && far.seconds() <= 200, "60 s + 4 s per light-year");
      assertTrue(local.contactSecond() > 0 && local.contactSecond() < local.seconds());
      kinds.add(local.kind());
    }
    assertEquals(Set.of(0, 1, 2, 3), kinds);
  }

  @Test
  void eachJourneyMeetsItsOwnSignalButKeepsTheRouteLength() {
    var sites = new HashSet<String>();
    var kinds = new HashSet<Integer>();
    for (long nonce = 0; nonce < 200; nonce++) {
      var trip = JourneyPlan.between(42, "a", "b", 10, 0, nonce * 0x9e3779b97f4a7c15L);
      assertEquals(JourneyPlan.between(42, "a", "b", 10, 0).seconds(), trip.seconds());
      assertTrue(trip.contactSecond() >= trip.seconds() / 4 && trip.contactSecond() < trip.seconds());
      sites.add(trip.site());
      kinds.add(trip.kind());
    }
    assertEquals(200, sites.size(), "Every journey's signal is a new site");
    assertEquals(Set.of(0, 1, 2, 3), kinds);
  }
}
