package com.nullandkale.nullspace.render.scene;

import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.render.scene.SceneMeshes.Quad;
import com.nullandkale.nullspace.render.scene.SceneViewport.BodyMesh;
import com.nullandkale.nullspace.render.scene.SceneViewport.Cached;
import com.nullandkale.nullspace.render.scene.SceneViewport.MeshKey;

import java.util.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.*;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Collects what a viewport draws: hull blocks from snapshots (cached per vessel) and celestial
 * bodies and stars.
 */
final class SceneGeometry
{

    static void blocks(SceneViewport o, CompoundTag data, Vec3 eye, List<Quad> output, float fill)
    {
        blocks(o, data, eye, output, fill, null);
    }

    static void blocks(SceneViewport o, CompoundTag data, Vec3 eye, List<Quad> output, float fill, ScenePose parent)
    {
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var id = data.getStringOr("id", "");
        var cache = o.meshes.get(id);
        if (cache == null || cache.source() != data || cache.models() != models || cache.fill() != fill)
        {
            var states = data.getIntArray("blocks").orElse(new int[0]);
            var light = data.getByteArray("light").orElse(new byte[0]);
            int hash = 31 * (31 * Arrays.hashCode(states) + Arrays.hashCode(light)) + Float.floatToIntBits(fill);
            hash = 31 * hash + Arrays.hashCode(data.getIntArray("halo").orElse(new int[0]));
            hash = 31 * hash + data.getIntOr("grass", 0) + data.getIntOr("foliage", 0) + data.getIntOr("water", 0);
            if (cache == null || cache.hash() != hash || cache.models() != models)
            {
                var key = new MeshKey(id, hash, models, fill);
                var geometry = SceneViewport.SHARED.get(key);
                if (geometry == null)
                {
                    var local = new ArrayList<Quad>();
                    SceneMeshes.localBlocks(data, local, fill);
                    geometry = List.copyOf(local);
                    SceneViewport.SHARED.put(key, geometry);
                    SceneViewport.sharedQuads += geometry.size();
                    while (SceneViewport.SHARED.size() > 192 || SceneViewport.sharedQuads > 131072 && SceneViewport.SHARED.size() > 1)
                    {
                        var it = SceneViewport.SHARED.entrySet().iterator();
                        SceneViewport.sharedQuads -= it.next().getValue().size();
                        it.remove();
                    }
                }
                cache = new Cached(data, hash, models, geometry, fill);
            }
            else
                cache = new Cached(data, hash, models, cache.quads(), fill);
            if (o.meshes.size() >= 192 && !o.meshes.containsKey(id))
                o.meshes.clear();
            o.meshes.put(id, cache);
        }
        var localPose = SceneViewport.pose(data);
        var pose = parent == null ? localPose : new ScenePose(parent.toUniverse(localPose.position()), parent.orientation().mul(localPose.orientation()));
        var transform = new Matrix4d().translation(pose.position().x - eye.x, pose.position().y - eye.y, pose.position().z - eye.z).rotate(pose.orientation());
        var vertex = new Vector3d();
        for (var original : cache.quads())
        {
            var q = o.transformed(original);
            double distance = 0;
            for (int n = 0; n < 4; n++)
            {
                var p = original.points[n];
                vertex.set(p.x, p.y, p.z);
                transform.transformPosition(vertex);
                q.points[n].set((float) vertex.x, (float) vertex.y, (float) vertex.z);
                distance += vertex.lengthSquared();
            }
            q.distance = distance;
            output.add(q);
        }
    }

    static void celestial(SceneViewport o, CompoundTag scene, Vec3 eye, List<Quad> output)
    {
        if (o.stars.isEmpty() || o.starSeed != scene.getLongOr("seed", 79419843))
        {
            var generated = new ArrayList<Quad>();
            var random = new java.util.Random(scene.getLongOr("seed", 79419843));
            for (int n = 0; n < 1200; n++)
            {
                double y = random.nextDouble() * 2 - 1, a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - y * y);
                var center = new Vec3(Math.cos(a) * r * 100000, y * 100000, Math.sin(a) * r * 100000);
                float size = 18 + random.nextFloat() * 35;
                var p = new Vector3f((float) center.x, (float) center.y, (float) center.z);
                var normal = new Vector3f(p).normalize();
                var right = new Vector3f(normal).cross(Math.abs(normal.y) > .99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0)).normalize().mul(size);
                var up = new Vector3f(right).cross(normal).normalize().mul(size);
                generated.add(SceneMeshes.colored(new Vector3f[]{new Vector3f(p).sub(right).sub(up), new Vector3f(p).add(right).sub(up), new Vector3f(p).add(right).add(up), new Vector3f(p).sub(right).add(up)}, 0xffb9ccdf, false));
            }
            o.stars = List.copyOf(generated);
            o.starSeed = scene.getLongOr("seed", 79419843);
        }
        output.addAll(o.stars);
        var catalog = scene.getCompoundOrEmpty("catalog").getListOrEmpty("bodies");
        int hash = catalog.hashCode();
        if (o.bodies.isEmpty() || o.bodyHash != hash)
        {
            var sun = catalog.stream().map(t -> (CompoundTag) t).filter(t -> t.getBooleanOr("emissive", false)).findFirst().orElse(new CompoundTag());
            var light = new Vec3(sun.getDoubleOr("x", -12000), sun.getDoubleOr("y", 6000), sun.getDoubleOr("z", -18000));
            var built = new ArrayList<BodyMesh>();
            for (Tag t : catalog)
            {
                var b = (CompoundTag) t;
                var center = new Vec3(b.getDoubleOr("x", 0), b.getDoubleOr("y", 0), b.getDoubleOr("z", 0));
                var quads = new ArrayList<Quad>();
                SceneMeshes.sphere(Vec3.ZERO, b.getDoubleOr("radius", 1), Vec3.ZERO, quads, b.getIntOr("color", 0xffaaaaaa), b.getBooleanOr("emissive", false), b.getStringOr("kind", "").equals("planet"), light.subtract(center));
                built.add(new BodyMesh(center, List.copyOf(quads)));
            }
            o.bodies = List.copyOf(built);
            o.bodyHash = hash;
        }
        for (var body : o.bodies)
        {
            var offset = body.center().subtract(eye);
            for (var q : body.quads())
            {
                var points = new Vector3f[4];
                double distance = 0;
                for (int n = 0; n < 4; n++)
                {
                    var p = q.points[n];
                    double x = offset.x + p.x, y = offset.y + p.y, z = offset.z + p.z;
                    points[n] = new Vector3f((float) x, (float) y, (float) z);
                    distance += x * x + y * y + z * z;
                }
                output.add(new Quad(points, q.uv, q.color, q.translucent, distance));
            }
        }
    }

    private SceneGeometry()
    {
    }
}
