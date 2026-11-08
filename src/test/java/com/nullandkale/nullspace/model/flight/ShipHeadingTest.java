package com.nullandkale.nullspace.model.flight;

import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipHeadingTest
{
    @Test
    void thrustOpposesTheMainExhaustForEveryHorizontalDirection()
    {
        for (var exhaust : Direction.Plane.HORIZONTAL)
        {
            var bow = ShipHeading.fromExhausts(List.of(exhaust, exhaust));
            assertEquals(exhaust.getOpposite(), bow);
            var step = FlightDynamics.step(new FlightDynamics.State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0), FlightDynamics.FORWARD, 2, bow.toYRot());
            assertTrue(step.velocity().dot(new Vec3(exhaust.getStepX(), 0, exhaust.getStepZ())) < 0);
        }
    }

    @Test
    void mainEnginesWinOverSideThrustersAndTiesAreStable()
    {
        assertEquals(Direction.NORTH, ShipHeading.fromExhausts(List.of(Direction.SOUTH, Direction.EAST, Direction.SOUTH)));
        assertEquals(ShipHeading.fromExhausts(List.of(Direction.EAST, Direction.SOUTH)), ShipHeading.fromExhausts(List.of(Direction.SOUTH, Direction.EAST)));
        assertEquals(Direction.NORTH, ShipHeading.fromExhausts(List.of(Direction.UP, Direction.DOWN)));
    }
}
