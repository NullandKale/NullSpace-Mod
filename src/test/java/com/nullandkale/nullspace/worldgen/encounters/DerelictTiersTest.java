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
    void theHoardRoomTakesTheTreasureAndEveryOtherRoomItsOwnTierOfTable()
    {
        var points = new ArrayList<com.nullandkale.nullspace.worldgen.piece.Piece.Point>();
        points.add(new com.nullandkale.nullspace.worldgen.piece.Piece.Point(new BlockPos(1, 0, 0), "roomloot", "kitchen_1 kitchen 0"));
        points.add(new com.nullandkale.nullspace.worldgen.piece.Piece.Point(new BlockPos(2, 0, 0), "roomloot", "hold_1 cargo_hold 0"));
        points.add(new com.nullandkale.nullspace.worldgen.piece.Piece.Point(new BlockPos(3, 0, 0), "roomloot", "engine_1 engineering 0"));
        var base = new BlockPos(100, 64, 100);
        var out = DerelictLoot.assignments(base, points, "treasure_2", 2, 5, "Test");
        assertEquals(3, out.size(), "one chest per marked room");
        var byPos = new HashMap<BlockPos, String>();
        for (var a : out)
            byPos.put(a.pos(), a.table());
        assertEquals("treasure_2", byPos.get(base.offset(2, 0, 0)), "the cargo hold is the hoard");
        assertEquals("derelict/kitchen_2", byPos.get(base.offset(1, 0, 0)));
        // Engineering has a power-failure variant; which one depends on the site's history.
        var engineering = byPos.get(base.offset(3, 0, 0));
        assertTrue(engineering.equals("derelict/engineering_2") || engineering.equals("derelict/engineering_dead_2"), engineering);
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
