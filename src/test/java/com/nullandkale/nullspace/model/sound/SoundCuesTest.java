package com.nullandkale.nullspace.model.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoundCuesTest
{
    @Test
    void everyCueTheGamePlaysIsInTheBundledFile()
    {
        for (String name : new String[]{"ftl.jolt", "ftl.drone", "ftl.arrive", "air.hiss", "air.refill", "air.leak", "alarm.lowair", "alarm.breach", "engine.hum", "dock.clamp", "ui.blip", "ui.error"})
            assertFalse(SoundCues.get(name).isEmpty(), name);
        assertTrue(SoundCues.preset("ftl.drone").loop());
        assertNull(SoundCues.preset("made.up"));
    }

    @Test
    void aCueCanLayerNotesWithDelays()
    {
        var chime = SoundCues.get("ftl.arrive");
        assertEquals(2, chime.size());
        assertEquals(0, chime.get(0).delay(), 1e-6);
        assertTrue(chime.get(1).delay() > 0 && chime.get(1).patch().toneFrom() > chime.get(0).patch().toneFrom(), "The second note comes after the first and higher");
        var parsed = SoundCues.parse("{\"a\": \"tone sine 200\", \"b\": [\"delay 0.5; noise 0.4 0.2\"]}");
        assertEquals(200, parsed.get("a").getFirst().patch().toneFrom(), 1e-4);
        assertEquals(.5f, parsed.get("b").getFirst().delay(), 1e-6);
    }
}
