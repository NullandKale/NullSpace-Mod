package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.model.galaxy.EncounterKind;
import com.nullandkale.nullspace.worldgen.condition.DerelictHistory;
import com.nullandkale.nullspace.worldgen.shipgen.Spec;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;

/**
 * Every setting of an encounter a creative Scanner can make: its kind and danger, the seed that builds it, the story its
 * wreck tells, which ship (for a ship) and the maker, purpose and size of its brief (for a station). Anything left to
 * chance is null, empty or zero, and the seed decides it as it does for an ordinary signal.
 */
public record CreativeEncounter(int kind, int danger, long seed, String design, DerelictHistory history, Spec.Maker maker, StationSpec.Purpose purpose, StationSpec.Size size)
{
    /** The words a screen sends: {@code kind danger history design maker purpose size seed}, with {@code auto} and {@code random} for chance. */
    public static CreativeEncounter parse(String text, long randomSeed)
    {
        var words = text.strip().split(" +");
        if (words.length != 8)
            throw new IllegalArgumentException("Choose every setting of the encounter");
        int kind = Integer.parseInt(words[0]);
        EncounterKind.saved(kind);
        int danger = Math.max(0, Math.min(3, Integer.parseInt(words[1])));
        DerelictHistory history = null;
        if (!words[2].equals("auto"))
            history = DerelictHistory.valueOf(words[2].toUpperCase(Locale.ROOT));
        String design = words[3].equals("auto") ? "" : ShipBlueprints.get(words[3]).id();
        if (!design.isEmpty() && ShipBlueprints.get(design).tiny())
            throw new IllegalArgumentException("Tiny ships are not found as wrecks");
        Spec.Maker maker = words[4].equals("auto") ? null : Spec.Maker.valueOf(words[4].toUpperCase(Locale.ROOT));
        StationSpec.Purpose purpose = words[5].equals("auto") ? null : StationSpec.Purpose.valueOf(words[5].toUpperCase(Locale.ROOT));
        StationSpec.Size size = words[6].equals("auto") ? null : StationSpec.Size.valueOf(words[6].toUpperCase(Locale.ROOT));
        long seed = words[7].equals("random") ? randomSeed : seed(words[7]);
        return new CreativeEncounter(kind, danger, seed, design, history, maker, purpose, size);
    }

    /** A number is the seed itself; any other text is turned into one, so a word makes the same encounter every time. */
    static long seed(String text)
    {
        try
        {
            return Long.parseLong(text);
        }
        catch (NumberFormatException word)
        {
            return text.hashCode() * 0x9E3779B97F4A7C15L;
        }
    }

    /** The station's brief: the chosen maker, purpose and size, and the seed's own pick for each one left open. */
    public StationSpec spec()
    {
        var drawn = com.nullandkale.nullspace.worldgen.stations.EncounterStations.encounter(seed);
        return new StationSpec(maker != null ? maker : drawn.maker(), purpose != null ? purpose : drawn.purpose(), size != null ? size : drawn.size(), StationSpec.Condition.DERELICT, seed);
    }

    public CompoundTag save()
    {
        var tag = new CompoundTag();
        tag.putString("design", design);
        if (history != null)
            tag.putInt("history", history.ordinal());
        if (kind == EncounterKind.ABANDONED_STATION.id)
            tag.put("stationSpec", spec().save());
        return tag;
    }

    public String describe()
    {
        var out = new StringBuilder(EncounterKind.saved(kind).label.toLowerCase(Locale.ROOT)).append(" at danger ").append(danger);
        if (history != null)
            out.append(", ").append(history.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (!design.isEmpty())
            out.append(", ").append(design.replace('_', ' '));
        return out.toString();
    }
}
