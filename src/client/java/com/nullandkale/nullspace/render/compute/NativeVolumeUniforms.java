package com.nullandkale.nullspace.render.compute;

import static org.lwjgl.opengl.GL43C.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.opengl.*;
import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;

/**
 * Applies the same cumulative optical field at native transparent fragment depths, including OIT.
 */
public final class NativeVolumeUniforms {
  private record Locations(int enabled, int sampler, int depth, int info, int inverse) {}

  private static final Map<Integer, Locations> LOCATIONS = new HashMap<>();
  private static boolean active;
  private static int oldTexture, oldSampler, oldDepth, oldDepthSampler;
  private static long binds;

  public static long binds() {
    return binds;
  }

  public static void begin() {
    if (active || !SpaceSceneRenderer.computeReady()) return;
    int unit = glGetInteger(GL_ACTIVE_TEXTURE);
    glActiveTexture(GL_TEXTURE0 + 14);
    oldDepth = glGetInteger(GL_TEXTURE_BINDING_2D);
    oldDepthSampler = glGetIntegeri(GL_SAMPLER_BINDING, 14);
    glBindTexture(
        GL_TEXTURE_2D,
        ((GlTexture) SpaceSceneRenderer.viewport().compute().depth().texture()).glId());
    glBindSampler(14, 0);
    glActiveTexture(GL_TEXTURE0 + 15);
    oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
    oldSampler = glGetIntegeri(GL_SAMPLER_BINDING, 15);
    glBindTexture(
        GL_TEXTURE_2D,
        ((GlTexture) SpaceSceneRenderer.viewport().compute().prefix().texture()).glId());
    glBindSampler(15, 0);
    glActiveTexture(unit);
    active = true;
  }

  public static void end() {
    if (!active) return;
    int unit = glGetInteger(GL_ACTIVE_TEXTURE);
    glActiveTexture(GL_TEXTURE0 + 14);
    glBindTexture(GL_TEXTURE_2D, oldDepth);
    glBindSampler(14, oldDepthSampler);
    glActiveTexture(GL_TEXTURE0 + 15);
    glBindTexture(GL_TEXTURE_2D, oldTexture);
    glBindSampler(15, oldSampler);
    glActiveTexture(unit);
    active = false;
  }

  public static void apply(int program) {
    if (program == 0) return;
    var locations =
        LOCATIONS.computeIfAbsent(
            program,
            p ->
                new Locations(
                    glGetUniformLocation(p, "NullSpaceVolumeEnabled"),
                    glGetUniformLocation(p, "NullSpaceVolume"),
                    glGetUniformLocation(p, "NullSpaceDepth"),
                    glGetUniformLocation(p, "NullSpaceVolumeInfo"),
                    glGetUniformLocation(p, "NullSpaceInverseView")));
    if (locations.enabled < 0) return;
    boolean enabled = active && SpaceSceneRenderer.computeReady();
    glUniform1f(locations.enabled, enabled ? 1 : 0);
    if (!enabled) return;
    var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
    glUniform1i(locations.sampler, 15);
    glUniform1i(locations.depth, 14);
    glUniform4f(
        locations.info,
        target.width,
        target.height,
        RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0,
        SpaceComputeRenderer.SLICES);
    try (var stack = MemoryStack.stackPush()) {
      glUniformMatrix4fv(
          locations.inverse,
          false,
          SpaceSceneRenderer.nativeProjection().invert().get(stack.mallocFloat(16)));
    }
    binds++;
  }

  public static void remove(int program) {
    LOCATIONS.remove(program);
  }

  public static void clear() {
    end();
    LOCATIONS.clear();
    binds = 0;
  }

  private NativeVolumeUniforms() {}
}
