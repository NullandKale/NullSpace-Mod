package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
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
    if (!SpaceSceneRenderer.physicalVisible(entity.getBoundingBox())) ci.setReturnValue(false);
  }
}
