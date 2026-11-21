package com.nullandkale.nullspace.model.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoundCuesTest
{
    @Test
    void everyCueTheGamePlaysIsInTheBundledFile()
    {
        for (String name : new String[]{"ship.hum", "ftl.jolt", "ftl.drone", "air.hiss", "air.refill", "air.leak", "alarm.lowair", "alarm.breach", "engine.hum", "dock.clamp", "dock.release", "rcs.puff", "weapon.fire", "turret.fire", "hull.impact"})
            assertFalse(SoundCues.get(name).isEmpty(), name);
        assertTrue(SoundCues.preset("ftl.drone").loop() && SoundCues.preset("ship.hum").loop());
        assertNull(SoundCues.preset("made.up"));
    }

    @Test
    void aCueCanLayerNotesWithDelays()
    {
        var clamp = SoundCues.get("dock.clamp");
        assertEquals(2, clamp.size());
        assertEquals(0, clamp.get(0).delay(), 1e-6);
        assertTrue(clamp.get(1).delay() > 0 && clamp.get(1).patch().wave() == SynthPatch.Wave.OFF, "The thump, then after it a hiss");
        var parsed = SoundCues.parse("{\"a\": \"tone sine 200\", \"b\": [\"delay 0.5; noise 0.4 0.2\"]}");
        assertEquals(200, parsed.get("a").getFirst().patch().toneFrom(), 1e-4);
        assertEquals(.5f, parsed.get("b").getFirst().delay(), 1e-6);
    }
}
