package com.nullandkale.nullspace.render.compute;

import static org.lwjgl.opengl.GL43C.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.flight.TravelVisuals;
import com.nullandkale.nullspace.model.optics.SpaceRenderMath;
import com.nullandkale.nullspace.render.SpaceSettings;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** The surface pass: ray-traced bodies, sky and temporal reuse of the previous frame. */
final class SurfacePass {

  public static void surfaces(
      SpaceComputeRenderer o, CompoundTag scene, Vec3 eye, Matrix4f projection) {
    long start = System.nanoTime();
    boolean jitter =
        false; // Spatial AA replaces unstable per-frame surface jitter, including stars.
    o.jitterX = jitter ? (float) (ComputeMath.halton(o.frames, 2) - .5) : 0;
    o.jitterY = jitter ? (float) (ComputeMath.halton(o.frames, 3) - .5) : 0;
    var subjects = scene.get("subjects");
    if (o.lastSubjects != subjects) {
      o.lastSubjects = subjects;
      o.subjectsHash = Objects.hashCode(subjects);
    }
    o.geometryHash = o.subjectsHash;
    o.currentKey =
        scene.getCompoundOrEmpty("catalog").getStringOr("id", "")
            + ":"
            + scene.getCompoundOrEmpty("catalog").getLongOr("revision", 0)
            + ":"
            + scene.getStringOr("observer", "")
            + ":"
            + scene.getLongOr("source", 0)
            + ":"
            + scene.getIntOr("selection", 0);
    o.currentEye = eye;
    o.currentProjection.set(projection);
    o.currentTime =
        scene.getDoubleOr("simulationTime", 0)
            + Math.min(
                2,
                Math.max(
                    0,
                    (System.nanoTime() - scene.getLongOr("receivedNanos", System.nanoTime()))
                        / 1e9));
    o.starParallax =
        !scene.getBooleanOr("planetSky", false)
                && scene
                    .getStringOr("dimension", "")
                    .equals(TravelService.SPACE.identifier().toString())
            ? 1
            : 0;
    o.starFtl = 0;
    for (var tag : scene.getListOrEmpty("subjects")) {
      var subject = (CompoundTag) tag;
      if (!subject.getStringOr("id", "").equals(scene.getStringOr("observer", ""))) continue;
      var bow = net.minecraft.core.Direction.byName(subject.getStringOr("bow", "north"));
      if (bow == null) bow = net.minecraft.core.Direction.NORTH;
      o.starForward =
          FlightDynamics.rotate(
              FlightDynamics.orientation(
                  subject.getDoubleOr("yaw", 0),
                  subject.getDoubleOr("pitch", 0),
                  subject.getDoubleOr("roll", 0)),
              new Vec3(bow.getStepX(), bow.getStepY(), bow.getStepZ()));
      if (subject.getBooleanOr("ftl", false)) {
        double extra =
            Math.clamp(o.currentTime - scene.getDoubleOr("simulationTime", o.currentTime), 0, 2);
        o.starFtl =
            (float)
                TravelVisuals.streaks(
                    subject.getDoubleOr("ftlElapsed", 0) + extra,
                    subject.getDoubleOr("ftlDuration", 0));
      }
      break;
    }
    SceneUpload.metadata(o, projection);
    try (var state = new ComputeStateScope()) {
      o.poll();
      o.measurementStarted = !o.pending[o.querySlot];
      SceneUpload.prepareData(o, scene, eye, o.currentTime);
      if (o.measurementStarted) glQueryCounter(o.queries[o.querySlot][8], GL_TIMESTAMP);
      o.currentKey +=
          ":weather:" + o.surfaceAtmosphere + ":" + o.surfaceRain + ":" + o.surfaceThunder;
      var renderSettings = SpaceSettings.get();
      o.currentKey +=
          ":atmosphere:"
              + o.atmosphere
              + ":"
              + renderSettings.clouds
              + ":"
              + renderSettings.cloudSteps;
      if (o.atmosphere != null) {
        o.atmosphereTables =
            AtmosphereLuts.get(
                o.atmosphere,
                SpaceComputeRenderer.transmittanceProgram,
                SpaceComputeRenderer.multipleProgram);
        var settings = SpaceSettings.get();
        String nextSkyKey =
            o.currentKey
                + ":"
                + o.atmosphere
                + ":"
                + o.atmosphereCenter
                + ":"
                + eye
                + ":"
                + Math.floor(o.currentTime * 10)
                + ":"
                + settings.clouds
                + ":"
                + settings.cloudSteps;
        if (!nextSkyKey.equals(o.skyKey)
            && o.atmosphereCenter.length() <= o.atmosphere.radius() + o.atmosphere.height()) {
          SceneUpload.uniforms(
              o,
              SpaceComputeRenderer.skyProgram,
              projection,
              SpaceComputeRenderer.SKY_WIDTH,
              SpaceComputeRenderer.SKY_HEIGHT);
          // Do not bind an image for sampling while writing it, even when that sampler is inactive.
          glActiveTexture(GL_TEXTURE6);
          glBindTexture(GL_TEXTURE_2D, 0);
          glActiveTexture(GL_TEXTURE7);
          glBindTexture(GL_TEXTURE_2D, 0);
          ComputeImages.image(0, o.skyRadiance, GL_WRITE_ONLY, GL_RGBA16F);
          ComputeImages.image(1, o.skyTransmission, GL_WRITE_ONLY, GL_RGBA16F);
          SpaceComputeRenderer.skyProgram.dispatch(
              SpaceComputeRenderer.SKY_WIDTH, SpaceComputeRenderer.SKY_HEIGHT);
          glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
          o.skyKey = nextSkyKey;
          o.atmosphereUpdates++;
        }
        // Exterior rays integrate their small bounded shell directly in the surface pass.
        // They do not need a camera sky lookup covering mostly empty space.
        if (o.atmosphereCenter.length() > o.atmosphere.radius() + o.atmosphere.height())
          o.skyKey = nextSkyKey;
      }
      if (o.measurementStarted) glQueryCounter(o.queries[o.querySlot][9], GL_TIMESTAMP);
      if (o.measurementStarted) glQueryCounter(o.queries[o.querySlot][0], GL_TIMESTAMP);
      SceneUpload.uniforms(o, SpaceComputeRenderer.surfaceProgram, projection, o.width, o.height);
      glUniform2f(SpaceComputeRenderer.surfaceProgram.uniform("Jitter"), o.jitterX, o.jitterY);
      ComputeImages.image(0, o.radiance, GL_WRITE_ONLY, GL_RGBA16F);
      ComputeImages.image(1, o.geometry, GL_WRITE_ONLY, GL_RGBA32F);
      SpaceComputeRenderer.surfaceProgram.dispatch(o.width, o.height);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
      if (o.measurementStarted) {
        glQueryCounter(o.queries[o.querySlot][1], GL_TIMESTAMP);
        glQueryCounter(o.queries[o.querySlot][2], GL_TIMESTAMP);
      }
      SpaceComputeRenderer.resolveProgram.use();
      SpaceComputeRenderer.resolveProgram.integer("Current", 0);
      SpaceComputeRenderer.resolveProgram.integer("Geometry", 1);
      SpaceComputeRenderer.resolveProgram.integer("History", 2);
      SpaceComputeRenderer.resolveProgram.integer("HistoryGeometry", 3);
      o.texture(0, o.radiance.texture());
      o.texture(1, o.geometry.texture());
      o.texture(2, o.oldRadiance.texture());
      o.texture(3, o.oldGeometry.texture());
      SpaceComputeRenderer.resolveProgram.matrix("InvView", new Matrix4f(projection).invert());
      SpaceComputeRenderer.resolveProgram.matrix("PreviousProjection", o.previousProjection);
      var delta = eye.subtract(o.previousEye);
      glUniform3f(
          SpaceComputeRenderer.resolveProgram.uniform("EyeDelta"),
          (float) delta.x,
          (float) delta.y,
          (float) delta.z);
      glUniform2i(SpaceComputeRenderer.resolveProgram.uniform("ViewSize"), o.width, o.height);
      String key = o.currentKey;
      boolean reuse =
          o.history
              && o.currentGeneration == SpaceComputeRenderer.generation
              && SpaceRenderMath.history(
                  delta.length(),
                  ComputeMath.directionDot(projection, o.previousProjection),
                  o.previousScene.equals(key),
                  ComputeMath.sameProjection(projection, o.previousProjection),
                  Math.abs(o.currentTime - o.previousTime) < .25);
      if (reuse) o.historyReuses++;
      else o.historyResets++;
      SpaceComputeRenderer.resolveProgram.integer(
          "HistoryValid",
          reuse
                  && SpaceSettings.get().temporal
                  && !Boolean.getBoolean("nullspace.compute.noHistory")
              ? 1
              : 0);
      SpaceComputeRenderer.resolveProgram.scalar(
          "DeltaTime", (float) (o.currentTime - o.previousTime));
      glUniform2f(
          SpaceComputeRenderer.resolveProgram.uniform("CurrentJitter"), o.jitterX, o.jitterY);
      glUniform2f(
          SpaceComputeRenderer.resolveProgram.uniform("PreviousJitter"),
          o.previousJitterX,
          o.previousJitterY);
      ComputeImages.image(0, o.resolved, GL_WRITE_ONLY, GL_RGBA16F);
      SpaceComputeRenderer.resolveProgram.dispatch(o.width, o.height);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
      if (o.measurementStarted) glQueryCounter(o.queries[o.querySlot][3], GL_TIMESTAMP);
      glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
      o.previousJitterX = o.jitterX;
      o.previousJitterY = o.jitterY;
      o.previousProjection.set(projection);
      o.previousEye = eye;
      o.previousScene = key;
      o.previousTime = o.currentTime;
      o.history = true;
      o.currentGeneration = SpaceComputeRenderer.generation;
    }
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    encoder.copyTextureToTexture(
        o.resolved.texture(), o.oldRadiance.texture(), 0, 0, 0, 0, 0, o.width, o.height);
    encoder.copyTextureToTexture(
        o.geometry.texture(), o.oldGeometry.texture(), 0, 0, 0, 0, 0, o.width, o.height);
    o.cpuMs = (System.nanoTime() - start) / 1e6;
    o.frames++;
  }

  private SurfacePass() {}
}
