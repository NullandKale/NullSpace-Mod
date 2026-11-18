package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

import java.util.HashMap;
import java.util.Map;

/**
 * Habs planned and built once for the whole test run: building one is the slow part of a hab test, and many tests look at the same few habs.
 */
public final class BuiltHabs
{
    public record Built(HabPlanner.Hab hab, HabStructure.Build build)
    {}

    /** The latest few only: a built hab is hundreds of thousands of blocks, and the tests of a run share one JVM. */
    private static final Map<StationSpec, Built> CACHE = new java.util.LinkedHashMap<>(8, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<StationSpec, Built> eldest)
        {
            return size() > 3;
        }
    };

    public static synchronized Built get(StationSpec brief)
    {
        var known = CACHE.get(brief);
        if (known == null)
        {
            var hab = HabPlanner.generate(brief);
            known = new Built(hab, HabStructure.build(hab));
            CACHE.put(brief, known);
        }
        return known;
    }

    public static Built get(Maker maker, StationSpec.Purpose purpose, StationSpec.Size size, long seed)
    {
        return get(new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, seed));
    }

    private BuiltHabs()
    {
    }
}
