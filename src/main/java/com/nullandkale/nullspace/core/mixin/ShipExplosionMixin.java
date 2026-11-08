package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.combat.ShipCombat;

import net.minecraft.world.level.ServerExplosion;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ShipExplosionMixin
{
    @Inject(method = "explode", at = @At("RETURN"))
    private void nullspace$propagateBlast(CallbackInfoReturnable<Integer> ci)
    {
        var explosion = (ServerExplosion) (Object) this;
        ShipCombat.localExplosion(explosion.level(), explosion.center(), explosion.radius(), explosion.getDirectSourceEntity());
    }
}
