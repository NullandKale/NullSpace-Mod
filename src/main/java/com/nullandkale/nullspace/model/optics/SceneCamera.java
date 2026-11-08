package com.nullandkale.nullspace.model.optics;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Matches the native camera while keeping compute rays relative to the effective eye. */
public final class SceneCamera
{
    public record Frame(Vector3f eyeOffset, Matrix4f projection, Matrix4f nativeProjection, Matrix4f depthProjection)
    {}

    public static Frame compose(Matrix4fc baseProjection, Matrix4fc worldProjection, Matrix4fc viewRotation, Quaterniondc shipOrientation, boolean zeroToOne)
    {
        // Native projection includes hurt/walk bob and portal effects. In particular,
        // walk bob translates the eye; treating a translated near-plane point as a
        // direction magnifies a few centimetres of bob into a tumbling background.
        var view = new Matrix4f(baseProjection).invert().mul(worldProjection).mul(viewRotation);
        var eyeOffset = new Matrix4f(view).invert().transformPosition(new Vector3f());
        view.m30(0).m31(0).m32(0);
        view.rotate(new Quaternionf(shipOrientation).conjugate());

        // Preserve the exact native X/Y projection (FOV, aspect and any lens shift),
        // changing only its reverse-Z range for the distant scene.
        var distant = new Matrix4f(baseProjection);
        var range = new Matrix4f().setPerspective((float) Math.toRadians(70), 1, 1_000_000, .05f, zeroToOne);
        distant.m22(range.m22()).m32(range.m32());
        return new Frame(eyeOffset, new Matrix4f(distant).mul(view), new Matrix4f(baseProjection).mul(view), distant);
    }

    private SceneCamera()
    {
    }
}
