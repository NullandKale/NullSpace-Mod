package com.nullandkale.nullspace.render.scene;

import com.nullandkale.nullspace.core.ResourceGroup;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.compute.ComputeProgram;
import com.nullandkale.nullspace.render.compute.SpaceComputeRenderer;
import com.nullandkale.nullspace.render.scene.SceneMeshes.Quad;

import java.util.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
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
public final class SceneViewport implements AutoCloseable
{
    private SpaceComputeRenderer compute;

    public SpaceComputeRenderer compute()
    {
        return compute;
    }

    private static final RenderPipeline COMPUTE_TRANSPARENT = RenderPipelines.register(RenderPipeline.builder().withLocation(Identifier.parse("nullspace:viewport_compute_transparent")).withVertexShader(Identifier.parse("nullspace:core/viewport")).withFragmentShader(Identifier.parse("nullspace:core/viewport_fog")).withBindGroupLayout(BindGroupLayouts.PROJECTION).withBindGroupLayout(SpaceComputeRenderer.VIEW).withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1).withBindGroupLayout(BindGroupLayouts.SAMPLER2).withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR).withPrimitiveTopology(PrimitiveTopology.QUADS).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)).withCull(false).withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 15)).build());

    private static RenderPipeline reverse(String name, boolean transparent)
    {
        return RenderPipelines.register(RenderPipeline.builder().withLocation(Identifier.parse("nullspace:" + name)).withVertexShader(Identifier.parse("nullspace:core/viewport")).withFragmentShader(Identifier.parse("nullspace:core/viewport")).withBindGroupLayout(BindGroupLayouts.PROJECTION).withBindGroupLayout(BindGroupLayouts.SAMPLER0).withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR).withPrimitiveTopology(PrimitiveTopology.QUADS).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, !transparent)).withCull(false).withColorTargetState(new ColorTargetState(transparent ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty(), GpuFormat.RGBA8_UNORM, 15)).build());
    }

    private static final RenderPipeline REVERSE_OPAQUE = reverse("viewport_reverse_opaque", false);
    private static final RenderPipeline REVERSE_TRANSPARENT = reverse("viewport_reverse_transparent", true);
    public final TextureTarget target;

    public static void initialize()
    {
    }

    private final ResourceGroup resources = new ResourceGroup();
    private final StagedVertexBuffer staging;
    private final GpuBuffer matrix;

    record Cached(CompoundTag source, int hash, Object models, List<Quad> quads, float fill)
    {}

    final Map<String, Cached> meshes = new HashMap<>();

    record MeshKey(String id, int hash, Object models, float fill)
    {}

    static final LinkedHashMap<MeshKey, List<Quad>> SHARED = new LinkedHashMap<>(192, .75f, true);
    static int sharedQuads;
    final ArrayList<Quad> transformed = new ArrayList<>(), frameQuads = new ArrayList<>(), transparentQuads = new ArrayList<>();
    private int transformedAt;
    private long transformedQuadsCreated;

    public long transformedQuadsCreated()
    {
        return transformedQuadsCreated;
    }

    public int transformedCapacity()
    {
        return transformed.size();
    }

    Quad transformed(Quad original)
    {
        Quad q;
        if (transformedAt < transformed.size())
            q = transformed.get(transformedAt);
        else
        {
            transformedQuadsCreated++;
            q = new Quad(new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()}, original.uv, original.color, original.translucent, 0);
            if (transformedAt < 131072)
                transformed.add(q);
        }
        transformedAt++;
        q.uv = original.uv;
        q.color = original.color;
        q.translucent = original.translucent;
        return q;
    }

    public static void clearSharedMeshes()
    {
        SHARED.clear();
        sharedQuads = 0;
    }

    public static int sharedMeshCount()
    {
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

    public int playerCount()
    {
        return playerCount;
    }

    public int blockEntityCount()
    {
        return ScenePlayers.blocks();
    }

    public int entityCount()
    {
        return ScenePlayers.entities();
    }

    long starSeed = Long.MIN_VALUE;
    List<Quad> stars = List.of();

    record BodyMesh(Vec3 center, List<Quad> quads)
    {}

    int bodyHash;
    List<BodyMesh> bodies = List.of();

    public long renderedFrames()
    {
        return renderedFrames;
    }

    public long reusedFrames()
    {
        return reusedFrames;
    }

    public int cachedSubjects()
    {
        return (int) meshes.keySet().stream().filter(id -> !id.contains("/")).count();
    }

    public int cachedTerrain()
    {
        return meshes.size() - cachedSubjects();
    }

    /** Share immutable packet arrays; only the camera fields of this view may be changed. */
    public static CompoundTag cameraView(CompoundTag source)
    {
        var view = new CompoundTag();
        for (String key : source.keySet())
            view.put(key, source.get(key));
        return view;
    }

    public SceneViewport(int width, int height)
    {
        if (width < 16 || height < 16 || width > 2048 || height > 2048)
            throw new IllegalArgumentException("Viewport resolution outside budget");
        try
        {
            target = new TextureTarget("NullSpace remote viewport", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            resources.defer(target::destroyBuffers);
            staging = resources.own(new StagedVertexBuffer(() -> "NullSpace viewport", 1024 * 1024));
            matrix = RenderSystem.getDevice().createBuffer(() -> "Viewport projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
            resources.own(matrix);
        }
        catch (RuntimeException | Error failure)
        {
            resources.rollback(failure);
            throw failure;
        }
    }

    public void render(CompoundTag scene, float yawOffset, float pitchOffset, float fov)
    {
        render(scene, yawOffset, pitchOffset, fov, null);
    }

    public void render(CompoundTag source, float yawOffset, float pitchOffset, float fov, Matrix4f observerMatrix)
    {
        int revision = SpaceSettings.revision();
        if (settingsRevision != revision)
        {
            settingsRevision = revision;
            hasImage = false;
            if (compute != null)
            {
                compute.close();
                compute = null;
            }
        }
        final var scene = SceneMotion.resolve(source);
        var subjects = scene.getListOrEmpty("subjects");
        if (subjects.isEmpty() || scene.contains("status") && !scene.getStringOr("status", "").startsWith("LIVE"))
        {
            clear();
            return;
        }
        var catalog = scene.get("catalog");
        if (sourceSubjects != subjects || sourceCatalog != catalog)
        {
            sourceSubjects = subjects;
            sourceCatalog = catalog;
            contentHash = 31 * subjects.hashCode() + Objects.hashCode(catalog);
        }
        int hash = Objects.hash(scene.getDoubleOr("simulationTime", 0), scene.getLongOr("source", 0), scene.getIntOr("selection", 0), contentHash, scene.getLongOr("seed", 79419843), scene.getLongOr("motionFrame", 0), scene.getBooleanOr("universe", false), TerrainClient.version());
        double cx = scene.getDoubleOr("cameraX", 0), cy = scene.getDoubleOr("cameraY", 0), cz = scene.getDoubleOr("cameraZ", 0);
        float cameraYaw = scene.getFloatOr("cameraYaw", 0) + yawOffset, cameraPitch = Math.clamp(scene.getFloatOr("cameraPitch", 0) + pitchOffset, -89, 89);
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        String observerId = scene.getStringOr("observer", "");
        boolean useCompute = scene.getBooleanOr("universe", false) && ComputeProgram.enabled();
        boolean actors = useCompute || subjects.stream().map(t -> (CompoundTag) t).anyMatch(t -> (observerMatrix == null || !t.getStringOr("id", "").equals(observerId)) && !t.getListOrEmpty("players").isEmpty());
        long actorTick = actors ? Minecraft.getInstance().level.getGameTime() : 0;
        if (subjects.stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("id", "").equals(observerId) && t.getBooleanOr("ftl", false)))
            actorTick = System.nanoTime() / 16_666_667;
        boolean sameProjection = observerMatrix == null ? lastProjection == null : lastProjection != null && observerMatrix.equals(lastProjection);
        if (hasImage && hash == lastHash && lastModels == models && cx == lastX && cy == lastY && cz == lastZ && cameraYaw == lastYaw && cameraPitch == lastPitch && fov == lastFov && observerId.equals(lastObserver) && sameProjection && actorTick == lastActorTick)
        {
            reusedFrames++;
            return;
        }
        var ids = new HashSet<String>();
        for (Tag tag : subjects)
            ids.add(((CompoundTag) tag).getStringOr("id", ""));
        for (var t : scene.getListOrEmpty("terrain"))
            ids.add(((CompoundTag) t).getStringOr("id", ""));
        meshes.keySet().retainAll(ids);
        var observer = subjects.stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", ""))).findFirst().orElse((CompoundTag) subjects.getFirst());
        var localEye = new Vec3(scene.getDoubleOr("cameraX", 0), scene.getDoubleOr("cameraY", 0), scene.getDoubleOr("cameraZ", 0));
        var observerPose = pose(observer);
        var eye = observerPose.toUniverse(localEye);
        float yaw = scene.getFloatOr("cameraYaw", 0) + yawOffset, pitch = Math.clamp(scene.getFloatOr("cameraPitch", 0) + pitchOffset, -89, 89);
        var localDirection = new Vec3(-Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)), -Math.sin(Math.toRadians(pitch)), Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        var direction = observerPose.toUniverse(localDirection).subtract(observerPose.position());
        var upV = observerPose.orientation().transform(new Vector3d(0, 1, 0));
        // Geometry is camera relative before conversion to float; physical storage offsets never enter
        // this matrix.
        var view = new Matrix4f().lookAt(0, 0, 0, (float) direction.x, (float) direction.y, (float) direction.z, (float) upV.x, (float) upV.y, (float) upV.z);
        boolean background = observerMatrix != null;
        var projection = background ? observerMatrix : new Matrix4f().perspective((float) Math.toRadians(fov), target.width / (float) target.height, 1000000, .05f, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()).mul(view);
        try (var stack = MemoryStack.stackPush())
        {
            var bytes = stack.malloc(64);
            projection.get(0, bytes);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(matrix.slice(), bytes);
        }
        transformedAt = 0;
        frameQuads.clear();
        var quads = frameQuads;
        for (var tag : subjects)
            if ((!background || !sameVessel((CompoundTag) tag, observer)) && (scene.getListOrEmpty("terrain").isEmpty() || !sameVessel((CompoundTag) tag, observer)))
                SceneGeometry.blocks(this, (CompoundTag) tag, eye, quads, scene.getBooleanOr("pilotView", false) ? .65f : 0);
        if (!background)
            for (var t : scene.getListOrEmpty("terrain"))
            {
                var data = TerrainClient.resolve((CompoundTag) t);
                if (data != null)
                    SceneGeometry.blocks(this, data, eye, quads, .15f, observerPose);
            }
        if (useCompute)
        {
            if (compute == null)
                compute = new SpaceComputeRenderer(target.width, target.height);
            compute.surfaces(scene, eye, projection);
        }
        else if (scene.getBooleanOr("universe", false))
            SceneGeometry.celestial(this, scene, eye, quads);
        var opaque = staging.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, null);
        var transparent = staging.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, null);
        for (var q : quads)
            if (!q.translucent)
                SceneMeshes.emit(staging.getVertexBuilder(opaque), q);
        transparentQuads.clear();
        for (var q : quads)
            if (q.translucent)
                transparentQuads.add(q);
        transparentQuads.sort(Comparator.comparingDouble(Quad::distance).reversed());
        for (var q : transparentQuads)
            SceneMeshes.emit(staging.getVertexBuilder(transparent), q);
        staging.upload();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(matrix.slice(), ProjectionType.PERSPECTIVE);
        FeatureRenderDispatcher.PreparedFrame players = null;
        try
        {
            var stack = RenderSystem.getModelViewStack();
            stack.pushMatrix();
            stack.identity();
            try
            {
                players = ScenePlayers.prepare(scene, eye, background);
                playerCount = ScenePlayers.submitted();
            }
            finally
            {
                stack.popMatrix();
            }
            try (var pass = encoder.createRenderPass(() -> "Independent remote scene", target.getColorTextureView(), Optional.of(new Vector4f(.002f, .003f, .008f, 1)), target.getDepthTextureView(), OptionalDouble.of(0)))
            {
                if (useCompute)
                    compute.drawSurfaces(pass);
                var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
                pass.setUniform("Projection", matrix);
                pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
                var info = staging.getExecuteInfo(opaque);
                if (info != null)
                {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(REVERSE_OPAQUE));
                    pass.setUniform("Projection", matrix);
                    pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
                    pass.setVertexBuffer(0, info.vertexBuffer().slice());
                    pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                    pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                }
                if (players != null)
                    players.executeSolid(pass);
            }
            if (useCompute && !background)
            {
                compute.volumes(target.getDepthTexture());
                compute.composite(target.getColorTextureView());
                encoder.copyTextureToTexture(compute.output().getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
            }
            try (var pass = encoder.createRenderPass(() -> "Remote transparent surfaces", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty()))
            {
                var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
                var info = staging.getExecuteInfo(transparent);
                if (info != null)
                {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(useCompute && !background ? COMPUTE_TRANSPARENT : REVERSE_TRANSPARENT));
                    pass.setUniform("Projection", matrix);
                    pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
                    if (useCompute && !background)
                    {
                        pass.setUniform("ComputeView", compute.viewBuffer());
                        pass.setUniform("Sampler1", compute.prefix(), RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST));
                        pass.setUniform("Sampler2", compute.depth(), RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST));
                    }
                    pass.setVertexBuffer(0, info.vertexBuffer().slice());
                    pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                    pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                }
                if (players != null)
                {
                    players.executeTranslucent(pass);
                    players.executeTranslucentAfterTerrain(pass);
                }
            }
        }
        finally
        {
            if (players != null)
                players.close();
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

    static ScenePose pose(CompoundTag data)
    {
        return new ScenePose(new Vec3(data.getDoubleOr("x", 0), data.getDoubleOr("y", 0), data.getDoubleOr("z", 0)), new Quaterniond().rotationYXZ(Math.toRadians(data.getDoubleOr("yaw", 0)), Math.toRadians(data.getDoubleOr("pitch", 0)), Math.toRadians(data.getDoubleOr("roll", 0))));
    }

    public void clear()
    {
        hasImage = false;
        playerCount = 0;
        if (cleared)
            return;
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(.002f, .003f, .008f, 1), target.getDepthTexture(), 0);
        cleared = true;
    }

    private static boolean sameVessel(CompoundTag subject, CompoundTag observer)
    {
        return subject == observer || subject.getStringOr("parent", "").equals(observer.getStringOr("id", ""));
    }

    @Override
    public void close()
    {
        if (compute != null)
        {
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
