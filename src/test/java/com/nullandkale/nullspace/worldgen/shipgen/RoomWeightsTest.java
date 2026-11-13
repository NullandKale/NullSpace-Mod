package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomWeightsTest
{
    @Test
    void roleChangesRoomChancesAcrossSeededDraws()
    {
        var counts = new EnumMap<Spec.Role, EnumMap<RoomUse, Integer>>(Spec.Role.class);
        var seeds = new Random(80941);
        for (var role : Spec.Role.values())
        {
            var byUse = new EnumMap<RoomUse, Integer>(RoomUse.class);
            counts.put(role, byUse);
            for (int i = 0; i < 4096; i++)
            {
                var bag = new Program.Bag(new Spec(Spec.Maker.RIVET, Spec.Shape.SPINE, Spec.Tier.LARGE, role, seeds.nextLong()));
                byUse.merge(bag.next(9, 7), 1, Integer::sum);
            }
            assertEquals(14, byUse.size(), "Every optional room remains possible for " + role);
        }
        assertTrue(counts.get(Spec.Role.FREIGHTER).get(RoomUse.CARGO) > counts.get(Spec.Role.SCOUT).get(RoomUse.CARGO) * 3);
        assertTrue(counts.get(Spec.Role.EXPLORER).get(RoomUse.LAB) > counts.get(Spec.Role.FREIGHTER).get(RoomUse.LAB) * 3);
        assertTrue(counts.get(Spec.Role.SCOUT).get(RoomUse.CREW) > counts.get(Spec.Role.EXPLORER).get(RoomUse.CREW) * 1.5);
    }

    @Test
    void weightedBagsRemainReproducibleAndUseEveryFittingTypeBeforeRepeats()
    {
        for (var role : Spec.Role.values())
        {
            var spec = new Spec(Spec.Maker.HALCYON, Spec.Shape.DOME, Spec.Tier.LARGE, role, 419);
            var a = new Program.Bag(spec);
            var b = new Program.Bag(spec);
            var seen = new HashSet<RoomUse>();
            for (int i = 0; i < 14; i++)
            {
                var use = a.next(9, 7);
                assertEquals(use, b.next(9, 7));
                assertTrue(seen.add(use), "No repeated room before the bag is exhausted");
            }
            assertTrue(seen.contains(a.next(9, 7)), "Exhausted bags may repeat");
        }
    }
}
