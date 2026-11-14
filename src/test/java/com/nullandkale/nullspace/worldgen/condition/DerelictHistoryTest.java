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
    void powerFailureUsesTheDeadVariantOnlyWhereThereAreElectronics()
    {
        for (var use : List.of("bridge", "engineering", "operations", "maintenance"))
            assertTrue(DerelictHistory.POWER_FAILURE.dead(use));
        for (var use : List.of("kitchen", "archive", "cabin", "cargo_hold"))
            assertFalse(DerelictHistory.POWER_FAILURE.dead(use));
        assertFalse(DerelictHistory.BREACH.dead("engineering"));
    }
}
