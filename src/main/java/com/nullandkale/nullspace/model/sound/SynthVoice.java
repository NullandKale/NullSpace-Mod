package com.nullandkale.nullspace.model.sound;

import java.util.SplittableRandom;

/**
 * Plays one {@link SynthPatch} as 16-bit mono samples. The patch and the live controls may change while it plays, from another thread than the one that reads
 * the samples. A one-shot ends by itself after its release; a loop sustains until {@link #release()}.
 *
 * <p>Live controls: {@code pitch} scales the frequency of the tone and of the noise's filter alike, so a machine's whole sound moves up or down together;
 * {@code brightness} shifts the noise filter on its own, {@code rate} scales the pulse, and {@code muffle}
 * (0 clear, 1 fully muffled) closes a smoothing filter over the whole voice, which is how a sound from another room or behind a hull sounds darker. The
 * loudness itself, including how much quieter muffling makes a sound, is the sound instance's volume.
 *
 * <p>The game sends live values a few times a second, in steps (a door shuts, the thrust changes). A playing voice glides to each new value over about a fifth of
 * a second instead of jumping, so walking from room to room or opening the throttle sounds continuous. Values set before the voice first plays apply at once.
 */
public final class SynthVoice
{
    public static final int RATE = 22050;
    private static final double GATE = 1 - Math.exp(-1 / (.004 * RATE));
    /** How many samples share one set of filter settings, and how far each set moves the live values toward their latest: about 0.2 s to settle. */
    private static final int STEP = 32;
    private static final double GLIDE = 1 - Math.exp(-STEP / (.2 * RATE));

    private volatile SynthPatch patch;
    private volatile float pitch = 1, brightness, rate = 1, muffle;
    private volatile boolean releasing;
    private final SplittableRandom random;
    private long sample;
    private double phase, pulse, noise, band1, band2, low1, low2, out, gate = 1, releasedAt = -1, releaseFrom, lastEnvelope;
    private boolean done, started;
    /** The live values as they are heard now, gliding toward the latest ones. */
    private double pitchNow = 1, brightNow, muffleNow;
    /** The filter settings of the current step. */
    private int untilStep;
    private boolean focused;
    private double noiseA, noiseGain, k, a1, a2, a3, b1, b2, b3, outA;

    public SynthVoice(SynthPatch patch, long seed)
    {
        this.patch = patch;
        random = new SplittableRandom(seed);
        if (patch.pulseRate() > 0)
            gate = 0;
    }

    public SynthPatch patch()
    {
        return patch;
    }

    /** Changes the settings of a playing voice (the Synth block is edited while it loops). */
    public void patch(SynthPatch next)
    {
        patch = next;
    }

    public synchronized void controls(float pitch, float brightness, float rate, float muffle)
    {
        this.pitch = Math.max(.25f, Math.min(4, pitch));
        this.brightness = Math.max(-1, Math.min(1, brightness));
        this.rate = Math.max(.25f, Math.min(4, rate));
        this.muffle = Math.max(0, Math.min(1, muffle));
        if (!started)
        {
            pitchNow = this.pitch;
            brightNow = this.brightness;
            muffleNow = this.muffle;
        }
    }

    /** Fades the voice out over its release, then it is finished. */
    public void release()
    {
        releasing = true;
    }

    public boolean finished()
    {
        return done;
    }

    /** How far the voice has played, in seconds. */
    public double time()
    {
        return sample / (double) RATE;
    }

    /** Writes up to {@code count} samples and returns how many; fewer than asked, or none, once the voice has finished. */
    public synchronized int render(short[] samples, int count)
    {
        if (done)
            return 0;
        var p = patch;
        started = true;
        double pulseRate = p.pulseRate() * rate;
        for (int i = 0; i < count; i++)
        {
            if (untilStep-- <= 0)
            {
                step(p);
                untilStep = STEP - 1;
            }
            double t = sample / (double) RATE;
            double envelope = envelope(p, t);
            if (done)
                return i;
            phase += frequency(p, t) * pitchNow / RATE;
            phase -= Math.floor(phase);
            double tone = switch (p.wave())
            {
                case SINE -> Math.sin(2 * Math.PI * phase);
                case SQUARE -> phase < .5 ? .6 : -.6;
                case OFF -> 0;
            };
            double white = random.nextDouble() * 2 - 1, shaped;
            if (focused)
            {
                double v3 = white - band2, v1 = a1 * band1 + a2 * v3, v2 = band2 + a2 * band1 + a3 * v3;
                band1 = 2 * v1 - band1;
                band2 = 2 * v2 - band2;
                shaped = k * v1;
            }
            else
            {
                noise += noiseA * (white - noise);
                shaped = noise;
            }
            double w3 = shaped - low2, w1 = b1 * low1 + b2 * w3, w2 = low2 + b2 * low1 + b3 * w3;
            low1 = 2 * w1 - low1;
            low2 = 2 * w2 - low2;
            shaped = w2;
            double value = tone * p.toneLevel() + shaped * noiseGain * p.noiseLevel();
            if (pulseRate > 0)
            {
                pulse += pulseRate / RATE;
                pulse -= Math.floor(pulse);
                gate += GATE * ((pulse < p.pulseDuty() ? 1 : 0) - gate);
                value *= gate;
            }
            out += outA * (value * envelope - out);
            samples[i] = (short) Math.round(Math.max(-1, Math.min(1, out * .7)) * 32767);
            sample++;
        }
        return count;
    }

    /** Moves the live values a step toward their latest and works out the filters for them. */
    private void step(SynthPatch p)
    {
        pitchNow += (pitch - pitchNow) * GLIDE;
        brightNow += (brightness - brightNow) * GLIDE;
        muffleNow += (muffle - muffleNow) * GLIDE;
        // The noise filter's frequency: brightness from 60 Hz to about 22 kHz, moved with the tone by pitch.
        double cutoff = Math.min(RATE * .45, 60 * Math.pow(2, Math.max(0, Math.min(1, p.brightness() + brightNow)) * 8.5) * pitchNow);
        focused = p.focus() > 0;
        noiseA = alpha(cutoff);
        // Focused noise is a band around the cutoff (a state-variable band-pass, stable at any frequency), narrower as focus rises, with unity gain at its
        // centre; its gain brings it to the loudness of the plain noise.
        double q = .8 + 12 * p.focus(), g = Math.tan(Math.PI * cutoff / RATE);
        k = 1 / q;
        a1 = 1 / (1 + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
        // A second, steep filter a little above the first stops dark noise from hissing: alone, the first filter's gentle slope leaves enough of white noise's
        // highs that a low rumble is heard as a faint hiss over it, the ear being far keener there than at the rumble. Bright noise, above most of the range,
        // is hardly changed. The gains below make up the loudness it takes away.
        double gl = Math.tan(Math.PI * Math.min(RATE * .45, cutoff * 3) / RATE), kl = Math.sqrt(2);
        b1 = 1 / (1 + gl * (gl + kl));
        b2 = gl * b1;
        b3 = gl * b2;
        if (focused)
            noiseGain = Math.min(24, .4 / Math.sqrt(Math.PI * cutoff / (q * RATE) / 3)) / (1 - .1 / q);
        else
            noiseGain = Math.min(6, .4 / (.577 * Math.sqrt(noiseA / (2 - noiseA)))) / .885;
        outA = alpha(9000 * Math.pow(250 / 9000.0, muffleNow));
    }

    private double envelope(SynthPatch p, double t)
    {
        double level;
        if (t < p.attack())
            level = t / p.attack();
        else if (p.loop() || t < p.attack() + p.hold())
            level = 1;
        else
        {
            double fade = (t - p.attack() - p.hold()) / p.release();
            level = 1 - fade;
        }
        if (releasing && releasedAt < 0)
        {
            releasedAt = t;
            releaseFrom = lastEnvelope;
        }
        if (releasedAt >= 0)
            level = Math.min(level, releaseFrom * (1 - (t - releasedAt) / p.release()));
        if (level <= 0 && (releasedAt >= 0 || !p.loop() && t >= p.attack()))
        {
            done = true;
            return 0;
        }
        lastEnvelope = level;
        return level;
    }

    private static double frequency(SynthPatch p, double t)
    {
        if (p.sweep() <= 0 || p.toneFrom() == p.toneTo())
            return p.toneFrom();
        double x;
        if (p.loop())
        {
            double u = (t % (2 * p.sweep())) / p.sweep();
            x = u <= 1 ? u : 2 - u;
        }
        else
            x = Math.min(1, t / p.sweep());
        return p.toneFrom() * Math.pow(p.toneTo() / (double) p.toneFrom(), x);
    }

    private static double alpha(double cutoff)
    {
        return 1 - Math.exp(-2 * Math.PI * Math.min(cutoff, RATE * .45) / RATE);
    }

    /** How much quieter muffling makes a sound: the share of its loudness left (1 clear, about a twentieth fully muffled). */
    public static float muffledGain(float muffle)
    {
        return (float) Math.pow(1 - Math.max(0, Math.min(1, muffle)) * .95, 1.6);
    }
}
