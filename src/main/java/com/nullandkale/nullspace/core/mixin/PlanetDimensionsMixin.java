package com.nullandkale.nullspace.core.mixin;

import com.nullandkale.nullspace.gameplay.navigation.PlanetDimensions;

import java.util.Map;
import java.util.concurrent.Executor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class PlanetDimensionsMixin
{
    @Shadow
    @Final
    private Executor executor;
    @Shadow
    @Final
    protected LevelStorageSource.LevelStorageAccess storageSource;
    @Shadow
    @Final
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Inject(method = "createLevels", at = @At("RETURN"))
    private void nullspace$planetLevels(CallbackInfo ci)
    {
        PlanetDimensions.create((MinecraftServer) (Object) this, executor, storageSource, levels);
    }
}
