package com.nullandkale.nullspace.render.scene;

import com.nullandkale.nullspace.gameplay.systems.EngineEffects;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.render.scene.SceneMeshes.Quad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

/**
 * The engine exhaust as the scene viewport draws it. The world view's exhaust is ordinary Minecraft particles, which the
 * viewport never draws (it is its own renderer), so this is the same exhaust again: the same dust puffs, with the same
 * sprites, sizes, colours, lifetimes and slow drift as vanilla's dust particles, and the same plume the server makes
 * ({@link EngineEffects#plume}, {@link EngineEffects#palette}), laid out from the nozzles the server sends with each thrusting
 * ship. Puffs live in the universe, so they trail behind a moving ship as they do in the world.
 */
final class ExhaustPuffs
{
    private static final Identifier[] DUST = new Identifier[8];

    static
    {
        for (int i = 0; i < 8; i++)
            DUST[i] = Identifier.parse("minecraft:generic_" + i);
    }

    private static final Identifier FLAME = Identifier.parse("minecraft:flame");

    private static final class Puff
    {
        double x, y, z, vx, vy, vz;
        int age, life;
        float half;
        int from, to;
        boolean flame;
    }

    private final List<Puff> puffs = new ArrayList<>();
    private final Random random = new Random();
    private long lastTick = Long.MIN_VALUE;

    void clear()
    {
        puffs.clear();
        lastTick = Long.MIN_VALUE;
    }

    /** Ages the puffs and adds this tick's, for each tick since the last call (at most three). */
    void update(long gameTime, CompoundTag scene, Predicate<CompoundTag> include)
    {
        long steps = lastTick == Long.MIN_VALUE ? 1 : Math.clamp(gameTime - lastTick, 0, 3);
        lastTick = gameTime;
        for (long step = 0; step < steps; step++)
        {
            for (var iterator = puffs.iterator(); iterator.hasNext();)
            {
                var p = iterator.next();
                p.x += p.vx;
                p.y += p.vy;
                p.z += p.vz;
                double drag = p.flame ? .96 : .96;
                p.vx *= drag;
                p.vy *= drag;
                p.vz *= drag;
                if (++p.age >= p.life)
                    iterator.remove();
            }
            for (var tag : scene.getListOrEmpty("subjects"))
            {
                var subject = (CompoundTag) tag;
                var nozzles = subject.getListOrEmpty("exhausts");
                if (!nozzles.isEmpty() && include.test(subject))
                    spawn(subject, nozzles);
            }
        }
        if (puffs.size() > 6000)
            puffs.subList(0, puffs.size() - 6000).clear();
    }

    private void spawn(CompoundTag subject, net.minecraft.nbt.ListTag nozzles)
    {
        var pose = SceneViewport.pose(subject);
        var plume = EngineEffects.plume(subject.getDoubleOr("thrust", 0), subject.getDoubleOr("speed", 0));
        int segments = Math.max(2, (int) Math.ceil(plume.length() / 1.1));
        for (var tag : nozzles)
        {
            var nozzle = (CompoundTag) tag;
            var origin = pose.toUniverse(new Vec3(nozzle.getDoubleOr("x", 0), nozzle.getDoubleOr("y", 0), nozzle.getDoubleOr("z", 0)));
            var d = FlightDynamics.rotate(pose.orientation(), new Vec3(nozzle.getIntOr("dx", 0), 0, nozzle.getIntOr("dz", 0)));
            var colours = EngineEffects.palette(nozzle.getIntOr("tier", 0));
            dust(origin.add(d.scale(.3)), .12, Math.max(1.6f, plume.size() * .9f), colours.core(), colours.core(), plume.density());
            for (int k = 0; k < segments; k++)
            {
                boolean hot = k < segments / 3;
                dust(origin.add(d.scale(.8 + k * 1.1)), .15 + k * .12, (float) Math.min(4, plume.size() + k * .3), hot ? colours.hotFrom() : colours.warmFrom(), hot ? colours.hotTo() : colours.warmTo(), plume.density());
            }
            flame(origin.add(d.scale(1.5)), d.scale(.2 + plume.length() * .03));
            dust(origin.add(d.scale(plume.length() * .7 + random.nextDouble() * 2)), .5, 4, colours.smokeFrom(), colours.smokeTo(), plume.density());
        }
    }

    /** One dust particle as vanilla makes it: a gaussian scatter about the point, a slow random drift, a lifetime that grows with its size. */
    private void dust(Vec3 at, double spread, float scale, int from, int to, int count)
    {
        for (int n = 0; n < count; n++)
        {
            var p = new Puff();
            p.x = at.x + random.nextGaussian() * spread;
            p.y = at.y + random.nextGaussian() * spread;
            p.z = at.z + random.nextGaussian() * spread;
            double x = random.nextDouble() * 2 - 1, y = random.nextDouble() * 2 - 1, z = random.nextDouble() * 2 - 1;
            double length = Math.max(1e-6, Math.sqrt(x * x + y * y + z * z)), speed = (random.nextDouble() + random.nextDouble() + 1) * .15 * .4 * .1;
            p.vx = x / length * speed;
            p.vy = y / length * speed;
            p.vz = z / length * speed;
            p.half = (float) (.1 * (random.nextDouble() * .5 + .5) * 2 * .75 * scale);
            p.life = Math.max(1, (int) ((int) (8 / (random.nextDouble() * .8 + .2)) * scale));
            // Vanilla darkens each dust particle by a random share, so a plume is not one flat colour.
            float shade = (float) (random.nextDouble() * .4 + .6);
            p.from = shade(from, shade);
            p.to = shade(to, shade);
            puffs.add(p);
        }
    }

    private void flame(Vec3 at, Vec3 velocity)
    {
        var p = new Puff();
        p.x = at.x;
        p.y = at.y;
        p.z = at.z;
        p.vx = velocity.x;
        p.vy = velocity.y;
        p.vz = velocity.z;
        p.half = (float) (.1 * (random.nextDouble() * .5 + .5) * 2 * .5);
        p.life = (int) (4 / (random.nextDouble() * .9 + .1));
        p.flame = true;
        p.from = p.to = 0xffffff;
        puffs.add(p);
    }

    private static int shade(int colour, float shade)
    {
        return (int) ((colour >> 16 & 255) * shade) << 16 | (int) ((colour >> 8 & 255) * shade) << 8 | (int) ((colour & 255) * shade);
    }

    /** The puffs as camera-facing quads, relative to the eye, farthest first so they blend correctly. */
    List<Quad> quads(Vec3 eye, Vec3 forward, Vec3 up, TextureAtlas atlas)
    {
        var out = new ArrayList<Quad>(puffs.size());
        if (puffs.isEmpty())
            return out;
        var f = forward.normalize();
        var right = f.cross(up).normalize();
        var trueUp = right.cross(f).normalize();
        var dust = new net.minecraft.client.renderer.texture.TextureAtlasSprite[8];
        for (int i = 0; i < 8; i++)
            dust[i] = atlas.getSprite(DUST[i]);
        var flame = atlas.getSprite(FLAME);
        for (var p : puffs)
        {
            double fraction = p.age / (double) p.life;
            float half = p.flame ? (float) (p.half * (1 - fraction * .5)) : (float) (p.half * Math.min(1, fraction * 32));
            if (half <= 0)
                continue;
            var sprite = p.flame ? flame : dust[7 - Math.min(7, (int) (fraction * 7))];
            int colour = p.flame ? 0xffffffff : 0xff000000 | lerp(p.from, p.to, (float) fraction);
            double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
            var r = right.scale(half);
            var u = trueUp.scale(half);
            var points = new Vector3f[4];
            double[][] corner = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; i++)
                points[i] = new Vector3f((float) (dx + (r.x * corner[i][0] + u.x * corner[i][1]) / half * half), (float) (dy + (r.y * corner[i][0] + u.y * corner[i][1]) / half * half), (float) (dz + (r.z * corner[i][0] + u.z * corner[i][1]) / half * half));
            float[] uv = {sprite.getU0(), sprite.getV1(), sprite.getU1(), sprite.getV1(), sprite.getU1(), sprite.getV0(), sprite.getU0(), sprite.getV0()};
            out.add(new Quad(points, uv, colour, true, dx * dx + dy * dy + dz * dz));
        }
        out.sort(Comparator.comparingDouble(Quad::distance).reversed());
        return out;
    }

    private static int lerp(int a, int b, float t)
    {
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t), g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t), bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }
}
