package com.nullandkale.nullspace.gameplay.systems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnginePlumeTest
{
    @Test
    void theHarderTheBurnTheLongerAndBiggerThePlume()
    {
        var previous = EngineEffects.plume(0, 0);
        for (int step = 1; step <= 10; step++)
        {
            var now = EngineEffects.plume(step / 10.0, 0);
            assertTrue(now.length() > previous.length() && now.size() >= previous.size(), "More thrust, bigger plume at " + step);
            previous = now;
        }
    }

    @Test
    void aFasterShipTrailsALongerFlameAtTheSameThrust()
    {
        var slow = EngineEffects.plume(.5, 0);
        var fast = EngineEffects.plume(.5, 1);
        assertTrue(fast.length() > slow.length() && fast.size() > slow.size() && fast.density() >= slow.density());
        assertEquals(fast, EngineEffects.plume(.5, 50), "Past full speed the plume stops growing");
    }

    @Test
    void eachEngineUpgradeTierBurnsADifferentColour()
    {
        var seen = new java.util.HashSet<Integer>();
        for (int tier = 0; tier <= 4; tier++)
        {
            var palette = EngineEffects.palette(tier);
            assertTrue(seen.add(palette.hotTo()) && seen.add(palette.warmTo() + 0x1000000), "Tier " + tier + " has its own colours");
        }
        // The hue is what tells the tiers apart: amber, blue, violet and green.
        assertEquals(0xFF9A30, EngineEffects.palette(0).hotTo());
        assertTrue((EngineEffects.palette(1).hotTo() & 255) > (EngineEffects.palette(1).hotTo() >> 16 & 255), "Tier 1 is blue");
        assertTrue((EngineEffects.palette(2).hotTo() & 255) > (EngineEffects.palette(2).hotTo() >> 8 & 255), "Tier 2 is violet");
        assertTrue((EngineEffects.palette(3).hotTo() >> 8 & 255) > (EngineEffects.palette(3).hotTo() >> 16 & 255), "Tier 3 is green");
    }

    @Test
    void evenTheSmallestPlumeIsLargeAndNoPlumeIsHuge()
    {
        for (double thrust : new double[]{0, .05, .5, 1, 9})
            for (double speed : new double[]{0, .3, 1, 100})
            {
                var plume = EngineEffects.plume(thrust, speed);
                assertTrue(plume.length() >= 4 && plume.length() <= 12 + 1e-9, "Length in range: " + plume);
                assertTrue(plume.size() >= 2.2f && plume.size() <= 4f, "Size in range, never small: " + plume);
                assertTrue(plume.density() >= 1 && plume.density() <= 3, "Density in range: " + plume);
            }
    }
}
