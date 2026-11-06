package com.nullandkale.nullspace.navigation;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Deterministic 20 Hz arcade flight; center-of-mass rotation, bounded inputs and damped
 * translation.
 */
public final class FlightDynamics {
  public static final int FORWARD = 1,
      REVERSE = 2,
      LEFT = 4,
      RIGHT = 8,
      UP = 16,
      DOWN = 32,
      ROLL_LEFT = 64,
      ROLL_RIGHT = 128,
      PITCH_UP = 256,
      PITCH_DOWN = 512,
      BRAKE = 1024,
      FIRE = 2048,
      ALL = 4095;
  public static final double LOCAL_RADIUS = 8192;

  public record State(Vec3 center, Vec3 velocity, double yaw, double pitch, double roll) {}

  public record ArrivalFrame(double yaw, double pitch, double roll, double altitude) {}

  /** Vary the rendered flight frame and altitude; crew keep their upright physical cabin. */
  public static ArrivalFrame randomArrival(java.util.random.RandomGenerator random) {
    return new ArrivalFrame(
        random.nextDouble(-180, 180),
        random.nextDouble(-65, 65),
        random.nextDouble(-180, 180),
        random.nextDouble(-240, 240));
  }

  public static int axis(int bits, int positive, int negative) {
    return ((bits & positive) != 0 ? 1 : 0) - ((bits & negative) != 0 ? 1 : 0);
  }

  public static double wrap(double angle) {
    return ((angle + 180) % 360 + 360) % 360 - 180;
  }

  public static Quaterniond orientation(double yaw, double pitch, double roll) {
    return new Quaterniond()
        .rotationYXZ(Math.toRadians(yaw), Math.toRadians(pitch), Math.toRadians(roll));
  }

  public static Vec3 rotate(Quaterniond q, Vec3 p) {
    var v = q.transform(new Vector3d(p.x, p.y, p.z));
    return new Vec3(v.x, v.y, v.z);
  }

  public static State step(State old, int bits, int power, float forwardYaw) {
    if ((bits & ~ALL) != 0 || power < 0 || power > 8 || !Float.isFinite(forwardYaw))
      throw new IllegalArgumentException("Invalid flight input/power");
    if (!finite(old.center)
        || !finite(old.velocity)
        || !Double.isFinite(old.yaw)
        || !Double.isFinite(old.pitch)
        || !Double.isFinite(old.roll)) throw new IllegalArgumentException("Invalid flight state");
    double angleLocal = Math.toRadians(forwardYaw);
    var localForward = new Vec3(-Math.sin(angleLocal), 0, Math.cos(angleLocal));
    var right = localForward.cross(new Vec3(0, 1, 0));
    var rotation =
        orientation(old.yaw, old.pitch, old.roll)
            .rotateY(Math.toRadians(-axis(bits, RIGHT, LEFT) * 2.25))
            .rotateAxis(
                Math.toRadians(axis(bits, PITCH_UP, PITCH_DOWN) * 1.75), right.x, right.y, right.z)
            .rotateAxis(
                Math.toRadians(axis(bits, ROLL_RIGHT, ROLL_LEFT) * 2.25),
                localForward.x,
                localForward.y,
                localForward.z);
    var euler = rotation.getEulerAnglesYXZ(new Vector3d());
    double yaw = wrap(Math.toDegrees(euler.y)),
        pitch = Math.toDegrees(euler.x),
        roll = wrap(Math.toDegrees(euler.z));
    var q = orientation(yaw, pitch, roll);
    double angle = Math.toRadians(forwardYaw);
    var forward = rotate(q, new Vec3(-Math.sin(angle), 0, Math.cos(angle)));
    var up = rotate(q, new Vec3(0, 1, 0));
    var acceleration =
        forward.scale(axis(bits, FORWARD, REVERSE)).add(up.scale(axis(bits, UP, DOWN)));
    if (acceleration.lengthSqr() > 1) acceleration = acceleration.normalize();
    var velocity =
        old.velocity.scale((bits & BRAKE) != 0 ? .65 : .975).add(acceleration.scale(power * .6));
    double limit = 12 + power * 8;
    if (velocity.length() > limit) velocity = velocity.normalize().scale(limit);
    if (velocity.lengthSqr() < .0001) velocity = Vec3.ZERO;
    if (power == 0) {
      velocity = Vec3.ZERO;
      yaw = old.yaw;
      pitch = old.pitch;
      roll = old.roll;
    }
    return new State(old.center.add(velocity.scale(.05)), velocity, yaw, pitch, roll);
  }

  public static boolean finite(Vec3 p) {
    return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
  }

  private FlightDynamics() {}
}
