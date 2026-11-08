package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;

import java.lang.reflect.*;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.*;

/** Owns a delegating main pass: close opaque, run compute, resume native transparency. */
public final class WorldVolumeBridge implements InvocationHandler
{
    private RenderPass active;
    private final Supplier<RenderPass> resume;
    private boolean boundary, closed;
    private static GpuTexture copy;
    private static GpuTextureView copyView;

    private WorldVolumeBridge(RenderPass active, Supplier<RenderPass> resume)
    {
        this.active = active;
        this.resume = resume;
    }

    public static RenderPass wrap(RenderPass pass, Supplier<RenderPass> resume)
    {
        return (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(), new Class<?>[]{RenderPass.class}, new WorldVolumeBridge(pass, resume));
    }

    public static void afterOpaque(RenderPass pass)
    {
        if (Proxy.isProxyClass(pass.getClass()) && Proxy.getInvocationHandler(pass) instanceof WorldVolumeBridge bridge)
            bridge.boundary();
    }

    private void boundary()
    {
        if (boundary || closed || !SpaceSceneRenderer.computeReady())
            return;
        boundary = true;
        active.close();
        active = null;
        try
        {
            var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            var color = main.getColorTexture();
            if (copy == null || copy.getWidth(0) != color.getWidth(0) || copy.getHeight(0) != color.getHeight(0) || copy.getFormat() != color.getFormat())
            {
                clear();
                copy = RenderSystem.getDevice().createTexture("Space opaque snapshot", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, color.getFormat(), color.getWidth(0), color.getHeight(0), 1, 1);
                copyView = RenderSystem.getDevice().createTextureView(copy);
            }
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(color, copy, 0, 0, 0, 0, 0, color.getWidth(0), color.getHeight(0));
            var compute = SpaceSceneRenderer.viewport().compute();
            compute.volumes(main.getDepthTexture(), SpaceSceneRenderer.nativeProjection());
            try (var composite = encoder.createRenderPass(() -> "Space world volume", main.getColorTextureView(), Optional.empty()))
            {
                compute.drawVolume(composite, copyView);
            }
        }
        finally
        {
            active = resume.get();
            RenderSystem.bindDefaultUniforms(active);
        }
        NativeVolumeUniforms.begin();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        if (method.getName().equals("close"))
        {
            if (!closed)
            {
                closed = true;
                if (active != null)
                    active.close();
            }
            return null;
        }
        if (closed || active == null)
            throw new IllegalStateException("World pass is closed");
        try
        {
            return method.invoke(active, args);
        }
        catch (InvocationTargetException e)
        {
            throw e.getCause();
        }
    }

    public static void clear()
    {
        NativeVolumeUniforms.clear();
        if (copyView != null)
        {
            copyView.close();
            copyView = null;
        }
        if (copy != null)
        {
            copy.close();
            copy = null;
        }
    }

    private WorldVolumeBridge()
    {
        resume = null;
    }
}
