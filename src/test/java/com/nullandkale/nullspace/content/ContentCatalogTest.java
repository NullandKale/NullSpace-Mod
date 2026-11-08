package com.nullandkale.nullspace.content;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentCatalogTest
{
    @Test
    void everyDefinedBlockHasWrappersLootRecipeAndAuthoredModel()
    {
        for (var d : ContentCatalog.EQUIPMENT)
        {
            {
                assertNotNull(getClass().getResource("/data/nullspace/recipe/" + d.id() + ".json"));
                assertNotNull(getClass().getResource("/data/nullspace/advancement/recipes/ship/" + d.id() + ".json"));
            }
            if (d.kind().equals("block"))
            {
                assertNotNull(getClass().getResource("/assets/nullspace/blockstates/" + d.id() + ".json"));
                assertNotNull(getClass().getResource("/assets/nullspace/models/block/" + d.id() + ".json"));
                assertNotNull(getClass().getResource("/assets/nullspace/items/" + d.id() + ".json"));
                assertNotNull(getClass().getResource("/data/nullspace/loot_table/blocks/" + d.id() + ".json"));
            }
        }
        assertEquals(26, ContentCatalog.EQUIPMENT.size());
    }

    @Test
    void planetsKeepTheirAddressesAndBreathableSurfaces()
    {
        assertEquals(Set.of("sol_terra", "sol_luna", "sol_ares"), ContentCatalog.PLANETS.stream().map(ContentCatalog.PlanetDefinition::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("sol_terra"), ContentCatalog.PLANETS.stream().filter(ContentCatalog.PlanetDefinition::breathable).map(ContentCatalog.PlanetDefinition::id).collect(java.util.stream.Collectors.toSet()));
        for (var planet : ContentCatalog.PLANETS)
            if (!planet.id().equals("sol_terra"))
                assertNotNull(getClass().getResource("/data/nullspace/dimension/" + planet.id() + ".json"));
        assertThrows(IllegalArgumentException.class, () -> ContentCatalog.planet("made_up"));
        // P9 surface pool: every slot has a generated dimension, ocean and jungle have air.
        int pool = 0;
        for (var e : GalaxyPlan.SURFACE_POOL.entrySet())
            pool += e.getValue();
        assertEquals(pool, ContentCatalog.SURFACE_POOL.size());
        for (var surface : ContentCatalog.SURFACE_POOL)
        {
            assertNotNull(getClass().getResource("/data/nullspace/dimension/" + surface.id() + ".json"));
            assertEquals(surface.id().startsWith("surface_ocean") || surface.id().startsWith("surface_jungle"), surface.breathable(), surface.id());
        }
    }

    @Test
    void capabilitiesDoNotTurnCraftingComponentsIntoActiveDevices()
    {
        assertFalse(ContentCatalog.equipment("computer_base").orElseThrow().has("device"));
        assertFalse(ContentCatalog.equipment("ship_beacon").orElseThrow().has("device"));
        assertTrue(ContentCatalog.equipment("ship_computer").orElseThrow().has("navigation"));
        assertTrue(ContentCatalog.equipment("local_scanner").orElseThrow().has("equipment_menu"));
        assertFalse(ContentCatalog.equipment("space_helmet").orElseThrow().has("device"));
    }
}
