package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.crew.ShipRespawns;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerPlayer.class)
public abstract class VesselRespawnMixin {
  @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
  private void nullspace$resolveBed(
      boolean consume,
      TeleportTransition.PostTeleportTransition post,
      CallbackInfoReturnable<TeleportTransition> cir) {
    var transition = ShipRespawns.resolve((ServerPlayer) (Object) this, post);
    if (transition != null) cir.setReturnValue(transition);
  }

  @Inject(method = "setRespawnPosition", at = @At("TAIL"))
  private void nullspace$surfaceBed(
      ServerPlayer.RespawnConfig config, boolean message, CallbackInfo ci) {
    ShipRespawns.surfaceSpawnChanged((ServerPlayer) (Object) this, config);
  }
}
