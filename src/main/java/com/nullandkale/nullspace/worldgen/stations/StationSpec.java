package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

import java.util.*;

import net.minecraft.nbt.CompoundTag;

/** Generation inputs and provenance, independent of ownership and encounter difficulty. */
public record StationSpec(Maker maker, Purpose purpose, Size size, Condition condition, long seed)
{

    public static final int VERSION = 1;

    public enum Purpose
    {
        HARBOR, CARGO, INDUSTRY, AGRICULTURE, RESEARCH, HABITATION, SECURITY
    }

    public enum Size
    {
        COMPACT, STANDARD, LARGE
    }

    public enum Condition
    {
        OPERATIONAL, DERELICT
    }

    public StationSpec
    {
        Objects.requireNonNull(maker);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(size);
        Objects.requireNonNull(condition);
    }

    public static StationSpec parse(String maker, String purpose, String size, String condition, long seed)
    {
        return new StationSpec(value(Maker.class, maker), value(Purpose.class, purpose), value(Size.class, size), value(Condition.class, condition), seed);
    }

    private static <T extends Enum<T>> T value(Class<T> type, String id)
    {
        try
        {
            return Enum.valueOf(type, id.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException failure)
        {
            throw new IllegalArgumentException("Unknown station " + type.getSimpleName().toLowerCase(Locale.ROOT) + ": " + id);
        }
    }

    public CompoundTag save()
    {
        var out = new CompoundTag();
        out.putInt("version", VERSION);
        out.putString("maker", maker.name().toLowerCase(Locale.ROOT));
        out.putString("purpose", purpose.name().toLowerCase(Locale.ROOT));
        out.putString("size", size.name().toLowerCase(Locale.ROOT));
        out.putString("condition", condition.name().toLowerCase(Locale.ROOT));
        out.putLong("seed", seed);
        return out;
    }

    public static StationSpec read(CompoundTag data)
    {
        if (data.getIntOr("version", -1) != VERSION)
            throw new IllegalArgumentException("Unsupported station specification version: " + data.getIntOr("version", -1));
        if (!data.contains("seed"))
            throw new IllegalArgumentException("Station specification is missing its seed");
        return parse(data.getStringOr("maker", ""), data.getStringOr("purpose", ""), data.getStringOr("size", ""), data.getStringOr("condition", ""), data.getLongOr("seed", 0));
    }

    public String description()
    {
        return maker.name().toLowerCase(Locale.ROOT) + " / " + purpose.name().toLowerCase(Locale.ROOT) + " / " + size.name().toLowerCase(Locale.ROOT) + " / " + condition.name().toLowerCase(Locale.ROOT) + " / seed " + seed;
    }
}
