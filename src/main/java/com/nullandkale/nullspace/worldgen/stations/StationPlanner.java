package com.nullandkale.nullspace.worldgen.stations;

import java.util.SplittableRandom;

/**
 * Turns a {@link StationSpec} into a {@link StationPlan}. The room program, layout and berth choices
 * use separate random streams derived from the seed, so the same brief and seed reproduce exactly the
 * same plan and changing one stream does not rearrange another. A bounded number of attempts is made;
 * failure names the last unmet requirement and never substitutes another maker or purpose.
 */
public final class StationPlanner
{
    public static final int ATTEMPTS = 64;

    private static SplittableRandom stream(long seed, long salt)
    {
        return new SplittableRandom(seed * 0x9E3779B97F4A7C15L ^ (salt * 0xBF58476D1CE4E5B9L + 0x94D049BB133111EBL));
    }

    private static int minimumBerths(StationSpec.Size size)
    {
        return switch (size)
        {
            case COMPACT -> 1;
            case STANDARD -> 2;
            case LARGE -> 3;
        };
    }

    /**
     * How many decks an attempt builds. The seed picks a preferred count inside the size's range (small
     * 1-2, medium 2-3, large 3-4); the first attempts try it, and later ones fall back to the range's
     * lowest count so a hard seed still yields a station.
     */
    static int levelsFor(StationSpec spec, int attempt)
    {
        var range = StationBuilder.levelRange(spec.size());
        int preferred = range[0] + stream(spec.seed(), 4).nextInt(range[1] - range[0] + 1);
        return attempt < ATTEMPTS * 2 / 3 ? preferred : range[0];
    }

    public static StationPlan generate(StationSpec spec)
    {
        var program = StationProfiles.program(spec, stream(spec.seed(), 1));
        int target = StationProfiles.berthTarget(spec, stream(spec.seed(), 3));
        String failure = "no attempt succeeded";
        for (int attempt = 0; attempt < ATTEMPTS; attempt++)
        {
            int levels = levelsFor(spec, attempt);
            var builder = new StationBuilder(spec, stream(spec.seed(), 100 + attempt), levels);
            if (!StationMethods.layout(spec.maker(), builder, program))
            {
                failure = "required rooms did not fit" + (builder.note.isEmpty() ? "" : " (" + builder.note + ")");
                continue;
            }
            builder.berths(target);
            if (builder.berths.size() < minimumBerths(spec.size()))
            {
                failure = "only " + builder.berths.size() + " berths fit";
                continue;
            }
            var plan = builder.finish();
            if (plan == null)
            {
                failure = "the plan failed validation";
                continue;
            }
            if (plan.levels() < levels)
            {
                failure = "only " + plan.levels() + " of " + levels + " levels carry rooms";
                continue;
            }
            return plan;
        }
        throw new IllegalStateException("Could not generate " + spec.description() + ": " + failure);
    }

    private StationPlanner()
    {
    }
}
