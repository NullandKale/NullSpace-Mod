package com.nullandkale.nullspace.model.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SynthPatchTest
{
    @Test
    void theTextFormReadsBackToTheSamePatch()
    {
        var patch = SynthPatch.parse("tone square 880 440 0.4 0.6; noise 0.3 0.8; env 0.01 0.1 0.5; pulse 4 0.5; loop on");
        assertEquals(SynthPatch.Wave.SQUARE, patch.wave());
        assertEquals(880, patch.toneFrom(), 1e-4);
        assertEquals(440, patch.toneTo(), 1e-4);
        assertEquals(.4f, patch.sweep(), 1e-4);
        assertEquals(.3f, patch.noiseLevel(), 1e-4);
        assertEquals(4, patch.pulseRate(), 1e-4);
        assertTrue(patch.loop());
        assertEquals(patch, SynthPatch.parse(patch.text()));
    }

    @Test
    void clausesLeftOutKeepTheirValuesAndOneSettingChangesAlone()
    {
        var patch = SynthPatch.parse("noise 0.5 0.2");
        assertEquals(SynthPatch.DEFAULT.toneFrom(), patch.toneFrom());
        assertEquals(.5f, patch.noiseLevel(), 1e-4);
        var one = SynthPatch.parse("tone sine 300");
        assertEquals(300, one.toneFrom(), 1e-4);
        assertEquals(300, one.toneTo(), 1e-4, "A single pitch is both ends of the tone");
        var changed = patch.with("from 220").with("bright 0.9").with("attack 0.2").with("duty 0.25").with("wave square").with("loop on");
        assertEquals(220, changed.toneFrom(), 1e-4);
        assertEquals(.9f, changed.brightness(), 1e-4);
        assertEquals(.2f, changed.attack(), 1e-4);
        assertEquals(.25f, changed.pulseDuty(), 1e-4);
        assertEquals(SynthPatch.Wave.SQUARE, changed.wave());
        assertTrue(changed.loop());
        assertEquals(.5f, changed.noiseLevel(), 1e-4, "Other settings stay as they were");
    }

    @Test
    void valuesAreKeptInRangeAndNonsenseIsRefused()
    {
        var wild = SynthPatch.parse("tone sine 99999 1; noise 5 -2; pulse 100 2");
        assertEquals(8000, wild.toneFrom(), 1e-4);
        assertEquals(20, wild.toneTo(), 1e-4);
        assertEquals(1, wild.noiseLevel(), 1e-4);
        assertEquals(0, wild.brightness(), 1e-4);
        assertEquals(30, wild.pulseRate(), 1e-4);
        assertEquals(.95f, wild.pulseDuty(), 1e-4);
        assertThrows(IllegalArgumentException.class, () -> SynthPatch.parse("tone saw 440"));
        assertThrows(IllegalArgumentException.class, () -> SynthPatch.parse("volume 3"));
        assertThrows(IllegalArgumentException.class, () -> SynthPatch.parse("noise loud"));
        assertThrows(IllegalArgumentException.class, () -> SynthPatch.parse("loop maybe"));
    }

    @Test
    void theNoiseFocusIsAFourthNumberWrittenOnlyWhenSet()
    {
        var plain = SynthPatch.parse("noise 0.3 0.8");
        assertEquals(0, plain.focus(), 1e-4);
        assertTrue(plain.text().contains("noise 0.3 0.8;"), "A plain noise writes two numbers, as before: " + plain.text());
        var focused = plain.with("noise 0.3 0.8 0.6");
        assertEquals(.6f, focused.focus(), 1e-4);
        assertTrue(focused.text().contains("noise 0.3 0.8 0.6;"), focused.text());
        assertEquals(focused, SynthPatch.parse(focused.text()));
        assertEquals(.25f, focused.with("focus 0.25").focus(), 1e-4, "It can be changed alone");
        assertEquals(.6f, focused.with("noise 0.5").focus(), 1e-4, "A shorter noise clause keeps it");
        assertEquals(1, plain.with("focus 7").focus(), 1e-4, "It is kept in range");
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        focused.write(buffer);
        assertEquals(focused, SynthPatch.read(buffer), "It travels to the client");
    }
}
