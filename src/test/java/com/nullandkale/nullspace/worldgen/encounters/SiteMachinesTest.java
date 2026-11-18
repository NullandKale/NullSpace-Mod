package com.nullandkale.nullspace.worldgen.encounters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SiteMachinesTest
{
    @Test
    void onlyTheDeepestSitesCanHaveARepairBayAndMostOfThemDoNot()
    {
        int[] hits = new int[4];
        for (long seed = 0; seed < 4000; seed++)
            for (int danger = 0; danger < 4; danger++)
                if (SiteMachines.hasBay(danger, seed))
                    hits[danger]++;
        assertEquals(0, hits[0], "a site with no danger never has one");
        assertEquals(0, hits[1], "nor does danger 1");
        assertTrue(hits[2] > 600 && hits[2] < 1000, "about one in five at danger 2: " + hits[2]);
        assertTrue(hits[3] > 1600 && hits[3] < 2000, "a little under half at danger 3: " + hits[3]);
    }

    @Test
    void theSameSeedAlwaysGivesTheSameAnswer()
    {
        for (long seed = 0; seed < 50; seed++)
            assertEquals(SiteMachines.hasBay(3, seed), SiteMachines.hasBay(3, seed));
    }
}
