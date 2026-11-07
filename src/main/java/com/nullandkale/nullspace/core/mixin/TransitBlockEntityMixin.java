package com.nullandkale.nullspace.core.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class TransitBlockEntityMixin {
  @WrapWithCondition(
      method = "tickBlockEntities",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
  private boolean nullspace$pauseTransit(TickingBlockEntity ticker) {
    return !((Object) this instanceof ServerLevel server)
        || !TravelService.locked(server, ticker.getPos());
  }
}
