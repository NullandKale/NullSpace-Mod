package com.nullandkale.nullspace.render.entity;

import com.nullandkale.nullspace.content.entity.Stowaways;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.monster.slime.SlimeModel;
import net.minecraft.client.model.monster.spider.SpiderModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.CreeperRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.SlimeRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Draws the stowaways ({@link Stowaways}) as the vanilla creatures with the mod's own textures. Nothing here touches the
 * vanilla creatures' textures, so they look the same on planets. The ghost creeper is drawn translucent, the spiders get
 * their own eye glow, and the violet slime gets its own outer shell.
 */
public final class StowawayRenderers
{
    private static Identifier texture(String name)
    {
        return Identifier.parse("nullspace:textures/entity/" + name + ".png");
    }

    private static final Identifier GHOST_CREEPER = texture("ghost_creeper");
    private static final Identifier PALE_SPIDER = texture("pale_spider");
    private static final Identifier PALE_CAVE_SPIDER = texture("pale_cave_spider");
    private static final Identifier PALE_SPIDER_EYES = texture("pale_spider_eyes");
    private static final Identifier PALE_SILVERFISH = texture("pale_silverfish");
    private static final Identifier VIOLET_SLIME = texture("violet_slime");
    private static final Identifier VIOLET_BLAZE = texture("violet_blaze");

    public static void register()
    {
        EntityRendererRegistry.register(Stowaways.GHOST_CREEPER, GhostCreeper::new);
        EntityRendererRegistry.register(Stowaways.PALE_SPIDER, PaleSpider::new);
        EntityRendererRegistry.register(Stowaways.PALE_CAVE_SPIDER, PaleCaveSpider::new);
        EntityRendererRegistry.register(Stowaways.PALE_SILVERFISH, PaleSilverfish::new);
        EntityRendererRegistry.register(Stowaways.VIOLET_SLIME, VioletSlime::new);
        EntityRendererRegistry.register(Stowaways.VIOLET_BLAZE, VioletBlaze::new);
    }

    /** Grey and half transparent: the body is drawn with blending, which the vanilla creeper's cutout pass would draw solid. */
    private static final class GhostCreeper extends CreeperRenderer
    {
        GhostCreeper(EntityRendererProvider.Context context)
        {
            super(context);
        }

        @Override
        public Identifier getTextureLocation(CreeperRenderState state)
        {
            return GHOST_CREEPER;
        }

        @Override
        protected RenderType getRenderType(CreeperRenderState state, boolean visible, boolean translucent, boolean glowing)
        {
            return visible ? RenderTypes.entityTranslucentCull(GHOST_CREEPER) : super.getRenderType(state, visible, translucent, glowing);
        }
    }

    private static EyesLayer<LivingEntityRenderState, SpiderModel> eyes(RenderLayerParent<LivingEntityRenderState, SpiderModel> parent)
    {
        return new EyesLayer<>(parent)
        {
            @Override
            public RenderType renderType()
            {
                return RenderTypes.eyes(PALE_SPIDER_EYES);
            }
        };
    }

    private static final class PaleSpider extends SpiderRenderer<Stowaways.PaleSpider>
    {
        PaleSpider(EntityRendererProvider.Context context)
        {
            super(context, ModelLayers.SPIDER);
            layers.clear();
            addLayer(eyes(this));
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state)
        {
            return PALE_SPIDER;
        }
    }

    private static final class PaleCaveSpider extends SpiderRenderer<Stowaways.PaleCaveSpider>
    {
        PaleCaveSpider(EntityRendererProvider.Context context)
        {
            super(context, ModelLayers.CAVE_SPIDER);
            shadowRadius *= 0.7F;
            layers.clear();
            addLayer(eyes(this));
        }

        @Override
        protected void scale(LivingEntityRenderState state, PoseStack pose)
        {
            pose.scale(0.7F, 0.7F, 0.7F);
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state)
        {
            return PALE_CAVE_SPIDER;
        }
    }

    private static final class PaleSilverfish extends SilverfishRenderer
    {
        PaleSilverfish(EntityRendererProvider.Context context)
        {
            super(context);
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state)
        {
            return PALE_SILVERFISH;
        }
    }

    private static final class VioletSlime extends SlimeRenderer
    {
        VioletSlime(EntityRendererProvider.Context context)
        {
            super(context);
            layers.clear();
            var outer = new SlimeModel(context.bakeLayer(ModelLayers.SLIME_OUTER));
            addLayer(new RenderLayer<SlimeRenderState, SlimeModel>(this)
            {
                @Override
                public void submit(PoseStack pose, SubmitNodeCollector collector, int light, SlimeRenderState state, float yRot, float xRot)
                {
                    if (state.isInvisible)
                        return;
                    collector.order(1).submitModel(outer, state, pose, RenderTypes.entityTranslucent(VIOLET_SLIME), light, LivingEntityRenderer.getOverlayCoords(state, 0F), state.outlineColor);
                }
            });
        }

        @Override
        public Identifier getTextureLocation(SlimeRenderState state)
        {
            return VIOLET_SLIME;
        }
    }

    private static final class VioletBlaze extends BlazeRenderer
    {
        VioletBlaze(EntityRendererProvider.Context context)
        {
            super(context);
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state)
        {
            return VIOLET_BLAZE;
        }
    }

    private StowawayRenderers()
    {
    }
}
