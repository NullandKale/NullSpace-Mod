package com.nullandkale.nullspace.render.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.optics.SceneCamera;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.compute.SpaceComputeRenderer;
import com.nullandkale.nullspace.render.compute.WorldVolumeBridge;
import com.nullandkale.nullspace.ui.screen.SceneScreen;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.lwjgl.system.MemoryStack;

/**
 * Composes the same viewport around the real local hull. Native opaque depth protects cockpit
 * geometry.
 */
public final class SpaceSceneRenderer {
  private static final RenderPipeline COMPOSE =
      RenderPipelines.register(
          RenderPipeline.builder()
              .withLocation(Identifier.parse("nullspace:scene_compose"))
              .withVertexShader(Identifier.parse("nullspace:core/scene_compose"))
              .withFragmentShader(Identifier.parse("nullspace:core/scene_compose"))
              .withBindGroupLayout(BindGroupLayouts.PROJECTION)
              .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
              .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
              .withCull(false)
              .withDepthStencilState(DepthStencilState.DEFAULT)
              .withColorTargetState(
                  new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
              .build());
  private static SceneService.Frame frame;
  private static CompoundTag cameraScene;
  private static SceneViewport viewport;
  private static GpuBuffer depth;
  private static boolean prepared;
  private static final Matrix4f nativeViewProjection = new Matrix4f();

  public static void update(SceneService.Frame next) {
    if (frame == null
        || !frame.lease().equals(next.lease())
        || next.revision() > frame.revision()) {
      frame = next;
      cameraScene = SceneViewport.cameraView(next.scene());
    }
  }

  public static void prepare(Matrix4f worldProjection) {
    prepared = false;
    var mc = Minecraft.getInstance();
    if (mc.gui.screen() instanceof SceneScreen screen) {
      screen.renderFrame();
      return;
    }
    if (frame == null
        || mc.level == null
        || (!mc.level.dimension().equals(TravelService.SPACE)
            && (!frame.scene().getBooleanOr("planetSky", false) || !SpaceSettings.get().planetSky))
        || !frame
            .scene()
            .getStringOr("dimension", "")
            .equals(mc.level.dimension().identifier().toString())) return;
    var scene = SceneMotion.resolve(cameraScene);
    var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
    var subjects = scene.getListOrEmpty("subjects");
    var observer =
        subjects.stream()
            .map(t -> (CompoundTag) t)
            .filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", "")))
            .findFirst()
            .orElse(null);
    if (observer == null) return;
    var origin = BlockPos.of(observer.getLongOr("base", 0));
    if (!scene.getBooleanOr("planetSky", false)
        && mc.player != null
        && !observerBounds(observer).contains(mc.player.position())) return;
    var offset = camera.pos.subtract(Vec3.atLowerCornerOf(origin));
    var pose =
        new ScenePose(
            new Vec3(
                observer.getDoubleOr("x", 0),
                observer.getDoubleOr("y", 0),
                observer.getDoubleOr("z", 0)),
            new Quaterniond()
                .rotationYXZ(
                    Math.toRadians(observer.getDoubleOr("yaw", 0)),
                    Math.toRadians(observer.getDoubleOr("pitch", 0)),
                    Math.toRadians(observer.getDoubleOr("roll", 0))));
    if (viewport != null && viewport.target.width != SpaceSettings.width()) {
      viewport.close();
      viewport = null;
    }
    if (depth == null)
      depth =
          RenderSystem.getDevice()
              .createBuffer(
                  () -> "Space depth conversion",
                  GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                  64);
    if (viewport == null) {
      viewport = new SceneViewport(SpaceSettings.width(), SpaceSettings.height());
    }
    boolean zeroOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    var aligned =
        SceneCamera.compose(
            camera.projectionMatrix,
            worldProjection,
            camera.viewRotationMatrix,
            pose.orientation(),
            zeroOne);
    var shift = aligned.eyeOffset();
    scene.putDouble("cameraX", offset.x + shift.x);
    scene.putDouble("cameraY", offset.y + shift.y);
    scene.putDouble("cameraZ", offset.z + shift.z);
    var sourceProjection = aligned.depthProjection();
    nativeViewProjection.set(aligned.nativeProjection());
    viewport.render(scene, 0, 0, 70, aligned.projection());
    var conversion =
        new Matrix4f()
            .zero()
            .m00(sourceProjection.m22())
            .m01(sourceProjection.m32())
            .m02(camera.projectionMatrix.m22())
            .m03(camera.projectionMatrix.m32())
            .m10(zeroOne ? 1 : 0);
    try (var stack = MemoryStack.stackPush()) {
      var bytes = stack.malloc(64);
      conversion.get(0, bytes);
      RenderSystem.getDevice().createCommandEncoder().writeToBuffer(depth.slice(), bytes);
    }
    prepared = true;
  }

  public static void compose(RenderPass pass) {
    if (prepared) {
      var sampler =
          RenderSystem.getSamplerCache()
              .getSampler(
                  AddressMode.CLAMP_TO_EDGE,
                  AddressMode.CLAMP_TO_EDGE,
                  FilterMode.NEAREST,
                  FilterMode.NEAREST,
                  false);
      pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSE));
      pass.setUniform("Projection", depth);
      pass.setUniform("Sampler0", viewport.target.getColorTextureView(), sampler);
      pass.setUniform("Sampler1", viewport.target.getDepthTextureView(), sampler);
      pass.draw(3, 1, 0, 0);
      RenderSystem.bindDefaultUniforms(pass);
      var atlas =
          Minecraft.getInstance()
              .getTextureManager()
              .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
      pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
    }
    MonitorRenderer.compose(pass);
  }

  public static boolean physicalVisible(net.minecraft.world.phys.AABB box) {
    var mc = Minecraft.getInstance();
    if (mc.level == null || !mc.level.dimension().equals(TravelService.SPACE)) return true;
    if (frame == null)
      return mc.player == null || mc.player.getBoundingBox().inflate(104).intersects(box);
    var observer =
        frame.scene().getListOrEmpty("subjects").stream()
            .map(t -> (CompoundTag) t)
            .filter(t -> t.getStringOr("id", "").equals(frame.scene().getStringOr("observer", "")))
            .findFirst()
            .orElse(null);
    if (observer == null)
      return mc.player == null || mc.player.getBoundingBox().inflate(104).intersects(box);
    var p = BlockPos.of(observer.getLongOr("base", 0));
    if (mc.player != null && !observerBounds(observer).contains(mc.player.position()))
      return mc.player.getBoundingBox().inflate(104).intersects(box);
    return observerBounds(observer).intersects(box);
  }

  private static net.minecraft.world.phys.AABB observerBounds(CompoundTag observer) {
    var p = BlockPos.of(observer.getLongOr("base", 0));
    return new net.minecraft.world.phys.AABB(
            p.getX(),
            p.getY(),
            p.getZ(),
            p.getX() + observer.getIntOr("width", 32),
            p.getY() + observer.getIntOr("height", 32),
            p.getZ() + observer.getIntOr("depth", 32))
        .inflate(72);
  }

  public static SceneViewport viewport() {
    return viewport;
  }

  public static boolean readyForScene(String id) {
    return prepared
        && viewport != null
        && frame != null
        && id.equals(frame.scene().getStringOr("sceneId", ""));
  }

  public static boolean planetSkyReady() {
    return prepared && frame != null && frame.scene().getBooleanOr("planetSky", false);
  }

  public static boolean computeReady() {
    return prepared && viewport != null && viewport.compute() != null;
  }

  public static Matrix4f nativeProjection() {
    return new Matrix4f(nativeViewProjection);
  }

  public static void initialize() {
    SpaceComputeRenderer.initialize();
    MonitorRenderer.initialize();
  }

  public static void close() {
    frame = null;
    cameraScene = null;
    prepared = false;
    MonitorRenderer.close();
    ScenePlayers.close();
    SceneViewport.clearSharedMeshes();
    if (viewport != null) {
      viewport.close();
      viewport = null;
    }
    if (depth != null) {
      depth.close();
      depth = null;
    }
    WorldVolumeBridge.clear();
    SpaceComputeRenderer.clear();
  }

  private SpaceSceneRenderer() {}
}
