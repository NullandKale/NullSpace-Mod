package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HabRoomCountTest
{
    /** The rooms of a hab people go into: everything but corridors, the entry hall and the grove. */
    private static long rooms(HabPlanner.Hab hab)
    {
        return hab.plan().rooms().stream().filter(r -> r.use() != Use.CORRIDOR && r.use() != Use.ARRIVAL && !r.id().equals(hab.grove())).count();
    }

    @Test
    void everySizeHoldsEnoughRoomsOnEveryMakerAndPurpose()
    {
        var low = new java.util.ArrayList<String>();
        int[] least = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        long[] total = new long[3], widest = new long[3], deepest = new long[3];
        int[] count = new int[3];
        for (var maker : Maker.values())
            for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY})
                for (var size : StationSpec.Size.values())
                    for (long seed = 1; seed <= 4; seed++)
                    {
                        var hab = HabPlanner.generate(new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, seed));
                        long n = rooms(hab);
                        if (n < HabPlanner.minimumRooms(size))
                            low.add(maker + " " + purpose + " " + size + " " + seed + "=" + n + " " + HabPlanner.formFor(hab.plan().spec()) + " " + HabPlanner.scalesFor(hab.plan().spec()).values());
                        least[size.ordinal()] = (int) Math.min(least[size.ordinal()], n);
                        total[size.ordinal()] += n;
                        widest[size.ordinal()] += hab.plan().width();
                        deepest[size.ordinal()] += hab.plan().depth();
                        count[size.ordinal()]++;
                    }
        for (var size : StationSpec.Size.values())
            System.out.println("HAB ROOMS " + size + ": least " + least[size.ordinal()] + ", average " + total[size.ordinal()] / count[size.ordinal()] + ", average footprint " + widest[size.ordinal()] / count[size.ordinal()] + " by " + deepest[size.ordinal()] / count[size.ordinal()]);
        System.out.println("HAB LOW " + low);
        assertTrue(low.isEmpty(), "habs under the minimum: " + low);
        assertTrue(least[0] >= HabPlanner.minimumRooms(StationSpec.Size.COMPACT), "small: " + least[0]);
        assertTrue(least[1] >= HabPlanner.minimumRooms(StationSpec.Size.STANDARD), "medium: " + least[1]);
        assertTrue(least[2] >= HabPlanner.minimumRooms(StationSpec.Size.LARGE), "large: " + least[2]);
    }
}
