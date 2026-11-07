package com.nullandkale.nullspace.core.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.nullandkale.nullspace.gameplay.crew.ShipRespawns;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEdits;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Quarantine also covers environmental edits, such as an explosion originating outside the ship.
 */
@Mixin(Level.class)
public abstract class TransitBlocksMixin {
  @Inject(
      method =
          "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
      at = @At("HEAD"),
      cancellable = true)
  private void nullspace$protectArrival(
      BlockPos pos,
      BlockState state,
      int flags,
      int limit,
      CallbackInfoReturnable<Boolean> ci,
      @Share("nullspace$before") LocalRef<BlockState> before) {
    if ((Object) this instanceof ServerLevel level && TravelService.locked(level, pos))
      ci.setReturnValue(false);
    else if ((Object) this instanceof ServerLevel level) before.set(level.getBlockState(pos));
  }

  @Inject(
      method =
          "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
      at = @At("RETURN"))
  private void nullspace$changed(
      BlockPos pos,
      BlockState state,
      int flags,
      int limit,
      CallbackInfoReturnable<Boolean> ci,
      @Share("nullspace$before") LocalRef<BlockState> before) {
    if (ci.getReturnValue() && (Object) this instanceof ServerLevel level && before.get() != null) {
      var after = level.getBlockState(pos);
      if (before.get() == after) return;
      if (before.get().getBlock() instanceof net.minecraft.world.level.block.AbstractBedBlock
          && before.get().getBlock() != after.getBlock()) ShipRespawns.changed(level, pos, true);
      HullEdits.blockChanged(
          level, pos, !before.get().isAir() && before.get().getBlock() != after.getBlock());
    }
  }
}
