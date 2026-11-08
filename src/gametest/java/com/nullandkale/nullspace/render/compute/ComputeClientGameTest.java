package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ResourceGroup;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import static org.lwjgl.opengl.GL43C.*;

/** Exact SSBO result inside Minecraft's real context, followed by ordinary native rendering. */
public final class ComputeClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext context)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        context.runOnClient(client ->
        {
            ComputeProgram.require();
            try (var validView = new SpaceComputeRenderer(16, 16))
            {
                int baseline = ResourceGroup.liveEntries(), views = SpaceComputeRenderer.liveViews();
                for (int stage = 1; stage <= 23; stage++)
                {
                    final int failAt = stage;
                    SpaceComputeRenderer.allocationProbe = n ->
                    {
                        if (n == failAt)
                            throw new IllegalStateException("allocation probe");
                    };
                    boolean failed = false;
                    try
                    {
                        new SpaceComputeRenderer(16, 16);
                    }
                    catch (IllegalStateException expected)
                    {
                        failed = expected.getMessage().equals("allocation probe");
                    }
                    finally
                    {
                        SpaceComputeRenderer.allocationProbe = n ->
                        {};
                    }
                    if (!failed || ResourceGroup.liveEntries() != baseline || SpaceComputeRenderer.liveViews() != views)
                        throw new AssertionError("Resource leak at allocation stage " + stage);
                }
                validView.close();
                validView.close();
            }

            NullSpace.LOGGER.info("Compute proof: GL {} / GLSL {} / {}", glGetString(GL_VERSION), glGetString(GL_SHADING_LANGUAGE_VERSION), glGetString(GL_RENDERER));
            int old = glGetInteger(GL_CURRENT_PROGRAM), buffer = glGenBuffers();
            try (var state = new ComputeStateScope(); var program = new ComputeProgram("#version 430\n" + "layout(local_size_x=8,local_size_y=8) in;layout(std430,binding=0)" + " buffer Output{uint values[];};void main(){uint" + " i=gl_GlobalInvocationID.x+gl_GlobalInvocationID.y*16u;if(i<256u)values[i]=i*17u+29u;}"))
            {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
                glBufferData(GL_SHADER_STORAGE_BUFFER, 1024, GL_DYNAMIC_COPY);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
                program.use();
                program.dispatch(16, 16);
                glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                int[] output = new int[256];
                glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, output);
                for (int i = 0; i < output.length; i++)
                    if (output[i] != i * 17 + 29)
                        throw new AssertionError("Compute output " + i + "=" + output[i]);
            }
            finally
            {
                glDeleteBuffers(buffer);
            }
            if (glGetInteger(GL_CURRENT_PROGRAM) != old || glGetError() != GL_NO_ERROR)
                throw new AssertionError("Compute state/error regression");
        });
        try (var world = context.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            context.waitTicks(5);
            context.takeScreenshot("nullspace-gl43-native-world");
        }
        ClientTestEvidence.passed(getClass());
    }
}
