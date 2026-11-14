package com.nullandkale.nullspace.worldgen.condition;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DerelictHistoryTest
{
    @Test
    void dangerChoosesWhichHistoriesAreAvailable()
    {
        for (int danger = 0; danger <= 3; danger++)
        {
            var seen = EnumSet.noneOf(DerelictHistory.class);
            for (long seed = 0; seed < 200; seed++)
                seen.add(DerelictHistory.of(seed, danger));
            switch (danger)
            {
                case 0, 1 -> assertEquals(EnumSet.of(DerelictHistory.EVACUATED, DerelictHistory.INTERRUPTED_WORK), seen);
                case 2 -> assertEquals(EnumSet.of(DerelictHistory.BREACH, DerelictHistory.POWER_FAILURE, DerelictHistory.INTERRUPTED_WORK), seen);
                default -> assertEquals(EnumSet.of(DerelictHistory.BREACH, DerelictHistory.POWER_FAILURE), seen);
            }
        }
    }

    @Test
    void theSameSiteAlwaysHasTheSameHistoryAndWound()
    {
        for (long seed = 0; seed < 50; seed++)
        {
            assertEquals(DerelictHistory.of(seed, 2), DerelictHistory.of(seed, 2));
            assertEquals(DerelictHistory.wound(seed, "lab_1"), DerelictHistory.wound(seed, "lab_1"));
        }
    }

    @Test
    void aHistoryNeverMovesATierByMoreThanOneAndABreachedRoomIsNeverEmptierThanAnIntactOne()
    {
        var uses = List.of("bridge", "engineering", "workshop", "kitchen", "archive", "cabin", "cargo_hold");
        for (var history : DerelictHistory.values())
            for (int danger = 0; danger <= 3; danger++)
                for (long seed = 0; seed < 30; seed++)
                    for (var use : uses)
                        for (int room = 0; room < 6; room++)
                        {
                            int tier = history.tier(danger, seed, use + "_" + room, use, false);
                            assertTrue(tier >= 0 && tier <= 3 && Math.abs(tier - danger) <= 1, history + " " + danger + " -> " + tier);
                            if (history == DerelictHistory.BREACH || history == DerelictHistory.INTERRUPTED_WORK)
                                assertTrue(tier >= danger, "A breach or interrupted work never lowers a room");
                        }
    }

    @Test
    void evacuatedLowersAboutHalfTheRoomsButNeverTheArchiveOrTheHoard()
    {
        int lowered = 0, total = 0;
        for (long seed = 0; seed < 40; seed++)
            for (int room = 0; room < 20; room++)
            {
                assertEquals(2, DerelictHistory.EVACUATED.tier(2, seed, "archive_" + room, "archive", false));
                assertEquals(2, DerelictHistory.EVACUATED.tier(2, seed, "lab_" + room, "lab", true));
                lowered += DerelictHistory.EVACUATED.tier(2, seed, "stores_" + room, "stores", false) == 1 ? 1 : 0;
                total++;
            }
        assertEquals(.5, lowered / (double) total, .08);
    }

    @Test
    void powerFailureUsesTheDeadVariantOnlyWhereThereAreElectronics()
    {
        for (var use : List.of("bridge", "engineering", "operations", "maintenance"))
            assertTrue(DerelictHistory.POWER_FAILURE.dead(use));
        for (var use : List.of("kitchen", "archive", "cabin", "cargo_hold"))
            assertFalse(DerelictHistory.POWER_FAILURE.dead(use));
        assertFalse(DerelictHistory.BREACH.dead("engineering"));
    }
}
