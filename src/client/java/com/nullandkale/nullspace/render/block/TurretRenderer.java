package com.nullandkale.nullspace.render.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nullandkale.nullspace.gameplay.defence.DefenceBlocks;
import com.nullandkale.nullspace.gameplay.defence.TurretBlockEntity;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws a salvage turret's head and gun on top of its fixed base, turned toward what it is aiming at. The base is the block's own
 * model; the head turns about the vertical, and the gun also lifts, both about the pivot (8, 10, 8) of the models in
 * {@code assets/nullspace/models/item/turret_head.json} and {@code turret_gun.json}. The drawn angles ease toward the aim the server sends.
 */
public final class TurretRenderer implements BlockEntityRenderer<TurretBlockEntity, TurretRenderer.State>
{
    private static final float PIVOT_Y = 10F / 16F;

    public static void register()
    {
        BlockEntityRenderers.register(DefenceBlocks.TURRET, TurretRenderer::new);
    }

    public static final class State extends BlockEntityRenderState
    {
        final ItemStackRenderState head = new ItemStackRenderState(), gun = new ItemStackRenderState();
        float yaw, pitch;
    }

    private final ItemModelResolver items;

    public TurretRenderer(BlockEntityRendererProvider.Context context)
    {
        items = context.itemModelResolver();
    }

    @Override
    public State createRenderState()
    {
        return new State();
    }

    @Override
    public void extractRenderState(TurretBlockEntity turret, State state, float partialTicks, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress)
    {
        BlockEntityRenderer.super.extractRenderState(turret, state, partialTicks, camera, breakProgress);
        turret.drawYaw += Mth.wrapDegrees(turret.aimYaw() - turret.drawYaw) * 0.2F;
        turret.drawPitch += (turret.aimPitch() - turret.drawPitch) * 0.2F;
        state.yaw = turret.drawYaw;
        state.pitch = turret.drawPitch;
        items.updateForTopItem(state.head, new ItemStack(DefenceBlocks.TURRET_HEAD), ItemDisplayContext.NONE, turret.getLevel(), null, 0);
        items.updateForTopItem(state.gun, new ItemStack(DefenceBlocks.TURRET_GUN), ItemDisplayContext.NONE, turret.getLevel(), null, 0);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera)
    {
        for (int part = 0; part < 2; part++)
        {
            pose.pushPose();
            pose.translate(0.5F, PIVOT_Y, 0.5F);
            pose.rotateDegrees(Axis.YP, state.yaw);
            if (part == 1)
                pose.rotateDegrees(Axis.XP, state.pitch);
            // Item models are drawn centred on the origin (shifted half a block back), so only the rest of the way back to the corner.
            pose.translate(0F, 0.5F - PIVOT_Y, 0F);
            var model = part == 0 ? state.head : state.gun;
            if (!model.isEmpty())
                model.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }
}
