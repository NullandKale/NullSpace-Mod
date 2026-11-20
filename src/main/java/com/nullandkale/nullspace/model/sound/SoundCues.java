package com.nullandkale.nullspace.model.sound;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * The named sounds the game plays, from {@code assets/nullspace/synth/cues.json}: each name has one or more layers, a {@link SynthPatch} in its text form with
 * an optional {@code delay <seconds>} clause, so a chime can be two notes. Game code plays a cue by name. The client reloads the file with its resources; the
 * server reads the copy in the mod for the Synth block's presets.
 */
public final class SoundCues
{
    public record Layer(float delay, SynthPatch patch)
    {}

    public static final String FILE = "/assets/nullspace/synth/cues.json";
    private static volatile Map<String, List<Layer>> cues = bundled();

    public static List<Layer> get(String name)
    {
        return cues.getOrDefault(name, List.of());
    }

    public static Set<String> names()
    {
        return cues.keySet();
    }

    /** Replaces the cues with a freshly read file (the client's resource reload). */
    public static void replace(Map<String, List<Layer>> next)
    {
        cues = Map.copyOf(next);
    }

    /** The first layer of a cue as a patch to start from, or null for a name that is not a cue. */
    public static SynthPatch preset(String name)
    {
        var layers = get(name);
        return layers.isEmpty() ? null : layers.getFirst().patch();
    }

    public static Map<String, List<Layer>> parse(String json)
    {
        var out = new LinkedHashMap<String, List<Layer>>();
        for (var entry : JsonParser.parseString(json).getAsJsonObject().entrySet())
        {
            var layers = new ArrayList<Layer>();
            var value = entry.getValue();
            for (JsonElement layer : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value))
                layers.add(layer(layer.getAsString()));
            out.put(entry.getKey(), List.copyOf(layers));
        }
        return out;
    }

    /** One layer: {@code delay 0.2; tone sine 990; env 0.005 0.1 1}. */
    public static Layer layer(String text)
    {
        float delay = 0;
        var rest = new StringBuilder();
        for (String clause : text.split(";"))
        {
            var words = clause.trim().split("\\s+");
            if (words.length == 2 && words[0].equals("delay"))
                delay = Math.max(0, Math.min(10, Float.parseFloat(words[1])));
            else
                rest.append(clause).append(';');
        }
        return new Layer(delay, SynthPatch.parse(rest.toString()));
    }

    private static Map<String, List<Layer>> bundled()
    {
        try (var in = SoundCues.class.getResourceAsStream(FILE))
        {
            if (in == null)
                return Map.of();
            return Map.copyOf(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        catch (Exception unreadable)
        {
            return Map.of();
        }
    }

    private SoundCues()
    {
    }
}
