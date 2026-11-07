package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.travel.TravelService;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class TransitEntityMixin {
  @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
  private void nullspace$freezeCargo(Entity entity, CallbackInfo ci) {
    if (!(entity instanceof ServerPlayer)
        && TravelService.locked((ServerLevel) (Object) this, entity.blockPosition())) ci.cancel();
  }
}
