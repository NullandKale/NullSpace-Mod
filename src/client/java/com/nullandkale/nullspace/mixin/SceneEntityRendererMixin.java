package com.nullandkale.nullspace.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class SceneEntityRendererMixin {
  @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
  private void nullspace$hideStorageEntities(
      Entity entity,
      Frustum frustum,
      double x,
      double y,
      double z,
      float partial,
      CallbackInfoReturnable<Boolean> ci) {
    if (!com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.physicalVisible(
        entity.getBoundingBox())) ci.setReturnValue(false);
  }
}
