package com.nullandkale.nullspace.model.space;

import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Local-to-simulated transform. Storage coordinates never enter the simulated pose. */
public record ScenePose(Vec3 position, Quaterniond orientation)
{
    public ScenePose
    {
        if (!Double.isFinite(position.x + position.y + position.z) || !Double.isFinite(orientation.lengthSquared()) || orientation.lengthSquared() < 1e-12)
            throw new IllegalArgumentException("Invalid scene pose");
        orientation = new Quaterniond(orientation).normalize();
    }

    @Override
    public Quaterniond orientation()
    {
        return new Quaterniond(orientation);
    }

    public Vec3 toUniverse(Vec3 local)
    {
        var p = orientation.transform(new Vector3d(local.x, local.y, local.z));
        return position.add(p.x, p.y, p.z);
    }

    public Vec3 toLocal(Vec3 universe)
    {
        var p = universe.subtract(position);
        var v = orientation().invert().transform(new Vector3d(p.x, p.y, p.z));
        return new Vec3(v.x, v.y, v.z);
    }

    public Vec3 directionToLocal(Vec3 direction)
    {
        var v = orientation().invert().transform(new Vector3d(direction.x, direction.y, direction.z));
        return new Vec3(v.x, v.y, v.z);
    }

    public static ScenePose identity()
    {
        return new ScenePose(Vec3.ZERO, new Quaterniond());
    }
}
