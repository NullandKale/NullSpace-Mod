package com.nullandkale.nullspace.client.scene.compute;

import static org.lwjgl.opengl.GL43C.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.nullandkale.nullspace.scene.AtmosphereProfile;
import com.nullandkale.nullspace.server.ResourceGroup;
import java.util.LinkedHashMap;

/** Bounded render-thread cache of planet-only optical tables, shared by all viewports. */
final class AtmosphereLuts implements AutoCloseable {
  static final int TRANS_WIDTH = 256, TRANS_HEIGHT = 64, MULTIPLE_SIZE = 32;
  private static final LinkedHashMap<AtmosphereProfile, AtmosphereLuts> CACHE =
      new LinkedHashMap<>(8, .75f, true);
  final SpaceComputeRenderer.Image transmittance, multiple;
  private final ResourceGroup resources = new ResourceGroup();

  private AtmosphereLuts(AtmosphereProfile profile, ComputeProgram trans, ComputeProgram multi) {
    try {
      transmittance =
          resources.own(
              SpaceComputeRenderer.Image.make(
                  "Atmosphere transmittance", TRANS_WIDTH, TRANS_HEIGHT, GpuFormat.RGBA16_FLOAT));
      multiple =
          resources.own(
              SpaceComputeRenderer.Image.make(
                  "Atmosphere multiple scattering",
                  MULTIPLE_SIZE,
                  MULTIPLE_SIZE,
                  GpuFormat.RGBA16_FLOAT));
      trans.use();
      uniforms(trans, profile);
      glBindImageTexture(0, transmittance.id(), 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
      trans.dispatch(TRANS_WIDTH, TRANS_HEIGHT);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
      multi.use();
      uniforms(multi, profile);
      bind(4, transmittance.id());
      multi.integer("TransmittanceLut", 4);
      glBindImageTexture(0, multiple.id(), 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
      multi.dispatch(MULTIPLE_SIZE, MULTIPLE_SIZE);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    } catch (RuntimeException | Error error) {
      resources.rollback(error);
      throw error;
    }
  }

  static AtmosphereLuts get(AtmosphereProfile profile, ComputeProgram trans, ComputeProgram multi) {
    var existing = CACHE.get(profile);
    if (existing != null) return existing;
    var next = new AtmosphereLuts(profile, trans, multi);
    CACHE.put(profile, next);
    while (CACHE.size() > 4) {
      var first = CACHE.entrySet().iterator();
      var entry = first.next();
      first.remove();
      entry.getValue().close();
    }
    return next;
  }

  static void bind(int unit, int id) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, id);
    glBindSampler(unit, 0);
  }

  static void uniforms(ComputeProgram p, AtmosphereProfile a) {
    p.scalar("AtmoRadius", (float) a.radius());
    p.scalar("AtmoHeight", (float) a.height());
    p.scalar("AtmoRayleighHeight", (float) a.rayleighHeight());
    p.scalar("AtmoMieHeight", (float) a.mieHeight());
    p.scalar("AtmoMie", (float) a.mie());
    p.scalar("AtmoAlbedo", (float) a.albedo());
    p.scalar("AtmoClouds", (float) a.clouds());
    glUniform3f(
        p.uniform("AtmoRayleigh"),
        (float) a.rayleigh().x,
        (float) a.rayleigh().y,
        (float) a.rayleigh().z);
    glUniform3f(
        p.uniform("AtmoAbsorption"),
        (float) a.absorption().x,
        (float) a.absorption().y,
        (float) a.absorption().z);
  }

  static int count() {
    return CACHE.size();
  }

  static void clear() {
    CACHE.values().forEach(AtmosphereLuts::close);
    CACHE.clear();
  }

  @Override
  public void close() {
    resources.close();
  }
}
