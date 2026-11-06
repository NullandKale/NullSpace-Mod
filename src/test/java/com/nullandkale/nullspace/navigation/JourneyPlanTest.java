package com.nullandkale.nullspace.navigation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class JourneyPlanTest {
  @Test
  void routesAreStableAndSharedInBothDirections() {
    var a = JourneyPlan.between(42, "sol_terra", "sol_luna", false, 0);
    assertEquals(a, JourneyPlan.between(42, "sol_luna", "sol_terra", false, 0));
    assertTrue(a.site().length() <= 32);
  }

  @Test
  void speedsDoNotRerollContacts() {
    var a = JourneyPlan.between(42, "a", "b", false, 0);
    for (int tier = 1; tier <= 3; tier++) {
      var b = JourneyPlan.between(42, "a", "b", false, tier);
      assertEquals(a.site(), b.site());
      assertEquals(a.kind(), b.kind());
      assertTrue(b.seconds() < a.seconds());
      assertTrue(b.contactSecond() < b.seconds());
    }
  }

  @Test
  void worldAndRouteChangeTheDiscoveries() {
    assertNotEquals(
        JourneyPlan.between(1, "a", "b", false, 0).site(),
        JourneyPlan.between(2, "a", "b", false, 0).site());
    assertNotEquals(
        JourneyPlan.between(1, "a", "b", false, 0).site(),
        JourneyPlan.between(1, "a", "c", false, 0).site());
  }

  @Test
  void allEncounterKindsOccurWithinTimingBounds() {
    var kinds = new HashSet<Integer>();
    for (int i = 0; i < 1000; i++) {
      var local = JourneyPlan.between(i, "a", "b", false, 0);
      var far = JourneyPlan.between(i, "a", "b", true, 0);
      assertTrue(local.seconds() >= 45 && local.seconds() <= 90);
      assertTrue(far.seconds() >= 120 && far.seconds() <= 180);
      assertTrue(local.contactSecond() > 0 && local.contactSecond() < local.seconds());
      kinds.add(local.kind());
    }
    assertEquals(Set.of(0, 1, 2), kinds);
  }
}
