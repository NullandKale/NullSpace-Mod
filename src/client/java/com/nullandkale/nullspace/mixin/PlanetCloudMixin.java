package com.nullandkale.nullspace.mixin;

import com.nullandkale.nullspace.client.scene.SpaceSceneRenderer;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class PlanetCloudMixin {
  @Inject(
      method = {
        "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        "renderOit"
      },
      at = @At("HEAD"),
      cancellable = true)
  private void nullspace$clouds(CallbackInfo ci) {
    if (SpaceSceneRenderer.planetSkyReady() && SpaceSceneRenderer.computeReady()) ci.cancel();
  }
}
