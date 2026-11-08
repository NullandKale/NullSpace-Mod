package com.nullandkale.nullspace.render.compute;

import static org.lwjgl.opengl.GL43C.*;

/** Raw GL calls leave both actual state and RenderPearl's untouched state caches consistent. */
public final class ComputeStateScope implements AutoCloseable
{
    private final int program = glGetInteger(GL_CURRENT_PROGRAM), pipeline = glGetInteger(GL_PROGRAM_PIPELINE_BINDING), active = glGetInteger(GL_ACTIVE_TEXTURE), buffer = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
    private final int[] textures = new int[8], samplers = new int[8], buffers = new int[3];
    private final long[] starts = new long[3], sizes = new long[3];
    private final int[][] images = new int[8][6];

    public ComputeStateScope()
    {
        for (int i = 0; i < textures.length; i++)
        {
            glActiveTexture(GL_TEXTURE0 + i);
            textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
            samplers[i] = glGetIntegeri(GL_SAMPLER_BINDING, i);
        }
        glActiveTexture(active);
        for (int i = 0; i < buffers.length; i++)
        {
            buffers[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
            starts[i] = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, i);
            sizes[i] = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, i);
        }
        int[] keys = {GL_IMAGE_BINDING_NAME, GL_IMAGE_BINDING_LEVEL, GL_IMAGE_BINDING_LAYERED, GL_IMAGE_BINDING_LAYER, GL_IMAGE_BINDING_ACCESS, GL_IMAGE_BINDING_FORMAT};
        for (int i = 0; i < images.length; i++)
            for (int j = 0; j < keys.length; j++)
                images[i][j] = glGetIntegeri(keys[j], i);
    }

    @Override
    public void close()
    {
        for (int i = 0; i < textures.length; i++)
        {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            glBindSampler(i, samplers[i]);
        }
        glActiveTexture(active);
        for (int i = 0; i < buffers.length; i++)
            if (buffers[i] != 0 && sizes[i] > 0)
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, i, buffers[i], starts[i], sizes[i]);
            else
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, buffers[i]);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        for (int i = 0; i < images.length; i++)
        {
            int[] a = images[i];
            glBindImageTexture(i, a[0], a[1], a[2] != 0, a[3], a[4], a[5]);
        }
        glBindProgramPipeline(pipeline);
        glUseProgram(program);
    }
}
