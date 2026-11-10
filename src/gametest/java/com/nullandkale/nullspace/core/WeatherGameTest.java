package com.nullandkale.nullspace.core;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The weather clock is one for the whole server, however many planet dimensions are loaded. */
public final class WeatherGameTest
{
    @GameTest(maxTicks = 120)
    public void weatherTimersTickOncePerTickNotOncePerDimension(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var weather = server.getWeatherData();
        weather.setRaining(false);
        weather.setThundering(false);
        weather.setClearWeatherTime(100_000);
        int counted = 0;
        for (var level : server.getAllLevels())
            if (level.canHaveWeather())
                counted++;
        int levels = counted;
        ctx.assertTrue(levels > 1, "The test needs several dimensions that can have weather: " + levels);
        ctx.runAfterDelay(40, () ->
        {
            int elapsed = 100_000 - weather.getClearWeatherTime();
            ctx.assertTrue(elapsed >= 30 && elapsed <= 50, "40 ticks use about 40 ticks of weather time, not once per dimension (" + levels + " can have weather): " + elapsed);
            ctx.succeed();
        });
    }
}
