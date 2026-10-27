package com.nullandkale.nullspace.mixin;

import com.nullandkale.nullspace.ship.TravelService;
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
    if (TravelService.busy((ServerPlayer) (Object) this)) cir.setReturnValue(false);
  }
}
