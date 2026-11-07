package com.nullandkale.nullspace.render.entity;

import com.nullandkale.nullspace.content.entity.RepairBot;
import com.nullandkale.nullspace.worldgen.encounters.SiteThreats;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** The repair bot: a boxy hovering drone with a lit eye and two small tool arms. */
public final class RepairBotRenderer
    extends MobRenderer<RepairBot, LivingEntityRenderState, RepairBotRenderer.Model> {
  public static final ModelLayerLocation LAYER =
      new ModelLayerLocation(Identifier.parse("nullspace:repair_bot"), "main");
  private static final Identifier TEXTURE =
      Identifier.parse("nullspace:textures/entity/repair_bot.png");

  public static void register() {
    net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry.registerModelLayer(
        LAYER, Model::createLayer);
    net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
        SiteThreats.REPAIR_BOT, RepairBotRenderer::new);
  }

  public RepairBotRenderer(EntityRendererProvider.Context context) {
    super(context, new Model(context.bakeLayer(LAYER)), 0.3F);
  }

  @Override
  protected int getBlockLightLevel(RepairBot entity, BlockPos pos) {
    return Math.max(8, super.getBlockLightLevel(entity, pos));
  }

  @Override
  public Identifier getTextureLocation(LivingEntityRenderState state) {
    return TEXTURE;
  }

  @Override
  public LivingEntityRenderState createRenderState() {
    return new LivingEntityRenderState();
  }

  public static final class Model extends EntityModel<LivingEntityRenderState> {
    private final ModelPart body;
    private final ModelPart left;
    private final ModelPart right;

    Model(ModelPart root) {
      super(root);
      body = root.getChild("body");
      left = body.getChild("left_arm");
      right = body.getChild("right_arm");
    }

    static LayerDefinition createLayer() {
      var mesh = new MeshDefinition();
      var body =
          mesh.getRoot()
              .addOrReplaceChild(
                  "body",
                  CubeListBuilder.create()
                      .texOffs(0, 0)
                      .addBox(-4, -4, -4, 8, 8, 8) // Hull.
                      .texOffs(0, 16)
                      .addBox(-2, -2, -5, 4, 3, 1) // Eye lens.
                      .texOffs(24, 0)
                      .addBox(-1, -7, -1, 2, 3, 2), // Antenna.
                  PartPose.offset(0, 18, 0));
      body.addOrReplaceChild(
          "left_arm",
          CubeListBuilder.create().texOffs(32, 0).addBox(0, -1, -1, 4, 2, 2),
          PartPose.offset(4, 2, -1));
      body.addOrReplaceChild(
          "right_arm",
          CubeListBuilder.create().texOffs(32, 4).addBox(-4, -1, -1, 4, 2, 2),
          PartPose.offset(-4, 2, -1));
      return LayerDefinition.create(mesh, 64, 32);
    }

    @Override
    public void setupAnim(LivingEntityRenderState state) {
      super.setupAnim(state);
      body.y = 18 + Mth.sin(state.ageInTicks * 0.15F) * 1.2F;
      body.yRot = state.yRot * Mth.DEG_TO_RAD;
      body.xRot = state.xRot * Mth.DEG_TO_RAD * 0.5F;
      left.zRot = 0.35F + Mth.sin(state.ageInTicks * 0.3F) * 0.25F;
      right.zRot = -0.35F - Mth.sin(state.ageInTicks * 0.3F + 1) * 0.25F;
    }
  }
}
