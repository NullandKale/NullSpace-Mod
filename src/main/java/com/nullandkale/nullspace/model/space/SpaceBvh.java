package com.nullandkale.nullspace.model.space;

import java.nio.*;
import java.util.*;

import net.minecraft.world.phys.Vec3;

/** Balanced AABB hierarchy with a stable 32-byte std430 node ABI and an exact sphere reference. */
public final class SpaceBvh
{
    public record Node(Vec3 min, Vec3 max, int left, int right)
    {}

    public record Hit(int index, double distance)
    {}

    private final List<Node> nodes = new ArrayList<>();
    private final List<SpaceDefinition.Body> bodies;
    private final double time, horizon;

    public SpaceBvh(List<SpaceDefinition.Body> bodies, double time)
    {
        this(bodies, time, 0);
    }

    public SpaceBvh(List<SpaceDefinition.Body> bodies, double time, double horizon)
    {
        this.horizon = horizon;
        if (!Double.isFinite(horizon) || horizon < 0 || horizon > 1)
            throw new IllegalArgumentException("Invalid motion interval");
        this.bodies = List.copyOf(bodies);
        this.time = time;
        if (!Double.isFinite(time) || bodies.size() > SpaceDefinition.MAX_INSTANCES)
            throw new IllegalArgumentException("Invalid BVH");
        if (!bodies.isEmpty())
            build(java.util.stream.IntStream.range(0, bodies.size()).boxed().toList());
    }

    private int build(List<Integer> ids)
    {
        var min = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        var max = min.scale(-1);
        for (int i : ids)
        {
            var b = bodies.get(i);
            var c = b.at(time);
            var end = b.at(time + horizon);
            double r = b.radius() * (1 + b.roughness());
            min = new Vec3(Math.min(min.x, Math.min(c.x, end.x) - r), Math.min(min.y, Math.min(c.y, end.y) - r), Math.min(min.z, Math.min(c.z, end.z) - r));
            max = new Vec3(Math.max(max.x, Math.max(c.x, end.x) + r), Math.max(max.y, Math.max(c.y, end.y) + r), Math.max(max.z, Math.max(c.z, end.z) + r));
        }
        int index = nodes.size();
        nodes.add(null);
        int left, right;
        if (ids.size() == 1)
        {
            left = -ids.getFirst() - 1;
            right = -1;
        }
        else
        {
            var extent = max.subtract(min);
            int axis = extent.x >= extent.y && extent.x >= extent.z ? 0 : extent.y >= extent.z ? 1 : 2;
            var sorted = new ArrayList<>(ids);
            sorted.sort(Comparator.comparingDouble(i ->
            {
                var p = bodies.get(i).at(time);
                return axis == 0 ? p.x : axis == 1 ? p.y : p.z;
            }));
            int mid = sorted.size() / 2;
            left = build(sorted.subList(0, mid));
            right = build(sorted.subList(mid, sorted.size()));
        }
        nodes.set(index, new Node(min, max, left, right));
        return index;
    }

    public List<Node> nodes()
    {
        return List.copyOf(nodes);
    }

    public ByteBuffer pack(Vec3 eye)
    {
        var bytes = ByteBuffer.allocateDirect(Math.max(32, nodes.size() * 32)).order(ByteOrder.nativeOrder());
        write(bytes, eye);
        return bytes.flip();
    }

    public void write(ByteBuffer bytes, Vec3 eye)
    {
        for (var n : nodes)
        {
            put(bytes, n.min.subtract(eye));
            bytes.putInt(n.left);
            put(bytes, n.max.subtract(eye));
            bytes.putInt(n.right);
        }
    }

    private static void put(ByteBuffer b, Vec3 p)
    {
        b.putFloat((float) p.x).putFloat((float) p.y).putFloat((float) p.z);
    }

    public Hit intersect(Vec3 origin, Vec3 direction)
    {
        if (nodes.isEmpty())
            return new Hit(-1, Double.POSITIVE_INFINITY);
        var stack = new int[64];
        int count = 1;
        stack[0] = 0;
        int hit = -1;
        double distance = Double.POSITIVE_INFINITY;
        while (count > 0)
        {
            var n = nodes.get(stack[--count]);
            if (!box(origin, direction, n.min, n.max, distance))
                continue;
            if (n.left < 0)
            {
                int index = -n.left - 1;
                var b = bodies.get(index);
                double d = sphere(origin, direction, b.at(time), b.radius());
                if (d < distance)
                {
                    hit = index;
                    distance = d;
                }
            }
            else
            {
                stack[count++] = n.left;
                stack[count++] = n.right;
            }
        }
        return new Hit(hit, distance);
    }

    public static boolean box(Vec3 o, Vec3 d, Vec3 min, Vec3 max, double limit)
    {
        double near = 0, far = limit;
        double[] a = {o.x, o.y, o.z}, v = {d.x, d.y, d.z}, lo = {min.x, min.y, min.z}, hi = {max.x, max.y, max.z};
        for (int i = 0; i < 3; i++)
        {
            if (Math.abs(v[i]) < 1e-15)
            {
                if (a[i] < lo[i] || a[i] > hi[i])
                    return false;
                continue;
            }
            double x = (lo[i] - a[i]) / v[i], y = (hi[i] - a[i]) / v[i];
            near = Math.max(near, Math.min(x, y));
            far = Math.min(far, Math.max(x, y));
            if (far < near)
                return false;
        }
        return true;
    }

    public static double sphere(Vec3 o, Vec3 d, Vec3 c, double r)
    {
        var q = o.subtract(c);
        double b = q.dot(d), h = b * b - q.lengthSqr() + r * r;
        if (h < 0)
            return Double.POSITIVE_INFINITY;
        double root = Math.sqrt(h), near = -b - root, far = -b + root;
        return near >= 0 ? near : far >= 0 ? far : Double.POSITIVE_INFINITY;
    }
}
