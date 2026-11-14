package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StationPlannerTest
{
    private static StationSpec spec(Maker maker, StationSpec.Purpose purpose, StationSpec.Size size, long seed)
    {
        return new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, seed);
    }

    /** A footprint signature: room uses and boxes, ignoring ids. */
    private static String shape(StationPlan plan)
    {
        return plan.rooms().stream().map(r -> r.use() + "@" + r.x() + "," + r.y() + "," + r.z() + ":" + r.w() + "x" + r.d() + "x" + r.levels()).sorted().collect(Collectors.joining(";"));
    }

    @Test
    void everyBriefProducesASoundPlan()
    {
        var failures = new ArrayList<String>();
        int total = 0;
        for (var maker : Maker.values())
            for (var purpose : StationSpec.Purpose.values())
                for (var size : StationSpec.Size.values())
                    for (long seed = 1; seed <= 6; seed++)
                    {
                        total++;
                        var spec = spec(maker, purpose, size, seed * 7919);
                        try
                        {
                            var plan = StationPlanner.generate(spec);
                            var problems = plan.problems();
                            if (!problems.isEmpty())
                                failures.add(spec.description() + " " + problems);
                        }
                        catch (IllegalStateException e)
                        {
                            failures.add(e.getMessage());
                        }
                    }
        assertTrue(failures.isEmpty(), failures.size() + " of " + total + " briefs failed, first: " + failures.stream().limit(8).toList());
    }

    @Test
    void sameBriefAndSeedReproduceExactly()
    {
        for (var maker : Maker.values())
        {
            var spec = spec(maker, StationSpec.Purpose.AGRICULTURE, StationSpec.Size.STANDARD, 4242);
            assertEquals(StationPlanner.generate(spec), StationPlanner.generate(spec));
        }
    }

    @Test
    void differentSeedsGiveDifferentLayoutsOfTheSameBrief()
    {
        for (var maker : Maker.values())
            for (var size : StationSpec.Size.values())
            {
                var shapes = new HashSet<String>();
                var uses = new HashSet<String>();
                for (long seed = 1; seed <= 12; seed++)
                {
                    var plan = StationPlanner.generate(spec(maker, StationSpec.Purpose.CARGO, size, seed * 104729));
                    shapes.add(shape(plan));
                    uses.add(plan.rooms().stream().map(r -> r.use().name()).sorted().collect(Collectors.joining(",")));
                }
                assertTrue(shapes.size() >= 10, maker + " " + size + " produced only " + shapes.size() + " distinct layouts of 12");
                assertTrue(uses.size() >= 3 || size == StationSpec.Size.COMPACT, maker + " " + size + " produced only " + uses.size() + " distinct room mixes");
            }
    }

    @Test
    void roomDrawsAreNotAFixedSequence()
    {
        var sequences = new HashSet<String>();
        var uses = new HashSet<Use>();
        for (long seed = 1; seed <= 30; seed++)
        {
            var plan = StationPlanner.generate(spec(Maker.RIVET, StationSpec.Purpose.INDUSTRY, StationSpec.Size.STANDARD, seed * 31));
            var placed = plan.rooms().stream().map(StationPlan.Room::use).filter(u -> u != Use.CORRIDOR && u != Use.ARRIVAL && u != Use.DOCK).toList();
            sequences.add(placed.toString());
            uses.addAll(placed);
        }
        assertTrue(sequences.size() >= 10, "Room orders should vary, saw " + sequences.size() + " of 30");
        assertTrue(uses.size() >= 6, "Optional draws should vary, saw " + uses);
    }

    @Test
    void purposeDecidesTheDominantRooms()
    {
        var expected = Map.of(StationSpec.Purpose.AGRICULTURE, Use.GROWING_BAY, StationSpec.Purpose.CARGO, Use.CARGO_HOLD, StationSpec.Purpose.INDUSTRY, Use.FOUNDRY, StationSpec.Purpose.HABITATION, Use.CABIN, StationSpec.Purpose.RESEARCH, Use.LAB, StationSpec.Purpose.SECURITY, Use.BUNK_ROOM, StationSpec.Purpose.HARBOR, Use.COMMONS);
        expected.forEach((purpose, use) ->
        {
            var plan = StationPlanner.generate(spec(Maker.HALCYON, purpose, StationSpec.Size.STANDARD, 99));
            assertFalse(plan.rooms(use).isEmpty(), purpose + " should place " + use);
        });
    }

    @Test
    void largeStationsAreBiggerAndHaveMoreBerths()
    {
        double compactRooms = 0, largeRooms = 0, compactBerths = 0, largeBerths = 0;
        int n = 8;
        for (long seed = 1; seed <= n; seed++)
        {
            var c = StationPlanner.generate(spec(Maker.BASTION, StationSpec.Purpose.HARBOR, StationSpec.Size.COMPACT, seed * 17));
            var l = StationPlanner.generate(spec(Maker.BASTION, StationSpec.Purpose.HARBOR, StationSpec.Size.LARGE, seed * 17));
            compactRooms += c.rooms().size();
            largeRooms += l.rooms().size();
            compactBerths += c.berths().size();
            largeBerths += l.berths().size();
            assertTrue(c.width() <= 64 && c.depth() <= 64, "compact fits 64 x 64: " + c.width() + " x " + c.depth());
            assertTrue(l.width() <= 128 && l.depth() <= 128 && l.height() <= 32);
        }
        assertTrue(largeRooms > compactRooms * 1.5);
        assertTrue(largeBerths > compactBerths);
    }

    @Test
    void berthApproachesReserveFullSizeShips()
    {
        var plan = StationPlanner.generate(spec(Maker.RIVET, StationSpec.Purpose.HARBOR, StationSpec.Size.LARGE, 12));
        for (var berth : plan.berths())
        {
            var a = berth.approach();
            assertEquals(34, a.x1() - a.x0() + 1 == 34 ? 34 : a.z1() - a.z0() + 1);
            assertEquals(34, a.y1() - a.y0() + 1);
        }
    }

    @Test
    void multiLevelStationsConnectEveryLevelFromTheArrival()
    {
        boolean sawUpper = false;
        for (var maker : Maker.values())
            for (long seed = 1; seed <= 20; seed++)
            {
                var plan = StationPlanner.generate(spec(maker, StationSpec.Purpose.RESEARCH, StationSpec.Size.LARGE, seed * 13));
                if (plan.levels() > 1)
                {
                    sawUpper = true;
                    var reach = plan.reachable(plan.arrival());
                    for (var room : plan.rooms())
                        assertTrue(reach.contains(room.id()));
                    var hall = plan.room(plan.arrival());
                    assertTrue(hall.levels() >= 2 && hall.climb() != null, "Upper levels are reached by the arrival atrium's stair");
                }
            }
        assertTrue(sawUpper);
    }

    @Test
    void heightFollowsTheSize()
    {
        var seen = new java.util.EnumMap<StationSpec.Size, java.util.Set<Integer>>(StationSpec.Size.class);
        for (var size : StationSpec.Size.values())
            seen.put(size, new java.util.TreeSet<>());
        for (var maker : Maker.values())
            for (var size : StationSpec.Size.values())
                for (long seed = 1; seed <= 12; seed++)
                {
                    var plan = StationPlanner.generate(spec(maker, StationSpec.Purpose.HARBOR, size, seed * 29));
                    var range = StationBuilder.levelRange(size);
                    var hall = plan.room(plan.arrival());
                    assertTrue(hall.levels() >= range[0] && hall.levels() <= range[1], plan.spec().description() + " has " + hall.levels() + " levels");
                    assertEquals(hall.levels(), plan.levels(), plan.spec().description() + " should use every level it stands");
                    seen.get(size).add(hall.levels());
                }
        assertTrue(seen.get(StationSpec.Size.COMPACT).contains(1), "Small stations are often one floor: " + seen);
        assertTrue(seen.get(StationSpec.Size.STANDARD).contains(2), "Medium stations are often two floors: " + seen);
        assertTrue(seen.get(StationSpec.Size.LARGE).stream().allMatch(l -> l >= 3), "Large stations are never lower than three floors: " + seen);
        assertTrue(seen.get(StationSpec.Size.LARGE).contains(4) || seen.get(StationSpec.Size.LARGE).contains(3), seen.toString());
    }

    private static List<StationPlan> sample()
    {
        var out = new ArrayList<StationPlan>();
        for (var maker : Maker.values())
            for (var purpose : StationSpec.Purpose.values())
                for (var size : StationSpec.Size.values())
                    out.add(StationPlanner.generate(spec(maker, purpose, size, 313 + purpose.ordinal() * 17)));
        return out;
    }

    @Test
    void theRoomGraphIsATree()
    {
        for (var plan : sample())
            assertEquals(plan.rooms().size() - 1, plan.doors().size(), plan.spec().description() + " should have no loops");
    }

    @Test
    void everyCorridorIsStraightAndFiveWide()
    {
        for (var plan : sample())
            for (var room : plan.rooms(Use.CORRIDOR))
                assertTrue(room.w() == 5 || room.d() == 5 || room.w() == room.d(), plan.spec().description() + " corridor " + room.id() + " is " + room.w() + " x " + room.d());
    }

    @Test
    void everyDoorIsCentredOnTheWallItJoins()
    {
        for (var plan : sample())
            for (var door : plan.doors())
            {
                var a = plan.room(door.a());
                var b = plan.room(door.b());
                var x = door.through().getAxis() == net.minecraft.core.Direction.Axis.X;
                // The doorway sits at the middle of the interiors' overlap along the wall.
                int lo = x ? Math.max(a.interior().z0(), b.interior().z0()) : Math.max(a.interior().x0(), b.interior().x0());
                int hi = x ? Math.min(a.interior().z1(), b.interior().z1()) : Math.min(a.interior().x1(), b.interior().x1());
                assertEquals((lo + hi) / 2, x ? door.at().getZ() : door.at().getX(), plan.spec().description() + " door " + door);
            }
    }

    @Test
    void roomsFaceEachOtherAcrossTheirCarrier()
    {
        int rooms = 0, mirrored = 0;
        for (var plan : sample())
        {
            var structural = EnumSet.of(Use.CORRIDOR, Use.ARRIVAL, Use.DOCK);
            for (var door : plan.doors())
            {
                var carrier = plan.room(door.a());
                var room = plan.room(door.b());
                if (carrier.use() != Use.CORRIDOR && carrier.use() != Use.ARRIVAL || structural.contains(room.use()))
                    continue;
                rooms++;
                boolean alongX = door.through().getAxis() == net.minecraft.core.Direction.Axis.Z;
                for (var other : plan.doors())
                {
                    if (other == door || !other.a().equals(carrier.id()))
                        continue;
                    var twin = plan.room(other.b());
                    if (structural.contains(twin.use()) || twin.y() != room.y() || twin.w() != room.w() || twin.d() != room.d() || other.through() != door.through().getOpposite())
                        continue;
                    if (alongX ? twin.x() == room.x() : twin.z() == room.z())
                    {
                        mirrored++;
                        break;
                    }
                }
            }
        }
        assertTrue(rooms > 0 && mirrored >= rooms * 0.6, mirrored + " of " + rooms + " bay rooms have a mirrored twin");
    }
}
