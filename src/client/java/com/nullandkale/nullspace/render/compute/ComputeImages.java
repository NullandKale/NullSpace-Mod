package com.nullandkale.nullspace.render.compute;

import java.util.*;

import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.opengl.GlTexture;

import static org.lwjgl.opengl.GL43C.*;

/**
 * GPU images used by the compute passes: creating them, binding them to shaders and reading them
 * back for tests.
 */
public final class ComputeImages
{

    record Image(GpuTexture texture, GpuTextureView view) implements AutoCloseable
    {
        static Image make(String name, int w, int h, GpuFormat format)
        {
            var device = RenderSystem.getDevice();
            var texture = device.createTexture(name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, format, w, h, 1, 1);
            try
            {
                return new Image(texture, device.createTextureView(texture));
            }
            catch (RuntimeException | Error failure)
            {
                texture.close();
                throw failure;
            }
        }

        int id()
        {
            return ((GlTexture) texture).glId();
        }

        @Override
        public void close()
        {
            view.close();
            texture.close();
        }
    }

    static void image(int binding, Image image, int access, int format)
    {
        glBindImageTexture(binding, image.id(), 0, false, 0, access, format);
    }

    /** Diagnostic readback with isolated pixel-pack state and checked destination capacity. */
    public static float[] inspectTexture(int texture, int w, int h)
    {
        if (w < 1 || h < 1)
            throw new IllegalArgumentException("Invalid readback dimensions");
        float[] result = new float[w * h * 4];
        int pbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] keys = {GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_PIXELS, GL_PACK_SKIP_ROWS, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_IMAGES, GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST};
        int[] old = new int[keys.length];
        for (int i = 0; i < keys.length; i++)
            old[i] = glGetInteger(keys[i]);
        try (var state = new ComputeStateScope())
        {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            for (int key : keys)
                glPixelStorei(key, key == GL_PACK_ALIGNMENT ? 1 : 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != w || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT) != h)
                throw new IllegalArgumentException("Readback dimensions do not match the texture");
            glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, result);
        }
        finally
        {
            for (int i = 0; i < keys.length; i++)
                glPixelStorei(keys[i], old[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
        }
        return result;
    }

    private ComputeImages()
    {
    }
}
