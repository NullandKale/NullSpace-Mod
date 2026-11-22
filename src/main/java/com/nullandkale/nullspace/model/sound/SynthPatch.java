package com.nullandkale.nullspace.model.sound;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One synthesizer voice's settings: a tone (sine or square, sweeping from one pitch to another) and white noise through a filter, shaped by an envelope and an
 * optional pulse. A looping patch sustains until it is stopped, and a sweep in a loop goes back and forth. The game's own cues never loop a sweep or a square
 * wave (no sirens, no buzz); a player's Synth may.
 *
 * <p>The noise's {@code brightness} sets its filter's frequency (dark rumble to bright hiss) and its {@code focus} the filter's kind: at 0 a smoothing filter
 * that lets everything below that frequency through, above 0 a band around it that narrows as focus rises, so the noise has a pitch of its own (a machine's
 * whine or a pitched whoosh) that can be heard to move when the sound is shifted.
 *
 * <p>Its text form is what the cue file, the Synth block's {@code dump} and the terminal use: clauses separated by {@code ;}, for example
 * {@code tone square 880 440 0.4 0.6; noise 0.3 0.8 0.5; env 0.01 0.1 0.5; pulse 4 0.5; loop on}. A clause that is left out keeps its default, and the noise's
 * focus is written only when it is above 0.
 */
public record SynthPatch(Wave wave, float toneFrom, float toneTo, float sweep, float toneLevel, float noiseLevel, float brightness, float focus, float attack, float hold, float release, float pulseRate, float pulseDuty, boolean loop)
{
    public enum Wave
    {
        OFF, SINE, SQUARE
    }

    public static final SynthPatch DEFAULT = new SynthPatch(Wave.SINE, 440, 440, 0, .6f, 0, .5f, 0, .01f, .2f, .3f, 0, .5f, false);

    /** The numbers of a patch in order, and the single settings that name them (the Synth block's buttons and quick terminal changes). */
    private static final int FROM = 0, TO = 1, SWEEP = 2, TONE_LEVEL = 3, NOISE_LEVEL = 4, BRIGHT = 5, FOCUS = 6, ATTACK = 7, HOLD = 8, RELEASE = 9, RATE = 10, DUTY = 11;
    private static final Map<String, Integer> SINGLE = Map.ofEntries(Map.entry("from", FROM), Map.entry("to", TO), Map.entry("sweep", SWEEP), Map.entry("tonelevel", TONE_LEVEL), Map.entry("noiselevel", NOISE_LEVEL), Map.entry("bright", BRIGHT), Map.entry("focus", FOCUS), Map.entry("attack", ATTACK), Map.entry("hold", HOLD), Map.entry("release", RELEASE), Map.entry("rate", RATE), Map.entry("duty", DUTY));

    /** The settings that can be changed one at a time, for the terminal's command list. */
    public static final List<String> SETTINGS = List.of("wave", "from", "to", "sweep", "tonelevel", "noiselevel", "bright", "focus", "attack", "hold", "release", "rate", "duty");

    public SynthPatch
    {
        toneFrom = clamp(toneFrom, 20, 8000);
        toneTo = clamp(toneTo, 20, 8000);
        sweep = clamp(sweep, 0, 10);
        toneLevel = clamp(toneLevel, 0, 1);
        noiseLevel = clamp(noiseLevel, 0, 1);
        brightness = clamp(brightness, 0, 1);
        focus = clamp(focus, 0, 1);
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

    private float[] values()
    {
        return new float[]{toneFrom, toneTo, sweep, toneLevel, noiseLevel, brightness, focus, attack, hold, release, pulseRate, pulseDuty};
    }

    private static SynthPatch of(Wave wave, float[] v, boolean loop)
    {
        return new SynthPatch(wave, v[FROM], v[TO], v[SWEEP], v[TONE_LEVEL], v[NOISE_LEVEL], v[BRIGHT], v[FOCUS], v[ATTACK], v[HOLD], v[RELEASE], v[RATE], v[DUTY], loop);
    }

    /** How long a one-shot lasts, in seconds; a loop lasts until it is stopped. */
    public float length()
    {
        return attack + hold + release;
    }

    public String text()
    {
        return String.format(Locale.ROOT, "tone %s %s %s %s %s; noise %s %s%s; env %s %s %s; pulse %s %s; loop %s", wave.name().toLowerCase(Locale.ROOT), number(toneFrom), number(toneTo), number(sweep), number(toneLevel), number(noiseLevel), number(brightness), focus > 0 ? " " + number(focus) : "", number(attack), number(hold), number(release), number(pulseRate), number(pulseDuty), loop ? "on" : "off");
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
     * This patch with the clauses in {@code text} applied, the terminal's way of changing one part: {@code tone square 880 440 0.4 0.6}, {@code noise 0.3 0.8 0.5},
     * {@code env 0.01 0.1 0.5}, {@code pulse 4 0.5}, {@code loop on}, or one setting alone ({@link #SETTINGS}, {@code from 660}). A clause may give fewer numbers
     * than it takes; the rest stay as they were.
     */
    public SynthPatch with(String text)
    {
        var p = this;
        for (String clause : text.split(";"))
        {
            var words = clause.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (words.length == 0 || words[0].isEmpty())
                continue;
            var wave = p.wave;
            var v = p.values();
            boolean loop = p.loop;
            switch (words[0])
            {
                case "tone" ->
                {
                    if (words.length > 1)
                        wave = wave(words[1]);
                    // One frequency alone sets both ends: a steady tone.
                    v[TO] = at(words, 3, words.length == 3 ? at(words, 2, v[TO]) : v[TO]);
                    v[FROM] = at(words, 2, v[FROM]);
                    v[SWEEP] = at(words, 4, v[SWEEP]);
                    v[TONE_LEVEL] = at(words, 5, v[TONE_LEVEL]);
                }
                case "noise" ->
                {
                    v[NOISE_LEVEL] = at(words, 1, v[NOISE_LEVEL]);
                    v[BRIGHT] = at(words, 2, v[BRIGHT]);
                    v[FOCUS] = at(words, 3, v[FOCUS]);
                }
                case "env" ->
                {
                    v[ATTACK] = at(words, 1, v[ATTACK]);
                    v[HOLD] = at(words, 2, v[HOLD]);
                    v[RELEASE] = at(words, 3, v[RELEASE]);
                }
                case "pulse" ->
                {
                    v[RATE] = at(words, 1, v[RATE]);
                    v[DUTY] = at(words, 2, v[DUTY]);
                }
                case "loop" ->
                {
                    if (words.length < 2 || !(words[1].equals("on") || words[1].equals("off")))
                        throw new IllegalArgumentException("loop on or loop off");
                    loop = words[1].equals("on");
                }
                case "wave" ->
                {
                    if (words.length > 1)
                        wave = wave(words[1]);
                }
                default ->
                {
                    var index = SINGLE.get(words[0]);
                    if (index == null)
                        throw new IllegalArgumentException("Unknown synth setting: " + words[0] + " (tone, noise, env, pulse, loop, or one of " + String.join(", ", SETTINGS) + ")");
                    v[index] = at(words, 1, v[index]);
                }
            }
            p = of(wave, v, loop);
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
        for (float value : values())
            buffer.writeFloat(value);
        buffer.writeBoolean(loop);
    }

    public static SynthPatch read(FriendlyByteBuf buffer)
    {
        var wave = Wave.values()[Math.floorMod(buffer.readVarInt(), Wave.values().length)];
        float[] v = new float[DUTY + 1];
        for (int i = 0; i < v.length; i++)
            v[i] = buffer.readFloat();
        return of(wave, v, buffer.readBoolean());
    }
}
