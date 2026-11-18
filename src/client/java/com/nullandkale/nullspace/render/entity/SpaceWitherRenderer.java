package com.nullandkale.nullspace.render.entity;

import com.nullandkale.nullspace.gameplay.boss.SpaceBoss;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.WitherBossRenderer;
import net.minecraft.client.renderer.entity.state.WitherRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** The vanilla wither's model in white, with its spawn flicker and armor swirl unchanged. */
public final class SpaceWitherRenderer extends WitherBossRenderer
{
    private static final Identifier WHITE = Identifier.parse("nullspace:textures/entity/space_wither.png");
    private static final Identifier SPAWNING = Identifier.withDefaultNamespace("textures/entity/wither/wither_invulnerable.png");

    public static void register()
    {
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(SpaceBoss.SPACE_WITHER, SpaceWitherRenderer::new);
    }

    public SpaceWitherRenderer(EntityRendererProvider.Context context)
    {
        super(context);
    }

    @Override
    public Identifier getTextureLocation(WitherRenderState state)
    {
        int ticks = Mth.floor(state.invulnerableTicks);
        return ticks > 0 && (ticks > 80 || ticks / 5 % 2 != 1) ? SPAWNING : WHITE;
    }
}
