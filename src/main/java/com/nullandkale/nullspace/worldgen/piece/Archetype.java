package com.nullandkale.nullspace.worldgen.piece;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.core.Vec3i;

import com.google.gson.*;

/**
 * What to assemble from a kit, {@code data/nullspace/archetype/<id>.json}:
 *
 * <pre>
 * { "kind": "ship", "start": "nose",
 *   "roles": [ { "role": "hull", "min": 1, "max": 3 }, { "role": "engine", "min": 1, "max": 1 },
 *              { "role": "wing", "min": 0, "max": 2 } ],
 *   "cap": "cap", "max": [25, 15, 31] }
 * </pre>
 *
 * Roles are listed in priority order: an open connector takes the first role that is still wanted
 * and has a fitting piece. Leftover open connectors are closed with {@code cap} pieces.
 */
public record Archetype(String id, Kind kind, String start, List<Count> roles, String cap, Vec3i max)
{
    public enum Kind
    {
        /** Needs a computer, a generator, an engine and an Air Supply to fly. */
        SHIP,
        /** Needs a Station Core. */
        STATION,
        /** No required machines. */
        SITE
    }

    public record Count(String role, int min, int max)
    {
        public Count
        {
            if (min < 0 || max < min)
                throw new IllegalArgumentException("Role " + role + " needs 0 <= min <= max");
        }
    }

    public Archetype
    {
        roles = List.copyOf(roles);
    }

    public static Archetype load(String id)
    {
        try (var in = Archetype.class.getResourceAsStream("/data/nullspace/archetype/" + id + ".json"))
        {
            if (in == null)
                throw new IllegalArgumentException("No archetype " + id);
            return parse(id, JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    public static Archetype parse(String id, JsonObject json)
    {
        var roles = new ArrayList<Count>();
        for (var element : json.getAsJsonArray("roles"))
        {
            var r = element.getAsJsonObject();
            roles.add(new Count(r.get("role").getAsString(), r.get("min").getAsInt(), r.get("max").getAsInt()));
        }
        var max = json.getAsJsonArray("max");
        return new Archetype(id, Kind.valueOf(json.get("kind").getAsString().toUpperCase(Locale.ROOT)), json.get("start").getAsString(), roles, json.has("cap") ? json.get("cap").getAsString() : "cap", new Vec3i(max.get(0).getAsInt(), max.get(1).getAsInt(), max.get(2).getAsInt()));
    }
}
