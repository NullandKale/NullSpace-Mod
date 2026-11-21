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

    /** Zero crossings a second over a whole render: about twice the frequency of a narrow band of noise. */
    private static double rate(short[] s)
    {
        return crossings(s, 0, s.length) / (s.length / (double) SynthVoice.RATE);
    }

    private static double rms(short[] s)
    {
        double sum = 0;
        for (short v : s)
            sum += v * (double) v;
        return Math.sqrt(sum / s.length);
    }

    @Test
    void focusedNoiseIsABandAtItsFrequencyAndPitchShiftsItWithTheTone()
    {
        // Brightness 0.5 puts the filter at 60 * 2^4.25, about 1140 Hz.
        var patch = SynthPatch.parse("tone off; noise 1 0.5 0.8; env 0.001 0 0.1; loop on");
        double centre = 60 * Math.pow(2, 4.25);
        var normal = render(new SynthVoice(patch, 6), 2);
        assertEquals(2 * centre, rate(normal), centre * .5, "A focused noise crosses zero about twice a cycle of its centre");
        var raised = new SynthVoice(patch, 6);
        raised.controls(2, 0, 1, 0);
        double ratio = rate(render(raised, 2)) / rate(normal);
        assertEquals(2, ratio, .35, "Twice the pitch moves the band an octave up: " + ratio);
        var plainPatch = SynthPatch.parse("tone off; noise 1 0.3; env 0.001 0 0.1; loop on");
        var plainLow = render(new SynthVoice(plainPatch, 7), 2);
        var plainHigh = new SynthVoice(plainPatch, 7);
        plainHigh.controls(2, 0, 1, 0);
        assertTrue(rate(render(plainHigh, 2)) > rate(plainLow) * 1.2, "Plain noise moves up with the pitch too");
        double loud = rms(normal), plain = rms(plainLow);
        assertTrue(loud > plain / 2 && loud < plain * 2, "A band of noise is about as loud as plain noise: " + loud + " against " + plain);
    }

    /** How rough a sound is beside its loudness: about 2 pi f / rate for a tone of frequency f, and far more for a hiss. */
    private static double highShare(short[] s)
    {
        double diff = 0;
        for (int i = 1; i < s.length; i++)
            diff += (s[i] - s[i - 1]) * (double) (s[i] - s[i - 1]);
        return Math.sqrt(diff / (s.length - 1)) / rms(s);
    }

    @Test
    void darkNoiseRumblesWithoutAHissOverIt()
    {
        // Brightness 0.1 puts the filter near 108 Hz. With the first filter alone, plain noise kept enough highs to measure about 0.24 here and a band about
        // 0.08: a faint hiss over the rumble, which the ear hears first.
        for (String noise : new String[]{"noise 1 0.1", "noise 1 0.1 0.4"})
        {
            double high = highShare(render(new SynthVoice(SynthPatch.parse("tone off; " + noise + "; env 0.001 0 0.1; loop on"), 8), 2));
            assertTrue(high < .06, "Almost nothing is left high up in " + noise + ": " + high);
        }
        double bright = highShare(render(new SynthVoice(SynthPatch.parse("tone off; noise 1 0.82 0.25; env 0.001 0 0.1; loop on"), 8), 2));
        assertTrue(bright > 1.2, "A bright hiss keeps its highs: " + bright);
    }

    @Test
    void aLiveChangeGlidesToItsNewValueInsteadOfJumping()
    {
        var voice = new SynthVoice(SynthPatch.parse("tone sine 440 440 0 0.8; env 0.001 0 0.1; loop on"), 9);
        render(voice, .5);
        voice.controls(2, 0, 1, 0);
        // The 440 Hz tone crosses zero about 18 times in 20 ms, and at 880 Hz about 35.
        var soon = render(voice, .1);
        int early = crossings(soon, 0, SynthVoice.RATE / 50);
        assertTrue(early < 24, "Just after the change the pitch has only begun to rise: " + early + " crossings in 20 ms");
        var later = render(voice, 1.2);
        int settled = crossings(later, later.length - SynthVoice.RATE / 5, later.length);
        assertEquals(2 * 880 / 5.0, settled, 20, "A second later it has arrived at 880 Hz");
        var fresh = new SynthVoice(SynthPatch.parse("tone sine 440 440 0 0.8; env 0.001 0 0.1; loop on"), 9);
        fresh.controls(2, 0, 1, 0);
        var first = render(fresh, .2);
        assertEquals(2 * 880 / 5.0, crossings(first, 0, first.length), 20, "A value set before the voice plays applies at once");
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
