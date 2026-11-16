package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla fades far terrain into its biome fog colour over the last few chunks, which is a pale band against the planet sky.
 * {@link FogRenderer#setupFog} sets that range itself after the fog environments have run, so it is turned off here, when the
 * atmospheric fog is already off for the planet sky, and its range is kept for the composite, which fades far terrain into the
 * sky's own colour instead.
 */
@Mixin(FogRenderer.class)
public abstract class PlanetChunkFogMixin
{
    /** {@code -Dnullspace.fog.vanilla=true} keeps vanilla's chunk-edge fog, to compare the two. */
    private static final boolean VANILLA = Boolean.getBoolean("nullspace.fog.vanilla");

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void nullspace$chunkFade(Camera camera, int renderDistance, DeltaTracker delta, float darkness, ClientLevel level, CallbackInfoReturnable<FogData> result)
    {
        var fog = result.getReturnValue();
        if (!VANILLA && fog.environmentalStart == Float.MAX_VALUE && fog.environmentalEnd == Float.MAX_VALUE && SpaceSceneRenderer.planetSkyReady() && SpaceSceneRenderer.computeReady())
        {
            // Vanilla starts its fade 10 percent from the edge, which is a row or two at the horizon. A longer fade reads as haze.
            SpaceSceneRenderer.chunkFade(fog.renderDistanceEnd * .6f, fog.renderDistanceEnd);
            fog.renderDistanceStart = Float.MAX_VALUE;
            fog.renderDistanceEnd = Float.MAX_VALUE;
        }
        else
            SpaceSceneRenderer.chunkFade(Float.MAX_VALUE, Float.MAX_VALUE);
    }
}
