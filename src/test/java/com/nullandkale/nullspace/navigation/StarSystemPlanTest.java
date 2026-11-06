package com.nullandkale.nullspace.navigation;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.content.ContentCatalog;
import com.nullandkale.nullspace.scene.SpaceDefinitionCodec;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class StarSystemPlanTest {
  @Test
  void oneWorldSeedDeterminesUniqueSystemsAndLandableRegionIdentities() {
    for (long seed = 0; seed < 64; seed++) {
      var systems = StarSystemPlan.generate(seed);
      assertEquals(systems, StarSystemPlan.generate(seed));
      assertNotEquals(systems, StarSystemPlan.generate(seed + 1));
      assertEquals(ContentCatalog.SYSTEM_SLOTS.size(), systems.size());
      var ids = new HashSet<String>();
      var names = new HashSet<String>();
      for (var system : systems) {
        assertTrue(ids.add(system.id()));
        assertTrue(names.add(system.name()));
        assertEquals(3, system.regions().size());
        for (var r : system.regions()) {
          var d = r.destination();
          assertTrue(ids.add(d.id()));
          assertTrue(names.add(d.name()));
          assertEquals(system.id(), d.system());
          assertEquals(system.name(), d.systemName());
          assertTrue(LocalSpaces.known(d.id()));
          assertNotNull(PlanetSurfaces.dimension(d.id()));
          assertEquals(ContentCatalog.planet(d.id()).breathable(), r.atmosphere() > 0);
          assertEquals(ContentCatalog.planet(d.id()).moon(), d.moon());
        }
      }
    }
  }

  @Test
  void regionsRenderTheSameStarAndPlanetsInConsistentTranslatedFrames() {
    for (var system : StarSystemPlan.generate(12345)) {
      var previous =
          SpaceDefinitionCodec.decode(StarSystems.scene(system, system.regions().getFirst()));
      for (var region : system.regions()) {
        var scene = SpaceDefinitionCodec.decode(StarSystems.scene(system, region));
        assertEquals(5, scene.bodies().size());
        for (int i = 0; i < 4; i++) {
          var body = scene.bodies().get(i);
          var before = previous.bodies().get(i);
          assertEquals(before.id(), body.id());
          assertEquals(before.seed(), body.seed());
          assertEquals(before.radius(), body.radius());
          assertEquals(before.color(), body.color());
          var starRelative = body.center().subtract(scene.bodies().getFirst().center());
          var expected = before.center().subtract(previous.bodies().getFirst().center());
          assertEquals(0, expected.distanceTo(starRelative), 1e-8);
        }
        var primary =
            scene.bodies().stream()
                .filter(b -> b.id().equals("nullspace:" + region.destination().id()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, new Vec3(0, -2200, 5200).distanceTo(primary.center()), 1e-8);
        assertTrue(primary.center().length() > primary.radius() + 256);
        assertDoesNotThrow(scene::instances);
        assertTrue(scene.bodies().getFirst().emission() > 0);
      }
    }
  }

  @Test
  void addingNewSystemsDoesNotChangeTheOriginalPlanetCatalog() {
    assertEquals(
        List.of("sol_terra", "sol_luna", "sol_ares", "vega_pelagos", "vega_cinder"),
        LocalSpaces.DESTINATIONS.stream().map(LocalSpaces.Destination::id).toList());
    assertEquals(17, ContentCatalog.SURFACES.size());
    for (var slot : ContentCatalog.SYSTEM_SLOTS)
      for (var region : slot.regions())
        assertNotNull(getClass().getResource("/data/nullspace/dimension/" + region.id() + ".json"));
  }
}
