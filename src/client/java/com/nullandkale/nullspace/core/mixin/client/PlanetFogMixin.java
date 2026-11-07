package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace environmental haze, preserving chunk-edge fading, fluids, effects and boss fog. */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class PlanetFogMixin {
  @Inject(method = "setupFog", at = @At("RETURN"))
  private void nullspace$atmosphere(
      FogData fog,
      Camera camera,
      ClientLevel level,
      float renderDistance,
      DeltaTracker delta,
      CallbackInfo ci) {
    if (SpaceSceneRenderer.planetSkyReady()
        && SpaceSceneRenderer.computeReady()
        && !Minecraft.getInstance().gui.hud.getBossOverlay().shouldCreateWorldFog()) {
      fog.environmentalStart = Float.MAX_VALUE;
      fog.environmentalEnd = Float.MAX_VALUE;
    }
  }
}
