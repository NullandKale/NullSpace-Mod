package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

/** Opt-in planning times and example seeds for the hab gallery; run with the benchmarkHabPlanning Gradle task. */
public final class HabPlanningBenchmark
{
    public static void main(String[] args)
    {
        long slowest = 0;
        for (var size : StationSpec.Size.values())
            for (var form : HabPlanner.Form.values())
            {
                if (size == StationSpec.Size.COMPACT && (form == HabPlanner.Form.BEADS || form == HabPlanner.Form.RING))
                    continue;
                var maker = Maker.values()[(size.ordinal() + form.ordinal()) % 3];
                var found = new StringBuilder();
                long total = 0;
                int tried = 0;
                for (long seed = 1; seed <= 400 && found.length() < 40; seed++)
                {
                    var brief = new StationSpec(maker, StationSpec.Purpose.HABITATION, size, StationSpec.Condition.OPERATIONAL, seed);
                    if (HabPlanner.formFor(brief) != form)
                        continue;
                    long start = System.nanoTime();
                    var hab = HabPlanner.generate(brief);
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    total += ms;
                    tried++;
                    slowest = Math.max(slowest, ms);
                    if (hab.form() == form)
                        found.append(seed).append(" (").append(ms).append(" ms) ");
                    else if (tried == 1)
                        System.out.println("HAB TIMING " + size + " " + form + " " + maker + " seed " + seed + " fell back: " + HabPlanner.formFailure);
                }
                System.out.println("HAB TIMING " + size + " " + form + " " + maker + ": average " + (tried == 0 ? 0 : total / tried) + " ms over " + tried + "; seeds that really give it: " + found);
            }
        System.out.println("HAB TIMING slowest plan " + slowest + " ms");
    }
}
