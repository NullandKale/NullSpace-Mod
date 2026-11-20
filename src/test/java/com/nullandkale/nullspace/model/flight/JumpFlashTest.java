package com.nullandkale.nullspace.model.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JumpFlashTest
{
    @Test
    void theFlashRisesHoldsUntilTheSkyIsReadyAndFades()
    {
        assertEquals(0, JumpFlash.opacity(0, 0));
        assertEquals(JumpFlash.PEAK, JumpFlash.opacity(JumpFlash.RISE, Double.POSITIVE_INFINITY), 1e-9);
        // Still waiting for the sky: it holds at its peak.
        assertEquals(JumpFlash.PEAK, JumpFlash.opacity(.5, Double.POSITIVE_INFINITY), 1e-9);
        // The sky was ready at once: it fades right after rising.
        assertTrue(JumpFlash.opacity(JumpFlash.RISE + .3, 0) < JumpFlash.PEAK);
        assertEquals(0, JumpFlash.opacity(JumpFlash.RISE + JumpFlash.FADE, 0), 1e-9);
        // A sky that never gets ready cannot hold it longer than its limit.
        assertEquals(0, JumpFlash.opacity(JumpFlash.HOLD + JumpFlash.FADE, Double.POSITIVE_INFINITY), 1e-9);
        assertTrue(JumpFlash.over(JumpFlash.HOLD + JumpFlash.FADE));
        double previous = JumpFlash.PEAK;
        for (int step = 0; step <= 100; step++)
        {
            double opacity = JumpFlash.opacity(.4 + step / 100.0 * JumpFlash.FADE, .4);
            assertTrue(opacity <= previous + 1e-12 && opacity >= 0, "The fade only falls");
            previous = opacity;
        }
    }
}
