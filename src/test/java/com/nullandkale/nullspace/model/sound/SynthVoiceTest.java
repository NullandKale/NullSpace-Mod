package com.nullandkale.nullspace.model.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SynthVoiceTest
{
    /** Renders a voice to the end, or for {@code seconds}, in the stream's tenth-of-a-second chunks. */
    private static short[] render(SynthVoice voice, double seconds)
    {
        var out = new java.io.ByteArrayOutputStream();
        var chunk = new short[SynthVoice.RATE / 10];
        int total = 0;
        while (total < seconds * SynthVoice.RATE)
        {
            int n = voice.render(chunk, chunk.length);
            if (n == 0)
                break;
            for (int i = 0; i < n; i++)
            {
                out.write(chunk[i] & 0xff);
                out.write(chunk[i] >> 8 & 0xff);
            }
            total += n;
        }
        var bytes = out.toByteArray();
        var samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++)
            samples[i] = (short) (bytes[2 * i] & 0xff | bytes[2 * i + 1] << 8);
        return samples;
    }

    @Test
    void aOneShotEndsAfterItsEnvelope()
    {
        var patch = SynthPatch.parse("tone sine 440; env 0.05 0.1 0.2");
        var voice = new SynthVoice(patch, 1);
        var samples = render(voice, 10);
        assertTrue(voice.finished());
        assertEquals(patch.length() * SynthVoice.RATE, samples.length, SynthVoice.RATE * .02, "It lasts its attack, hold and release");
        assertTrue(java.util.stream.IntStream.range(0, samples.length).map(i -> Math.abs(samples[i])).max().orElse(0) > 10000, "It is audible");
    }

    @Test
    void aLoopSustainsUntilReleasedAndThenFades()
    {
        var voice = new SynthVoice(SynthPatch.parse("tone sine 300; env 0.05 0 0.3; loop on"), 2);
        render(voice, 3);
        assertFalse(voice.finished(), "A loop keeps playing");
        voice.release();
        var tail = render(voice, 5);
        assertTrue(voice.finished(), "Released, it finishes");
        assertEquals(.3 * SynthVoice.RATE, tail.length, SynthVoice.RATE * .12, "It fades over its release");
    }

    /** How much the signal changes from one sample to the next, on average: high for bright sound, low for muffled. */
    private static double roughness(short[] s)
    {
        double sum = 0;
        for (int i = 1; i < s.length; i++)
            sum += Math.abs(s[i] - s[i - 1]);
        return sum / (s.length - 1);
    }

    @Test
    void mufflingDarkensTheSound()
    {
        var patch = SynthPatch.parse("tone off; noise 1 1; env 0.01 0 0.1; loop on");
        var clear = new SynthVoice(patch, 3);
        var muffled = new SynthVoice(patch, 3);
        muffled.controls(1, 0, 1, 1);
        double bright = roughness(render(clear, 1)), dark = roughness(render(muffled, 1));
        assertTrue(dark < bright / 4, "A muffled voice loses its high frequencies: " + dark + " against " + bright);
        assertTrue(SynthVoice.muffledGain(1) < .1f && SynthVoice.muffledGain(0) == 1, "and most of its loudness");
    }

    @Test
    void aPulseGatesTheVoiceOnAndOff()
    {
        var voice = new SynthVoice(SynthPatch.parse("tone square 600; env 0.001 0 0.05; pulse 4 0.5; loop on"), 4);
        var samples = render(voice, 2);
        long silent = java.util.stream.IntStream.range(0, samples.length).filter(i -> Math.abs(samples[i]) < 2000).count();
        assertEquals(.5, silent / (double) samples.length, .1, "About half the time it is off");
    }

    @Test
    void aSweepMovesThePitch()
    {
        var voice = new SynthVoice(SynthPatch.parse("tone sine 100 1000 1 0.8; env 0.001 1.2 0.05"), 5);
        var samples = render(voice, 1.2);
        int early = crossings(samples, 0, SynthVoice.RATE / 10), late = crossings(samples, (int) (SynthVoice.RATE * .9), SynthVoice.RATE);
        assertTrue(late > early * 5, "The pitch rises from 100 to 1000 Hz: " + early + " then " + late + " crossings a tenth of a second");
    }

    private static int crossings(short[] s, int from, int to)
    {
        int n = 0;
        for (int i = Math.max(1, from); i < Math.min(s.length, to); i++)
            if (s[i - 1] < 0 != s[i] < 0)
                n++;
        return n;
    }
}
