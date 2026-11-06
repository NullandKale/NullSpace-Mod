package com.nullandkale.nullspace.computer;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.content.ContentCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContentCatalogTest {
  @Test
  void everyDefinedBlockHasWrappersLootRecipeAndAuthoredModel() {
    for (var d : ContentCatalog.EQUIPMENT) {
      if (!d.has("retired")) {
        assertNotNull(getClass().getResource("/data/nullspace/recipe/" + d.id() + ".json"));
        assertNotNull(
            getClass().getResource("/data/nullspace/advancement/recipes/ship/" + d.id() + ".json"));
      }
      if (d.kind().equals("block")) {
        assertNotNull(getClass().getResource("/assets/nullspace/blockstates/" + d.id() + ".json"));
        assertNotNull(getClass().getResource("/assets/nullspace/models/block/" + d.id() + ".json"));
        assertNotNull(getClass().getResource("/assets/nullspace/items/" + d.id() + ".json"));
        assertNotNull(
            getClass().getResource("/data/nullspace/loot_table/blocks/" + d.id() + ".json"));
      }
    }
    assertEquals(30, ContentCatalog.EQUIPMENT.size());
  }

  @Test
  void planetsKeepTheirAddressesAndBreathableSurfaces() {
    assertEquals(
        Set.of("sol_terra", "sol_luna", "sol_ares", "vega_pelagos", "vega_cinder"),
        ContentCatalog.PLANETS.stream()
            .map(ContentCatalog.PlanetDefinition::id)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        Set.of("sol_terra", "vega_pelagos"),
        ContentCatalog.PLANETS.stream()
            .filter(ContentCatalog.PlanetDefinition::breathable)
            .map(ContentCatalog.PlanetDefinition::id)
            .collect(java.util.stream.Collectors.toSet()));
    for (var planet : ContentCatalog.PLANETS)
      if (!planet.id().equals("sol_terra"))
        assertNotNull(getClass().getResource("/data/nullspace/dimension/" + planet.id() + ".json"));
    assertThrows(IllegalArgumentException.class, () -> ContentCatalog.planet("made_up"));
  }

  @Test
  void capabilitiesDoNotTurnCraftingComponentsIntoActiveDevices() {
    assertFalse(ContentCatalog.equipment("computer_base").orElseThrow().has("device"));
    assertFalse(ContentCatalog.equipment("ship_beacon").orElseThrow().has("device"));
    assertTrue(ContentCatalog.equipment("helm").orElseThrow().has("navigation"));
    assertTrue(ContentCatalog.equipment("local_scanner").orElseThrow().has("equipment_menu"));
    assertFalse(ContentCatalog.equipment("space_helmet").orElseThrow().has("device"));
  }
}
