package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HabPlannerTest
{
    private static StationSpec spec(Maker maker, StationSpec.Purpose purpose, StationSpec.Size size, long seed)
    {
        return new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, seed);
    }

    @Test
    void everyKindOfHabPlansSoundlyOnManySeeds()
    {
        int made = 0;
        for (var maker : Maker.values())
            for (var size : StationSpec.Size.values())
                for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY})
                    for (long seed = 1; seed <= 6; seed++)
                    {
                        var hab = HabPlanner.generate(spec(maker, purpose, size, seed));
                        assertEquals(List.of(), HabPlanner.problems(hab), maker + " " + size + " " + purpose + " " + seed);
                        made++;
                    }
        assertEquals(3 * 3 * 5 * 6, made);
    }

    @Test
    void theSurfaceHoldsOnlyTheEntryHallAndEverythingElseIsBelowIt()
    {
        for (var size : StationSpec.Size.values())
            for (long seed = 1; seed <= 10; seed++)
            {
                var hab = HabPlanner.generate(spec(Maker.RIVET, StationSpec.Purpose.HABITATION, size, seed));
                var entry = hab.entry();
                assertEquals(HabPlanner.floorsFor(size), entry.levels(), "the entry hall is as tall as the hab");
                assertEquals(0, entry.level(), "and stands on the deepest floor, so its top story is the surface");
                int topStory = (hab.floors() - 1) * StationPlan.PITCH;
                for (var room : hab.plan().rooms())
                    if (room != entry)
                        assertTrue(room.outer().y1() <= topStory, room.id() + " is below the surface floor");
                assertEquals(topStory + 1, hab.groundY(), "the surface is the top story's floor surface");
            }
    }

    @Test
    void theGroveIsABigTwoLevelGardenOnTheDeepestFloorAndEveryRequiredRoomIsStillThere()
    {
        for (var size : StationSpec.Size.values())
            for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HABITATION, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.SECURITY})
                for (long seed = 1; seed <= 8; seed++)
                {
                    var hab = HabPlanner.generate(spec(Maker.BASTION, purpose, size, seed));
                    var grove = hab.groveRoom();
                    assertEquals(Use.GARDEN, grove.use());
                    assertEquals(0, grove.level());
                    assertEquals(2, grove.levels(), "tall enough for a tree");
                    assertTrue(grove.w() >= 19 && grove.d() >= 19 && grove.w() % 2 == 1, "big, and odd so the tree stands on the middle line: " + grove.w() + " by " + grove.d());
                    assertTrue(hab.plan().berths().isEmpty(), "nothing docks at a hab");
                    var plain = StationProfiles.program(spec(Maker.BASTION, purpose, size, seed), new java.util.SplittableRandom(seed * 0x9E3779B97F4A7C15L ^ (1 * 0xBF58476D1CE4E5B9L + 0x94D049BB133111EBL)));
                    for (var item : plain)
                        if (item.required())
                        {
                            // A hab's homes are family quarters, its engineering room is the machine hall, and a lookout or a receiving bay become other rooms.
                            var use = switch (item.use())
                            {
                                case CABIN -> Use.FAMILY_QUARTERS;
                                case ENGINEERING -> Use.MACHINE_HALL;
                                case LOOKOUT -> Use.OBSERVATION;
                                case RECEIVING_BAY -> Use.STORES;
                                default -> item.use();
                            };
                            assertTrue(hab.plan().rooms().stream().anyMatch(r -> r.use() == use), "the hab still has a " + use);
                        }
                }
    }

    @Test
    void everyHabHasOneControllerRoomAndOnePowerPlantAndAtMostOneShrineAndShaftAndNoRoomsForShips()
    {
        for (var maker : Maker.values())
            for (var size : StationSpec.Size.values())
                for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HABITATION, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH})
                    for (long seed = 1; seed <= 6; seed++)
                    {
                        var plan = HabPlanner.generate(spec(maker, purpose, size, seed)).plan();
                        var label = maker + " " + size + " " + purpose + " " + seed;
                        assertEquals(1, plan.rooms(Use.CONTROLLER_ROOM).size(), label + ": the room it is run from");
                        assertEquals(1, plan.rooms(Use.MACHINE_HALL).size(), label + ": the power plant");
                        assertTrue(plan.rooms(Use.CHAPEL).size() <= 1 && plan.rooms(Use.MINE_HEAD).size() <= 1, label + ": one shrine and one shaft at most");
                        for (var gone : new Use[]{Use.CABIN, Use.LOOKOUT, Use.RECEIVING_BAY, Use.ENGINEERING, Use.BRIDGE, Use.DOCK})
                            assertEquals(0, plan.rooms(gone).size(), label + ": a hab has no " + gone);
                        assertTrue(plan.rooms(Use.FAMILY_QUARTERS).size() >= 1 || purpose != StationSpec.Purpose.HABITATION, label + ": homes");
                    }
    }

    @Test
    void everyLayoutFormIsUsedAndEveryFormPlansSoundlyWithItsRoomsInItsWings()
    {
        var seen = new java.util.EnumMap<HabPlanner.Form, Integer>(HabPlanner.Form.class);
        for (var maker : Maker.values())
            for (var size : StationSpec.Size.values())
                for (long seed = 1; seed <= 24; seed++)
                {
                    var brief = spec(maker, StationSpec.Purpose.HABITATION, size, seed);
                    seen.merge(HabPlanner.formFor(brief), 1, Integer::sum);
                    var hab = HabPlanner.generate(brief);
                    assertEquals(List.of(), HabPlanner.problems(hab), maker + " " + size + " " + seed + " " + HabPlanner.formFor(brief));
                }
        for (var form : HabPlanner.Form.values())
            assertTrue(seen.getOrDefault(form, 0) >= 5, form + " is chosen for some habs: " + seen);
        // A small hab has no room for plazas or a ring.
        for (var maker : Maker.values())
            for (long seed = 1; seed <= 20; seed++)
            {
                assertNotEquals(HabPlanner.Form.BEADS, HabPlanner.formFor(spec(maker, StationSpec.Purpose.HABITATION, StationSpec.Size.COMPACT, seed)));
                assertNotEquals(HabPlanner.Form.RING, HabPlanner.formFor(spec(maker, StationSpec.Purpose.HABITATION, StationSpec.Size.COMPACT, seed)));
            }
    }

    @Test
    void roomsThatArePassedThroughHaveTheirDoorsInLineSoTheRouteIsStraight()
    {
        int suites = 0;
        for (var maker : Maker.values())
            for (var size : StationSpec.Size.values())
                for (long seed = 1; seed <= 10; seed++)
                {
                    var brief = spec(maker, StationSpec.Purpose.RESEARCH, size, seed);
                    if (HabPlanner.formFor(brief) != HabPlanner.Form.SUITES)
                        continue;
                    var plan = HabPlanner.generate(brief).plan();
                    suites++;
                    for (var room : plan.rooms())
                    {
                        var doors = plan.doors().stream().filter(d -> d.a().equals(room.id()) || d.b().equals(room.id())).toList();
                        if (doors.size() < 2 || room.use() == Use.ARRIVAL || room.use() == Use.CORRIDOR)
                            continue;
                        // The two doors on opposite walls are no more than a cell apart across the room.
                        for (var other : doors.subList(1, doors.size()))
                        {
                            var first = doors.getFirst();
                            if (first.through().getAxis() != other.through().getAxis())
                                continue;
                            int across = first.through().getAxis() == net.minecraft.core.Direction.Axis.X ? Math.abs(first.at().getZ() - other.at().getZ()) : Math.abs(first.at().getX() - other.at().getX());
                            assertTrue(across <= 2, plan.spec().description() + " " + room.id() + ": doors in line, off by " + across);
                        }
                    }
                }
        assertTrue(suites > 0, "some habs are suites");
    }

    @Test
    void aHabIsBuiltLargerThanAStationOfTheSameSize()
    {
        for (var size : StationSpec.Size.values())
        {
            var hab = HabPlanner.generate(spec(Maker.RIVET, StationSpec.Purpose.HABITATION, size, 5));
            assertTrue(hab.entry().w() >= 15, "the entry hall is at least 15 across: " + hab.entry().w());
            for (var room : hab.plan().rooms())
                if (room.use() == Use.CORRIDOR && room.outer().y0() == 0 && room != hab.entry())
                    assertTrue(Math.min(room.w(), room.d()) >= 7, "corridors are seven wide: " + room.id() + " " + room.w() + " by " + room.d());
        }
    }

    @Test
    void theSameBriefAlwaysGivesTheSameHab()
    {
        var a = HabPlanner.generate(spec(Maker.HALCYON, StationSpec.Purpose.RESEARCH, StationSpec.Size.STANDARD, 99));
        var b = HabPlanner.generate(spec(Maker.HALCYON, StationSpec.Purpose.RESEARCH, StationSpec.Size.STANDARD, 99));
        assertEquals(a.plan().rooms(), b.plan().rooms());
        assertEquals(a.plan().doors(), b.plan().doors());
        assertEquals(a.grove(), b.grove());
    }

    @Test
    void aHarborOrCargoBriefBecomesAHabitation()
    {
        assertEquals(StationSpec.Purpose.HABITATION, HabPlanner.purposeFor(StationSpec.Purpose.HARBOR));
        assertEquals(StationSpec.Purpose.HABITATION, HabPlanner.purposeFor(StationSpec.Purpose.CARGO));
        assertEquals(StationSpec.Purpose.INDUSTRY, HabPlanner.purposeFor(StationSpec.Purpose.INDUSTRY));
    }
}
