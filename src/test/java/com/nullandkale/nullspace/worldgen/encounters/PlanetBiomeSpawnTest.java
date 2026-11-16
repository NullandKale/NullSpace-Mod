package com.nullandkale.nullspace.worldgen.encounters;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanetBiomeSpawnTest
{
    private static final List<String> AIRLESS = List.of("barren_stony_peaks", "barren_stony_shore", "desert_badlands", "desert_desert", "desert_eroded_badlands", "desert_wooded_badlands", "ice_frozen_ocean", "ice_frozen_peaks", "ice_ice_spikes", "ice_snowy_slopes", "lava_basalt_deltas", "lava_soul_sand_valley");
    private static final List<String> WITH_AIR = List.of("ocean_deep_ocean", "ocean_mushroom_fields", "ocean_warm_ocean", "jungle_bamboo_jungle", "jungle_jungle", "jungle_swamp");
    /** Every space mob: the suited crews, the stowaways and the repair bot. */
    private static final Set<String> SPACE_MOBS = Set.of("minecraft:zombie", "minecraft:skeleton", "minecraft:stray", "minecraft:zombie_villager", "minecraft:wither_skeleton", "nullspace:ghost_creeper", "nullspace:pale_spider", "nullspace:pale_cave_spider", "nullspace:pale_silverfish", "nullspace:violet_slime", "nullspace:violet_blaze", "nullspace:repair_bot");

    private static JsonObject biome(String name)
    {
        var stream = PlanetBiomeSpawnTest.class.getResourceAsStream("/data/nullspace/worldgen/biome/planet/" + name + ".json");
        assertNotNull(stream, name);
        return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<String> monsters(JsonObject biome)
    {
        var spawns = biome.getAsJsonObject("attributes").getAsJsonObject("minecraft:gameplay/natural_mob_spawns");
        if (spawns == null)
            return List.of();
        var monster = spawns.getAsJsonObject("argument").getAsJsonObject("spawns_by_category").getAsJsonArray("monster");
        return monster == null ? List.of() : monster.asList().stream().map(e -> e.getAsJsonObject().get("type").getAsString()).collect(Collectors.toList());
    }

    @Test
    void everyAirlessBiomeSpawnsEverySpaceMobAndNothingElse()
    {
        for (var name : AIRLESS)
        {
            var found = monsters(biome(name));
            assertEquals(SPACE_MOBS, new HashSet<>(found), name + " spawns the whole crew of space mobs");
            assertEquals(found.size(), new HashSet<>(found).size(), name + " lists each once");
        }
    }

    @Test
    void theBiomesWithAirKeepTheirOwnLife()
    {
        for (var name : WITH_AIR)
            assertNotEquals(SPACE_MOBS, new HashSet<>(monsters(biome(name))), name + " keeps its vanilla spawns");
    }

    @Test
    void airlessBiomesHaveNoOtherCreaturesOrAmbientLife()
    {
        for (var name : AIRLESS)
        {
            var categories = biome(name).getAsJsonObject("attributes").getAsJsonObject("minecraft:gameplay/natural_mob_spawns").getAsJsonObject("argument").getAsJsonObject("spawns_by_category");
            assertEquals(List.of("monster"), List.copyOf(categories.keySet()), name + " has only monsters");
        }
    }
}
