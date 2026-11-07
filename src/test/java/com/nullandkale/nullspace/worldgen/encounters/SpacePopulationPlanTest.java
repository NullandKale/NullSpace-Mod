package com.nullandkale.nullspace.worldgen.encounters;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpacePopulationPlanTest {
  @Test
  void worldAndRegionDetermineInventoryIndependentlyOfArrivalOrder() {
    var ids = new HashSet<String>();
    for (var region : LocalSpaces.DESTINATIONS) {
      var plan = SpacePopulationPlan.sites(123, region);
      assertEquals(plan, SpacePopulationPlan.sites(123, region));
      assertNotEquals(plan, SpacePopulationPlan.sites(124, region));
      assertTrue(plan.size() >= 3 && plan.size() <= 5);
      assertEquals("waypoint", plan.get(0).role());
      assertEquals("exchange", plan.get(1).role());
      assertTrue(
          plan.subList(2, plan.size()).stream().allMatch(s -> s.role().startsWith("salvage-")));
      for (var site : plan) {
        assertTrue(ids.add(site.id()));
        assertDoesNotThrow(() -> UUID.fromString(site.id()));
        assertEquals(site.candidate(0), site.candidate(0));
        assertTrue(site.candidate(0).length() >= 640);
      }
      assertTrue(plan.getFirst().candidate(0).distanceTo(plan.getLast().candidate(0)) >= 460);
    }
  }

  @Test
  void seededShipTrafficUsesAllManufacturersAndRolesWithoutRerolling() {
    var designs = new HashSet<String>();
    var counts = new HashSet<Integer>();
    for (long seed = 0; seed < 100; seed++) {
      var sites = SpacePopulationPlan.sites(seed, LocalSpaces.get(LocalSpaces.HOME));
      counts.add(sites.size() - 2);
      for (var site : sites.subList(2, sites.size())) {
        int kind = SpacePopulationPlan.salvageKind(site.seed());
        designs.add(EncounterSites.design(kind, site.seed()));
      }
    }
    assertEquals(Set.of(1, 2, 3), counts);
    assertEquals(
        Set.of(
            "rivet_bolt",
            "halcyon_swift",
            "bastion_ward",
            "rivet_ranger",
            "halcyon_horizon",
            "bastion_pioneer",
            "rivet_hauler",
            "halcyon_courier",
            "bastion_tender"),
        designs);
  }

  @Test
  void alternativesRemainBoundedAndClearOfTheArrivalArea() {
    for (long seed = 0; seed < 100; seed++)
      for (var site : SpacePopulationPlan.sites(seed, LocalSpaces.get(LocalSpaces.HOME)))
        for (int attempt = 0; attempt < 32; attempt++) {
          var pos = site.candidate(attempt);
          assertTrue(pos.length() >= 640 && pos.length() < 3000);
          assertTrue(pos.y >= 48 && pos.y < 144);
        }
  }
}
