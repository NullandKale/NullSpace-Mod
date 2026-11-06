package com.nullandkale.nullspace.client.scene;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.nullandkale.nullspace.scene.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Independent GPU scene, target, matrices and mesh storage. Never reenters Minecraft's main
 * renderer.
 */
public final class SceneViewport implements AutoCloseable {
  private com.nullandkale.nullspace.client.scene.compute.SpaceComputeRenderer compute;

  public com.nullandkale.nullspace.client.scene.compute.SpaceComputeRenderer compute() {
    return compute;
  }

  private static final RenderPipeline COMPUTE_TRANSPARENT =
      RenderPipelines.register(
          RenderPipeline.builder()
              .withLocation(Identifier.parse("nullspace:viewport_compute_transparent"))
              .withVertexShader(Identifier.parse("nullspace:core/viewport"))
              .withFragmentShader(Identifier.parse("nullspace:core/viewport_fog"))
              .withBindGroupLayout(BindGroupLayouts.PROJECTION)
              .withBindGroupLayout(
                  com.nullandkale.nullspace.client.scene.compute.SpaceComputeRenderer.VIEW)
              .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
              .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
              .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
              .withPrimitiveTopology(PrimitiveTopology.QUADS)
              .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
              .withCull(false)
              .withColorTargetState(
                  new ColorTargetState(
                      Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 15))
              .build());

  private static RenderPipeline reverse(String name, boolean transparent) {
    return RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(Identifier.parse("nullspace:" + name))
            .withVertexShader(Identifier.parse("nullspace:core/viewport"))
            .withFragmentShader(Identifier.parse("nullspace:core/viewport"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(
                new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, !transparent))
            .withCull(false)
            .withColorTargetState(
                new ColorTargetState(
                    transparent ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty(),
                    GpuFormat.RGBA8_UNORM,
                    15))
            .build());
  }

  private static final RenderPipeline REVERSE_OPAQUE = reverse("viewport_reverse_opaque", false);
  private static final RenderPipeline REVERSE_TRANSPARENT =
      reverse("viewport_reverse_transparent", true);
  public final TextureTarget target;

  public static void initialize() {}

  private final com.nullandkale.nullspace.server.ResourceGroup resources =
      new com.nullandkale.nullspace.server.ResourceGroup();
  private final StagedVertexBuffer staging;
  private final GpuBuffer matrix;

  private record Cached(
      CompoundTag source, int hash, Object models, List<Quad> quads, float fill) {}

  private final Map<String, Cached> meshes = new HashMap<>();

  private static final class Quad {
    final Vector3f[] points;
    float[] uv;
    int color;
    boolean translucent;
    double distance;

    Quad(Vector3f[] points, float[] uv, int color, boolean translucent, double distance) {
      this.points = points;
      this.uv = uv;
      this.color = color;
      this.translucent = translucent;
      this.distance = distance;
    }

    double distance() {
      return distance;
    }

    boolean translucent() {
      return translucent;
    }
  }

  private record MeshKey(String id, int hash, Object models, float fill) {}

  private static final LinkedHashMap<MeshKey, List<Quad>> SHARED =
      new LinkedHashMap<>(192, .75f, true);
  private static int sharedQuads;
  private final ArrayList<Quad> transformed = new ArrayList<>(),
      frameQuads = new ArrayList<>(),
      transparentQuads = new ArrayList<>();
  private int transformedAt;
  private long transformedQuadsCreated;

  public long transformedQuadsCreated() {
    return transformedQuadsCreated;
  }

  public int transformedCapacity() {
    return transformed.size();
  }

  private Quad transformed(Quad original) {
    Quad q;
    if (transformedAt < transformed.size()) q = transformed.get(transformedAt);
    else {
      transformedQuadsCreated++;
      q =
          new Quad(
              new Vector3f[] {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()},
              original.uv,
              original.color,
              original.translucent,
              0);
      if (transformedAt < 131072) transformed.add(q);
    }
    transformedAt++;
    q.uv = original.uv;
    q.color = original.color;
    q.translucent = original.translucent;
    return q;
  }

  public static void clearSharedMeshes() {
    SHARED.clear();
    sharedQuads = 0;
  }

  public static int sharedMeshCount() {
    return SHARED.size();
  }

  private Tag sourceSubjects, sourceCatalog;
  private int contentHash, lastHash;
  private Object lastModels;
  private boolean hasImage, cleared;
  private int settingsRevision = -1;
  private double lastX, lastY, lastZ;
  private float lastYaw, lastPitch, lastFov;
  private String lastObserver = "";
  private Matrix4f lastProjection;
  private long renderedFrames, reusedFrames, lastActorTick = Long.MIN_VALUE;
  private int playerCount;

  public int playerCount() {
    return playerCount;
  }

  public int blockEntityCount() {
    return ScenePlayers.blocks();
  }

  public int entityCount() {
    return ScenePlayers.entities();
  }

  private long starSeed = Long.MIN_VALUE;
  private List<Quad> stars = List.of();

  private record BodyMesh(Vec3 center, List<Quad> quads) {}

  private int bodyHash;
  private List<BodyMesh> bodies = List.of();

  public long renderedFrames() {
    return renderedFrames;
  }

  public long reusedFrames() {
    return reusedFrames;
  }

  public int cachedSubjects() {
    return (int) meshes.keySet().stream().filter(id -> !id.contains("/")).count();
  }

  public int cachedTerrain() {
    return meshes.size() - cachedSubjects();
  }

  /** Share immutable packet arrays; only the camera fields of this view may be changed. */
  public static CompoundTag cameraView(CompoundTag source) {
    var view = new CompoundTag();
    for (String key : source.keySet()) view.put(key, source.get(key));
    return view;
  }

  public SceneViewport(int width, int height) {
    if (width < 16 || height < 16 || width > 2048 || height > 2048)
      throw new IllegalArgumentException("Viewport resolution outside budget");
    try {
      target =
          new TextureTarget(
              "NullSpace remote viewport",
              width,
              height,
              GpuFormat.RGBA8_UNORM,
              GpuFormat.D32_FLOAT);
      resources.defer(target::destroyBuffers);
      staging = resources.own(new StagedVertexBuffer(() -> "NullSpace viewport", 1024 * 1024));
      matrix =
          RenderSystem.getDevice()
              .createBuffer(
                  () -> "Viewport projection",
                  GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                  64);
      resources.own(matrix);
    } catch (RuntimeException | Error failure) {
      resources.rollback(failure);
      throw failure;
    }
  }

  public void render(CompoundTag scene, float yawOffset, float pitchOffset, float fov) {
    render(scene, yawOffset, pitchOffset, fov, null);
  }

  public void render(
      CompoundTag source, float yawOffset, float pitchOffset, float fov, Matrix4f observerMatrix) {
    int revision = com.nullandkale.nullspace.client.SpaceSettings.revision();
    if (settingsRevision != revision) {
      settingsRevision = revision;
      hasImage = false;
      if (compute != null) {
        compute.close();
        compute = null;
      }
    }
    final var scene = SceneMotion.resolve(source);
    var subjects = scene.getListOrEmpty("subjects");
    if (subjects.isEmpty()
        || scene.contains("status") && !scene.getStringOr("status", "").startsWith("LIVE")) {
      clear();
      return;
    }
    var catalog = scene.get("catalog");
    if (sourceSubjects != subjects || sourceCatalog != catalog) {
      sourceSubjects = subjects;
      sourceCatalog = catalog;
      contentHash = 31 * subjects.hashCode() + Objects.hashCode(catalog);
    }
    int hash =
        Objects.hash(
            scene.getDoubleOr("simulationTime", 0),
            scene.getLongOr("source", 0),
            scene.getIntOr("selection", 0),
            contentHash,
            scene.getLongOr("seed", 79419843),
            scene.getLongOr("motionFrame", 0),
            scene.getBooleanOr("universe", false),
            TerrainClient.version());
    double cx = scene.getDoubleOr("cameraX", 0),
        cy = scene.getDoubleOr("cameraY", 0),
        cz = scene.getDoubleOr("cameraZ", 0);
    float cameraYaw = scene.getFloatOr("cameraYaw", 0) + yawOffset,
        cameraPitch = Math.clamp(scene.getFloatOr("cameraPitch", 0) + pitchOffset, -89, 89);
    var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    String observerId = scene.getStringOr("observer", "");
    boolean useCompute =
        scene.getBooleanOr("universe", false)
            && com.nullandkale.nullspace.client.scene.compute.ComputeProgram.enabled();
    boolean actors =
        useCompute
            || subjects.stream()
                .map(t -> (CompoundTag) t)
                .anyMatch(
                    t ->
                        (observerMatrix == null || !t.getStringOr("id", "").equals(observerId))
                            && !t.getListOrEmpty("players").isEmpty());
    long actorTick = actors ? Minecraft.getInstance().level.getGameTime() : 0;
    if (subjects.stream()
        .map(t -> (CompoundTag) t)
        .anyMatch(t -> t.getStringOr("id", "").equals(observerId) && t.getBooleanOr("ftl", false)))
      actorTick = System.nanoTime() / 16_666_667;
    boolean sameProjection =
        observerMatrix == null
            ? lastProjection == null
            : lastProjection != null && observerMatrix.equals(lastProjection);
    if (hasImage
        && hash == lastHash
        && lastModels == models
        && cx == lastX
        && cy == lastY
        && cz == lastZ
        && cameraYaw == lastYaw
        && cameraPitch == lastPitch
        && fov == lastFov
        && observerId.equals(lastObserver)
        && sameProjection
        && actorTick == lastActorTick) {
      reusedFrames++;
      return;
    }
    var ids = new HashSet<String>();
    for (Tag tag : subjects) ids.add(((CompoundTag) tag).getStringOr("id", ""));
    for (var t : scene.getListOrEmpty("terrain")) ids.add(((CompoundTag) t).getStringOr("id", ""));
    meshes.keySet().retainAll(ids);
    var observer =
        subjects.stream()
            .map(t -> (CompoundTag) t)
            .filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", "")))
            .findFirst()
            .orElse((CompoundTag) subjects.getFirst());
    var localEye =
        new Vec3(
            scene.getDoubleOr("cameraX", 0),
            scene.getDoubleOr("cameraY", 0),
            scene.getDoubleOr("cameraZ", 0));
    var observerPose = pose(observer);
    var eye = observerPose.toUniverse(localEye);
    float yaw = scene.getFloatOr("cameraYaw", 0) + yawOffset,
        pitch = Math.clamp(scene.getFloatOr("cameraPitch", 0) + pitchOffset, -89, 89);
    var localDirection =
        new Vec3(
            -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)),
            -Math.sin(Math.toRadians(pitch)),
            Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
    var direction = observerPose.toUniverse(localDirection).subtract(observerPose.position());
    var upV = observerPose.orientation().transform(new Vector3d(0, 1, 0));
    // Geometry is camera relative before conversion to float; physical storage offsets never enter
    // this matrix.
    var view =
        new Matrix4f()
            .lookAt(
                0,
                0,
                0,
                (float) direction.x,
                (float) direction.y,
                (float) direction.z,
                (float) upV.x,
                (float) upV.y,
                (float) upV.z);
    boolean background = observerMatrix != null;
    var projection =
        background
            ? observerMatrix
            : new Matrix4f()
                .perspective(
                    (float) Math.toRadians(fov),
                    target.width / (float) target.height,
                    1000000,
                    .05f,
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne())
                .mul(view);
    try (var stack = MemoryStack.stackPush()) {
      var bytes = stack.malloc(64);
      projection.get(0, bytes);
      RenderSystem.getDevice().createCommandEncoder().writeToBuffer(matrix.slice(), bytes);
    }
    transformedAt = 0;
    frameQuads.clear();
    var quads = frameQuads;
    for (var tag : subjects)
      if ((!background || !sameVessel((CompoundTag) tag, observer))
          && (scene.getListOrEmpty("terrain").isEmpty()
              || !sameVessel((CompoundTag) tag, observer)))
        blocks((CompoundTag) tag, eye, quads, scene.getBooleanOr("pilotView", false) ? .65f : 0);
    if (!background)
      for (var t : scene.getListOrEmpty("terrain")) {
        var data = TerrainClient.resolve((CompoundTag) t);
        if (data != null) blocks(data, eye, quads, .15f, observerPose);
      }
    if (useCompute) {
      if (compute == null)
        compute =
            new com.nullandkale.nullspace.client.scene.compute.SpaceComputeRenderer(
                target.width, target.height);
      compute.surfaces(scene, eye, projection);
    } else if (scene.getBooleanOr("universe", false)) celestial(scene, eye, quads);
    var opaque =
        staging.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, null);
    var transparent =
        staging.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, null);
    for (var q : quads) if (!q.translucent) emit(staging.getVertexBuilder(opaque), q);
    transparentQuads.clear();
    for (var q : quads) if (q.translucent) transparentQuads.add(q);
    transparentQuads.sort(Comparator.comparingDouble(Quad::distance).reversed());
    for (var q : transparentQuads) emit(staging.getVertexBuilder(transparent), q);
    staging.upload();
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    RenderSystem.backupProjectionMatrix();
    RenderSystem.setProjectionMatrix(matrix.slice(), ProjectionType.PERSPECTIVE);
    FeatureRenderDispatcher.PreparedFrame players = null;
    try {
      var stack = RenderSystem.getModelViewStack();
      stack.pushMatrix();
      stack.identity();
      try {
        players = ScenePlayers.prepare(scene, eye, background);
        playerCount = ScenePlayers.submitted();
      } finally {
        stack.popMatrix();
      }
      try (var pass =
          encoder.createRenderPass(
              () -> "Independent remote scene",
              target.getColorTextureView(),
              Optional.of(new Vector4f(.002f, .003f, .008f, 1)),
              target.getDepthTextureView(),
              OptionalDouble.of(0))) {
        if (useCompute) compute.drawSurfaces(pass);
        var atlas =
            Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        pass.setUniform("Projection", matrix);
        pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
        var info = staging.getExecuteInfo(opaque);
        if (info != null) {
          pass.setPipeline(RenderSystem.getCompiledPipeline(REVERSE_OPAQUE));
          pass.setUniform("Projection", matrix);
          pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
          pass.setVertexBuffer(0, info.vertexBuffer().slice());
          pass.setIndexBuffer(info.indexBuffer(), info.indexType());
          pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
        if (players != null) players.executeSolid(pass);
      }
      if (useCompute && !background) {
        compute.volumes(target.getDepthTexture());
        compute.composite(target.getColorTextureView());
        encoder.copyTextureToTexture(
            compute.output().getColorTexture(),
            target.getColorTexture(),
            0,
            0,
            0,
            0,
            0,
            target.width,
            target.height);
      }
      try (var pass =
          encoder.createRenderPass(
              () -> "Remote transparent surfaces",
              target.getColorTextureView(),
              Optional.empty(),
              target.getDepthTextureView(),
              OptionalDouble.empty())) {
        var atlas =
            Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        var info = staging.getExecuteInfo(transparent);
        if (info != null) {
          pass.setPipeline(
              RenderSystem.getCompiledPipeline(
                  useCompute && !background ? COMPUTE_TRANSPARENT : REVERSE_TRANSPARENT));
          pass.setUniform("Projection", matrix);
          pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
          if (useCompute && !background) {
            pass.setUniform("ComputeView", compute.viewBuffer());
            pass.setUniform(
                "Sampler1",
                compute.prefix(),
                RenderSystem.getSamplerCache()
                    .getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST));
            pass.setUniform(
                "Sampler2",
                compute.depth(),
                RenderSystem.getSamplerCache()
                    .getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST));
          }
          pass.setVertexBuffer(0, info.vertexBuffer().slice());
          pass.setIndexBuffer(info.indexBuffer(), info.indexType());
          pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
        if (players != null) {
          players.executeTranslucent(pass);
          players.executeTranslucentAfterTerrain(pass);
        }
      }
    } finally {
      if (players != null) players.close();
      ScenePlayers.endFrame();
      RenderSystem.restoreProjectionMatrix();
    }
    staging.endFrame();
    hasImage = true;
    cleared = false;
    renderedFrames++;
    lastHash = hash;
    lastModels = models;
    lastX = cx;
    lastY = cy;
    lastZ = cz;
    lastYaw = cameraYaw;
    lastPitch = cameraPitch;
    lastFov = fov;
    lastObserver = observerId;
    lastProjection = observerMatrix == null ? null : new Matrix4f(observerMatrix);
    lastActorTick = actorTick;
  }

  private static ScenePose pose(CompoundTag data) {
    return new ScenePose(
        new Vec3(data.getDoubleOr("x", 0), data.getDoubleOr("y", 0), data.getDoubleOr("z", 0)),
        new Quaterniond()
            .rotationYXZ(
                Math.toRadians(data.getDoubleOr("yaw", 0)),
                Math.toRadians(data.getDoubleOr("pitch", 0)),
                Math.toRadians(data.getDoubleOr("roll", 0))));
  }

  public void clear() {
    hasImage = false;
    playerCount = 0;
    if (cleared) return;
    RenderSystem.getDevice()
        .createCommandEncoder()
        .clearColorAndDepthTextures(
            target.getColorTexture(),
            new Vector4f(.002f, .003f, .008f, 1),
            target.getDepthTexture(),
            0);
    cleared = true;
  }

  private static boolean sameVessel(CompoundTag subject, CompoundTag observer) {
    return subject == observer
        || subject.getStringOr("parent", "").equals(observer.getStringOr("id", ""));
  }

  private void blocks(CompoundTag data, Vec3 eye, List<Quad> output, float fill) {
    blocks(data, eye, output, fill, null);
  }

  private void blocks(CompoundTag data, Vec3 eye, List<Quad> output, float fill, ScenePose parent) {
    var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    var id = data.getStringOr("id", "");
    var cache = meshes.get(id);
    if (cache == null || cache.source != data || cache.models != models || cache.fill != fill) {
      var states = data.getIntArray("blocks").orElse(new int[0]);
      var light = data.getByteArray("light").orElse(new byte[0]);
      int hash =
          31 * (31 * Arrays.hashCode(states) + Arrays.hashCode(light)) + Float.floatToIntBits(fill);
      hash = 31 * hash + Arrays.hashCode(data.getIntArray("halo").orElse(new int[0]));
      hash =
          31 * hash
              + data.getIntOr("grass", 0)
              + data.getIntOr("foliage", 0)
              + data.getIntOr("water", 0);
      if (cache == null || cache.hash != hash || cache.models != models) {
        var key = new MeshKey(id, hash, models, fill);
        var geometry = SHARED.get(key);
        if (geometry == null) {
          var local = new ArrayList<Quad>();
          localBlocks(data, local, fill);
          geometry = List.copyOf(local);
          SHARED.put(key, geometry);
          sharedQuads += geometry.size();
          while (SHARED.size() > 192 || sharedQuads > 131072 && SHARED.size() > 1) {
            var it = SHARED.entrySet().iterator();
            sharedQuads -= it.next().getValue().size();
            it.remove();
          }
        }
        cache = new Cached(data, hash, models, geometry, fill);
      } else cache = new Cached(data, hash, models, cache.quads, fill);
      if (meshes.size() >= 192 && !meshes.containsKey(id)) meshes.clear();
      meshes.put(id, cache);
    }
    var localPose = pose(data);
    var pose =
        parent == null
            ? localPose
            : new ScenePose(
                parent.toUniverse(localPose.position()),
                parent.orientation().mul(localPose.orientation()));
    var transform =
        new Matrix4d()
            .translation(
                pose.position().x - eye.x, pose.position().y - eye.y, pose.position().z - eye.z)
            .rotate(pose.orientation());
    var vertex = new Vector3d();
    for (var original : cache.quads) {
      var q = transformed(original);
      double distance = 0;
      for (int n = 0; n < 4; n++) {
        var p = original.points[n];
        vertex.set(p.x, p.y, p.z);
        transform.transformPosition(vertex);
        q.points[n].set((float) vertex.x, (float) vertex.y, (float) vertex.z);
        distance += vertex.lengthSquared();
      }
      q.distance = distance;
      output.add(q);
    }
  }

  private static void localBlocks(CompoundTag data, List<Quad> output, float fill) {
    int[] states = data.getIntArray("blocks").orElse(new int[0]);
    int size = data.getIntOr("size", 32);
    if ((size != 16 && size != 32) || states.length != size * size * size) return;
    var world = new SectionWorld(data, size);
    var fluids =
        new net.minecraft.client.renderer.block.FluidRenderer(
            Minecraft.getInstance().getModelManager().getFluidStateModelSet());
    var lights = data.getByteArray("light").orElse(new byte[0]);
    var random = RandomSource.create(0);
    var parts = new ArrayList<BlockStateModelPart>();
    var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    for (int i = 0; i < states.length; i++) {
      var state = Block.stateById(states[i]);
      if (state.isAir()) continue;
      int x = i % size, y = i / (size * size), z = i / size % size;
      if (!state.getFluidState().isEmpty()) {
        var collector = new FluidCollector(output);
        fluids.tesselate(
            world,
            new BlockPos(x, y, z),
            layer -> {
              collector.translucent =
                  layer == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT;
              return collector;
            },
            state,
            state.getFluidState());
        collector.flush();
      }
      parts.clear();
      random.setSeed(i);
      models.get(state).collectParts(random, parts);
      for (var part : parts)
        for (int face = -1; face < 6; face++) {
          var facing = face < 0 ? null : Direction.values()[face];
          if (facing != null) {
            int nx = x + facing.getStepX(), ny = y + facing.getStepY(), nz = z + facing.getStepZ();
            if (!Block.shouldRenderFace(
                state, world.getBlockState(new BlockPos(nx, ny, nz)), facing)) continue;
          }
          for (BakedQuad q : part.getQuads(facing)) {
            Vector3f[] positions = new Vector3f[4];
            float[] uv = new float[8];
            double distance = 0;
            for (int v = 0; v < 4; v++) {
              var p = q.position(v);
              var w = new Vec3(x + p.x(), y + p.y(), z + p.z());
              positions[v] = new Vector3f((float) w.x, (float) w.y, (float) w.z);
              distance += w.lengthSqr();
              uv[v * 2] = UVPair.unpackU(q.packedUV(v));
              uv[v * 2 + 1] = UVPair.unpackV(q.packedUV(v));
            }
            float shade =
                q.materialInfo().lightEmission() > 0
                    ? 1
                    : switch (q.direction()) {
                      case UP -> 1;
                      case DOWN -> .55f;
                      case NORTH, SOUTH -> .8f;
                      default -> .7f;
                    };
            if (q.materialInfo().lightEmission() == 0 && lights.length == states.length) {
              int light = Byte.toUnsignedInt(lights[i]);
              var dir = q.direction();
              int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
              if (nx >= 0 && nx < size && ny >= 0 && ny < size && nz >= 0 && nz < size)
                light =
                    Math.max(light, Byte.toUnsignedInt(lights[nx + nz * size + ny * size * size]));
              shade *= Math.max(fill, .15f + .85f * Math.min(15, light) / 15f);
            }
            int c = (int) (shade * 255);
            int color = 0xff000000 | c << 16 | c << 8 | c;
            if (q.materialInfo().isTinted()) {
              var tintSource =
                  Minecraft.getInstance()
                      .getBlockColors()
                      .getTintSource(state, q.materialInfo().tintIndex());
              int tint =
                  tintSource == null
                      ? -1
                      : tintSource.colorInWorld(state, world, new BlockPos(x, y, z));
              color =
                  0xff000000
                      | ((tint >> 16 & 255) * c / 255) << 16
                      | ((tint >> 8 & 255) * c / 255) << 8
                      | (tint & 255) * c / 255;
            }
            output.add(
                new Quad(positions, uv, color, q.materialInfo().layer().translucent(), distance));
          }
        }
    }
  }

  private static final class SectionWorld
      implements net.minecraft.client.renderer.block.BlockAndTintGetter {
    final CompoundTag data;
    final int size;
    final int[] blocks, halo;
    final byte[] lights;

    SectionWorld(CompoundTag d, int n) {
      data = d;
      size = n;
      blocks = d.getIntArray("blocks").orElse(new int[0]);
      halo = d.getIntArray("halo").orElse(new int[0]);
      lights = d.getByteArray("light").orElse(new byte[0]);
    }

    public net.minecraft.world.level.CardinalLighting cardinalLighting() {
      return net.minecraft.world.level.CardinalLighting.DEFAULT;
    }

    public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
      return net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
    }

    public int getBlockTint(BlockPos p, net.minecraft.world.level.ColorResolver c) {
      return data.getIntOr(
          c == BiomeColors.GRASS_COLOR_RESOLVER
              ? "grass"
              : c == BiomeColors.FOLIAGE_COLOR_RESOLVER
                      || c == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER
                  ? "foliage"
                  : "water",
          c == BiomeColors.WATER_COLOR_RESOLVER ? 0x3f76e4 : 0x90bc70);
    }

    public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p) {
      return null;
    }

    public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p) {
      int x = p.getX(), y = p.getY(), z = p.getZ();
      if (x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size)
        return Block.stateById(blocks[x + z * size + y * size * size]);
      if (size == 16
          && halo.length == 5832
          && x >= -1
          && x <= 16
          && y >= -1
          && y <= 16
          && z >= -1
          && z <= 16) return Block.stateById(halo[(x + 1) + (z + 1) * 18 + (y + 1) * 324]);
      return Blocks.AIR.defaultBlockState();
    }

    public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p) {
      return getBlockState(p).getFluidState();
    }

    public int getHeight() {
      return size;
    }

    public int getMinY() {
      return 0;
    }

    public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos p) {
      int x = p.getX(), y = p.getY(), z = p.getZ();
      return x >= 0
              && x < size
              && y >= 0
              && y < size
              && z >= 0
              && z < size
              && lights.length == blocks.length
          ? Byte.toUnsignedInt(lights[x + z * size + y * size * size])
          : 15;
    }

    public int getRawBrightness(BlockPos p, int dark) {
      return getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p);
    }
  }

  private static final class FluidCollector implements VertexConsumer {
    final List<Quad> output;
    final Vector3f[] points = new Vector3f[4];
    final float[] uv = new float[8];
    int count, color = -1;
    boolean translucent = true;

    FluidCollector(List<Quad> output) {
      this.output = output;
    }

    void flush() {
      if (count == 4) {
        double d = 0;
        for (var p : points) d += p.lengthSquared();
        output.add(
            new Quad(
                points.clone(),
                uv.clone(),
                translucent ? (color & 0x00ffffff) | 0xb0000000 : color,
                translucent,
                d));
      }
      count = 0;
    }

    public VertexConsumer addVertex(float x, float y, float z) {
      if (count == 4) flush();
      points[count++] = new Vector3f(x, y, z);
      return this;
    }

    public VertexConsumer setColor(int r, int g, int b, int a) {
      color = a << 24 | r << 16 | g << 8 | b;
      return this;
    }

    public VertexConsumer setColor(int c) {
      color = c;
      return this;
    }

    public VertexConsumer setUv(float u, float v) {
      uv[(count - 1) * 2] = u;
      uv[(count - 1) * 2 + 1] = v;
      return this;
    }

    public VertexConsumer setUv1(int u, int v) {
      return this;
    }

    public VertexConsumer setUv2(int u, int v) {
      return this;
    }

    public VertexConsumer setUv3(float u, float v) {
      return this;
    }

    public VertexConsumer setNormal(float x, float y, float z) {
      return this;
    }

    public VertexConsumer setLineWidth(float w) {
      return this;
    }
  }

  private void celestial(CompoundTag scene, Vec3 eye, List<Quad> output) {
    if (stars.isEmpty() || starSeed != scene.getLongOr("seed", 79419843)) {
      var generated = new ArrayList<Quad>();
      var random = new java.util.Random(scene.getLongOr("seed", 79419843));
      for (int n = 0; n < 1200; n++) {
        double y = random.nextDouble() * 2 - 1,
            a = random.nextDouble() * Math.PI * 2,
            r = Math.sqrt(1 - y * y);
        var center = new Vec3(Math.cos(a) * r * 100000, y * 100000, Math.sin(a) * r * 100000);
        float size = 18 + random.nextFloat() * 35;
        var p = new Vector3f((float) center.x, (float) center.y, (float) center.z);
        var normal = new Vector3f(p).normalize();
        var right =
            new Vector3f(normal)
                .cross(Math.abs(normal.y) > .99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0))
                .normalize()
                .mul(size);
        var up = new Vector3f(right).cross(normal).normalize().mul(size);
        generated.add(
            colored(
                new Vector3f[] {
                  new Vector3f(p).sub(right).sub(up),
                  new Vector3f(p).add(right).sub(up),
                  new Vector3f(p).add(right).add(up),
                  new Vector3f(p).sub(right).add(up)
                },
                0xffb9ccdf,
                false));
      }
      stars = List.copyOf(generated);
      starSeed = scene.getLongOr("seed", 79419843);
    }
    output.addAll(stars);
    var catalog = scene.getCompoundOrEmpty("catalog").getListOrEmpty("bodies");
    int hash = catalog.hashCode();
    if (bodies.isEmpty() || bodyHash != hash) {
      var sun =
          catalog.stream()
              .map(t -> (CompoundTag) t)
              .filter(t -> t.getBooleanOr("emissive", false))
              .findFirst()
              .orElse(new CompoundTag());
      var light =
          new Vec3(
              sun.getDoubleOr("x", -12000),
              sun.getDoubleOr("y", 6000),
              sun.getDoubleOr("z", -18000));
      var built = new ArrayList<BodyMesh>();
      for (Tag t : catalog) {
        var b = (CompoundTag) t;
        var center = new Vec3(b.getDoubleOr("x", 0), b.getDoubleOr("y", 0), b.getDoubleOr("z", 0));
        var quads = new ArrayList<Quad>();
        sphere(
            Vec3.ZERO,
            b.getDoubleOr("radius", 1),
            Vec3.ZERO,
            quads,
            b.getIntOr("color", 0xffaaaaaa),
            b.getBooleanOr("emissive", false),
            b.getStringOr("kind", "").equals("planet"),
            light.subtract(center));
        built.add(new BodyMesh(center, List.copyOf(quads)));
      }
      bodies = List.copyOf(built);
      bodyHash = hash;
    }
    for (var body : bodies) {
      var offset = body.center.subtract(eye);
      for (var q : body.quads) {
        var points = new Vector3f[4];
        double distance = 0;
        for (int n = 0; n < 4; n++) {
          var p = q.points[n];
          double x = offset.x + p.x, y = offset.y + p.y, z = offset.z + p.z;
          points[n] = new Vector3f((float) x, (float) y, (float) z);
          distance += x * x + y * y + z * z;
        }
        output.add(new Quad(points, q.uv, q.color, q.translucent, distance));
      }
    }
  }

  private static void sphere(
      Vec3 center,
      double radius,
      Vec3 eye,
      List<Quad> output,
      int color,
      boolean emissive,
      boolean earth,
      Vec3 sun) {
    int segments = 48, rings = 24;
    var light = sun.subtract(center).normalize();
    for (int y = 0; y < rings; y++)
      for (int x = 0; x < segments; x++) {
        Vector3f[] vertices = new Vector3f[4];
        var normal = Vec3.ZERO;
        int[][] corners = {{x, y}, {x + 1, y}, {x + 1, y + 1}, {x, y + 1}};
        for (int n = 0; n < 4; n++) {
          double lat = corners[n][1] * Math.PI / rings,
              lon = corners[n][0] * Math.PI * 2 / segments;
          var unit =
              new Vec3(Math.sin(lat) * Math.cos(lon), Math.cos(lat), Math.sin(lat) * Math.sin(lon));
          normal = normal.add(unit);
          var point = center.add(unit.scale(radius)).subtract(eye);
          vertices[n] = new Vector3f((float) point.x, (float) point.y, (float) point.z);
        }
        normal = normal.normalize();
        int base = color;
        if (earth
            && Math.sin(normal.x * 13 + normal.z * 7) + Math.sin(normal.y * 19 - normal.z * 9)
                > .45) base = 0xff4c944d;
        float shade = emissive ? 1 : (float) (.08 + .92 * Math.max(0, normal.dot(light)));
        int r = (int) ((base >> 16 & 255) * shade),
            g = (int) ((base >> 8 & 255) * shade),
            b = (int) ((base & 255) * shade);
        output.add(colored(vertices, 0xff000000 | r << 16 | g << 8 | b, false));
      }
  }

  private static Quad colored(Vector3f[] p, int color, boolean transparent) {
    double d = 0;
    for (var v : p) d += v.lengthSquared();
    return new Quad(p, new float[] {-1, 0, -1, 0, -1, 0, -1, 0}, color, transparent, d);
  }

  private static void emit(VertexConsumer output, Quad quad) {
    for (int i = 0; i < 4; i++)
      output
          .addVertex(quad.points[i])
          .setUv(quad.uv[i * 2], quad.uv[i * 2 + 1])
          .setColor(quad.color);
  }

  @Override
  public void close() {
    if (compute != null) {
      compute.close();
      compute = null;
    }
    resources.close();
    meshes.clear();
    transformed.clear();
    frameQuads.clear();
    transparentQuads.clear();
  }
}
