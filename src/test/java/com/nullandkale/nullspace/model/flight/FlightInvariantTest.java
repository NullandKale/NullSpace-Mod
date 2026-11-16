package com.nullandkale.nullspace.model.flight;

import com.nullandkale.nullspace.model.space.ScenePose;

import java.util.*;
import java.util.stream.*;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class FlightInvariantTest
{
    @TestFactory
    Stream<DynamicTest> seededFlightsRemainBoundedAndTransformConsistent()
    {
        return IntStream.rangeClosed(1, 8).mapToObj(power -> DynamicTest.dynamicTest("long random flight with power " + power, () ->
        {
            var random = new Random(900 + power);
            var state = new FlightDynamics.State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0);
            for (int i = 0; i < 2000; i++)
            {
                var old = state;
                int bits = random.nextInt(FlightDynamics.ALL + 1);
                var result = FlightDynamics.simulate(old, bits, new FlightDynamics.Craft(200 + random.nextInt(3000), power, (bits & FlightDynamics.ASSIST_OFF) == 0), 90);
                state = result.state();
                assertTrue(FlightDynamics.finite(state.center()));
                assertTrue(FlightDynamics.finite(state.velocity()) && FlightDynamics.finite(state.spin()));
                assertTrue(state.velocity().length() <= FlightDynamics.ABSOLUTE_SPEED + 1e-8);
                assertTrue(result.thrust() >= 0 && result.thrust() <= 1 + 1e-9, "Thrust is a share of the main engines' push");
                assertTrue(state.center().subtract(old.center()).distanceTo(state.velocity().scale(FlightDynamics.DT)) < 1e-8);
                var pose = new ScenePose(state.center(), FlightDynamics.orientation(state.yaw(), state.pitch(), state.roll()));
                var local = new Vec3(3.5, -2.25, 7.75);
                assertTrue(pose.toLocal(pose.toUniverse(local)).distanceTo(local) < 1e-8);
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> brakeAlwaysBringsAMovingShipToRestWithoutSpeedingItUp()
    {
        return IntStream.range(0, 8).mapToObj(direction -> DynamicTest.dynamicTest("brake orientation " + direction, () ->
        {
            var velocity = FlightDynamics.rotate(FlightDynamics.orientation(direction * 45, 30, -17), new Vec3(0, 0, 50));
            var state = new FlightDynamics.State(Vec3.ZERO, velocity, 30, -20, 90);
            var craft = new FlightDynamics.Craft(FlightDynamics.REFERENCE_MASS, 8, true);
            for (int i = 0; i < 800; i++)
            {
                double speed = state.velocity().length();
                state = FlightDynamics.simulate(state, FlightDynamics.BRAKE, craft, 0).state();
                assertTrue(state.velocity().length() <= speed + 1e-9, "Braking never speeds the ship up");
            }
            assertEquals(Vec3.ZERO, state.velocity(), "Braking ends at rest");
        }));
    }

    @Test
    void allOpposingControlsCancelSimultaneously()
    {
        var state = new FlightDynamics.State(new Vec3(1, 2, 3), Vec3.ZERO, 20, -30, 40);
        var next = FlightDynamics.step(state, FlightDynamics.ALL & ~FlightDynamics.BRAKE & ~FlightDynamics.ASSIST_OFF, 8, 270);
        assertEquals(state.center(), next.center());
        assertEquals(Vec3.ZERO, next.velocity());
        assertEquals(state.yaw(), next.yaw(), 1e-8);
        assertEquals(state.pitch(), next.pitch(), 1e-8);
        assertEquals(state.roll(), next.roll(), 1e-8);
    }

    @Test
    void nonFiniteHelmYawCannotPoisonTheSimulation()
    {
        var state = new FlightDynamics.State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0);
        for (float yaw : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> FlightDynamics.step(state, FlightDynamics.FORWARD, 1, yaw));
    }
}
