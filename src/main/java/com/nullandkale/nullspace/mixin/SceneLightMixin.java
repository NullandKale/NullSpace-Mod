package com.nullandkale.nullspace.mixin;

import com.nullandkale.nullspace.scene.SceneSnapshots;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native light propagation invalidates only camera chunks with active cache subscriptions. */
@Mixin(ServerChunkCache.class)
public abstract class SceneLightMixin {
  @Shadow @Final private ServerLevel level;

  @Inject(method = "onLightUpdate", at = @At("HEAD"))
  private void nullspace$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
    SceneSnapshots.lightChanged(level, pos.chunk().pack());
  }
}
