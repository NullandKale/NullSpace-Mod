package com.nullandkale.nullspace.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.WeatherData;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The weather clock belongs to the whole server, but every level that can have weather advances
 * it each tick. Vanilla has one such level (the overworld); the planet surfaces here are all of
 * them, so weather ran about twenty-five times too fast. Only the overworld moves the clock. Each
 * level still reads it to ease its own rain and thunder and to tell its players.
 */
@Mixin(ServerLevel.class)
public abstract class ServerWeatherMixin
{
    private boolean nullspace$movesWeatherClock()
    {
        return ((ServerLevel) (Object) this).dimension() == Level.OVERWORLD;
    }

    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;setClearWeatherTime(I)V"))
    private boolean nullspace$clearTime(WeatherData data, int time)
    {
        return nullspace$movesWeatherClock();
    }

    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;setRainTime(I)V"))
    private boolean nullspace$rainTime(WeatherData data, int time)
    {
        return nullspace$movesWeatherClock();
    }

    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;setThunderTime(I)V"))
    private boolean nullspace$thunderTime(WeatherData data, int time)
    {
        return nullspace$movesWeatherClock();
    }

    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;setRaining(Z)V"))
    private boolean nullspace$raining(WeatherData data, boolean raining)
    {
        return nullspace$movesWeatherClock();
    }

    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/WeatherData;setThundering(Z)V"))
    private boolean nullspace$thundering(WeatherData data, boolean thundering)
    {
        return nullspace$movesWeatherClock();
    }
}
