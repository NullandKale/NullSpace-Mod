package com.nullandkale.nullspace.model.sound;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One synthesizer voice's settings: a tone (sine or square, sweeping from one pitch to another) and white noise through a smoothing filter, shaped by an
 * envelope and an optional pulse. A looping patch sustains until it is stopped, and a sweep in a loop goes back and forth, for sirens and wavering whistles.
 *
 * <p>Its text form is what the cue file, the Synth block's {@code dump} and the terminal use: clauses separated by {@code ;}, for example
 * {@code tone square 880 440 0.4 0.6; noise 0.3 0.8; env 0.01 0.1 0.5; pulse 4 0.5; loop on}. A clause that is left out keeps its default.
 */
public record SynthPatch(Wave wave, float toneFrom, float toneTo, float sweep, float toneLevel, float noiseLevel, float brightness, float attack, float hold, float release, float pulseRate, float pulseDuty, boolean loop)
{
    public enum Wave
    {
        OFF, SINE, SQUARE
    }

    public static final SynthPatch DEFAULT = new SynthPatch(Wave.SINE, 440, 440, 0, .6f, 0, .5f, .01f, .2f, .3f, 0, .5f, false);

    public SynthPatch
    {
        toneFrom = clamp(toneFrom, 20, 8000);
        toneTo = clamp(toneTo, 20, 8000);
        sweep = clamp(sweep, 0, 10);
        toneLevel = clamp(toneLevel, 0, 1);
        noiseLevel = clamp(noiseLevel, 0, 1);
        brightness = clamp(brightness, 0, 1);
        attack = clamp(attack, .001f, 10);
        hold = clamp(hold, 0, 10);
        release = clamp(release, .001f, 10);
        pulseRate = clamp(pulseRate, 0, 30);
        pulseDuty = clamp(pulseDuty, .05f, .95f);
    }

    private static float clamp(float value, float low, float high)
    {
        return Float.isNaN(value) ? low : Math.max(low, Math.min(high, value));
    }

    /** How long a one-shot lasts, in seconds; a loop lasts until it is stopped. */
    public float length()
    {
        return attack + hold + release;
    }

    public String text()
    {
        return String.format(Locale.ROOT, "tone %s %s %s %s %s; noise %s %s; env %s %s %s; pulse %s %s; loop %s", wave.name().toLowerCase(Locale.ROOT), number(toneFrom), number(toneTo), number(sweep), number(toneLevel), number(noiseLevel), number(brightness), number(attack), number(hold), number(release), number(pulseRate), number(pulseDuty), loop ? "on" : "off");
    }

    private static String number(float value)
    {
        String text = String.format(Locale.ROOT, "%.3f", value);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Reads a patch from its text form, starting from the default for clauses it leaves out. */
    public static SynthPatch parse(String text)
    {
        return DEFAULT.with(text);
    }

    /**
     * This patch with the clauses in {@code text} applied, the terminal's way of changing one part: {@code tone square 880 440 0.4 0.6}, {@code noise 0.3 0.8},
     * {@code env 0.01 0.1 0.5}, {@code pulse 4 0.5}, {@code loop on}. A clause may give fewer numbers than it takes; the rest stay as they were.
     */
    public SynthPatch with(String text)
    {
        var p = this;
        for (String clause : text.split(";"))
        {
            var words = clause.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (words.length == 0 || words[0].isEmpty())
                continue;
            switch (words[0])
            {
                case "tone" ->
                {
                    var wave = words.length > 1 ? wave(words[1]) : p.wave;
                    p = new SynthPatch(wave, at(words, 2, p.toneFrom), at(words, 3, words.length > 2 && words.length <= 3 ? at(words, 2, p.toneTo) : p.toneTo), at(words, 4, p.sweep), at(words, 5, p.toneLevel), p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                }
                case "noise" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, at(words, 1, p.noiseLevel), at(words, 2, p.brightness), p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "env" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, at(words, 1, p.attack), at(words, 2, p.hold), at(words, 3, p.release), p.pulseRate, p.pulseDuty, p.loop);
                case "pulse" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, at(words, 1, p.pulseRate), at(words, 2, p.pulseDuty), p.loop);
                case "loop" ->
                {
                    if (words.length < 2 || !(words[1].equals("on") || words[1].equals("off")))
                        throw new IllegalArgumentException("loop on or loop off");
                    p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, words[1].equals("on"));
                }
                // One setting at a time, for the Synth block's buttons and quick terminal changes: wave, from, to, sweep, tonelevel, noiselevel, bright,
                // attack, hold, release, rate, duty.
                case "wave" -> p = new SynthPatch(words.length > 1 ? wave(words[1]) : p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "from" -> p = new SynthPatch(p.wave, at(words, 1, p.toneFrom), p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "to" -> p = new SynthPatch(p.wave, p.toneFrom, at(words, 1, p.toneTo), p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "sweep" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, at(words, 1, p.sweep), p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "tonelevel" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, at(words, 1, p.toneLevel), p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "noiselevel" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, at(words, 1, p.noiseLevel), p.brightness, p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "bright" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, at(words, 1, p.brightness), p.attack, p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "attack" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, at(words, 1, p.attack), p.hold, p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "hold" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, at(words, 1, p.hold), p.release, p.pulseRate, p.pulseDuty, p.loop);
                case "release" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, at(words, 1, p.release), p.pulseRate, p.pulseDuty, p.loop);
                case "rate" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, at(words, 1, p.pulseRate), p.pulseDuty, p.loop);
                case "duty" -> p = new SynthPatch(p.wave, p.toneFrom, p.toneTo, p.sweep, p.toneLevel, p.noiseLevel, p.brightness, p.attack, p.hold, p.release, p.pulseRate, at(words, 1, p.pulseDuty), p.loop);
                default -> throw new IllegalArgumentException("Unknown synth setting: " + words[0] + " (tone, noise, env, pulse, loop, or one of wave, from, to, sweep, tonelevel, noiselevel, bright, attack, hold, release, rate, duty)");
            }
        }
        return p;
    }

    private static Wave wave(String word)
    {
        return switch (word)
        {
            case "off", "none" -> Wave.OFF;
            case "sine", "sin" -> Wave.SINE;
            case "square", "sq" -> Wave.SQUARE;
            default -> throw new IllegalArgumentException("Tone wave is off, sine or square, not " + word);
        };
    }

    private static float at(String[] words, int index, float fallback)
    {
        if (index >= words.length)
            return fallback;
        try
        {
            return Float.parseFloat(words[index]);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Not a number: " + words[index]);
        }
    }

    public CompoundTag save()
    {
        var tag = new CompoundTag();
        tag.putString("patch", text());
        return tag;
    }

    public static SynthPatch read(CompoundTag tag)
    {
        try
        {
            return tag.contains("patch") ? parse(tag.getStringOr("patch", "")) : DEFAULT;
        }
        catch (IllegalArgumentException damaged)
        {
            return DEFAULT;
        }
    }

    public void write(FriendlyByteBuf buffer)
    {
        buffer.writeVarInt(wave.ordinal());
        for (float value : new float[]{toneFrom, toneTo, sweep, toneLevel, noiseLevel, brightness, attack, hold, release, pulseRate, pulseDuty})
            buffer.writeFloat(value);
        buffer.writeBoolean(loop);
    }

    public static SynthPatch read(FriendlyByteBuf buffer)
    {
        var wave = Wave.values()[Math.floorMod(buffer.readVarInt(), Wave.values().length)];
        float[] v = new float[11];
        for (int i = 0; i < v.length; i++)
            v[i] = buffer.readFloat();
        return new SynthPatch(wave, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], buffer.readBoolean());
    }
}
