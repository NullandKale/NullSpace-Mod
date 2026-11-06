package com.nullandkale.nullspace.mixin;
import com.nullandkale.nullspace.ship.VesselRespawns;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerPlayer.class)
public abstract class VesselRespawnMixin {
  @Inject(method="findRespawnPositionAndUseSpawnBlock",at=@At("HEAD"),cancellable=true)
  private void nullspace$resolveBed(boolean consume,TeleportTransition.PostTeleportTransition post,CallbackInfoReturnable<TeleportTransition> cir) {
    var transition=VesselRespawns.resolve((ServerPlayer)(Object)this,post);
    if(transition!=null) cir.setReturnValue(transition);
  }
  @Inject(method="setRespawnPosition",at=@At("TAIL"))
  private void nullspace$surfaceBed(ServerPlayer.RespawnConfig config,boolean message,CallbackInfo ci) {
    VesselRespawns.surfaceSpawnChanged((ServerPlayer)(Object)this,config);
  }
}
