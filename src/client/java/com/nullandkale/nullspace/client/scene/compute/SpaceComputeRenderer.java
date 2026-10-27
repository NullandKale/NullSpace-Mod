package com.nullandkale.nullspace.client.scene.compute;

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
import com.nullandkale.nullspace.scene.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
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
  private static ComputeProgram surfaceProgram, volumeProgram, resolveProgram, volumeResolveProgram;
  private static ComputeProgram transmittanceProgram, multipleProgram, skyProgram;
  private static int generation;
  private static final Set<SpaceComputeRenderer> LIVE =
      Collections.newSetFromMap(new IdentityHashMap<>());

  private static final class Prepared {
    final CompoundTag source;
    final SpaceDefinition definition;
    final List<SpaceDefinition.Body> bodies;
    double tick = Double.NaN;
    SpaceBvh hierarchy;

    Prepared(CompoundTag source, SpaceDefinition definition, List<SpaceDefinition.Body> bodies) {
      this.source = source;
      this.definition = definition;
      this.bodies = bodies;
    }

    SpaceBvh bvh(double time) {
      double next = Math.floor(time * 20) / 20;
      if (next != tick) {
        tick = next;
        hierarchy = new SpaceBvh(bodies, next, .05);
      }
      return hierarchy;
    }
  }

  private static final LinkedHashMap<String, Prepared> DEFINITIONS =
      new LinkedHashMap<>(8, .75f, true);

  record Image(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
    static Image make(String name, int w, int h, GpuFormat format) {
      var device = RenderSystem.getDevice();
      var texture =
          device.createTexture(
              name,
              GpuTexture.USAGE_TEXTURE_BINDING
                  | GpuTexture.USAGE_COPY_SRC
                  | GpuTexture.USAGE_COPY_DST
                  | GpuTexture.USAGE_RENDER_ATTACHMENT,
              format,
              w,
              h,
              1,
              1);
      try {
        return new Image(texture, device.createTextureView(texture));
      } catch (RuntimeException | Error failure) {
        texture.close();
        throw failure;
      }
    }

    int id() {
      return ((GlTexture) texture).glId();
    }

    @Override
    public void close() {
      view.close();
      texture.close();
    }
  }

  private final int width, height, volumeWidth, volumeHeight;
  private final Image radiance,
      geometry,
      resolved,
      oldRadiance,
      oldGeometry,
      prefix,
      rawPrefix,
      oldPrefix,
      stopImage,
      oldStop;
  private final Image skyRadiance, skyTransmission;
  private static final int SKY_WIDTH = 256, SKY_HEIGHT = 128;
  private AtmosphereProfile atmosphere;
  private Vec3 atmosphereCenter = Vec3.ZERO;
  private Vec3 celestialEye = Vec3.ZERO, cloudOffset = Vec3.ZERO;
  private Matrix4f cloudRotation = new Matrix4f();
  private float atmosphereDistanceScale = 1;
  private String atmosphereBody = "", skyKey = "";
  private long atmosphereUpdates;
  private AtmosphereLuts atmosphereTables;
  private Image depthCopy;
  private final TextureTarget composed;
  private final GpuBuffer viewBuffer;
  private final int bodyBuffer, nodeBuffer, sceneBuffer, computeSampler;

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

  private final com.nullandkale.nullspace.server.ResourceGroup resources =
      new com.nullandkale.nullspace.server.ResourceGroup();
  private final ByteBuffer bodyBytes, nodeBytes, sceneBytes;

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

  private final Matrix4f previousProjection = new Matrix4f();
  private Vec3 previousEye = Vec3.ZERO;
  private String previousScene = "";
  private double previousTime;
  private boolean history;
  private boolean surfaceAtmosphere;
  private float surfaceRain, surfaceThunder;
  private Vector3f surfaceUp = new Vector3f(0, 1, 0);
  private Matrix4f currentProjection = new Matrix4f();
  private Vec3 currentEye = Vec3.ZERO;
  private double currentTime;
  private String currentKey = "";
  private Prepared current;
  private int currentGeneration,
      bodyCount,
      nodeCount,
      volumeCount,
      cabinCount,
      geometryHash,
      previousGeometryHash;
  private Tag lastSubjects;
  private int subjectsHash;
  private boolean volumeHistory;
  private final Matrix4f previousVolumeProjection = new Matrix4f();
  private Vec3 previousVolumeEye = Vec3.ZERO;
  private String previousVolumeKey = "";
  private double previousVolumeTime;
  private float jitterX, jitterY, previousJitterX, previousJitterY;
  private final int[][] queries = new int[4][10];
  private int querySlot;
  private boolean measurementStarted;
  private final boolean[] pending = new boolean[4];
  private long frames, uploadedBytes, historyReuses, historyResets;
  private double surfaceMs, volumeMs, resolveMs, cpuMs, volumeIntegrationMs, volumeReconstructionMs;
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
                : depthCopy.texture.getWidth(0)
                    * (long) depthCopy.texture.getHeight(0)
                    * depthCopy.texture.getFormat().blockSize())
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
    return prefix.view;
  }

  public GpuTextureView depth() {
    return depthCopy == null ? geometry.view : depthCopy.view;
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
    DEFINITIONS.clear();
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
                "nullspace.compute.volumeScale",
                Double.toString(com.nullandkale.nullspace.client.SpaceSettings.get().volumeScale)));
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
      sceneBytes = bytes((80 + 4 * 261) * 16);
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

  private static Prepared prepared(CompoundTag catalog) {
    String key = catalog.getStringOr("id", "") + ":" + catalog.getLongOr("revision", 0);
    var old = DEFINITIONS.get(key);
    if (old != null && old.source.equals(catalog)) return old;
    var definition = SpaceDefinitionCodec.decode(catalog);
    var next = new Prepared(catalog.copy(), definition, definition.instances());
    DEFINITIONS.put(key, next);
    while (DEFINITIONS.size() > 4) DEFINITIONS.remove(DEFINITIONS.keySet().iterator().next());
    return next;
  }

  private static void vec(ByteBuffer bytes, double x, double y, double z, double w) {
    bytes.putFloat((float) x).putFloat((float) y).putFloat((float) z).putFloat((float) w);
  }

  private static void rgb(ByteBuffer b, int color, double w) {
    vec(b, (color >> 16 & 255) / 255., (color >> 8 & 255) / 255., (color & 255) / 255., w);
  }

  private void upload(int buffer, ByteBuffer bytes, int binding) {
    bytes.flip();
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
    glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_STREAM_DRAW);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer);
    uploadedBytes += bytes.remaining();
  }

  private void prepareData(CompoundTag scene, Vec3 eye, double time) {
    surfaceAtmosphere = scene.getBooleanOr("surfaceAtmosphere", false);
    surfaceRain = (float) scene.getDoubleOr("surfaceRain", 0);
    surfaceThunder = (float) scene.getDoubleOr("surfaceThunder", 0);
    var level = Minecraft.getInstance().level;
    // Weather belongs to the viewed surface, never to an unrelated world or an airless moon.
    if (surfaceAtmosphere
        && scene.getBooleanOr("planetSky", false)
        && level != null
        && level.dimension().identifier().toString().equals(scene.getStringOr("dimension", ""))) {
      surfaceRain = level.getRainLevel(1);
      surfaceThunder = level.getThunderLevel(1);
    }
    surfaceRain =
        surfaceAtmosphere ? Math.clamp(Float.isFinite(surfaceRain) ? surfaceRain : 0, 0, 1) : 0;
    surfaceThunder =
        surfaceAtmosphere
            ? Math.clamp(Float.isFinite(surfaceThunder) ? surfaceThunder : 0, 0, surfaceRain)
            : 0;
    var observer =
        scene.getListOrEmpty("subjects").stream()
            .map(t -> (CompoundTag) t)
            .filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", "")))
            .findFirst()
            .orElse(new CompoundTag());
    surfaceUp
        .set(0, 1, 0)
        .rotate(
            new Quaternionf()
                .rotationYXZ(
                    (float) Math.toRadians(observer.getDoubleOr("yaw", 0)),
                    (float) Math.toRadians(observer.getDoubleOr("pitch", 0)),
                    (float) Math.toRadians(observer.getDoubleOr("roll", 0))));
    current = prepared(scene.getCompoundOrEmpty("catalog"));
    bodyBytes.clear();
    sceneBytes.clear();
    bodyCount = current.bodies.size();
    double elapsed = time - current.definition.epoch();
    selectAtmosphere(scene, eye, elapsed);
    for (var b : current.bodies) {
      var p = b.at(elapsed).subtract(celestialEye);
      vec(bodyBytes, p.x, p.y, p.z, b.radius());
      rgb(bodyBytes, b.color(), b.emission());
      vec(bodyBytes, b.velocity().x, b.velocity().y, b.velocity().z, b.kind());
      vec(bodyBytes, b.roughness(), Math.floorMod(b.seed(), 10000), b.atmosphere(), b.spin());
      vec(
          bodyBytes,
          b.rotationAxis().x,
          b.rotationAxis().y,
          b.rotationAxis().z,
          elapsed * b.spin());
    }
    var bvh = current.bvh(elapsed);
    nodeCount = bvh.nodes().size();
    nodeBytes.clear();
    bvh.write(nodeBytes, celestialEye);
    var volumes = new ArrayList<>(current.definition.volumes());
    for (var b : current.definition.bodies())
      if (b.atmosphere() > 0 && !b.id().equals(atmosphereBody)) {
        double outer = b.radius() * (1 + b.atmosphere());
        volumes.add(
            new SpaceDefinition.Volume(
                b.id() + ":air",
                b.center(),
                new Vec3(outer, outer, outer),
                b.velocity(),
                0xff75b8ff,
                .14,
                .008,
                0,
                .1,
                b.seed(),
                1));
      }
    for (var b : current.definition.bodies())
      if (b.kind() == 4) {
        var axis = b.velocity().lengthSqr() > 0 ? b.velocity().normalize() : new Vec3(0, 0, 1);
        volumes.add(
            new SpaceDefinition.Volume(
                b.id() + ":tail",
                b.center().subtract(axis.scale(b.radius() * 8)),
                new Vec3(b.radius() * 2, b.radius() * 2, b.radius() * 12),
                b.velocity(),
                0xff87bfd9,
                .22,
                .012,
                .2,
                .4,
                b.seed(),
                2));
      }
    if (volumes.size() > 16)
      throw new IllegalArgumentException("Atmospheres/tails exceed volume budget");
    volumeCount = volumes.size();
    for (var v : volumes) {
      var p = v.center().add(v.velocity().scale(elapsed)).subtract(celestialEye);
      vec(sceneBytes, p.x, p.y, p.z, v.kind());
      vec(sceneBytes, v.extent().x, v.extent().y, v.extent().z, v.density());
      rgb(sceneBytes, v.color(), v.extinction());
      vec(
          sceneBytes,
          (v.color() >> 16 & 255) / 255. * v.emission(),
          (v.color() >> 8 & 255) / 255. * v.emission(),
          (v.color() & 255) / 255. * v.emission(),
          v.phase());
      double inner =
          v.kind() == 1
              ? current.definition.bodies().stream()
                  .filter(b -> (b.id() + ":air").equals(v.id()))
                  .mapToDouble(SpaceDefinition.Body::radius)
                  .findFirst()
                  .orElse(0)
              : 0;
      var axis = v.velocity().lengthSqr() > 0 ? v.velocity().normalize() : new Vec3(0, 0, 1);
      vec(
          sceneBytes,
          v.kind() == 2 ? axis.x : inner,
          v.kind() == 2 ? axis.y : 0,
          v.kind() == 2 ? axis.z : 0,
          Math.floorMod(v.seed(), 10000));
    }
    while (sceneBytes.position() < 80 * 16) sceneBytes.putLong(0);
    cabinCount = 0;
    for (Tag tag : scene.getListOrEmpty("subjects")) {
      if (cabinCount == 4) break;
      var subject = (CompoundTag) tag;
      var mask = VisualCabins.mask(subject);
      boolean interior = false;
      for (int bits : mask)
        if (bits != 0) {
          interior = true;
          break;
        }
      if (!interior) continue;
      var position =
          new Vec3(
              subject.getDoubleOr("x", 0),
              subject.getDoubleOr("y", 0),
              subject.getDoubleOr("z", 0));
      var orientation =
          new Quaternionf()
              .rotationYXZ(
                  (float) Math.toRadians(subject.getDoubleOr("yaw", 0)),
                  (float) Math.toRadians(subject.getDoubleOr("pitch", 0)),
                  (float) Math.toRadians(subject.getDoubleOr("roll", 0)))
              .conjugate();
      var delta = eye.subtract(position);
      var inverse =
          new Matrix4f()
              .rotation(orientation)
              .translate((float) delta.x, (float) delta.y, (float) delta.z);
      inverse.get(sceneBytes.position(), sceneBytes);
      sceneBytes.position(sceneBytes.position() + 64);
      vec(sceneBytes, 0, 0, 0, 0);
      for (int bits : mask) sceneBytes.putInt(bits);
      cabinCount++;
    }
    if (bodyBytes.position() == 0) vec(bodyBytes, 0, 0, 0, 0);
    if (nodeBytes.position() == 0) vec(nodeBytes, 0, 0, 0, 0);
    upload(bodyBuffer, bodyBytes, 0);
    upload(nodeBuffer, nodeBytes, 1);
    upload(sceneBuffer, sceneBytes, 2);
  }

  private void selectAtmosphere(CompoundTag scene, Vec3 eye, double elapsed) {
    atmosphere = null;
    atmosphereBody = "";
    celestialEye = eye;
    cloudOffset = Vec3.ZERO;
    cloudRotation.identity();
    atmosphereDistanceScale = 1;
    if (surfaceAtmosphere) {
      var profile = scene.getCompoundOrEmpty("atmosphereProfile");
      atmosphere =
          profile.isEmpty()
              ? AtmosphereProfile.temperate(2200, .04)
              : AtmosphereProfile.decode(profile);
      if (!profile.isEmpty()) {
        atmosphereCenter =
            new Vec3(
                    scene.getDoubleOr("atmosphereX", 0),
                    scene.getDoubleOr("atmosphereY", 0),
                    scene.getDoubleOr("atmosphereZ", 0))
                .subtract(eye);
      } else {
        // Compatibility with old frame fixtures/saves which predate an explicit planet profile.
        var observer =
            scene.getListOrEmpty("subjects").stream()
                .map(t -> (CompoundTag) t)
                .filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", "")))
                .findFirst()
                .orElse(new CompoundTag());
        atmosphereCenter =
            new Vec3(
                    observer.getDoubleOr("x", 0),
                    observer.getDoubleOr("y", 0),
                    observer.getDoubleOr("z", 0))
                .subtract(eye)
                .subtract(
                    new Vec3(surfaceUp.x, surfaceUp.y, surfaceUp.z)
                        .scale(atmosphere.radius() + .05));
      }
      atmosphereBody = scene.getStringOr("surfaceBody", "");
    }
    if (scene.getBooleanOr("planetSky", false) && scene.contains("surfaceRadius")) {
      var observer =
          scene.getListOrEmpty("subjects").stream()
              .map(t -> (CompoundTag) t)
              .filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", "")))
              .findFirst()
              .orElse(new CompoundTag());
      var view = PlanetSky.surfaceView(scene, observer, eye, surfaceAtmosphere ? atmosphere : null);
      celestialEye = view.celestialEye();
      if (surfaceAtmosphere) {
        atmosphereCenter = view.center();
        cloudOffset = view.cloudOffset();
        cloudRotation.rotation(new Quaternionf(view.cloudRotation()));
        atmosphereDistanceScale = (float) view.distanceScale();
      }
    }
    if (!surfaceAtmosphere) {
      double largest = .002;
      for (var b : current.definition.bodies()) {
        if (b.atmosphere() <= 0 || b.radius() < 1) continue;
        var position = b.at(elapsed).subtract(celestialEye);
        double apparent = b.radius() / Math.max(1, position.length());
        if (apparent > largest) {
          atmosphere = b.atmosphereProfile();
          atmosphereCenter = position;
          atmosphereBody = b.id();
          largest = apparent;
        }
      }
    }
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

  private void uniforms(ComputeProgram p, Matrix4f projection, int w, int h) {
    // A cache miss dispatches other programs. Resolve it before binding this pass's program.
    if (atmosphere != null)
      atmosphereTables = AtmosphereLuts.get(atmosphere, transmittanceProgram, multipleProgram);
    p.use();
    p.matrix("InvView", new Matrix4f(projection).invert());
    p.matrix("ViewProjection", projection);
    p.integer("BodyCount", bodyCount);
    int[] lights =
        java.util.stream.IntStream.range(0, current.bodies.size())
            .filter(i -> current.bodies.get(i).emission() > 0)
            .limit(8)
            .toArray();
    p.integer("LightCount", lights.length);
    if (lights.length > 0) glUniform1iv(p.uniform("Lights"), lights);
    p.integer("NodeCount", nodeCount);
    p.integer("VolumeCount", volumeCount);
    p.integer("CabinCount", cabinCount);
    p.integer("HasSurfaceAtmosphere", surfaceAtmosphere ? 1 : 0);
    p.scalar("SurfaceRain", surfaceRain);
    p.scalar("SurfaceThunder", surfaceThunder);
    p.integer("HasAtmosphere", atmosphere == null ? 0 : 1);
    if (atmosphere != null) {
      AtmosphereLuts.uniforms(p, atmosphere);
      glUniform3f(
          p.uniform("AtmoCenter"),
          (float) atmosphereCenter.x,
          (float) atmosphereCenter.y,
          (float) atmosphereCenter.z);
      p.integer("TransmittanceLut", 4);
      p.integer("MultipleScatteringLut", 5);
      p.integer("SkyRadianceLut", 6);
      p.integer("SkyTransmissionLut", 7);
      p.integer(
          "CloudsEnabled",
          com.nullandkale.nullspace.client.SpaceSettings.get().clouds || surfaceRain > 0 ? 1 : 0);
      p.scalar("AtmoDistanceScale", atmosphereDistanceScale);
      p.matrix("CloudRotation", cloudRotation);
      glUniform3f(
          p.uniform("CloudOffset"),
          (float) cloudOffset.x,
          (float) cloudOffset.y,
          (float) cloudOffset.z);
      p.integer("CloudSteps", com.nullandkale.nullspace.client.SpaceSettings.get().cloudSteps);
      p.scalar("CloudTime", (float) (Math.floor(currentTime * 10) / 10));
      texture(4, atmosphereTables.transmittance.texture());
      texture(5, atmosphereTables.multiple.texture());
      texture(6, skyRadiance.texture);
      texture(7, skyTransmission.texture);
    }
    glUniform3f(p.uniform("SurfaceUp"), surfaceUp.x, surfaceUp.y, surfaceUp.z);
    p.integer(
        "SurfaceSamples", com.nullandkale.nullspace.client.SpaceSettings.get().surfaceSamples);
    p.integer("VolumeSteps", com.nullandkale.nullspace.client.SpaceSettings.get().volumeSteps);
    p.integer("BruteForce", Boolean.getBoolean("nullspace.compute.bruteForce") ? 1 : 0);
    p.scalar("Time", (float) (currentTime % 100000));
    glUniform2i(p.uniform("ViewSize"), w, h);
    glUniform2f(p.uniform("Jitter"), 0, 0);
  }

  private static void image(int binding, Image image, int access, int format) {
    glBindImageTexture(binding, image.id(), 0, false, 0, access, format);
  }

  private void texture(int unit, GpuTexture texture) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, ((GlTexture) texture).glId());
    glBindSampler(unit, computeSampler);
  }

  private void metadata(Matrix4f projection) {
    try (var stack = MemoryStack.stackPush()) {
      var b = stack.malloc(160);
      new Matrix4f(projection).invert().get(0, b);
      projection.get(64, b);
      b.putFloat(128, width)
          .putFloat(132, height)
          .putFloat(136, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0)
          .putFloat(140, jitterX);
      b.putFloat(144, .05f).putFloat(148, 1e6f).putFloat(152, SLICES).putFloat(156, jitterY);
      RenderSystem.getDevice().createCommandEncoder().writeToBuffer(viewBuffer.slice(), b);
    }
  }

  public void surfaces(CompoundTag scene, Vec3 eye, Matrix4f projection) {
    long start = System.nanoTime();
    boolean jitter =
        false; // Spatial AA replaces unstable per-frame surface jitter, including stars.
    jitterX = jitter ? (float) (halton(frames, 2) - .5) : 0;
    jitterY = jitter ? (float) (halton(frames, 3) - .5) : 0;
    var subjects = scene.get("subjects");
    if (lastSubjects != subjects) {
      lastSubjects = subjects;
      subjectsHash = Objects.hashCode(subjects);
    }
    geometryHash = subjectsHash;
    currentKey =
        scene.getCompoundOrEmpty("catalog").getStringOr("id", "")
            + ":"
            + scene.getCompoundOrEmpty("catalog").getLongOr("revision", 0)
            + ":"
            + scene.getStringOr("observer", "")
            + ":"
            + scene.getLongOr("source", 0)
            + ":"
            + scene.getIntOr("selection", 0);
    currentEye = eye;
    currentProjection.set(projection);
    currentTime =
        scene.getDoubleOr("simulationTime", 0)
            + Math.min(
                2,
                Math.max(
                    0,
                    (System.nanoTime() - scene.getLongOr("receivedNanos", System.nanoTime()))
                        / 1e9));
    metadata(projection);
    try (var state = new ComputeStateScope()) {
      poll();
      measurementStarted = !pending[querySlot];
      prepareData(scene, eye, currentTime);
      if (measurementStarted) glQueryCounter(queries[querySlot][8], GL_TIMESTAMP);
      currentKey += ":weather:" + surfaceAtmosphere + ":" + surfaceRain + ":" + surfaceThunder;
      var renderSettings = com.nullandkale.nullspace.client.SpaceSettings.get();
      currentKey +=
          ":atmosphere:"
              + atmosphere
              + ":"
              + renderSettings.clouds
              + ":"
              + renderSettings.cloudSteps;
      if (atmosphere != null) {
        atmosphereTables = AtmosphereLuts.get(atmosphere, transmittanceProgram, multipleProgram);
        var settings = com.nullandkale.nullspace.client.SpaceSettings.get();
        String nextSkyKey =
            currentKey
                + ":"
                + atmosphere
                + ":"
                + atmosphereCenter
                + ":"
                + eye
                + ":"
                + Math.floor(currentTime * 10)
                + ":"
                + settings.clouds
                + ":"
                + settings.cloudSteps;
        if (!nextSkyKey.equals(skyKey)
            && atmosphereCenter.length() <= atmosphere.radius() + atmosphere.height()) {
          uniforms(skyProgram, projection, SKY_WIDTH, SKY_HEIGHT);
          // Do not bind an image for sampling while writing it, even when that sampler is inactive.
          glActiveTexture(GL_TEXTURE6);
          glBindTexture(GL_TEXTURE_2D, 0);
          glActiveTexture(GL_TEXTURE7);
          glBindTexture(GL_TEXTURE_2D, 0);
          image(0, skyRadiance, GL_WRITE_ONLY, GL_RGBA16F);
          image(1, skyTransmission, GL_WRITE_ONLY, GL_RGBA16F);
          skyProgram.dispatch(SKY_WIDTH, SKY_HEIGHT);
          glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
          skyKey = nextSkyKey;
          atmosphereUpdates++;
        }
        // Exterior rays integrate their small bounded shell directly in the surface pass.
        // They do not need a camera sky lookup covering mostly empty space.
        if (atmosphereCenter.length() > atmosphere.radius() + atmosphere.height())
          skyKey = nextSkyKey;
      }
      if (measurementStarted) glQueryCounter(queries[querySlot][9], GL_TIMESTAMP);
      if (measurementStarted) glQueryCounter(queries[querySlot][0], GL_TIMESTAMP);
      uniforms(surfaceProgram, projection, width, height);
      glUniform2f(surfaceProgram.uniform("Jitter"), jitterX, jitterY);
      image(0, radiance, GL_WRITE_ONLY, GL_RGBA16F);
      image(1, geometry, GL_WRITE_ONLY, GL_RGBA32F);
      surfaceProgram.dispatch(width, height);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
      if (measurementStarted) {
        glQueryCounter(queries[querySlot][1], GL_TIMESTAMP);
        glQueryCounter(queries[querySlot][2], GL_TIMESTAMP);
      }
      resolveProgram.use();
      resolveProgram.integer("Current", 0);
      resolveProgram.integer("Geometry", 1);
      resolveProgram.integer("History", 2);
      resolveProgram.integer("HistoryGeometry", 3);
      texture(0, radiance.texture);
      texture(1, geometry.texture);
      texture(2, oldRadiance.texture);
      texture(3, oldGeometry.texture);
      resolveProgram.matrix("InvView", new Matrix4f(projection).invert());
      resolveProgram.matrix("PreviousProjection", previousProjection);
      var delta = eye.subtract(previousEye);
      glUniform3f(
          resolveProgram.uniform("EyeDelta"), (float) delta.x, (float) delta.y, (float) delta.z);
      glUniform2i(resolveProgram.uniform("ViewSize"), width, height);
      String key = currentKey;
      boolean reuse =
          history
              && currentGeneration == generation
              && SpaceRenderMath.history(
                  delta.length(),
                  directionDot(projection, previousProjection),
                  previousScene.equals(key),
                  sameProjection(projection, previousProjection),
                  Math.abs(currentTime - previousTime) < .25);
      if (reuse) historyReuses++;
      else historyResets++;
      resolveProgram.integer(
          "HistoryValid",
          reuse
                  && com.nullandkale.nullspace.client.SpaceSettings.get().temporal
                  && !Boolean.getBoolean("nullspace.compute.noHistory")
              ? 1
              : 0);
      resolveProgram.scalar("DeltaTime", (float) (currentTime - previousTime));
      glUniform2f(resolveProgram.uniform("CurrentJitter"), jitterX, jitterY);
      glUniform2f(resolveProgram.uniform("PreviousJitter"), previousJitterX, previousJitterY);
      image(0, resolved, GL_WRITE_ONLY, GL_RGBA16F);
      resolveProgram.dispatch(width, height);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
      if (measurementStarted) glQueryCounter(queries[querySlot][3], GL_TIMESTAMP);
      glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
      previousJitterX = jitterX;
      previousJitterY = jitterY;
      previousProjection.set(projection);
      previousEye = eye;
      previousScene = key;
      previousTime = currentTime;
      history = true;
      currentGeneration = generation;
    }
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    encoder.copyTextureToTexture(
        resolved.texture, oldRadiance.texture, 0, 0, 0, 0, 0, width, height);
    encoder.copyTextureToTexture(
        geometry.texture, oldGeometry.texture, 0, 0, 0, 0, 0, width, height);
    cpuMs = (System.nanoTime() - start) / 1e6;
    frames++;
  }

  private static double halton(long n, int base) {
    double f = 1, r = 0;
    while (n > 0) {
      f /= base;
      r += f * (n % base);
      n /= base;
    }
    return r;
  }

  private static double directionDot(Matrix4f a, Matrix4f b) {
    var x = new Matrix4f(a).invert().transformProject(new Vector3f(0, 0, 1)).normalize();
    var y = new Matrix4f(b).invert().transformProject(new Vector3f(0, 0, 1)).normalize();
    return x.dot(y);
  }

  private static boolean sameProjection(Matrix4f a, Matrix4f b) {
    double ax = Math.sqrt(a.m00() * a.m00() + a.m10() * a.m10() + a.m20() * a.m20()),
        bx = Math.sqrt(b.m00() * b.m00() + b.m10() * b.m10() + b.m20() * b.m20()),
        ay = Math.sqrt(a.m01() * a.m01() + a.m11() * a.m11() + a.m21() * a.m21()),
        by = Math.sqrt(b.m01() * b.m01() + b.m11() * b.m11() + b.m21() * b.m21());
    return Math.abs(ax - bx) < .0001 && Math.abs(ay - by) < .0001;
  }

  public void drawSurfaces(RenderPass pass) {
    bind(pass, SURFACE, resolved.view, geometry.view);
    pass.draw(3, 1, 0, 0);
  }

  private void bind(
      RenderPass pass, RenderPipeline pipeline, GpuTextureView color, GpuTextureView extra) {
    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
    pass.setUniform("ComputeView", viewBuffer);
    var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    pass.setUniform("Sampler0", color, sampler);
    pass.setUniform("Sampler1", extra, sampler);
    pass.setUniform("Sampler2", pipeline == VOLUME ? depth() : geometry.view, sampler);
  }

  public void volumes(GpuTexture opaqueDepth) {
    volumes(opaqueDepth, currentProjection);
  }

  public void volumes(GpuTexture opaqueDepth, Matrix4f depthProjection) {
    if (opaqueDepth != null) {
      if (depthCopy == null
          || depthCopy.texture.getWidth(0) != opaqueDepth.getWidth(0)
          || depthCopy.texture.getHeight(0) != opaqueDepth.getHeight(0)) {
        if (depthCopy != null) depthCopy.close();
        depthCopy =
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
              depthCopy.texture,
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
      RenderSystem.getDevice().createCommandEncoder().writeToBuffer(viewBuffer.slice(160, 64), b);
    }
    try (var state = new ComputeStateScope()) {
      if (measurementStarted) glQueryCounter(queries[querySlot][4], GL_TIMESTAMP);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, bodyBuffer);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeBuffer);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, sceneBuffer);
      uniforms(volumeProgram, currentProjection, volumeWidth, volumeHeight);
      volumeProgram.matrix("InvDepth", new Matrix4f(depthProjection).invert());
      volumeProgram.integer("OpaqueDepth", 0);
      volumeProgram.integer("HasDepth", opaqueDepth != null ? 1 : 0);
      volumeProgram.integer(
          "ZeroToOne", RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
      if (opaqueDepth != null) texture(0, depthCopy.texture);
      image(0, rawPrefix, GL_WRITE_ONLY, GL_RGBA16F);
      image(1, stopImage, GL_WRITE_ONLY, GL_RGBA32F);
      volumeProgram.scalar(
          "SamplePhase",
          Boolean.getBoolean("nullspace.compute.noJitter")
                  || !com.nullandkale.nullspace.client.SpaceSettings.get().stochastic
              ? .5f
              : (float) halton(frames + 1, 5));
      volumeProgram.dispatch(volumeWidth, volumeHeight, 8, 4);
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
      if (measurementStarted) {
        glQueryCounter(queries[querySlot][5], GL_TIMESTAMP);
        glQueryCounter(queries[querySlot][6], GL_TIMESTAMP);
      }
      volumeResolveProgram.use();
      volumeResolveProgram.integer("Current", 0);
      volumeResolveProgram.integer("History", 1);
      volumeResolveProgram.integer("CurrentStop", 2);
      volumeResolveProgram.integer("HistoryStop", 3);
      texture(0, rawPrefix.texture);
      texture(1, oldPrefix.texture);
      texture(2, stopImage.texture);
      texture(3, oldStop.texture);
      volumeResolveProgram.matrix("InvView", new Matrix4f(currentProjection).invert());
      volumeResolveProgram.matrix("PreviousProjection", previousVolumeProjection);
      var delta = currentEye.subtract(previousVolumeEye);
      glUniform3f(
          volumeResolveProgram.uniform("EyeDelta"),
          (float) delta.x,
          (float) delta.y,
          (float) delta.z);
      glUniform2i(volumeResolveProgram.uniform("ViewSize"), volumeWidth, volumeHeight);
      String key = currentKey;
      boolean reuse =
          volumeHistory
              && previousGeometryHash == geometryHash
              && SpaceRenderMath.history(
                  delta.length(),
                  directionDot(currentProjection, previousVolumeProjection),
                  key.equals(previousVolumeKey),
                  sameProjection(currentProjection, previousVolumeProjection),
                  Math.abs(currentTime - previousVolumeTime) < .25);
      volumeResolveProgram.integer(
          "HistoryValid",
          reuse
                  && com.nullandkale.nullspace.client.SpaceSettings.get().temporal
                  && !Boolean.getBoolean("nullspace.compute.noHistory")
              ? 1
              : 0);
      image(0, prefix, GL_WRITE_ONLY, GL_RGBA16F);
      volumeResolveProgram.dispatch(volumeWidth, volumeHeight);
      glMemoryBarrier(
          GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
              | GL_TEXTURE_FETCH_BARRIER_BIT
              | GL_TEXTURE_UPDATE_BARRIER_BIT);
      if (measurementStarted) {
        glQueryCounter(queries[querySlot][7], GL_TIMESTAMP);
        pending[querySlot] = true;
        querySlot = (querySlot + 1) % queries.length;
        measurementStarted = false;
      }
    }
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    encoder.copyTextureToTexture(
        prefix.texture, oldPrefix.texture, 0, 0, 0, 0, 0, volumeWidth, volumeHeight * SLICES);
    encoder.copyTextureToTexture(
        stopImage.texture, oldStop.texture, 0, 0, 0, 0, 0, volumeWidth, volumeHeight);
    previousVolumeProjection.set(currentProjection);
    previousVolumeEye = currentEye;
    previousVolumeTime = currentTime;
    previousGeometryHash = geometryHash;
    previousVolumeKey = currentKey;
    volumeHistory = true;
  }

  public void composite(GpuTextureView opaqueColor) {
    try (var pass =
        RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(
                () -> "Space volume resolve", composed.getColorTextureView(), Optional.empty())) {
      bind(pass, VOLUME, opaqueColor, prefix.view);
      pass.draw(3, 1, 0, 0);
    }
  }

  public void drawVolume(RenderPass pass, GpuTextureView sourceColor) {
    bind(pass, VOLUME, sourceColor, prefix.view);
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
    return inspectTexture(image.id(), w, h);
  }

  /** Diagnostic readback with isolated pixel-pack state and checked destination capacity. */
  public static float[] inspectTexture(int texture, int w, int h) {
    if (w < 1 || h < 1) throw new IllegalArgumentException("Invalid readback dimensions");
    float[] result = new float[w * h * 4];
    int pbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
    int[] keys = {
      GL_PACK_ALIGNMENT,
      GL_PACK_ROW_LENGTH,
      GL_PACK_SKIP_PIXELS,
      GL_PACK_SKIP_ROWS,
      GL_PACK_IMAGE_HEIGHT,
      GL_PACK_SKIP_IMAGES,
      GL_PACK_SWAP_BYTES,
      GL_PACK_LSB_FIRST
    };
    int[] old = new int[keys.length];
    for (int i = 0; i < keys.length; i++) old[i] = glGetInteger(keys[i]);
    try (var state = new ComputeStateScope()) {
      glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
      for (int key : keys) glPixelStorei(key, key == GL_PACK_ALIGNMENT ? 1 : 0);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, texture);
      if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != w
          || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT) != h)
        throw new IllegalArgumentException("Readback dimensions do not match the texture");
      glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
      glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, result);
    } finally {
      for (int i = 0; i < keys.length; i++) glPixelStorei(keys[i], old[i]);
      glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
    }
    return result;
  }

  private void poll() {
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
}
