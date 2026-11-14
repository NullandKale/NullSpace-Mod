package com.nullandkale.nullspace.worldgen.encounters;

import java.util.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** How a found vessel spreads its loot tiers over its rooms. */
class DerelictTiersTest
{
    private static List<DerelictLoot.Spot> rooms(int count)
    {
        var uses = List.of("workshop", "stores", "maintenance", "bunk_room", "infirmary", "engineering", "commons", "cabin", "lab", "eva_prep", "armory", "observation", "brig", "archive", "operations", "kitchen", "lookout", "garden");
        var out = new ArrayList<DerelictLoot.Spot>();
        for (int i = 0; i < count; i++)
            out.add(new DerelictLoot.Spot(new BlockPos(i, 0, 0), uses.get(i % uses.size()) + "_" + i, uses.get(i % uses.size()), 0));
        return out;
    }

    @Test
    void belowDangerThreeEveryRoomGivesTheZonesOwnTier()
    {
        var spots = rooms(14);
        for (int danger = 0; danger <= 2; danger++)
            for (long seed = 0; seed < 20; seed++)
            {
                var tiers = DerelictLoot.tiers(spots, spots.getFirst(), danger, seed);
                assertEquals(13, tiers.size(), "The hoard room takes the treasure instead");
                for (int tier : tiers.values())
                    assertEquals(danger, tier);
            }
    }

    @Test
    void aDangerThreeStationHasFourOrFiveHighRoomsAndTheRestMedium()
    {
        var spots = rooms(16);
        var seen = new HashSet<Integer>();
        for (long seed = 0; seed < 60; seed++)
        {
            var tiers = DerelictLoot.tiers(spots, spots.getFirst(), 3, seed);
            long high = tiers.values().stream().filter(t -> t == 3).count();
            assertTrue(high == 4 || high == 5, "High rooms: " + high);
            assertEquals(tiers.size() - high, tiers.values().stream().filter(t -> t == 2).count(), "Every other room is medium");
            seen.add((int) high);
        }
        assertEquals(Set.of(4, 5), seen, "Seeds give both counts");
    }

    @Test
    void aSmallVesselKeepsAtMostHalfItsRoomsHighAndTheChoiceIsStable()
    {
        var spots = rooms(5);
        for (long seed = 0; seed < 30; seed++)
        {
            var tiers = DerelictLoot.tiers(spots, spots.getFirst(), 3, seed);
            assertTrue(tiers.values().stream().filter(t -> t == 3).count() <= 2);
            assertEquals(tiers, DerelictLoot.tiers(spots, spots.getFirst(), 3, seed));
        }
    }

    @Test
    void theFavoredRoomsAreTheHighOnes()
    {
        var spots = rooms(18);
        var tiers = DerelictLoot.tiers(spots, spots.getFirst(), 3, 7);
        for (var e : tiers.entrySet())
            if (e.getValue() == 3)
                assertTrue(List.of("armory", "foundry", "workshop", "lab", "receiving_bay", "cargo_hold", "engineering", "archive", "cabin").contains(e.getKey().use()), e.getKey().use());
    }
}
