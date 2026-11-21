package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.sound.ShipSounds;
import com.nullandkale.nullspace.model.space.ScenePose;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.joml.AxisAngle4d;
import org.joml.Quaterniond;

/**
 * What an automatic docking maneuver looks and sounds like from aboard. The main engines burn while the ship moves, harder the faster it goes (their plume and
 * hum follow the thrust as in piloted flight), and maneuvering jets puff cold gas from the hull to slide the ship and turn it, each burst heard through the
 * hull. A ship's blocks keep their own frame in space while its pose moves, so the jets fire in the hull's frame: from the side away from where the ship is
 * pushed, and for a turn from both ends of the hull in opposite directions.
 */
final class DockingThrusters
{
    /** The longest docking step, in blocks a tick: the approach's pace, which is full thrust. */
    private static final double FULL_STEP = .8;
    /** How many steps between the jets' sounds: a burst about every 0.4 s. */
    private static final int SOUND_EVERY = 8;
    /** How long a measured hull is trusted, in ticks, before it is measured again. */
    private static final long MEASURE_EVERY = 200;
    private static final ServerContext.Key<Map<String, Integer>> STEPS = new ServerContext.Key<>();
    private static final ServerContext.Key<Map<String, Hull>> HULLS = new ServerContext.Key<>();

    /** The blocks of a hull, as the box around them in the world, and when it was measured. */
    private record Hull(Vec3 min, Vec3 max, long measured)
    {}

    /** A jet: where its gas leaves the hull and which way it goes. */
    record Jet(Vec3 at, Vec3 direction)
    {}

    /**
     * Fires the engines and jets for one docking step from {@code from} to {@code to}. Placing the ship stops its piloted flight ({@code Piloting.stop}), which
     * clears the engines' demand, so this runs after the step is placed.
     */
    static void fire(MinecraftServer server, ServerLevel level, CompoundTag own, ScenePose from, ScenePose to)
    {
        own.putInt("thrustDemand", NavigationService.propulsion(level, own));
        var move = from.directionToLocal(to.position().subtract(from.position()));
        double share = Math.min(1, move.length() / FULL_STEP);
        own.putDouble("thrustShare", Math.max(.25, share));
        own.putDouble("speedShare", share * .5);
        var jets = jets(hull(server, level, own), move, turn(from, to));
        if (jets.isEmpty())
            return;
        var random = level.getRandom();
        for (var jet : jets)
            for (int i = 0; i < 2; i++)
                level.sendParticles(ParticleTypes.CLOUD, jet.at().x, jet.at().y, jet.at().z, 0, jet.direction().x, jet.direction().y, jet.direction().z, .2 + random.nextDouble() * .15);
        int step = ServerContext.get(server).service(STEPS, HashMap::new).merge(DockingService.id(own), 1, Integer::sum);
        if ((step - 1) % SOUND_EVERY == 0)
            ShipSounds.hull(level, own, jets.getFirst().at(), "rcs.puff");
    }

    /** Forgets a maneuver's jets once it ends, so the next one starts with a burst. */
    static void stop(MinecraftServer server, CompoundTag own)
    {
        ServerContext.get(server).service(STEPS, HashMap::new).remove(DockingService.id(own));
    }

    /** The turn from one pose to the next in the hull's own frame, as an axis scaled by the angle in radians. */
    static Vec3 turn(ScenePose from, ScenePose to)
    {
        var relative = new Quaterniond(from.orientation()).invert().mul(to.orientation());
        var angle = new AxisAngle4d().set(relative.normalize());
        if (angle.angle < 1e-6)
            return Vec3.ZERO;
        return new Vec3(angle.x, angle.y, angle.z).normalize().scale(angle.angle);
    }

    /** The jets that slide a hull along {@code move} and turn it by {@code spin}, both in the hull's frame: two for each, on opposite sides of the hull. */
    static List<Jet> jets(Hull hull, Vec3 move, Vec3 spin)
    {
        var out = new ArrayList<Jet>();
        double[] min = {hull.min().x, hull.min().y, hull.min().z}, max = {hull.max().x, hull.max().y, hull.max().z};
        double[] centre = new double[3], half = new double[3];
        for (int i = 0; i < 3; i++)
        {
            centre[i] = (min[i] + max[i]) / 2;
            half[i] = (max[i] - min[i]) / 2;
        }
        if (move.lengthSqr() > 1e-8)
        {
            // Pushed along the move, the hull vents from the side it leaves behind, at two points across that side's widest way.
            double[] gas = {-move.x, -move.y, -move.z};
            int out1 = dominant(gas);
            int across = widestOther(half, out1, -1);
            for (int sign : new int[]{-1, 1})
            {
                double[] at = centre.clone();
                at[out1] = centre[out1] + Math.signum(gas[out1]) * (half[out1] + .6);
                at[across] = centre[across] + sign * Math.max(0, half[across] - 1);
                out.add(new Jet(new Vec3(at[0], at[1], at[2]), axis(out1, Math.signum(gas[out1]))));
            }
        }
        if (spin.lengthSqr() > 1e-8)
        {
            // A turn is a pair of jets at the two ends of the hull's longest arm across the turn's axis, pushing in opposite directions.
            double[] turnAxis = {spin.x, spin.y, spin.z};
            double length = spin.length();
            int arm = 0;
            double best = -1;
            for (int i = 0; i < 3; i++)
            {
                double reach = half[i] * (1 - Math.abs(turnAxis[i]) / length);
                if (reach > best)
                {
                    best = reach;
                    arm = i;
                }
            }
            var axisVector = spin.scale(1 / length);
            for (int sign : new int[]{-1, 1})
            {
                var armVector = axis(arm, sign);
                var push = axisVector.cross(armVector);
                double[] gas = {-push.x, -push.y, -push.z};
                int out1 = dominant(gas);
                double[] at = centre.clone();
                at[arm] = centre[arm] + sign * Math.max(0, half[arm] - 1);
                at[out1] = centre[out1] + Math.signum(gas[out1]) * (half[out1] + .6);
                out.add(new Jet(new Vec3(at[0], at[1], at[2]), axis(out1, Math.signum(gas[out1]))));
            }
        }
        return out;
    }

    private static int dominant(double[] v)
    {
        int best = 0;
        for (int i = 1; i < 3; i++)
            if (Math.abs(v[i]) > Math.abs(v[best]))
                best = i;
        return best;
    }

    private static int widestOther(double[] half, int not, int alsoNot)
    {
        int best = -1;
        for (int i = 0; i < 3; i++)
            if (i != not && i != alsoNot && (best < 0 || half[i] > half[best]))
                best = i;
        return best;
    }

    private static Vec3 axis(int index, double sign)
    {
        return new Vec3(index == 0 ? sign : 0, index == 1 ? sign : 0, index == 2 ? sign : 0);
    }

    /** The box around a ship's blocks, measured now and then: the jets sit on its faces. */
    private static Hull hull(MinecraftServer server, ServerLevel level, CompoundTag own)
    {
        var hulls = ServerContext.get(server).service(HULLS, HashMap::new);
        long now = level.getGameTime();
        var known = hulls.get(DockingService.id(own));
        if (known != null && now - known.measured() < MEASURE_EVERY)
            return known;
        var bounds = WorldStore.bounds(own);
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
            if (!level.getBlockState(at).isAir())
            {
                lo[0] = Math.min(lo[0], at.getX());
                lo[1] = Math.min(lo[1], at.getY());
                lo[2] = Math.min(lo[2], at.getZ());
                hi[0] = Math.max(hi[0], at.getX());
                hi[1] = Math.max(hi[1], at.getY());
                hi[2] = Math.max(hi[2], at.getZ());
            }
        var hull = lo[0] > hi[0] ? new Hull(Vec3.atLowerCornerOf(bounds.min()), Vec3.atLowerCornerOf(bounds.max()).add(1, 1, 1), now) : new Hull(new Vec3(lo[0], lo[1], lo[2]), new Vec3(hi[0] + 1, hi[1] + 1, hi[2] + 1), now);
        hulls.put(DockingService.id(own), hull);
        return hull;
    }

    private DockingThrusters()
    {
    }
}
