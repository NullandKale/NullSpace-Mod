package com.nullandkale.nullspace.worldgen;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What the planet data says: where the geodes and the scaled ores are, and that every name resolves to a file. */
class SpaceMaterialDataTest
{
    private static final List<String> AIRLESS = List.of("barren_stony_peaks", "barren_stony_shore", "desert_badlands", "desert_desert", "desert_eroded_badlands", "desert_wooded_badlands", "ice_frozen_ocean", "ice_frozen_peaks", "ice_ice_spikes", "ice_snowy_slopes", "lava_basalt_deltas", "lava_soul_sand_valley");
    private static final List<String> WITH_AIR = List.of("ocean_deep_ocean", "ocean_mushroom_fields", "ocean_warm_ocean", "jungle_bamboo_jungle", "jungle_jungle", "jungle_swamp");
    private static final List<String> LAVA = List.of("lava_basalt_deltas", "lava_soul_sand_valley");

    private static JsonObject read(String path)
    {
        var stream = SpaceMaterialDataTest.class.getResourceAsStream("/data/nullspace/" + path + ".json");
        assertNotNull(stream, path);
        return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<String> features(String biome)
    {
        var out = new ArrayList<String>();
        for (var step : read("worldgen/biome/planet/" + biome).getAsJsonArray("features"))
            for (var feature : step.getAsJsonArray())
                out.add(feature.getAsString());
        return out;
    }

    @Test
    void everyAirlessWorldHasTheTwoSurfaceGeodesAndNoUndergroundAmethystOne()
    {
        for (var biome : AIRLESS)
        {
            var list = features(biome);
            assertTrue(list.contains("nullspace:green_geode"), biome + " has the green geode");
            assertTrue(list.contains("nullspace:amethyst_geode_surface"), biome + " has the surface amethyst geode");
            assertFalse(list.contains("minecraft:amethyst_geode"), biome + " does not also keep the underground one");
        }
    }

    @Test
    void worldsWithAirKeepTheirVanillaGeodeAndHaveNoGreenOne()
    {
        for (var biome : WITH_AIR)
        {
            var list = features(biome);
            assertTrue(list.contains("minecraft:amethyst_geode"), biome);
            assertFalse(list.contains("nullspace:green_geode"), biome);
            assertFalse(list.contains("nullspace:amethyst_geode_surface"), biome);
        }
    }

    @Test
    void lavaWorldsHaveOrdinaryOresAndTheDebrisAndNoOtherWorldHasDebris()
    {
        for (var biome : LAVA)
        {
            var list = features(biome);
            for (var ore : List.of("nullspace:ore_coal_upper", "nullspace:ore_iron_upper", "nullspace:ore_copper", "nullspace:ore_diamond", "nullspace:ore_redstone", "nullspace:ore_lapis", "nullspace:ore_gold"))
                assertTrue(list.contains(ore), biome + " has " + ore);
            assertTrue(list.contains("minecraft:ore_ancient_debris_large") || list.contains("minecraft:ore_debris_small"), biome + " has ancient debris");
        }
        for (var biome : AIRLESS.stream().filter(b -> !LAVA.contains(b)).toList())
            for (var feature : features(biome))
                assertFalse(feature.contains("debris"), biome + " has no ancient debris");
        for (var biome : WITH_AIR)
            for (var feature : features(biome))
                assertFalse(feature.contains("debris"), biome + " has no ancient debris");
    }

    @Test
    void everyPlanetListsTheScaledCopiesOfTheMetalOresAndNeverTheVanillaOnes()
    {
        for (var biome : AIRLESS)
            for (var feature : features(biome))
                assertFalse(List.of("minecraft:ore_iron_upper", "minecraft:ore_coal_upper", "minecraft:ore_diamond", "minecraft:ore_copper", "minecraft:ore_lapis", "minecraft:ore_redstone", "minecraft:ore_gold").contains(feature), biome + " lists " + feature);
        for (var biome : WITH_AIR)
            assertTrue(features(biome).contains("nullspace:ore_iron_upper"), biome + " scales its iron too");
    }

    @Test
    void everyNullspaceFeatureNamedByAPlanetIsDefinedAndTheOreCopiesCarryTheRichnessModifier()
    {
        var names = new java.util.TreeSet<String>();
        for (var biome : java.util.stream.Stream.concat(AIRLESS.stream(), WITH_AIR.stream()).toList())
            for (var feature : features(biome))
                if (feature.startsWith("nullspace:"))
                    names.add(feature.substring("nullspace:".length()));
        assertTrue(names.size() > 15, "the planets list the scaled ores and the geodes: " + names);
        for (var name : names)
        {
            var placed = read("worldgen/placed_feature/" + name);
            var types = new ArrayList<String>();
            for (var modifier : placed.getAsJsonArray("placement"))
                types.add(modifier.getAsJsonObject().get("type").getAsString());
            if (name.startsWith("ore_"))
            {
                assertTrue(types.contains("nullspace:richness"), name + " scales with distance");
                assertEquals("minecraft:biome", types.getLast(), name + " keeps the biome filter last");
                assertTrue(types.indexOf("nullspace:richness") > types.indexOf("minecraft:height_range"), name + " scales after its height is chosen");
            }
            else
                read("worldgen/feature/" + placed.get("feature").getAsString().substring("nullspace:".length()));
        }
    }

    @Test
    void theGreenGeodeIsSmallFilledWithTheFluidAndNeverCracked()
    {
        var geode = read("worldgen/feature/green_geode");
        var blocks = geode.getAsJsonObject("blocks");
        assertEquals("nullspace:green_fluid", blocks.getAsJsonObject("filling_provider").get("id").getAsString());
        assertEquals("nullspace:green_gem_cluster", blocks.getAsJsonObject("alternate_inner_layer_provider").get("id").getAsString());
        assertEquals(0.0, geode.getAsJsonObject("crack").get("generate_crack_chance").getAsDouble(), "a crack would let the fluid out");
        assertTrue(geode.getAsJsonObject("outer_wall_distance").get("max_inclusive").getAsInt() <= 4, "small");
    }

    @Test
    void theFluidSwimsLikeWaterAndEveryNewBlockIsMinedWithAPickaxe()
    {
        var water = read("../minecraft/tags/fluid/water").getAsJsonArray("values").toString();
        assertTrue(water.contains("nullspace:green_fluid") && water.contains("nullspace:flowing_green_fluid"));
        var pickaxe = read("../minecraft/tags/block/mineable/pickaxe").getAsJsonArray("values").toString();
        for (var block : List.of("green_gem_block", "green_gem_cluster", "salvage_turret", "repair_bay"))
            assertTrue(pickaxe.contains("nullspace:" + block), block);
    }
}
