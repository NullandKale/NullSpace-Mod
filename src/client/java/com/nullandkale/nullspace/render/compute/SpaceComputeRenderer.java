package com.nullandkale.nullspace.render.compute;

import static org.lwjgl.opengl.GL43C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ResourceGroup;
import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import com.nullandkale.nullspace.model.optics.NativeVolumeShader;
import com.nullandkale.nullspace.model.space.SpaceDefinition;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.compute.ComputeImages.Image;
import com.nullandkale.nullspace.render.compute.PreparedScenes.Prepared;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * Resident per-view images and history, shared programs/definition generation; no live readback.
 */
public final class SpaceComputeRenderer implements AutoCloseable {
  public static final int SLICES = 24;
  public static final BindGroupLayout VIEW =
      BindGroupLayout.builder().withUniform("ComputeView", UniformType.UNIFORM_BUFFER).build();

  private static RenderPipeline pipeline(String name, String fragment, boolean depth) {
    return RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(Identifier.parse("nullspace:" + name))
            .withVertexShader(Identifier.parse("nullspace:core/scene_compose"))
            .withFragmentShader(Identifier.parse("nullspace:core/" + fragment))
            .withBindGroupLayout(VIEW)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(
                depth
                    ? new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)
                    : new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
            .build());
  }

  private static final RenderPipeline
      SURFACE = pipeline("compute_surface", "compute_surface", true),
      VOLUME = pipeline("compute_volume", "compute_volume", false);
  static ComputeProgram surfaceProgram, volumeProgram, resolveProgram, volumeResolveProgram;
  static ComputeProgram transmittanceProgram, multipleProgram, skyProgram;
  static int generation;
  private static final Set<SpaceComputeRenderer> LIVE =
      Collections.newSetFromMap(new IdentityHashMap<>());

  final int width, height, volumeWidth, volumeHeight;
  final Image radiance,
      geometry,
      resolved,
      oldRadiance,
      oldGeometry,
      prefix,
      rawPrefix,
      oldPrefix,
      stopImage,
      oldStop;
  final Image skyRadiance, skyTransmission;
  static final int SKY_WIDTH = 256, SKY_HEIGHT = 128;
  AtmosphereProfile atmosphere;
  Vec3 atmosphereCenter = Vec3.ZERO;
  Vec3 celestialEye = Vec3.ZERO, cloudOffset = Vec3.ZERO;
  Matrix4f cloudRotation = new Matrix4f();
  float atmosphereDistanceScale = 1;
  String atmosphereBody = "", skyKey = "";
  long atmosphereUpdates;
  AtmosphereLuts atmosphereTables;
  Image depthCopy;
  private final TextureTarget composed;
  final GpuBuffer viewBuffer;
  final int bodyBuffer, nodeBuffer, sceneBuffer, computeSampler;

  /** Allocation-stage fault seam used only by the graphical constructor regression. */
  public static java.util.function.IntConsumer allocationProbe = stage -> {};

  private <T extends AutoCloseable> T own(T resource) {
    resources.own(resource);
    allocationProbe.accept(resources.size());
    return resource;
  }

  private void cleanup(Runnable action) {
    resources.defer(action);
    allocationProbe.accept(resources.size());
  }

  private final ResourceGroup resources = new ResourceGroup();
  final ByteBuffer bodyBytes, nodeBytes, sceneBytes;

  private ByteBuffer bytes(int size) {
    var b = MemoryUtil.memAlloc(size);
    cleanup(() -> MemoryUtil.memFree(b));
    return b;
  }

  private int buffer() {
    int id = glGenBuffers();
    cleanup(() -> glDeleteBuffers(id));
    return id;
  }

  final Matrix4f previousProjection = new Matrix4f();
  Vec3 previousEye = Vec3.ZERO;
  String previousScene = "";
  double previousTime;
  boolean history;
  boolean surfaceAtmosphere;
  float surfaceRain, surfaceThunder;
  Vector3f surfaceUp = new Vector3f(0, 1, 0);
  Matrix4f currentProjection = new Matrix4f();
  Vec3 currentEye = Vec3.ZERO;
  double currentTime;
  Vec3 starForward = new Vec3(0, 0, -1);
  float starFtl, starParallax;
  String currentKey = "";
  Prepared current;
  int currentGeneration,
      bodyCount,
      nodeCount,
      volumeCount,
      cabinCount,
      geometryHash,
      previousGeometryHash;
  Tag lastSubjects;
  int subjectsHash;
  boolean volumeHistory;
  final Matrix4f previousVolumeProjection = new Matrix4f();
  Vec3 previousVolumeEye = Vec3.ZERO;
  String previousVolumeKey = "";
  double previousVolumeTime;
  float jitterX, jitterY, previousJitterX, previousJitterY;
  final int[][] queries = new int[4][10];
  int querySlot;
  boolean measurementStarted;
  final boolean[] pending = new boolean[4];
  long frames, uploadedBytes, historyReuses, historyResets;
  double surfaceMs, volumeMs, resolveMs, cpuMs, volumeIntegrationMs, volumeReconstructionMs;
  private double atmosphereMs;
  private boolean closed;

  public record Stats(
      long frames,
      long uploadedBytes,
      double surfaceMs,
      double volumeMs,
      double resolveMs,
      double cpuMs,
      long bytes,
      int bodies,
      int nodes,
      int volumes,
      double volumeIntegrationMs,
      double volumeReconstructionMs,
      double atmosphereMs) {}

  public Stats stats() {
    return new Stats(
        frames,
        uploadedBytes,
        surfaceMs,
        volumeMs,
        resolveMs,
        cpuMs,
        width * (long) height * 64
            + SKY_WIDTH * (long) SKY_HEIGHT * 16
            + volumeWidth * (long) volumeHeight * (SLICES * 24 + 32)
            + (depthCopy == null
                ? 0
                : depthCopy.texture().getWidth(0)
                    * (long) depthCopy.texture().getHeight(0)
                    * depthCopy.texture().getFormat().blockSize())
            + 2L * (bodyBytes.capacity() + nodeBytes.capacity() + sceneBytes.capacity())
            + 224,
        bodyCount,
        nodeCount,
        volumeCount,
        volumeIntegrationMs,
        volumeReconstructionMs,
        atmosphereMs);
  }

  public GpuTextureView prefix() {
    return prefix.view();
  }

  public GpuTextureView depth() {
    return depthCopy == null ? geometry.view() : depthCopy.view();
  }

  public int volumeWidth() {
    return volumeWidth;
  }

  public int volumeHeight() {
    return volumeHeight;
  }

  public GpuBuffer viewBuffer() {
    return viewBuffer;
  }

  public long historyReuses() {
    return historyReuses;
  }

  public long historyResets() {
    return historyResets;
  }

  public static int liveViews() {
    return LIVE.size();
  }

  public TextureTarget output() {
    return composed;
  }

  public static void initialize() {
    NativeVolumeShader.source(
        () -> {
          try (var reader =
              Minecraft.getInstance()
                  .getResourceManager()
                  .getResourceOrThrow(
                      Identifier.parse("nullspace:shaders/include/space_optics.glsl"))
                  .openAsReader()) {
            return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
          } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load native volume optics", error);
          }
        });
    net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(
            net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
        .registerReloadListener(
            new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
              @Override
              public Identifier getFabricId() {
                return Identifier.parse("nullspace:compute");
              }

              @Override
              public void onResourceManagerReload(
                  net.minecraft.server.packs.resources.ResourceManager manager) {
                if (surfaceProgram != null)
                  Minecraft.getInstance().execute(SpaceComputeRenderer::reload);
              }
            });
  }

  public static boolean reload() {
    return reload(Map.of());
  }

  public static boolean reload(Map<String, String> sources) {
    if (!ComputeProgram.enabled()) return false;
    ComputeProgram a = null, b = null, c = null, d = null, e = null, f = null, g = null;
    try (var scope = new ComputeStateScope()) {
      a =
          sources.containsKey("surface")
              ? new ComputeProgram(sources.get("surface"))
              : ComputeProgram.load("surface");
      b =
          sources.containsKey("volume")
              ? new ComputeProgram(sources.get("volume"))
              : ComputeProgram.load("volume");
      c =
          sources.containsKey("resolve")
              ? new ComputeProgram(sources.get("resolve"))
              : ComputeProgram.load("resolve");
      d = ComputeProgram.load("volume_resolve");
      e =
          sources.containsKey("atmosphere_transmittance")
              ? new ComputeProgram(sources.get("atmosphere_transmittance"))
              : ComputeProgram.load("atmosphere_transmittance");
      f =
          sources.containsKey("atmosphere_multiple")
              ? new ComputeProgram(sources.get("atmosphere_multiple"))
              : ComputeProgram.load("atmosphere_multiple");
      g =
          sources.containsKey("atmosphere_sky")
              ? new ComputeProgram(sources.get("atmosphere_sky"))
              : ComputeProgram.load("atmosphere_sky");
      if (surfaceProgram != null) {
        surfaceProgram.close();
        volumeProgram.close();
        resolveProgram.close();
        volumeResolveProgram.close();
        transmittanceProgram.close();
        multipleProgram.close();
        skyProgram.close();
      }
      surfaceProgram = a;
      volumeProgram = b;
      resolveProgram = c;
      volumeResolveProgram = d;
      transmittanceProgram = e;
      multipleProgram = f;
      skyProgram = g;
      AtmosphereLuts.clear();
      generation++;
      for (var view : LIVE) {
        view.history = false;
        view.volumeHistory = false;
        view.skyKey = "";
      }
      return true;
    } catch (RuntimeException error) {
      if (a != null) a.close();
      if (b != null) b.close();
      if (c != null) c.close();
      if (d != null) d.close();
      if (e != null) e.close();
      if (f != null) f.close();
      if (g != null) g.close();
      NullSpace.LOGGER.error(
          "Compute shader reload failed; retaining last working programs", error);
      if (surfaceProgram == null) throw error;
      return false;
    }
  }

  public static void clear() {
    for (var view : List.copyOf(LIVE)) view.close();
    PreparedScenes.DEFINITIONS.clear();
    VisualCabins.clear();
    AtmosphereLuts.clear();
    if (surfaceProgram != null) {
      surfaceProgram.close();
      volumeProgram.close();
      resolveProgram.close();
      volumeResolveProgram.close();
      transmittanceProgram.close();
      multipleProgram.close();
      skyProgram.close();
      transmittanceProgram = null;
      multipleProgram = null;
      skyProgram = null;
      surfaceProgram = null;
      volumeProgram = null;
      resolveProgram = null;
      volumeResolveProgram = null;
    }
  }

  public SpaceComputeRenderer(int width, int height) {
    if (width < 1 || height < 1 || width > 2048 || height > 2048)
      throw new IllegalArgumentException("Compute viewport outside budget");
    this.width = width;
    this.height = height;
    double scale =
        Double.parseDouble(
            System.getProperty(
                "nullspace.compute.volumeScale", Double.toString(SpaceSettings.get().volumeScale)));
    if (!Double.isFinite(scale) || scale < .125 || scale > 1)
      throw new IllegalArgumentException("Volume scale must be 0.125..1");
    volumeWidth = Math.max(1, (int) Math.ceil(width * scale));
    volumeHeight = Math.max(1, (int) Math.ceil(height * scale));
    ComputeProgram.require();
    if (volumeHeight * SLICES > glGetInteger(GL_MAX_TEXTURE_SIZE))
      throw new IllegalArgumentException("Volume atlas exceeds device texture limit");
    if (surfaceProgram == null) reload();
    try {
      bodyBytes = bytes(SpaceDefinition.MAX_INSTANCES * 80);
      nodeBytes = bytes(SpaceDefinition.MAX_INSTANCES * 64);
      sceneBytes = bytes((80 + 64 * 261) * 16);
      radiance = own(Image.make("Space HDR", width, height, GpuFormat.RGBA16_FLOAT));
      geometry =
          own(Image.make("Space hit distance and ID", width, height, GpuFormat.RGBA32_FLOAT));
      resolved = own(Image.make("Space resolved HDR", width, height, GpuFormat.RGBA16_FLOAT));
      oldRadiance = own(Image.make("Space HDR history", width, height, GpuFormat.RGBA16_FLOAT));
      oldGeometry = own(Image.make("Space history hits", width, height, GpuFormat.RGBA32_FLOAT));
      skyRadiance =
          own(Image.make("Sky view radiance", SKY_WIDTH, SKY_HEIGHT, GpuFormat.RGBA16_FLOAT));
      skyTransmission =
          own(Image.make("Sky view transmission", SKY_WIDTH, SKY_HEIGHT, GpuFormat.RGBA16_FLOAT));
      prefix =
          own(
              Image.make(
                  "Volume optical prefixes",
                  volumeWidth,
                  volumeHeight * SLICES,
                  GpuFormat.RGBA16_FLOAT));
      rawPrefix =
          own(
              Image.make(
                  "Volume samples", volumeWidth, volumeHeight * SLICES, GpuFormat.RGBA16_FLOAT));
      oldPrefix =
          own(
              Image.make(
                  "Volume history", volumeWidth, volumeHeight * SLICES, GpuFormat.RGBA16_FLOAT));
      stopImage =
          own(Image.make("Volume opaque stop", volumeWidth, volumeHeight, GpuFormat.RGBA32_FLOAT));
      oldStop =
          own(
              Image.make(
                  "Volume previous stop", volumeWidth, volumeHeight, GpuFormat.RGBA32_FLOAT));
      composed =
          new TextureTarget(
              "Space volume composite", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
      cleanup(composed::destroyBuffers);
      viewBuffer =
          RenderSystem.getDevice()
              .createBuffer(
                  () -> "Compute view", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 224);
      own(viewBuffer);
      try (var state = new ComputeStateScope()) {
        bodyBuffer = buffer();
        nodeBuffer = buffer();
        sceneBuffer = buffer();
        computeSampler = glGenSamplers();
        cleanup(() -> glDeleteSamplers(computeSampler));
        glSamplerParameteri(computeSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(computeSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(computeSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(computeSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        for (int[] row : queries) {
          glGenQueries(row);
          cleanup(() -> glDeleteQueries(row));
        }
      }
    } catch (RuntimeException | Error failure) {
      resources.rollback(failure);
      throw failure;
    }
    currentGeneration = generation;
    LIVE.add(this);
  }

  public boolean atmosphereReady() {
    return atmosphere != null && !skyKey.isEmpty();
  }

  public long atmosphereUpdates() {
    return atmosphereUpdates;
  }

  public static int sharedAtmospheres() {
    return AtmosphereLuts.count();
  }

  /** GPU diagnostics use the same isolated readback as the scene regression fixtures. */
  public float[] inspectTransmittance() {
    if (atmosphere == null) throw new IllegalStateException("No atmosphere in this view");
    try (var state = new ComputeStateScope()) {
      var tables = AtmosphereLuts.get(atmosphere, transmittanceProgram, multipleProgram);
      return inspect(tables.transmittance, AtmosphereLuts.TRANS_WIDTH, AtmosphereLuts.TRANS_HEIGHT);
    }
  }

  public float[] inspectSkyTransmission() {
    return inspect(skyTransmission, SKY_WIDTH, SKY_HEIGHT);
  }

  public float[] inspectSkyRadiance() {
    return inspect(skyRadiance, SKY_WIDTH, SKY_HEIGHT);
  }

  void texture(int unit, GpuTexture texture) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, ((GlTexture) texture).glId());
    glBindSampler(unit, computeSampler);
  }

  public void drawSurfaces(RenderPass pass) {
    bind(pass, SURFACE, resolved.view(), geometry.view());
    pass.draw(3, 1, 0, 0);
  }

  private void bind(
      RenderPass pass, RenderPipeline pipeline, GpuTextureView color, GpuTextureView extra) {
    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
    pass.setUniform("ComputeView", viewBuffer);
    var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    pass.setUniform("Sampler0", color, sampler);
    pass.setUniform("Sampler1", extra, sampler);
    pass.setUniform("Sampler2", pipeline == VOLUME ? depth() : geometry.view(), sampler);
  }

  public void composite(GpuTextureView opaqueColor) {
    try (var pass =
        RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(
                () -> "Space volume resolve", composed.getColorTextureView(), Optional.empty())) {
      bind(pass, VOLUME, opaqueColor, prefix.view());
      pass.draw(3, 1, 0, 0);
    }
  }

  public void drawVolume(RenderPass pass, GpuTextureView sourceColor) {
    bind(pass, VOLUME, sourceColor, prefix.view());
    pass.draw(3, 1, 0, 0);
  }

  /** Explicit diagnostics/tests only; normal rendering never synchronously reads GPU images. */
  public float[] inspectRadiance() {
    return inspect(resolved, width, height);
  }

  public float[] inspectHits() {
    return inspect(geometry, width, height);
  }

  public float[] inspectPrefixes() {
    return inspect(prefix, volumeWidth, volumeHeight * SLICES);
  }

  private float[] inspect(Image image, int w, int h) {
    return ComputeImages.inspectTexture(image.id(), w, h);
  }

  void poll() {
    for (int i = 0; i < queries.length; i++)
      if (pending[i] && glGetQueryObjecti(queries[i][7], GL_QUERY_RESULT_AVAILABLE) != 0) {
        long[] t = new long[10];
        for (int j = 0; j < 10; j++) t[j] = glGetQueryObjectui64(queries[i][j], GL_QUERY_RESULT);
        atmosphereMs = (t[9] - t[8]) / 1e6;
        surfaceMs = (t[1] - t[0]) / 1e6;
        resolveMs = (t[3] - t[2]) / 1e6;
        volumeIntegrationMs = (t[5] - t[4]) / 1e6;
        volumeReconstructionMs = (t[7] - t[6]) / 1e6;
        volumeMs = volumeIntegrationMs + volumeReconstructionMs;
        pending[i] = false;
      }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    LIVE.remove(this);
    try {
      if (depthCopy != null) depthCopy.close();
    } finally {
      resources.close();
    }
  }

  /** Ray-traces bodies and sky for this frame (SurfacePass). */
  public void surfaces(CompoundTag scene, Vec3 eye, Matrix4f projection) {
    SurfacePass.surfaces(this, scene, eye, projection);
  }

  /** Accumulates volumes against the opaque depth (VolumePass). */
  public void volumes(GpuTexture opaqueDepth) {
    VolumePass.volumes(this, opaqueDepth);
  }

  public void volumes(GpuTexture opaqueDepth, Matrix4f depthProjection) {
    VolumePass.volumes(this, opaqueDepth, depthProjection);
  }
}
