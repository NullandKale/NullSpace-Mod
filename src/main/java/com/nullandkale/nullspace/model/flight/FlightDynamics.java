package com.nullandkale.nullspace.model.flight;

import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Deterministic 20 Hz flight with real momentum. A ship keeps its velocity and spin until engines or thrusters change them:
 * there is no drag, no instant stop, and losing power leaves it drifting. How hard it can push or turn depends on its mass,
 * so a big ship is sluggish and a small one nimble.
 *
 * <p>With <b>flight assist</b> on (the default, and always for the autopilot) the pilot's keys ask for a motion (forward, turn,
 * stop) and the computer chooses the thrust and torque to get there, but only as much as the engines and thrusters can give, so
 * stopping still takes time, distance and fuel. With assist off the keys fire the engines and thrusters directly and nothing
 * damps anything. The model is a pure function of the previous state and the inputs.
 */
public final class FlightDynamics
{
    public static final int FORWARD = 1, REVERSE = 2, LEFT = 4, RIGHT = 8, UP = 16, DOWN = 32, ROLL_LEFT = 64, ROLL_RIGHT = 128, PITCH_UP = 256, PITCH_DOWN = 512, BRAKE = 1024, FIRE = 2048, ASSIST_OFF = 4096, ALL = 8191;
    public static final double LOCAL_RADIUS = 8192;

    /** One server tick, in seconds. */
    public static final double DT = .05;
    /** The hull mass, in blocks, of the reference ship the constants are tuned for: it reaches 20 blocks a second in about six seconds on power 3. */
    public static final double REFERENCE_MASS = 800;
    /** Main-engine acceleration, in blocks a second squared, for each level of engine power on the reference ship. */
    public static final double ACCELERATION_PER_POWER = 1.1;
    /** The thrusters' share of the main engines' push, for reverse, sideways and up or down. */
    public static final double RETRO = .6, SIDE = .6, VERTICAL = .5;
    /** The speed no ship passes, assist or not, so the local space and the arithmetic stay in range. */
    public static final double ABSOLUTE_SPEED = 400;
    /** The fastest spin, in degrees a second, with assist off. */
    public static final double MOST_SPIN = 120;

    /** {@code spin} is the turn rates in degrees a second as (yaw, pitch, roll), in the same sense as the keys that make them. */
    public record State(Vec3 center, Vec3 velocity, double yaw, double pitch, double roll, Vec3 spin)
    {
        public State(Vec3 center, Vec3 velocity, double yaw, double pitch, double roll)
        {
            this(center, velocity, yaw, pitch, roll, Vec3.ZERO);
        }
    }

    /** What the ship is made of: {@code mass} is its hull in blocks, {@code power} the engine power (0 to 8). */
    public record Craft(double mass, int power, boolean assist)
    {}

    /** The next state and {@code thrust}, the share (0 to 1) of the main engines' push used this tick: fuel and the exhaust follow it. */
    public record Result(State state, double thrust)
    {}

    public record ArrivalFrame(double yaw, double pitch, double roll, double altitude)
    {}

    /** Vary the rendered flight frame and altitude; crew keep their upright physical cabin. */
    public static ArrivalFrame randomArrival(java.util.random.RandomGenerator random)
    {
        return new ArrivalFrame(random.nextDouble(-180, 180), random.nextDouble(-65, 65), random.nextDouble(-180, 180), random.nextDouble(-240, 240));
    }

    public static int axis(int bits, int positive, int negative)
    {
        return ((bits & positive) != 0 ? 1 : 0) - ((bits & negative) != 0 ? 1 : 0);
    }

    public static double wrap(double angle)
    {
        return ((angle + 180) % 360 + 360) % 360 - 180;
    }

    public static Quaterniond orientation(double yaw, double pitch, double roll)
    {
        return new Quaterniond().rotationYXZ(Math.toRadians(yaw), Math.toRadians(pitch), Math.toRadians(roll));
    }

    public static Vec3 rotate(Quaterniond q, Vec3 p)
    {
        var v = q.transform(new Vector3d(p.x, p.y, p.z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** The main-engine acceleration of a craft, in blocks a second squared. */
    public static double mainAcceleration(Craft craft)
    {
        return ACCELERATION_PER_POWER * craft.power * REFERENCE_MASS / Math.clamp(craft.mass, 150, 6000);
    }

    /** The speed assist aims for at full stick: the old cap, so travel times stay familiar. */
    public static double governor(Craft craft)
    {
        return 12 + craft.power * 8;
    }

    /** The turn-rate and spin-up of a craft, as a multiplier on the reference ship's: a heavier ship turns more slowly. */
    private static double agility(Craft craft)
    {
        return REFERENCE_MASS / Math.clamp(craft.mass, 150, 6000);
    }

    /** Compatibility step for a craft of the reference mass; assist is on unless {@link #ASSIST_OFF} is held. */
    public static State step(State old, int bits, int power, float forwardYaw)
    {
        return simulate(old, bits, new Craft(REFERENCE_MASS, power, (bits & ASSIST_OFF) == 0), forwardYaw).state();
    }

    public static Result simulate(State old, int bits, Craft craft, float forwardYaw)
    {
        if ((bits & ~ALL) != 0 || craft.power < 0 || craft.power > 8 || !Float.isFinite(forwardYaw) || !Double.isFinite(craft.mass) || craft.mass <= 0)
            throw new IllegalArgumentException("Invalid flight input/power");
        if (!finite(old.center) || !finite(old.velocity) || !finite(old.spin) || !Double.isFinite(old.yaw) || !Double.isFinite(old.pitch) || !Double.isFinite(old.roll))
            throw new IllegalArgumentException("Invalid flight state");
        boolean powered = craft.power > 0;
        double agility = agility(craft);
        double maxRate = 45 * Math.clamp(agility, .35, 1.2);
        double spinAccel = 120 * Math.clamp(agility, .35, 2);
        // Turning: the keys ask for a rate (assist) or a torque (no assist); the spin carries over to the next tick.
        double[] want = {axis(bits, RIGHT, LEFT), axis(bits, PITCH_UP, PITCH_DOWN), axis(bits, ROLL_RIGHT, ROLL_LEFT)};
        double[] spin = {old.spin.x, old.spin.y, old.spin.z};
        for (int i = 0; i < 3; i++)
        {
            double change = 0;
            if (powered)
                change = craft.assist ? Math.clamp((want[i] * maxRate - spin[i]) / .4, -spinAccel, spinAccel) * DT : want[i] * spinAccel * DT;
            spin[i] = Math.clamp(spin[i] + change, craft.assist ? -maxRate : -MOST_SPIN, craft.assist ? maxRate : MOST_SPIN);
            if (craft.assist && powered && want[i] == 0 && Math.abs(spin[i]) < .05)
                spin[i] = 0;
        }
        double angleLocal = Math.toRadians(forwardYaw);
        var localForward = new Vec3(-Math.sin(angleLocal), 0, Math.cos(angleLocal));
        var right = localForward.cross(new Vec3(0, 1, 0));
        var rotation = orientation(old.yaw, old.pitch, old.roll).rotateY(Math.toRadians(-spin[0] * DT)).rotateAxis(Math.toRadians(spin[1] * DT), right.x, right.y, right.z).rotateAxis(Math.toRadians(spin[2] * DT), localForward.x, localForward.y, localForward.z);
        var euler = rotation.getEulerAnglesYXZ(new Vector3d());
        double yaw = wrap(Math.toDegrees(euler.y)), pitch = Math.toDegrees(euler.x), roll = wrap(Math.toDegrees(euler.z));
        var q = orientation(yaw, pitch, roll);
        var forward = rotate(q, localForward);
        var up = rotate(q, new Vec3(0, 1, 0));
        var side = forward.cross(up);
        // Pushing: assist works out the acceleration that would reach the wanted velocity, then the engines and thrusters give what they can.
        double main = mainAcceleration(craft), retro = main * RETRO, sideways = main * SIDE, vertical = main * VERTICAL;
        double f = axis(bits, FORWARD, REVERSE), u = axis(bits, UP, DOWN);
        var acceleration = Vec3.ZERO;
        if (powered)
        {
            if (craft.assist)
            {
                var wanted = (bits & BRAKE) != 0 ? Vec3.ZERO : forward.scale(f * governor(craft)).add(up.scale(u * governor(craft) * .6));
                var needed = wanted.subtract(old.velocity).scale(1 / ((bits & BRAKE) != 0 ? .5 : 1.));
                double af = Math.clamp(needed.dot(forward), -retro, main), au = Math.clamp(needed.dot(up), -vertical, vertical), as = Math.clamp(needed.dot(side), -sideways, sideways);
                acceleration = forward.scale(af).add(up.scale(au)).add(side.scale(as));
            }
            else
            {
                acceleration = forward.scale(f > 0 ? main : f < 0 ? -retro : 0).add(up.scale(u * vertical));
                if ((bits & BRAKE) != 0 && old.velocity.lengthSqr() > 0)
                    acceleration = acceleration.add(old.velocity.normalize().scale(-Math.min(retro, old.velocity.length() / DT)));
            }
        }
        var velocity = old.velocity.add(acceleration.scale(DT));
        if (velocity.length() > ABSOLUTE_SPEED)
            velocity = velocity.normalize().scale(ABSOLUTE_SPEED);
        if (craft.assist && powered && f == 0 && u == 0 && velocity.length() < .05)
            velocity = Vec3.ZERO;
        double thrust = main > 0 ? Math.min(1, acceleration.length() / main) : 0;
        return new Result(new State(old.center.add(velocity.scale(DT)), velocity, yaw, pitch, roll, new Vec3(spin[0], spin[1], spin[2])), thrust);
    }

    public static boolean finite(Vec3 p)
    {
        return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
    }

    private FlightDynamics()
    {
    }
}
