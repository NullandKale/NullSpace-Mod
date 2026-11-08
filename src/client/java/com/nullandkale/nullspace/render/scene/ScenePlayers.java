package com.nullandkale.nullspace.render.scene;

import com.nullandkale.nullspace.model.space.ScenePose;

import java.util.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

/**
 * Independent native visual submissions; snapshot actors are detached and never registered or
 * ticked.
 */
final class ScenePlayers
{
    private static RenderBuffers buffers;
    private static net.minecraft.client.multiplayer.ClientLevel sourceLevel;
    private static FeatureRenderDispatcher renderer;
    private static int submitted, playersSubmitted, entitiesSubmitted, blocksSubmitted, nextVisualId = -1;
    private static final Map<String, net.minecraft.world.entity.Entity> ENTITIES = new HashMap<>();

    private record VisualBlock(CompoundTag source, net.minecraft.world.level.block.entity.BlockEntity entity)
    {}

    private static final Map<String, VisualBlock> BLOCKS = new HashMap<>();

    static int entities()
    {
        return entitiesSubmitted;
    }

    static int blocks()
    {
        return blocksSubmitted;
    }

    static int submitted()
    {
        return playersSubmitted;
    }

    static FeatureRenderDispatcher.PreparedFrame prepare(CompoundTag scene, Vec3 eye, boolean background)
    {
        var mc = Minecraft.getInstance();
        if (sourceLevel != mc.level)
        {
            ENTITIES.clear();
            BLOCKS.clear();
            sourceLevel = mc.level;
        }
        var nodes = new SubmitNodeStorage();
        submitted = 0;
        entitiesSubmitted = 0;
        blocksSubmitted = 0;
        var camera = new CameraRenderState();
        camera.pos = eye;
        camera.initialized = true;
        camera.isFirstPerson = false;
        var seen = new HashSet<UUID>();
        for (var tag : scene.getListOrEmpty("subjects"))
        {
            var subject = (CompoundTag) tag;
            if (background && subject.getStringOr("id", "").equals(scene.getStringOr("observer", "")))
                continue;
            var base = BlockPos.of(subject.getLongOr("base", 0));
            var pose = new ScenePose(new Vec3(subject.getDoubleOr("x", 0), subject.getDoubleOr("y", 0), subject.getDoubleOr("z", 0)), new Quaterniond().rotationYXZ(Math.toRadians(subject.getDoubleOr("yaw", 0)), Math.toRadians(subject.getDoubleOr("pitch", 0)), Math.toRadians(subject.getDoubleOr("roll", 0))));
            for (var playerTag : subject.getListOrEmpty("players"))
            {
                if (seen.size() >= 32)
                    break;
                var p = (CompoundTag) playerTag;
                UUID id;
                try
                {
                    id = UUID.fromString(p.getStringOr("id", ""));
                }
                catch (IllegalArgumentException invalid)
                {
                    continue;
                }
                if (!seen.add(id))
                    continue;
                var tracked = mc.level.getPlayerByUUID(id);
                AvatarRenderState state;
                Vec3 local;
                if (tracked != null)
                {
                    if (tracked.isSpectator() || tracked.isInvisible())
                        continue;
                    float delta = mc.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
                    state = (AvatarRenderState) mc.getEntityRenderDispatcher().extractEntity(tracked, delta);
                    local = tracked.getPosition(delta).subtract(Vec3.atLowerCornerOf(base));
                    if (local.lengthSqr() > 140 * 140)
                        continue;
                }
                else
                {
                    state = new AvatarRenderState();
                    state.entityType = mc.player.getType();
                    var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
                    state.skin = info == null ? DefaultPlayerSkin.get(id) : info.getSkin();
                    state.bodyRot = p.getFloatOr("bodyYaw", 0);
                    state.yRot = p.getFloatOr("headYaw", 0);
                    state.xRot = p.getFloatOr("pitch", 0);
                    state.ageInTicks = p.getFloatOr("age", 0);
                    state.walkAnimationPos = p.getFloatOr("walk", 0);
                    state.walkAnimationSpeed = p.getFloatOr("speed", 0);
                    state.isCrouching = p.getBooleanOr("crouching", false);
                    state.headEquipment = item(p, "head");
                    state.chestEquipment = item(p, "chest");
                    state.legsEquipment = item(p, "legs");
                    state.feetEquipment = item(p, "feet");
                    local = new Vec3(p.getDoubleOr("x", 0), p.getDoubleOr("y", 0), p.getDoubleOr("z", 0));
                }
                var offset = pose.toUniverse(local).subtract(eye);
                state.distanceToCameraSq = offset.lengthSqr();
                state.nameTag = null;
                state.scoreText = null;
                state.shadowPieces.clear();
                state.displayFireAnimation = false;
                state.leashStates = null;
                var stack = new PoseStack();
                stack.translate(offset.x, offset.y, offset.z);
                stack.mulPose(new Matrix4f().rotation(new Quaternionf(pose.orientation())));
                mc.getEntityRenderDispatcher().submit(state, camera, 0, 0, 0, stack, nodes);
                submitted++;
            }
        }
        playersSubmitted = submitted;
        if (!background)
        {
            var observer = scene.getListOrEmpty("subjects").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(scene.getStringOr("observer", ""))).findFirst().orElse(new CompoundTag());
            var parent = new ScenePose(new Vec3(observer.getDoubleOr("x", 0), observer.getDoubleOr("y", 0), observer.getDoubleOr("z", 0)), new Quaterniond().rotationYXZ(Math.toRadians(observer.getDoubleOr("yaw", 0)), Math.toRadians(observer.getDoubleOr("pitch", 0)), Math.toRadians(observer.getDoubleOr("roll", 0))));
            var origin = BlockPos.of(observer.getLongOr("base", 0));
            var seenEntities = new HashSet<String>();
            for (var t : scene.getListOrEmpty("entities"))
            {
                var data = (CompoundTag) t;
                String id = data.getStringOr("id", "");
                if (!seenEntities.add(id))
                    continue;
                var typeId = Identifier.tryParse(data.getStringOr("type", ""));
                if (typeId == null)
                    continue;
                var type = BuiltInRegistries.ENTITY_TYPE.getValue(typeId);
                if (type == null)
                    continue;
                var local = new Vec3(data.getDoubleOr("x", 0), data.getDoubleOr("y", 0), data.getDoubleOr("z", 0));
                var physical = local.add(Vec3.atLowerCornerOf(origin));
                var entity = ENTITIES.get(id);
                if (entity == null || entity.getType() != type)
                {
                    entity = type.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
                    if (entity == null)
                        continue;
                    entity.setId(nextVisualId--);
                    if (ENTITIES.size() >= 128)
                        ENTITIES.clear();
                    ENTITIES.put(id, entity);
                }
                entity.setPos(physical);
                entity.setYRot(data.getFloatOr("yaw", 0));
                entity.setXRot(data.getFloatOr("pitch", 0));
                var state = mc.getEntityRenderDispatcher().extractEntity(entity, 0);
                var offset = parent.toUniverse(local).subtract(eye);
                state.distanceToCameraSq = offset.lengthSqr();
                state.nameTag = null;
                state.shadowPieces.clear();
                state.leashStates = null;
                var stack = new PoseStack();
                stack.translate(offset.x, offset.y, offset.z);
                stack.mulPose(new Matrix4f().rotation(new Quaternionf(parent.orientation())));
                mc.getEntityRenderDispatcher().submit(state, camera, 0, 0, 0, stack, nodes);
                submitted++;
                entitiesSubmitted++;
            }
            var visibleBlocks = new HashSet<String>();
            for (var t : scene.getListOrEmpty("subjects"))
            {
                var subject = (CompoundTag) t;
                if (!scene.getListOrEmpty("terrain").isEmpty() && subject == observer)
                    continue;
                var subjectPose = new ScenePose(new Vec3(subject.getDoubleOr("x", 0), subject.getDoubleOr("y", 0), subject.getDoubleOr("z", 0)), new Quaterniond().rotationYXZ(Math.toRadians(subject.getDoubleOr("yaw", 0)), Math.toRadians(subject.getDoubleOr("pitch", 0)), Math.toRadians(subject.getDoubleOr("roll", 0))));
                submitVisuals(mc, subject, subject, 32, subjectPose, BlockPos.of(subject.getLongOr("base", 0)), eye, camera, nodes, visibleBlocks);
            }
            for (var t : scene.getListOrEmpty("terrain"))
            {
                var ref = (CompoundTag) t;
                var data = TerrainClient.resolve(ref);
                if (data != null)
                    submitVisuals(mc, ref, data, 16, parent, origin, eye, camera, nodes, visibleBlocks);
            }
            BLOCKS.keySet().retainAll(visibleBlocks);
            ENTITIES.keySet().retainAll(seenEntities);
        }
        if (submitted == 0)
            return null;
        if (renderer == null)
        {
            buffers = new RenderBuffers(0);
            renderer = new FeatureRenderDispatcher(buffers, mc.getModelManager(), mc.getAtlasManager(), mc.font, mc.gameRenderer.gameRenderState());
        }
        return renderer.prepareFrame(nodes);
    }

    private static void submitVisuals(Minecraft mc, CompoundTag ref, CompoundTag data, int size, ScenePose parent, BlockPos origin, Vec3 eye, CameraRenderState camera, SubmitNodeStorage nodes, Set<String> visible)
    {
        var sectionBase = BlockPos.of(ref.getLongOr("base", 0));
        int[] states = data.getIntArray("blocks").orElse(new int[0]);
        if (states.length != size * size * size)
            return;
        for (var v : data.getListOrEmpty("blockEntities"))
        {
            var visual = (CompoundTag) v;
            int cell = visual.getIntOr("cell", -1);
            if (cell < 0 || cell >= states.length || visible.size() >= 256)
                continue;
            String key = ref.getStringOr("id", "") + "/" + cell;
            visible.add(key);
            var entry = BLOCKS.get(key);
            var physical = sectionBase.offset(cell % size, cell / (size * size), cell / size % size);
            if (entry == null || entry.source != visual)
            {
                var be = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(physical, net.minecraft.world.level.block.Block.stateById(states[cell]), visual.getCompoundOrEmpty("data"), mc.level.registryAccess());
                if (be == null)
                    continue;
                be.setLevel(mc.level);
                entry = new VisualBlock(visual, be);
                BLOCKS.put(key, entry);
            }
            submitBlock(mc, entry.entity, parent, origin, eye, camera, nodes);
            submitted++;
            blocksSubmitted++;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void submitBlock(Minecraft mc, net.minecraft.world.level.block.entity.BlockEntity be, ScenePose parent, BlockPos origin, Vec3 eye, CameraRenderState camera, SubmitNodeStorage nodes)
    {
        var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null)
            return;
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer raw = renderer;
        var state = renderer.createRenderState();
        var physicalEye = parent.toLocal(eye).add(Vec3.atLowerCornerOf(origin));
        raw.extractRenderState(be, state, 0, physicalEye, null);
        state.lightCoords = 0x00f000f0;
        var local = Vec3.atLowerCornerOf(be.getBlockPos().subtract(origin));
        var offset = parent.toUniverse(local).subtract(eye);
        var stack = new PoseStack();
        stack.translate(offset.x, offset.y, offset.z);
        stack.mulPose(new Matrix4f().rotation(new Quaternionf(parent.orientation())));
        raw.submit(state, stack, nodes, camera);
    }

    private static ItemStack item(CompoundTag p, String slot)
    {
        var id = Identifier.tryParse(p.getStringOr(slot, "minecraft:air"));
        if (id == null)
            return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.getValue(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    static void endFrame()
    {
        if (buffers != null)
            buffers.endFrame();
    }

    static void close()
    {
        if (renderer != null)
        {
            renderer.close();
            renderer = null;
        }
        if (buffers != null)
        {
            buffers.close();
            buffers = null;
        }
        submitted = 0;
        ENTITIES.clear();
        BLOCKS.clear();
        nextVisualId = -1;
        sourceLevel = null;
    }

    private ScenePlayers()
    {
    }
}
