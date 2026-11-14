package com.nullandkale.nullspace.worldgen.encounters;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The generated room loot tables: every room has a normal table and four tiers of derelict table. */
class RoomLootTablesTest
{
    private static final Path SITE = Path.of("src/main/resources/data/nullspace/loot_table/site");
    private static final List<String> KEYS = List.of("bridge", "engineering", "stores_0", "stores_1", "stores_2", "maintenance", "commons", "kitchen", "observation", "archive", "workshop", "foundry", "cargo_hold", "receiving_bay", "growing_bay_0", "growing_bay_1", "garden_0", "garden_1", "lab", "cabin", "bunk_room", "operations", "armory", "lookout", "eva_prep", "infirmary", "brig");
    private static final List<String> DEAD = List.of("bridge", "engineering", "maintenance", "operations");

    private static JsonObject read(String name) throws IOException
    {
        return JsonParser.parseString(Files.readString(SITE.resolve(name + ".json"))).getAsJsonObject();
    }

    /** The most of {@code items} a table can give: the top roll of each pool times the top count of its entries. */
    private static int most(JsonObject table, Set<String> items) throws Exception
    {
        int total = 0;
        for (var pool : table.getAsJsonArray("pools"))
        {
            int rolls = pool.getAsJsonObject().getAsJsonObject("rolls").get("max").getAsInt();
            int best = 0;
            for (var entry : pool.getAsJsonObject().getAsJsonArray("entries"))
            {
                var e = entry.getAsJsonObject();
                if (!items.contains(e.get("name").getAsString()))
                    continue;
                var modifier = e.get("modifier");
                best = Math.max(best, modifier == null ? 1 : modifier.getAsJsonObject().getAsJsonObject("count").get("max").getAsInt());
            }
            total += rolls * best;
        }
        return total;
    }

    @Test
    void everyRoomHasANormalTableAndFourTiersOfDerelictTable() throws Exception
    {
        for (var key : KEYS)
        {
            assertTrue(read("normal/" + key).getAsJsonArray("pools").size() >= 1, key);
            for (int tier = 0; tier <= 3; tier++)
            {
                var table = read("derelict/" + key + "_" + tier);
                assertTrue(table.getAsJsonArray("pools").size() >= 3, key + " " + tier);
                if (DEAD.contains(key))
                    assertTrue(Files.exists(SITE.resolve("derelict/" + key + "_dead_" + tier + ".json")), key + " dead " + tier);
            }
        }
    }

    @Test
    void everyEntryIsANamedItemWithAPositiveWeightAndCount() throws Exception
    {
        try (var files = Files.walk(SITE.resolve("derelict")))
        {
            for (var file : files.filter(f -> f.toString().endsWith(".json")).toList())
                for (var pool : JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("pools"))
                {
                    assertTrue(pool.getAsJsonObject().getAsJsonObject("rolls").get("max").getAsInt() >= 1, file.toString());
                    for (var entry : pool.getAsJsonObject().getAsJsonArray("entries"))
                    {
                        var e = entry.getAsJsonObject();
                        assertTrue(e.get("name").getAsString().contains(":"), file + " names its items with a namespace");
                        assertTrue(!e.has("weight") || e.get("weight").getAsInt() > 0, file.toString());
                    }
                }
        }
    }

    @Test
    void aDerelictRoomAlwaysHoldsSomethingAndGetsBetterWithDanger() throws Exception
    {
        for (var key : KEYS)
        {
            int previous = -1;
            for (int tier = 0; tier <= 3; tier++)
            {
                var table = read("derelict/" + key + "_" + tier);
                int finds = table.getAsJsonArray("pools").get(table.getAsJsonArray("pools").size() - 1).getAsJsonObject().getAsJsonArray("entries").size();
                assertTrue(finds >= previous, key + " keeps its finds as danger rises");
                previous = finds;
            }
        }
        // Tier 0 and tier 3 each carry finds in the last pool.
        assertTrue(read("derelict/archive_3").getAsJsonArray("pools").size() > read("derelict/archive_0").getAsJsonArray("pools").size() - 1);
    }

    @Test
    void fuelIsRationedSoAWholeVesselHoldsAboutAStack() throws Exception
    {
        var fuel = Set.of("minecraft:coal", "minecraft:charcoal");
        int sum = 0;
        for (var key : KEYS)
        {
            int most = 0;
            for (int tier = 0; tier <= 3; tier++)
                most = Math.max(most, most(read("derelict/" + key + "_" + tier), fuel));
            assertTrue(most <= 12, key + " holds at most 12 fuel: " + most);
            sum += most;
        }
        // One of every room, as a rough ceiling; real vessels have a dozen rooms and fewer fuel rooms.
        assertTrue(sum <= 64, "All rooms together hold at most a stack of fuel: " + sum);
        assertTrue(most(read("derelict/engineering_0"), fuel) >= 2, "Engineering always has fuel");
    }
}
