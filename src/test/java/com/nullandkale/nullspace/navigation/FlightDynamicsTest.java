package com.nullandkale.nullspace.navigation;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FlightDynamicsTest {
  private FlightDynamics.State idle() {
    return new FlightDynamics.State(Vec3.ZERO, Vec3.ZERO, 0, 0, 0);
  }

  @Test
  void forwardFollowsEveryHelmFacing() {
    for (float yaw : new float[] {0, 90, 180, 270}) {
      var s = FlightDynamics.step(idle(), FlightDynamics.FORWARD, 2, yaw);
      var expected = new Vec3(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
      assertTrue(s.velocity().normalize().distanceTo(expected) < 1e-8);
    }
  }

  @Test
  void yawTurnsRightAndPitchLiftsNoseForEveryHelmFacing() {
    for (float yaw : new float[] {0, 90, 180, 270}) {
      double a = Math.toRadians(yaw);
      var forward = new Vec3(-Math.sin(a), 0, Math.cos(a));
      var right = forward.cross(new Vec3(0, 1, 0));
      var s = FlightDynamics.step(idle(), FlightDynamics.RIGHT | FlightDynamics.FORWARD, 2, yaw);
      assertTrue(s.velocity().dot(right) > 0);
      s = FlightDynamics.step(idle(), FlightDynamics.PITCH_UP | FlightDynamics.FORWARD, 2, yaw);
      assertTrue(s.velocity().y > 0);
    }
  }

  @Test
  void diagonalThrustDoesNotAccelerateFasterAndSpeedIsBounded() {
    var straight = FlightDynamics.step(idle(), FlightDynamics.FORWARD, 8, 180);
    var diagonal = FlightDynamics.step(idle(), FlightDynamics.FORWARD | FlightDynamics.UP, 8, 180);
    assertEquals(straight.velocity().length(), diagonal.velocity().length(), 1e-8);
    var s = idle();
    for (int n = 0; n < 500; n++) s = FlightDynamics.step(s, FlightDynamics.FORWARD, 8, 180);
    assertTrue(s.velocity().length() <= 76.00001);
  }

  @Test
  void opposingInputsCancelAndBrakesDampMomentum() {
    assertEquals(
        Vec3.ZERO,
        FlightDynamics.step(idle(), FlightDynamics.FORWARD | FlightDynamics.REVERSE, 8, 0)
            .velocity());
    var moving = new FlightDynamics.State(Vec3.ZERO, new Vec3(20, 0, 0), 0, 0, 0);
    assertTrue(
        FlightDynamics.step(moving, FlightDynamics.BRAKE, 8, 0).velocity().length()
            < FlightDynamics.step(moving, 0, 8, 0).velocity().length());
  }

  @Test
  void powerLossStopsThrustAndRotation() {
    var s =
        FlightDynamics.step(
            new FlightDynamics.State(Vec3.ZERO, new Vec3(5, 0, 0), 30, 20, 10),
            FlightDynamics.FORWARD | FlightDynamics.RIGHT,
            0,
            0);
    assertEquals(Vec3.ZERO, s.center());
    assertEquals(Vec3.ZERO, s.velocity());
    assertEquals(30, s.yaw());
    assertEquals(20, s.pitch());
  }

  @Test
  void invalidStateAndUnboundedControlsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> FlightDynamics.step(idle(), 8192, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> FlightDynamics.step(idle(), 0, 9, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FlightDynamics.step(
                new FlightDynamics.State(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, 0, 0, 0), 0, 1, 0));
  }

  @Test
  void rotationsStayFiniteThroughRepeatedLoopsAndRolls() {
    var s = idle();
    for (int i = 0; i < 3000; i++)
      s =
          FlightDynamics.step(
              s, FlightDynamics.PITCH_UP | FlightDynamics.RIGHT | FlightDynamics.ROLL_LEFT, 1, 90);
    assertTrue(Double.isFinite(s.yaw()) && Double.isFinite(s.pitch()) && Double.isFinite(s.roll()));
    assertEquals(1, FlightDynamics.orientation(s.yaw(), s.pitch(), s.roll()).lengthSquared(), 1e-8);
  }
}
