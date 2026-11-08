package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;

import net.minecraft.client.renderer.SkyRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class PlanetSkyMixin
{
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void nullspace$planetSky(CallbackInfo ci)
    {
        if (SpaceSceneRenderer.planetSkyReady())
            ci.cancel();
    }
}
