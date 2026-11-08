package com.nullandkale.nullspace.render.compute;

import java.nio.ByteBuffer;
import java.util.*;

import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;

import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL43C.*;

/**
 * Packing values for shader buffers, and the projection tests that decide when temporal history can
 * be reused.
 */
public final class ComputeMath
{

    static void vec(ByteBuffer bytes, double x, double y, double z, double w)
    {
        bytes.putFloat((float) x).putFloat((float) y).putFloat((float) z).putFloat((float) w);
    }

    static void rgb(ByteBuffer b, int color, double w)
    {
        vec(b, (color >> 16 & 255) / 255., (color >> 8 & 255) / 255., (color & 255) / 255., w);
    }

    static double halton(long n, int base)
    {
        double f = 1, r = 0;
        while (n > 0)
        {
            f /= base;
            r += f * (n % base);
            n /= base;
        }
        return r;
    }

    static double directionDot(Matrix4f a, Matrix4f b)
    {
        var x = new Matrix4f(a).invert().transformProject(new Vector3f(0, 0, 1)).normalize();
        var y = new Matrix4f(b).invert().transformProject(new Vector3f(0, 0, 1)).normalize();
        return x.dot(y);
    }

    static boolean sameProjection(Matrix4f a, Matrix4f b)
    {
        double ax = Math.sqrt(a.m00() * a.m00() + a.m10() * a.m10() + a.m20() * a.m20()), bx = Math.sqrt(b.m00() * b.m00() + b.m10() * b.m10() + b.m20() * b.m20()), ay = Math.sqrt(a.m01() * a.m01() + a.m11() * a.m11() + a.m21() * a.m21()), by = Math.sqrt(b.m01() * b.m01() + b.m11() * b.m11() + b.m21() * b.m21());
        return Math.abs(ax - bx) < .0001 && Math.abs(ay - by) < .0001;
    }

    private ComputeMath()
    {
    }
}
