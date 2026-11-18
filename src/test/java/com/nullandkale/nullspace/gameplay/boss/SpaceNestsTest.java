package com.nullandkale.nullspace.gameplay.boss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpaceNestsTest
{
    @Test
    void aNewBossComesAMinecraftDayAfterTheLastKillAndNeverWhileOneLives()
    {
        assertEquals(24000L, SpaceNests.DAY);
        long kill = 100_000L;
        assertFalse(SpaceNests.due(kill, kill + 23_999, false), "not before a day");
        assertTrue(SpaceNests.due(kill, kill + 24_000, false), "exactly a day later");
        assertTrue(SpaceNests.due(kill, kill + 500_000, false), "any time after");
        assertFalse(SpaceNests.due(kill, kill + 500_000, true), "never while the last one is alive");
    }

    @Test
    void theFirstBossIsDueAtOnceBecauseNothingHasEverBeenKilled()
    {
        assertTrue(SpaceNests.due(Long.MIN_VALUE / 2, 0, false));
        assertTrue(SpaceNests.due(Long.MIN_VALUE / 2, 5000, false));
    }
}
