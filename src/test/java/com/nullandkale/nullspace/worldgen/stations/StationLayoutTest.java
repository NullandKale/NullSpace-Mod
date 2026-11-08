package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.space.ScenePose;

import java.util.*;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StationLayoutTest
{
    @Test
    void generatedLayoutsAreDeterministicConnectedAndBounded()
    {
        for (long seed = 0; seed < 500; seed++)
        {
            var layout = StationLayout.generate(seed);
            assertEquals(layout, StationLayout.generate(seed));
            assertEquals(4, layout.rooms().size());
            assertTrue(layout.joints().size() >= 3);
            assertEquals(4, layout.rooms().stream().map(StationLayout.Room::role).distinct().count());
            for (var room : layout.rooms())
                assertTrue(room.origin().getX() >= 0 && room.origin().getZ() >= 0 && room.origin().getX() + 16 <= 32 && room.origin().getZ() + 16 <= 32);
        }
    }

    @Test
    void disconnectedOverlappingAndNonadjacentLayoutsAreRejected()
    {
        var a = new StationLayout.Room("a", StationLayout.Role.ARRIVAL, 0, 0);
        var b = new StationLayout.Room("b", StationLayout.Role.ENGINEERING, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new StationLayout.Layout(List.of(a, b), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StationLayout.Layout(List.of(a, b), List.of(new StationLayout.Joint("a", "b"))));
        assertThrows(IllegalArgumentException.class, () -> new StationLayout.Layout(List.of(a, new StationLayout.Room("b", StationLayout.Role.ENGINEERING, 0, 0)), List.of()));
    }

    @Test
    void everyPortFacingMatesUnderArbitraryTargetOrientation()
    {
        for (var a : Direction.Plane.HORIZONTAL)
            for (var b : Direction.Plane.HORIZONTAL)
                for (int n = 0; n < 12; n++)
                {
                    var target = new ScenePose(new Vec3(150, -30, 600), FlightDynamics.orientation(n * 30, 19, -23));
                    var source = new Vec3(2.5, 5.5, 8);
                    var destination = new Vec3(31, 5.5, 8);
                    var result = DockingMotion.mate(source, a, target, destination, b, .05);
                    var normal = FlightDynamics.rotate(target.orientation(), DockingMotion.normal(b));
                    assertTrue(result.toUniverse(source).distanceTo(target.toUniverse(destination).add(normal.scale(.05))) < 1e-8);
                    assertTrue(FlightDynamics.rotate(result.orientation(), DockingMotion.normal(a)).distanceTo(normal.scale(-1)) < 1e-8);
                    assertTrue(FlightDynamics.rotate(result.orientation(), new Vec3(0, 1, 0)).distanceTo(FlightDynamics.rotate(target.orientation(), new Vec3(0, 1, 0))) < 1e-8);
                }
    }

    @Test
    void automaticRoutesGoAroundObstaclesAndRejectImpossibleEndpoints()
    {
        var obstacles = List.of(new DockingMotion.Obstacle(new Vec3(0, 0, 0), 35));
        var start = new Vec3(-150, 0, 0);
        var goal = new Vec3(150, 0, 0);
        var route = DockingMotion.route(start, goal, obstacles);
        assertEquals(goal, route.getLast());
        assertTrue(route.size() > 1);
        var previous = start;
        for (var p : route)
        {
            assertTrue(DockingMotion.clear(previous, p, obstacles, 29));
            previous = p;
        }
        assertThrows(IllegalArgumentException.class, () -> DockingMotion.route(start, Vec3.ZERO, obstacles));
    }

    @Test
    void movementAndRotationAreBounded()
    {
        var old = ScenePose.identity();
        var goal = new ScenePose(new Vec3(100, 30, -8), FlightDynamics.orientation(170, 20, 30));
        var next = DockingMotion.step(old, goal, .35, Math.toRadians(2));
        assertEquals(.35, next.position().length(), 1e-8);
        assertTrue(2 * Math.acos(Math.abs(next.orientation().dot(old.orientation()))) <= Math.toRadians(2) + 1e-8);
    }

    @Test
    void cancelledCloseApproachCanRetreatWithoutMovingDeeperIntoAMargin()
    {
        var obstacles = List.of(new DockingMotion.Obstacle(Vec3.ZERO, 28));
        var close = new Vec3(40, 0, 0);
        var safe = new Vec3(120, 0, 0);
        assertTrue(DockingMotion.clear(close, safe, obstacles, 29));
        assertEquals(List.of(safe), DockingMotion.route(close, safe, obstacles));
        assertFalse(DockingMotion.clear(close, new Vec3(20, 0, 0), obstacles, 29));
        assertFalse(DockingMotion.clear(close, close, obstacles, 29));
    }
}
