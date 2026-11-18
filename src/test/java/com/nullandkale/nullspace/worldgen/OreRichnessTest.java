package com.nullandkale.nullspace.worldgen;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OreRichnessTest
{
    private static double average(int band)
    {
        var random = RandomSource.create(42);
        long total = 0;
        int samples = 40000;
        for (int i = 0; i < samples; i++)
            total += OreRichness.attempts(band, random);
        return total / (double) samples;
    }

    @Test
    void theFartherTheWorldTheMoreAttemptsEachOreGets()
    {
        double[] expected = {1.0, 1.5, 2.0, 3.0};
        double last = 0;
        for (int band = 0; band < 4; band++)
        {
            double mean = average(band);
            assertEquals(expected[band], mean, 0.03, "band " + band);
            assertTrue(mean > last, "band " + band + " is richer than the one before");
            last = mean;
        }
    }

    @Test
    void aTerrestrialWorldKeepsItsOreUntouched()
    {
        var random = RandomSource.create(7);
        for (int i = 0; i < 200; i++)
        {
            assertEquals(1, OreRichness.attempts(0, random));
            assertEquals(37, OreRichness.lifted(0, 37, 90, random), "band 0 never lifts an attempt");
        }
    }

    @Test
    void veinsRiseTowardTheSurfaceButNeverBreakThroughItOrSinkBack()
    {
        var random = RandomSource.create(11);
        for (int band = 1; band < 4; band++)
        {
            int highest = Integer.MIN_VALUE;
            for (int i = 0; i < 2000; i++)
            {
                int y = OreRichness.lifted(band, 20, 120, random);
                assertTrue(y >= 20, "never lower than the vanilla height");
                assertTrue(y <= 120 - 3, "never into the open air: " + y);
                assertTrue(y <= 20 + OreRichness.LIFT[band], "never more than the band's lift: " + y);
                highest = Math.max(highest, y);
            }
            assertTrue(highest > 20 + OreRichness.LIFT[band] / 2, "band " + band + " lifts attempts a good way");
        }
        for (int i = 0; i < 200; i++)
            assertEquals(100, OreRichness.lifted(3, 100, 101, random), "an attempt already above the cover is left alone");
    }

    @Test
    void theLiftGrowsWithDistance()
    {
        for (int band = 1; band < 4; band++)
            assertTrue(OreRichness.LIFT[band] > OreRichness.LIFT[band - 1]);
    }
}
