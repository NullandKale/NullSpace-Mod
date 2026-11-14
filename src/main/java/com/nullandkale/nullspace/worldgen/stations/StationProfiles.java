package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Purpose profiles: which rooms a purpose requires, how many its size band calls for, and the
 * weighted pool its remaining space is drawn from. The planner reads these; no layout algorithm
 * hides a fixed room sequence.
 */
public final class StationProfiles
{
    /** Outer dimension ranges: small rooms hold up to 10 clear blocks, large rooms up to 18. */
    public enum Class
    {
        SMALL(8, 12, 0), LARGE(12, 20, 1);

        public final int min, max, tallChance;

        Class(int min, int max, int tallChance)
        {
            this.min = min;
            this.max = max;
            this.tallChance = tallChance;
        }
    }

    /** A room the planner must place: its use and size class. */
    public record Item(Use use, Class size, boolean required)
    {}

    private record Need(Use use, Class size, int compact, int standard, int large)
    {
        int count(StationSpec.Size band)
        {
            return switch (band)
            {
                case COMPACT -> compact;
                case STANDARD -> standard;
                case LARGE -> large;
            };
        }
    }

    private record Pool(Use use, Class size, double weight)
    {}

    private record Profile(List<Need> needs, List<Pool> pool)
    {}

    private static Need need(Use use, Class size, int a, int b, int c)
    {
        return new Need(use, size, a, b, c);
    }

    private static Pool pool(Use use, Class size, double weight)
    {
        return new Pool(use, size, weight);
    }

    private static Profile profile(StationSpec.Purpose purpose)
    {
        return switch (purpose)
        {
            case HARBOR -> new Profile(List.of(need(Use.COMMONS, Class.LARGE, 1, 1, 2), need(Use.WORKSHOP, Class.SMALL, 1, 1, 2), need(Use.STORES, Class.SMALL, 1, 2, 3)), List.of(pool(Use.BUNK_ROOM, Class.SMALL, 3), pool(Use.MAINTENANCE, Class.SMALL, 2), pool(Use.OBSERVATION, Class.SMALL, 1), pool(Use.KITCHEN, Class.SMALL, 1.5), pool(Use.ARCHIVE, Class.SMALL, .6), pool(Use.EVA_PREP, Class.SMALL, 2), pool(Use.INFIRMARY, Class.SMALL, .6)));
            case CARGO -> new Profile(List.of(need(Use.CARGO_HOLD, Class.LARGE, 1, 2, 4), need(Use.RECEIVING_BAY, Class.SMALL, 1, 1, 2)), List.of(pool(Use.STORES, Class.SMALL, 3), pool(Use.BUNK_ROOM, Class.SMALL, 2), pool(Use.MAINTENANCE, Class.SMALL, 2), pool(Use.COMMONS, Class.SMALL, 1), pool(Use.EVA_PREP, Class.SMALL, 1)));
            case INDUSTRY -> new Profile(List.of(need(Use.FOUNDRY, Class.LARGE, 1, 2, 3), need(Use.WORKSHOP, Class.LARGE, 1, 1, 3)), List.of(pool(Use.STORES, Class.SMALL, 3), pool(Use.MAINTENANCE, Class.SMALL, 3), pool(Use.BUNK_ROOM, Class.SMALL, 1.5), pool(Use.RECEIVING_BAY, Class.SMALL, 1), pool(Use.INFIRMARY, Class.SMALL, .5)));
            case AGRICULTURE -> new Profile(List.of(need(Use.GROWING_BAY, Class.LARGE, 1, 2, 4), need(Use.KITCHEN, Class.SMALL, 1, 1, 2), need(Use.STORES, Class.SMALL, 1, 1, 2)), List.of(pool(Use.STORES, Class.SMALL, 2), pool(Use.MAINTENANCE, Class.SMALL, 2), pool(Use.BUNK_ROOM, Class.SMALL, 2), pool(Use.COMMONS, Class.SMALL, 1), pool(Use.GARDEN, Class.SMALL, 1.5)));
            case RESEARCH -> new Profile(List.of(need(Use.LAB, Class.SMALL, 1, 3, 5), need(Use.OBSERVATION, Class.LARGE, 1, 1, 2), need(Use.ARCHIVE, Class.SMALL, 1, 1, 2)), List.of(pool(Use.STORES, Class.SMALL, 2), pool(Use.BUNK_ROOM, Class.SMALL, 2), pool(Use.COMMONS, Class.SMALL, 1), pool(Use.MAINTENANCE, Class.SMALL, 1), pool(Use.INFIRMARY, Class.SMALL, 1)));
            case HABITATION -> new Profile(List.of(need(Use.CABIN, Class.SMALL, 2, 6, 12), need(Use.COMMONS, Class.LARGE, 1, 1, 2), need(Use.KITCHEN, Class.SMALL, 0, 1, 2)), List.of(pool(Use.GARDEN, Class.SMALL, 2), pool(Use.STORES, Class.SMALL, 1.5), pool(Use.MAINTENANCE, Class.SMALL, 1), pool(Use.OBSERVATION, Class.SMALL, 1), pool(Use.INFIRMARY, Class.SMALL, 1)));
            case SECURITY -> new Profile(List.of(need(Use.OPERATIONS, Class.SMALL, 1, 1, 1), need(Use.BUNK_ROOM, Class.LARGE, 1, 2, 3), need(Use.ARMORY, Class.SMALL, 1, 1, 2), need(Use.LOOKOUT, Class.SMALL, 0, 2, 3)), List.of(pool(Use.STORES, Class.SMALL, 2), pool(Use.MAINTENANCE, Class.SMALL, 1.5), pool(Use.COMMONS, Class.SMALL, 1), pool(Use.BRIG, Class.SMALL, 1.5), pool(Use.EVA_PREP, Class.SMALL, 1.5), pool(Use.INFIRMARY, Class.SMALL, 1)));
        };
    }

    /** Optional rooms to draw after the required ones, by size band. */
    public static int optional(StationSpec.Size band, RandomGenerator rng)
    {
        return switch (band)
        {
            case COMPACT -> 5 + rng.nextInt(4);
            case STANDARD -> 11 + rng.nextInt(6);
            case LARGE -> 10 + rng.nextInt(8);
        };
    }

    /**
     * The room program: required rooms first (largest first, so they get the best slots), then
     * weighted draws that discourage repeating an optional use while other fitting uses remain.
     */
    public static List<Item> program(StationSpec spec, RandomGenerator rng)
    {
        var profile = profile(spec.purpose());
        var items = new ArrayList<Item>();
        var counts = new EnumMap<Use, Integer>(Use.class);
        for (var need : profile.needs())
            for (int i = 0; i < need.count(spec.size()); i++)
                items.add(new Item(need.use(), need.size(), true));
        items.sort(Comparator.comparingInt((Item i) -> -i.size().max).thenComparing(i -> i.use().ordinal()));
        items.add(items.size(), new Item(Use.ENGINEERING, Class.SMALL, true));
        for (var item : items)
            counts.merge(item.use(), 1, Integer::sum);
        int extra = optional(spec.size(), rng);
        for (int i = 0; i < extra; i++)
        {
            double total = 0;
            var weights = new double[profile.pool().size()];
            for (int k = 0; k < weights.length; k++)
            {
                var p = profile.pool().get(k);
                weights[k] = p.weight() / (1 + 2.5 * counts.getOrDefault(p.use(), 0));
                total += weights[k];
            }
            double roll = rng.nextDouble() * total;
            int pick = 0;
            for (; pick < weights.length - 1 && roll >= weights[pick]; pick++)
                roll -= weights[pick];
            var chosen = profile.pool().get(pick);
            counts.merge(chosen.use(), 1, Integer::sum);
            items.add(new Item(chosen.use(), chosen.size(), false));
        }
        return items;
    }

    /** The berth count aimed for: the size band's range, with harbors toward the top. */
    public static int berthTarget(StationSpec spec, RandomGenerator rng)
    {
        int lo, hi;
        switch (spec.size())
        {
            case COMPACT ->
            {
                lo = 1;
                hi = 2;
            }
            case STANDARD ->
            {
                lo = 2;
                hi = 4;
            }
            default ->
            {
                lo = 4;
                hi = 8;
            }
        }
        int span = hi - lo + 1;
        int roll = rng.nextInt(span);
        return spec.purpose() == StationSpec.Purpose.HARBOR ? Math.max(lo + roll, (lo + hi + 1) / 2) : lo + roll;
    }

    private StationProfiles()
    {
    }
}
