package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.crew.PlayerRescue;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class TransitPlayerMixin {
  @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
  private void nullspace$protectTransit(
      ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
    if (TransferLocks.busy((ServerPlayer) (Object) this)
        || PlayerRescue.protectedPlayer((ServerPlayer) (Object) this)) cir.setReturnValue(false);
  }
}
