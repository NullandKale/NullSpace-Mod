package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.navigation.PlanetDimensions;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class PlanetSeedMixin
{
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void nullspace$planetSeed(CallbackInfoReturnable<Long> ci)
    {
        var seed = PlanetDimensions.seed((ServerLevel) (Object) this);
        if (seed != null)
            ci.setReturnValue(seed);
    }
}
