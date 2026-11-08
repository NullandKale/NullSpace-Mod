package com.nullandkale.nullspace.core.mixin;

import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla saves the fuse duration, but omits elapsed fuse ticks during dimension recreation. */
@Mixin(Creeper.class)
public abstract class CreeperStateMixin
{
    @Shadow
    private int swell;
    @Shadow
    private int oldSwell;

    @Shadow
    public abstract int getSwellDir();

    @Shadow
    public abstract void setSwellDir(int direction);

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void nullspace$saveFuse(ValueOutput output, CallbackInfo ci)
    {
        output.putInt("nullspace:swell", swell);
        output.putInt("nullspace:old_swell", oldSwell);
        output.putInt("nullspace:swell_direction", getSwellDir());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void nullspace$loadFuse(ValueInput input, CallbackInfo ci)
    {
        swell = input.getIntOr("nullspace:swell", 0);
        oldSwell = input.getIntOr("nullspace:old_swell", swell);
        setSwellDir(input.getIntOr("nullspace:swell_direction", -1));
    }
}
