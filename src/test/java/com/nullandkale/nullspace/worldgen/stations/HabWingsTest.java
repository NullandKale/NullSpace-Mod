package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner.Wing;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HabWingsTest
{
    /** For each corridor of a hab, the share of its rooms that belong to the corridor's commonest wing; and which wings lead a corridor. */
    private static double purity(HabPlanner.Hab hab, HashSet<Wing> leading)
    {
        var rooms = new HashMap<String, StationPlan.Room>();
        for (var room : hab.plan().rooms())
            rooms.put(room.id(), room);
        double share = 0;
        int corridors = 0;
        for (var corridor : hab.plan().rooms())
        {
            if (corridor.use() != Use.CORRIDOR)
                continue;
            var counts = new EnumMap<Wing, Integer>(Wing.class);
            int total = 0;
            for (var door : hab.plan().doors())
            {
                var other = door.a().equals(corridor.id()) ? rooms.get(door.b()) : door.b().equals(corridor.id()) ? rooms.get(door.a()) : null;
                if (other == null || other.use() == Use.CORRIDOR || other.use() == Use.ARRIVAL || other.id().equals(hab.grove()))
                    continue;
                counts.merge(Wing.of(other.use()), 1, Integer::sum);
                total++;
            }
            if (total < 3)
                continue;
            var top = counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow();
            share += top.getValue() / (double) total;
            corridors++;
            leading.add(top.getKey());
        }
        return corridors == 0 ? 1 : share / corridors;
    }

    @Test
    void theArmsOfAHabAreWingsEachMadeOfOneKindOfLife()
    {
        for (var maker : new Maker[]{Maker.RIVET, Maker.HALCYON})
            for (var size : StationSpec.Size.values())
                for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY})
                    for (long seed = 1; seed <= 3; seed++)
                    {
                        var hab = HabPlanner.generate(new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, seed));
                        var leading = new HashSet<Wing>();
                        double purity = purity(hab, leading);
                        assertTrue(purity >= 0.8, maker + " " + purpose + " " + size + " " + seed + ": a corridor's rooms are " + Math.round(purity * 100) + " percent one wing");
                        assertTrue(HabPlanner.formFor(hab.plan().spec()) != HabPlanner.Form.ARMS || leading.size() >= 2, maker + " " + purpose + " " + size + " " + seed + ": the wings that lead a corridor: " + leading);
                    }
    }

    @Test
    void everyWingExistsWhateverThePurpose()
    {
        for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH})
        {
            var hab = HabPlanner.generate(new StationSpec(Maker.RIVET, purpose, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, 2));
            var counts = new EnumMap<Wing, Integer>(Wing.class);
            for (var room : hab.plan().rooms())
                if (room.use() != Use.CORRIDOR && room.use() != Use.ARRIVAL)
                    counts.merge(Wing.of(room.use()), 1, Integer::sum);
            for (var wing : Wing.values())
                assertTrue(counts.getOrDefault(wing, 0) > 0, purpose + ": the " + wing.label + " has rooms");
        }
    }

    @Test
    void wingSizeIsOptionalSomeHabsHaveEvenArmsAndOthersShorterAndLongerOnes()
    {
        int even = 0, lopsided = 0;
        for (long seed = 1; seed <= 40; seed++)
        {
            var spec = new StationSpec(Maker.RIVET, StationSpec.Purpose.HABITATION, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, seed);
            var scales = HabPlanner.scalesFor(spec);
            assertTrue(scales.get(HabPlanner.primary(spec.purpose())) == 1.0, "the purpose's wing is always full length");
            assertTrue(scales.values().stream().allMatch(v -> v >= 0.6 && v <= 1.0), "every arm is between 60 and 100 percent: " + scales);
            if (scales.values().stream().allMatch(v -> v == 1.0))
                even++;
            else
                lopsided++;
        }
        assertTrue(even >= 5 && lopsided >= 5, "both even and lopsided habs occur: " + even + " even, " + lopsided + " lopsided");
    }

    @Test
    void aShorterWingReallyIsAShorterArm()
    {
        int checked = 0;
        for (long seed = 1; seed <= 30; seed++)
        {
            var spec = new StationSpec(Maker.RIVET, StationSpec.Purpose.HABITATION, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, seed);
            var scales = HabPlanner.scalesFor(spec);
            var short_ = scales.entrySet().stream().filter(e -> e.getValue() <= 0.7).map(java.util.Map.Entry::getKey).findFirst();
            if (short_.isEmpty())
                continue;
            var hab = HabPlanner.generate(spec);
            var hub = hab.entry();
            int cx = hub.x() + hub.w() / 2, cz = hub.z() + hub.d() / 2;
            var reach = new EnumMap<Wing, Double>(Wing.class);
            var count = new EnumMap<Wing, Integer>(Wing.class);
            for (var room : hab.plan().rooms())
                if (room.use() != Use.CORRIDOR && room.use() != Use.ARRIVAL && !room.id().equals(hab.grove()))
                {
                    reach.merge(Wing.of(room.use()), (double) Math.max(Math.abs(room.x() + room.w() / 2 - cx), Math.abs(room.z() + room.d() / 2 - cz)), Double::sum);
                    count.merge(Wing.of(room.use()), 1, Integer::sum);
                }
            var lead = HabPlanner.primary(spec.purpose());
            // On average a shorter wing's rooms are nearer the hall (some of its rooms may spill into longer arms).
            assertTrue(reach.get(short_.get()) / count.get(short_.get()) < reach.get(lead) / count.get(lead), "seed " + seed + ": the " + short_.get().label + " (" + scales.get(short_.get()) + ") reaches " + reach.get(short_.get()) + ", the leading wing " + reach.get(lead));
            checked++;
        }
        assertTrue(checked >= 5, "enough shorter wings to check: " + checked);
    }
}
