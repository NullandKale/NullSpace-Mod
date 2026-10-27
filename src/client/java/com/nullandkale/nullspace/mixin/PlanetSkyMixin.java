package com.nullandkale.nullspace.mixin;

import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class PlanetSkyMixin {
  @Inject(method = "render", at = @At("HEAD"), cancellable = true)
  private void nullspace$planetSky(CallbackInfo ci) {
    if (com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.planetSkyReady()) ci.cancel();
  }
}
