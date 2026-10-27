package com.nullandkale.nullspace.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class TransitDamageMixin {
  @Inject(method = "isInvulnerableToBase", at = @At("HEAD"), cancellable = true)
  private void nullspace$protectCargo(DamageSource source, CallbackInfoReturnable<Boolean> ci) {
    var entity = (Entity) (Object) this;
    if (entity.level() instanceof ServerLevel level
        && com.nullandkale.nullspace.ship.TravelService.locked(level, entity.blockPosition()))
      ci.setReturnValue(true);
  }
}
