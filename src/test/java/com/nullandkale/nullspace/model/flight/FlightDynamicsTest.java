package com.nullandkale.nullspace.model.flight;

import com.nullandkale.nullspace.model.flight.FlightDynamics.Craft;
import com.nullandkale.nullspace.model.flight.FlightDynamics.State;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlightDynamicsTest
{
    private static final double REFERENCE = FlightDynamics.REFERENCE_MASS;

    private State idle()
    {
        return new State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0);
    }

    private State fly(State state, int bits, Craft craft, float yaw, int ticks)
    {
        for (int i = 0; i < ticks; i++)
            state = FlightDynamics.simulate(state, bits, craft, yaw).state();
        return state;
    }

    @Test
    void arrivalsVaryInThreeDimensionsAndUseFiniteNormalizedRotations()
    {
        var random = new java.util.Random(34);
        var headings = new java.util.HashSet<Integer>();
        var heights = new java.util.HashSet<Integer>();
        boolean banked = false, pitched = false;
        for (int i = 0; i < 64; i++)
        {
            var frame = FlightDynamics.randomArrival(random);
            var q = FlightDynamics.orientation(frame.yaw(), frame.pitch(), frame.roll());
            assertEquals(1, q.lengthSquared(), 1e-10);
            var up = FlightDynamics.rotate(q, new Vec3(0, 1, 0));
            var forward = FlightDynamics.rotate(q, new Vec3(0, 0, 1));
            banked |= Math.abs(up.x) > .2;
            pitched |= Math.abs(forward.y) > .2;
            assertTrue(Double.isFinite(frame.altitude()) && Math.abs(frame.altitude()) <= 240);
            headings.add((int) frame.yaw());
            heights.add((int) frame.altitude());
        }
        assertTrue(banked && pitched && headings.size() > 40 && heights.size() > 40, "Crews must not all arrive level or on the same horizontal plane");
    }

    @Test
    void forwardFollowsEveryHelmFacing()
    {
        for (float yaw : new float[]{0, 90, 180, 270})
        {
            var s = FlightDynamics.step(idle(), FlightDynamics.FORWARD, 2, yaw);
            var expected = new Vec3(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            assertTrue(s.velocity().normalize().distanceTo(expected) < 1e-8);
        }
    }

    @Test
    void yawTurnsRightAndPitchLiftsNoseForEveryHelmFacing()
    {
        for (float yaw : new float[]{0, 90, 180, 270})
        {
            double a = Math.toRadians(yaw);
            var forward = new Vec3(-Math.sin(a), 0, Math.cos(a));
            var right = forward.cross(new Vec3(0, 1, 0));
            var craft = new Craft(REFERENCE, 2, true);
            var turned = fly(idle(), FlightDynamics.RIGHT | FlightDynamics.FORWARD, craft, yaw, 20);
            assertTrue(turned.velocity().dot(right) > 0, "Yaw right sends the ship right");
            var pitched = fly(idle(), FlightDynamics.PITCH_UP | FlightDynamics.FORWARD, craft, yaw, 20);
            assertTrue(pitched.velocity().y > 0, "Pitch up lifts the nose");
        }
    }

    @Test
    void withNoInputAndAssistOffAShipKeepsItsVelocityAndSpinForever()
    {
        var moving = new State(Vec3.ZERO, new Vec3(20, 3, -5), 10, 5, 0, new Vec3(30, 0, -10));
        var craft = new Craft(REFERENCE, 4, false);
        var later = fly(moving, FlightDynamics.ASSIST_OFF, craft, 0, 400);
        assertEquals(moving.velocity().x, later.velocity().x, 1e-9);
        assertEquals(moving.velocity().y, later.velocity().y, 1e-9);
        assertEquals(moving.velocity().z, later.velocity().z, 1e-9);
        assertEquals(moving.spin().x, later.spin().x, 1e-9);
        assertEquals(moving.spin().z, later.spin().z, 1e-9);
        assertNotEquals(moving.yaw(), later.yaw(), "A spinning ship keeps turning");
    }

    @Test
    void losingPowerLeavesTheShipDriftingAtTheSpeedItHad()
    {
        var moving = new State(Vec3.ZERO, new Vec3(5, 0, 0), 30, 20, 10, new Vec3(20, 0, 0));
        var after = fly(moving, FlightDynamics.FORWARD | FlightDynamics.RIGHT, new Craft(REFERENCE, 0, true), 0, 20);
        assertEquals(5, after.velocity().x, 1e-9, "No power, no push and no braking");
        assertEquals(5 * 20 * FlightDynamics.DT, after.center().x, 1e-9, "It keeps moving");
        assertEquals(20, after.spin().x, 1e-9, "And keeps turning");
    }

    @Test
    void assistStopsTheShipButItTakesRealTimeDistanceAndFuel()
    {
        var craft = new Craft(REFERENCE, 3, true);
        var state = new State(Vec3.ZERO, new Vec3(0, 0, 30), 0, 0, 0);
        var first = FlightDynamics.simulate(state, 0, craft, 0);
        assertTrue(first.state().velocity().length() > 25, "Letting go does not stop the ship at once");
        assertTrue(first.thrust() > 0, "Braking burns the engines");
        int ticks = 0;
        double distance = 0;
        while (state.velocity().lengthSqr() > 0 && ticks < 2000)
        {
            var next = FlightDynamics.simulate(state, 0, craft, 0);
            distance += next.state().center().distanceTo(state.center());
            state = next.state();
            ticks++;
        }
        assertEquals(Vec3.ZERO, state.velocity(), "It does come to rest");
        assertTrue(ticks > 40 && ticks < 600, "Stopping from 30 takes seconds, not a tick: " + ticks + " ticks");
        assertTrue(distance > 10, "And covers distance: " + distance);
    }

    @Test
    void assistNeverAsksMoreOfTheEnginesThanTheyCanGive()
    {
        var craft = new Craft(REFERENCE, 3, true);
        double most = FlightDynamics.mainAcceleration(craft) * FlightDynamics.DT;
        var state = idle();
        for (int tick = 0; tick < 300; tick++)
        {
            var next = FlightDynamics.simulate(state, tick < 150 ? FlightDynamics.FORWARD : FlightDynamics.BRAKE, craft, 0).state();
            assertTrue(next.velocity().subtract(state.velocity()).length() <= most * 1.0001, "A tick changes the velocity by at most the main engines' push");
            state = next;
        }
    }

    @Test
    void aHeavierShipAcceleratesAndTurnsMoreSlowlyThanALightOne()
    {
        var light = new Craft(REFERENCE, 4, true);
        var heavy = new Craft(REFERENCE * 3, 4, true);
        double lightSpeed = fly(idle(), FlightDynamics.FORWARD, light, 0, 30).velocity().length();
        double heavySpeed = fly(idle(), FlightDynamics.FORWARD, heavy, 0, 30).velocity().length();
        assertEquals(3, lightSpeed / heavySpeed, .01, "Three times the mass, a third of the acceleration");
        double lightTurn = Math.abs(fly(idle(), FlightDynamics.RIGHT, light, 0, 40).yaw());
        double heavyTurn = Math.abs(fly(idle(), FlightDynamics.RIGHT, heavy, 0, 40).yaw());
        assertTrue(heavyTurn < lightTurn * .6, "A heavy ship turns more slowly");
    }

    @Test
    void moreEnginePowerPushesHarder()
    {
        double slow = fly(idle(), FlightDynamics.FORWARD, new Craft(REFERENCE, 2, true), 0, 30).velocity().length();
        double quick = fly(idle(), FlightDynamics.FORWARD, new Craft(REFERENCE, 6, true), 0, 30).velocity().length();
        assertEquals(3, quick / slow, .01);
    }

    @Test
    void theReferenceShipReachesTwentyBlocksASecondInAboutSixSeconds()
    {
        var craft = new Craft(REFERENCE, 3, false);
        var state = idle();
        int ticks = 0;
        while (state.velocity().length() < 20 && ticks < 1000)
        {
            state = FlightDynamics.simulate(state, FlightDynamics.FORWARD | FlightDynamics.ASSIST_OFF, craft, 0).state();
            ticks++;
        }
        assertEquals(6, ticks * FlightDynamics.DT, 1, "Seconds to reach 20 blocks a second");
    }

    @Test
    void assistHoldsTheGovernorButWithoutAssistTheShipKeepsSpeeding()
    {
        var assisted = fly(idle(), FlightDynamics.FORWARD, new Craft(REFERENCE, 4, true), 0, 2000);
        assertEquals(FlightDynamics.governor(new Craft(REFERENCE, 4, true)), assisted.velocity().length(), .5, "Assist cruises at the governor");
        var raw = fly(idle(), FlightDynamics.FORWARD | FlightDynamics.ASSIST_OFF, new Craft(REFERENCE, 4, false), 0, 2000);
        assertTrue(raw.velocity().length() > assisted.velocity().length() * 2, "Without assist nothing holds it back");
        assertTrue(raw.velocity().length() <= FlightDynamics.ABSOLUTE_SPEED + 1e-9, "Except the absolute limit");
    }

    @Test
    void spinCarriesOnAfterTheKeyIsReleasedAndAssistDampsItOverTime()
    {
        var craft = new Craft(REFERENCE, 3, false);
        var spinning = fly(idle(), FlightDynamics.RIGHT | FlightDynamics.ASSIST_OFF, craft, 0, 10);
        assertTrue(spinning.spin().x > 0);
        var coasting = fly(spinning, FlightDynamics.ASSIST_OFF, craft, 0, 40);
        assertEquals(spinning.spin().x, coasting.spin().x, 1e-9, "Released, a ship without assist keeps spinning");
        var assisted = new Craft(REFERENCE, 3, true);
        var damped = FlightDynamics.simulate(new State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0, spinning.spin()), 0, assisted, 0).state();
        assertTrue(damped.spin().x < spinning.spin().x && damped.spin().x > 0, "Assist slows the spin, but not to nothing in a tick");
        assertEquals(0, fly(new State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0, spinning.spin()), 0, assisted, 0, 400).spin().x, 1e-9, "In the end it stops");
    }

    @Test
    void invalidStateAndUnboundedControlsAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> FlightDynamics.step(idle(), FlightDynamics.ALL + 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> FlightDynamics.step(idle(), 0, 9, 0));
        assertThrows(IllegalArgumentException.class, () -> FlightDynamics.step(new State(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, 0, 0, 0), 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> FlightDynamics.step(new State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0, new Vec3(Double.NaN, 0, 0)), 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> FlightDynamics.simulate(idle(), 0, new Craft(0, 1, true), 0));
    }

    @Test
    void rotationsStayFiniteThroughRepeatedLoopsAndRolls()
    {
        var s = fly(idle(), FlightDynamics.PITCH_UP | FlightDynamics.RIGHT | FlightDynamics.ROLL_LEFT, new Craft(REFERENCE, 1, true), 90, 3000);
        assertTrue(Double.isFinite(s.yaw()) && Double.isFinite(s.pitch()) && Double.isFinite(s.roll()));
        assertEquals(1, FlightDynamics.orientation(s.yaw(), s.pitch(), s.roll()).lengthSquared(), 1e-8);
    }
}
