package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Role;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipPlannerTest
{
    private static List<ShipSpec> specs(int seeds)
    {
        var out = new ArrayList<ShipSpec>();
        for (var maker : Maker.values())
            for (var size : ShipSpec.Size.values())
                for (var role : Role.values())
                    for (long seed = 1; seed <= seeds; seed++)
                        out.add(new ShipSpec(maker, size, role, seed * 7919 + role.ordinal()));
        return out;
    }

    @Test
    void everyBriefPlansAShipInsideTheLimitWithItsRamp()
    {
        for (var spec : specs(12))
        {
            var plan = assertDoesNotThrow(() -> ShipPlanner.generate(spec), spec.description());
            assertTrue(plan.problems().isEmpty(), spec.description() + ": " + plan.problems());
            assertTrue(plan.width() <= 32 && plan.depth() + ShipPlanner.RAMP <= 32 && plan.height() <= 32, spec.description() + " is " + plan.width() + " x " + plan.height() + " x " + plan.depth());
            assertEquals(spec, spec, "stable");
            assertEquals(plan, ShipPlanner.generate(spec), "the same brief plans the same ship");
        }
    }

    @Test
    void everyShipHasABridgeAnEngineAndRoomsOfBothSizes()
    {
        for (var spec : specs(8))
        {
            var plan = ShipPlanner.generate(spec);
            assertEquals(1, plan.rooms(Use.BRIDGE).size(), spec.description());
            assertEquals(1, plan.rooms(Use.ENGINEERING).size(), spec.description());
            assertEquals(1, plan.berths().size(), "one docking door");
            assertEquals(plan.arrival(), plan.rooms(Use.BRIDGE).getFirst().id());
            int most = 0, least = Integer.MAX_VALUE;
            for (var room : plan.rooms())
            {
                if (room.use() == Use.CORRIDOR || room.use() == Use.ARRIVAL || room.use() == Use.BRIDGE || room.use() == Use.ENGINEERING)
                    continue;
                most = Math.max(most, room.w() * room.d());
                least = Math.min(least, room.w() * room.d());
            }
            assertTrue(most >= least * 1.25, spec.description() + " has large and small rooms: " + least + " to " + most);
            int decks = plan.levels();
            assertTrue(decks >= spec.size().decks && decks <= spec.size().decks + 1, spec.description() + " stands on " + decks + " decks");
        }
    }

    @Test
    void roomsComeInMirroredPairsAndEveryDesignedRoomHasOneDoor()
    {
        for (var spec : specs(8))
        {
            var plan = ShipPlanner.generate(spec);
            var bridge = plan.rooms(Use.BRIDGE).getFirst();
            int sum = 2 * bridge.x() + bridge.w() - 1;
            for (var room : plan.rooms())
            {
                int mirroredX = sum - (room.x() + room.w() - 1);
                assertTrue(plan.rooms().stream().anyMatch(o -> o.x() == mirroredX && o.y() == room.y() && o.z() == room.z() && o.w() == room.w() && o.d() == room.d() && o.levels() == room.levels()), spec.description() + ": " + room.id() + " has no mirror");
                if (StationRooms.furnishes(room.use()))
                    assertEquals(1, plan.doors().stream().filter(d -> d.a().equals(room.id()) || d.b().equals(room.id())).count(), spec.description() + ": " + room.id() + " has one door");
            }
        }
    }

    @Test
    void rolesChooseTheirRooms()
    {
        int cargo = 0, lab = 0;
        for (long seed = 1; seed <= 20; seed++)
        {
            if (!ShipPlanner.generate(new ShipSpec(Maker.RIVET, ShipSpec.Size.MEDIUM, Role.FREIGHTER, seed)).rooms(Use.CARGO_HOLD).isEmpty())
                cargo++;
            if (!ShipPlanner.generate(new ShipSpec(Maker.RIVET, ShipSpec.Size.MEDIUM, Role.EXPLORER, seed)).rooms(Use.LAB).isEmpty())
                lab++;
        }
        assertEquals(20, cargo, "a freighter always carries a hold");
        assertEquals(20, lab, "an explorer always has a lab");
    }
}
