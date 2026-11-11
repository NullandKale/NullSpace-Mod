package com.nullandkale.nullspace.render.scene;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets;
import com.nullandkale.nullspace.render.SpaceSettings;

import java.util.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/** The installed block and the fullscreen viewer use the same independent scene renderer. */
public final class MonitorRenderer
{
    private static final RenderPipeline PIPELINE = monitorPipeline();

    private static RenderPipeline monitorPipeline()
    {
        var pipeline = RenderPipeline.builder();
        pipeline.withLocation(Identifier.parse("nullspace:monitor_surface"));
        pipeline.withVertexShader(Identifier.parse("nullspace:core/viewport"));
        pipeline.withFragmentShader(Identifier.parse("nullspace:core/viewport"));
        pipeline.withBindGroupLayout(BindGroupLayouts.PROJECTION);
        pipeline.withBindGroupLayout(BindGroupLayouts.SAMPLER0);
        pipeline.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR);
        pipeline.withPrimitiveTopology(PrimitiveTopology.QUADS);
        pipeline.withDepthStencilState(DepthStencilState.DEFAULT);
        pipeline.withCull(false);
        pipeline.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15));
        return RenderPipelines.register(pipeline.build());
    }

    private record Display(SceneViewport viewport, StagedVertexBuffer vertices, StagedVertexBuffer.Draw draw)
    {}

    private static int captureCursor, lastCaptures;
    private static ScenePackets.Frame frame;
    private static long received;
    private static int lastCount;
    private static GpuBuffer matrix;
    private static final List<SceneViewport> VIEWS = new ArrayList<>();
    private static final List<StagedVertexBuffer> MESHES = new ArrayList<>();
    private static final List<Display> PREPARED = new ArrayList<>();
    private static final List<CompoundTag> SCENES = new ArrayList<>();
    private static ScenePackets.Frame sceneFrame;

    public static void initialize()
    {
    }

    public static void update(ScenePackets.Frame next)
    {
        if (frame == null || !frame.lease().equals(next.lease()) || next.revision() >= frame.revision())
        {
            frame = next;
            received = System.nanoTime();
        }
    }

    public static String status()
    {
        return frame == null ? "No panel frame received" : frame.scene().getListOrEmpty("displays").toString() + " subjects=" + frame.scene().getListOrEmpty("subjects").size();
    }

    public static int preparedCount()
    {
        return lastCount;
    }

    public static int lastCaptureCount()
    {
        return lastCaptures;
    }

    public static long capturedFrames(int slot)
    {
        return slot < VIEWS.size() ? VIEWS.get(slot).renderedFrames() : 0;
    }

    public static void prepare(Matrix4f worldProjection)
    {
        PREPARED.clear();
        lastCaptures = 0;
        var mc = Minecraft.getInstance();
        if (frame == null || mc.level == null || System.nanoTime() - received > 3_000_000_000L || !frame.scene().getStringOr("dimension", "").equals(mc.level.dimension().identifier().toString()))
            return;
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (sceneFrame != frame)
        {
            SCENES.clear();
            sceneFrame = frame;
        }
        if (matrix == null)
            matrix = RenderSystem.getDevice().createBuffer(() -> "Monitor surface projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        try (var stack = MemoryStack.stackPush())
        {
            var bytes = stack.malloc(64);
            new Matrix4f(worldProjection).mul(camera.viewRotationMatrix).get(0, bytes);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(matrix.slice(), bytes);
        }
        int available = 0;
        for (var tag : frame.scene().getListOrEmpty("displays"))
        {
            var display = (CompoundTag) tag;
            if (display.getStringOr("status", "").equals("LIVE") && mc.level.getBlockState(BlockPos.of(display.getLongOr("position", 0))).is(ModBlocks.MONITOR))
                available++;
        }
        int selected = Math.floorMod(captureCursor++, Math.max(1, Math.min(2, available)));
        int index = 0;
        for (var tag : frame.scene().getListOrEmpty("displays"))
        {
            if (index == 2)
                break;
            var display = (CompoundTag) tag;
            if (!display.getStringOr("status", "").equals("LIVE"))
                continue;
            var pos = BlockPos.of(display.getLongOr("position", 0));
            if (mc.level.getBlockState(pos).getBlock() != ModBlocks.MONITOR)
                continue;
            int maxWidth = SpaceSettings.cameraWidth() / 2;
            int maxHeight = maxWidth * 9 / 16;
            double aspect = display.getIntOr("wallWidth", 1) / (double) Math.max(1, display.getIntOr("wallHeight", 1));
            int captureWidth = Math.max(16, Math.min(maxWidth, (int) (maxHeight * aspect)));
            int captureHeight = Math.max(16, Math.min(maxHeight, (int) (maxWidth / aspect)));
            if (index == VIEWS.size())
            {
                VIEWS.add(new SceneViewport(captureWidth, captureHeight));
                MESHES.add(new StagedVertexBuffer(() -> "Monitor face", 4096));
            }
            else if (VIEWS.get(index).target.width != captureWidth || VIEWS.get(index).target.height != captureHeight)
            {
                VIEWS.get(index).close();
                VIEWS.set(index, new SceneViewport(captureWidth, captureHeight));
            }
            var viewport = VIEWS.get(index);
            var vertices = MESHES.get(index);
            if (index == SCENES.size())
            {
                var scene = SceneViewport.cameraView(frame.scene());
                for (String key : List.of("cameraX", "cameraY", "cameraZ", "cameraYaw", "source", "selection"))
                    scene.put(key, display.get(key));
                scene.putString("status", "LIVE");
                SCENES.add(scene);
            }
            // Warm both on first use; thereafter stagger captures to bound per-frame GPU work.
            if (index == selected || viewport.renderedFrames() == 0)
            {
                long before = viewport.renderedFrames();
                viewport.render(SCENES.get(index), 0, 0, 70);
                if (viewport.renderedFrames() > before)
                    lastCaptures++;
            }
            index++;
            var face = Direction.byName(display.getStringOr("facing", "north"));
            if (face == null)
                face = Direction.NORTH;
            // One centimetre in front of the opaque face, with normal world depth testing.
            var center = new net.minecraft.world.phys.Vec3(display.getDoubleOr("centerX", pos.getX() + .5), display.getDoubleOr("centerY", pos.getY() + .5), display.getDoubleOr("centerZ", pos.getZ() + .5)).add(face.getStepX() * (display.getBooleanOr("mounted", false) ? -.37 : .51), 0, face.getStepZ() * (display.getBooleanOr("mounted", false) ? -.37 : .51)).subtract(camera.pos);
            var right = new Vector3f(face.getStepZ() * .49f * display.getIntOr("wallWidth", 1), 0, -face.getStepX() * .49f * display.getIntOr("wallWidth", 1));
            var up = new Vector3f(0, .49f * display.getIntOr("wallHeight", 1), 0);
            var c = new Vector3f((float) center.x, (float) center.y, (float) center.z);
            var draw = vertices.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, null);
            var out = vertices.getVertexBuilder(draw);
            out.addVertex(new Vector3f(c).sub(right).sub(up)).setUv(0, 0).setColor(-1);
            out.addVertex(new Vector3f(c).add(right).sub(up)).setUv(1, 0).setColor(-1);
            out.addVertex(new Vector3f(c).add(right).add(up)).setUv(1, 1).setColor(-1);
            out.addVertex(new Vector3f(c).sub(right).add(up)).setUv(0, 1).setColor(-1);
            vertices.upload();
            PREPARED.add(new Display(viewport, vertices, draw));
        }
    }

    public static void compose(RenderPass pass)
    {
        for (var display : PREPARED)
        {
            var info = display.vertices.getExecuteInfo(display.draw);
            if (info == null)
                continue;
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("Projection", matrix);
            pass.setUniform("Sampler0", display.viewport.target.getColorTextureView(), RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false));
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
        RenderSystem.bindDefaultUniforms(pass);
        var atlas = Minecraft.getInstance().getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
    }

    public static void endFrame()
    {
        lastCount = PREPARED.size();
        for (var d : PREPARED)
            d.vertices.endFrame();
        PREPARED.clear();
    }

    public static void close()
    {
        frame = null;
        sceneFrame = null;
        SCENES.clear();
        lastCount = 0;
        lastCaptures = 0;
        captureCursor = 0;
        PREPARED.clear();
        for (var v : VIEWS)
            v.close();
        VIEWS.clear();
        for (var v : MESHES)
            v.close();
        MESHES.clear();
        if (matrix != null)
        {
            matrix.close();
            matrix = null;
        }
    }

    private MonitorRenderer()
    {
    }
}
