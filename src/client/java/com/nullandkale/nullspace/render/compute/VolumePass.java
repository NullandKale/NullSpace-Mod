package com.nullandkale.nullspace.render.compute;

import static org.lwjgl.opengl.GL43C.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.nullandkale.nullspace.model.optics.SpaceRenderMath;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.compute.ComputeImages.Image;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

/**
 * The volume pass: nebulae, atmospheres and tails accumulated in depth slices and reconstructed
 * over time.
 */
final class VolumePass {

  public static void volumes(SpaceComputeRenderer o, GpuTexture opaqueDepth) {
    volumes(o, opaqueDepth, o.currentProjection);
  }

  public static void volumes(
      SpaceComputeRenderer o, GpuTexture opaqueDepth, Matrix4f depthProjection) {
    if (opaqueDepth != null) {
      if (o.depthCopy == null
          || o.depthCopy.texture().getWidth(0) != opaqueDepth.getWidth(0)
          || o.depthCopy.texture().getHeight(0) != opaqueDepth.getHeight(0)) {
        if (o.depthCopy != null) o.depthCopy.close();
        o.depthCopy =
            Image.make(
                "Opaque depth sample",
                opaqueDepth.getWidth(0),
                opaqueDepth.getHeight(0),
                opaqueDepth.getFormat());
      }
      RenderSystem.getDevice()
          .createCommandEncoder()
          .copyTextureToTexture(
              opaqueDepth,
              o.depthCopy.texture(),
              0,
              0,
              0,
              0,
              0,
              opaqueDepth.getWidth(0),
              opaqueDepth.getHeight(0));
    }
    try (var stack = MemoryStack.stackPush()) {
      var b = stack.malloc(64);
      new Matrix4f(depthProjection).invert().get(0, b);
      RenderSystem.getDevice().createCommandEncoder().writeToBuffer(o.viewBuffer.slice(160, 64), b);
    }
    try (var state = new ComputeStateScope()) {
      if (o.measurementStarted) glQueryCounter(o.queries[o.querySlot][4], GL_TIMESTAMP);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, o.bodyBuffer);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, o.nodeBuffer);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, o.sceneBuffer);
      SceneUpload.uniforms(
          o,
          SpaceComputeRenderer.volumeProgram,
          o.currentProjection,
          o.volumeWidth,
          o.volumeHeight);
      SpaceComputeRenderer.volumeProgram.matrix("InvDepth", new Matrix4f(depthProjection).invert());
      SpaceComputeRenderer.volumeProgram.integer("OpaqueDepth", 0);
      SpaceComputeRenderer.volumeProgram.integer("HasDepth", opaqueDepth != null ? 1 : 0);
      SpaceComputeRenderer.volumeProgram.integer(
          "ZeroToOne", RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
      if (opaqueDepth != null) o.texture(0, o.depthCopy.texture());
      ComputeImages.image(0, o.rawPrefix, GL_WRITE_ONLY, GL_RGBA16F);
      ComputeImages.image(1, o.stopImage, GL_WRITE_ONLY, GL_RGBA32F);
      SpaceComputeRenderer.volumeProgram.scalar(
          "SamplePhase",
          Boolean.getBoolean("nullspace.compute.noJitter") || !SpaceSettings.get().stochastic
              ? .5f
              : (float) ComputeMath.halton(o.frames + 1, 5));
      SpaceComputeRenderer.volumeProgram.dispatch(o.volumeWidth, o.volumeHeight, 8, 4);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
      if (o.measurementStarted) {
        glQueryCounter(o.queries[o.querySlot][5], GL_TIMESTAMP);
        glQueryCounter(o.queries[o.querySlot][6], GL_TIMESTAMP);
      }
      SpaceComputeRenderer.volumeResolveProgram.use();
      SpaceComputeRenderer.volumeResolveProgram.integer("Current", 0);
      SpaceComputeRenderer.volumeResolveProgram.integer("History", 1);
      SpaceComputeRenderer.volumeResolveProgram.integer("CurrentStop", 2);
      SpaceComputeRenderer.volumeResolveProgram.integer("HistoryStop", 3);
      o.texture(0, o.rawPrefix.texture());
      o.texture(1, o.oldPrefix.texture());
      o.texture(2, o.stopImage.texture());
      o.texture(3, o.oldStop.texture());
      SpaceComputeRenderer.volumeResolveProgram.matrix(
          "InvView", new Matrix4f(o.currentProjection).invert());
      SpaceComputeRenderer.volumeResolveProgram.matrix(
          "PreviousProjection", o.previousVolumeProjection);
      var delta = o.currentEye.subtract(o.previousVolumeEye);
      glUniform3f(
          SpaceComputeRenderer.volumeResolveProgram.uniform("EyeDelta"),
          (float) delta.x,
          (float) delta.y,
          (float) delta.z);
      glUniform2i(
          SpaceComputeRenderer.volumeResolveProgram.uniform("ViewSize"),
          o.volumeWidth,
          o.volumeHeight);
      String key = o.currentKey;
      boolean reuse =
          o.volumeHistory
              && o.previousGeometryHash == o.geometryHash
              && SpaceRenderMath.history(
                  delta.length(),
                  ComputeMath.directionDot(o.currentProjection, o.previousVolumeProjection),
                  key.equals(o.previousVolumeKey),
                  ComputeMath.sameProjection(o.currentProjection, o.previousVolumeProjection),
                  Math.abs(o.currentTime - o.previousVolumeTime) < .25);
      SpaceComputeRenderer.volumeResolveProgram.integer(
          "HistoryValid",
          reuse
                  && SpaceSettings.get().temporal
                  && !Boolean.getBoolean("nullspace.compute.noHistory")
              ? 1
              : 0);
      ComputeImages.image(0, o.prefix, GL_WRITE_ONLY, GL_RGBA16F);
      SpaceComputeRenderer.volumeResolveProgram.dispatch(o.volumeWidth, o.volumeHeight);
      glMemoryBarrier(
          GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
              | GL_TEXTURE_FETCH_BARRIER_BIT
              | GL_TEXTURE_UPDATE_BARRIER_BIT);
      if (o.measurementStarted) {
        glQueryCounter(o.queries[o.querySlot][7], GL_TIMESTAMP);
        o.pending[o.querySlot] = true;
        o.querySlot = (o.querySlot + 1) % o.queries.length;
        o.measurementStarted = false;
      }
    }
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    encoder.copyTextureToTexture(
        o.prefix.texture(),
        o.oldPrefix.texture(),
        0,
        0,
        0,
        0,
        0,
        o.volumeWidth,
        o.volumeHeight * SpaceComputeRenderer.SLICES);
    encoder.copyTextureToTexture(
        o.stopImage.texture(), o.oldStop.texture(), 0, 0, 0, 0, 0, o.volumeWidth, o.volumeHeight);
    o.previousVolumeProjection.set(o.currentProjection);
    o.previousVolumeEye = o.currentEye;
    o.previousVolumeTime = o.currentTime;
    o.previousGeometryHash = o.geometryHash;
    o.previousVolumeKey = o.currentKey;
    o.volumeHistory = true;
  }

  private VolumePass() {}
}
