package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Space Graphics settings: presets, defaults, limits and the saved file, and screenshots of the screen at the top and the
 * bottom of its list for review. It does not click the controls.
 */
public final class SpaceSettingsClientGameTest implements FabricClientGameTest
{
    private static void check(boolean ok, String message)
    {
        if (!ok)
            throw new AssertionError(message);
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        c.runOnClient(mc ->
        {
            SpaceSettings.defaults();
            var v = SpaceSettings.get();
            check(SpaceSettings.matchesPreset(1), "The defaults are the Balanced preset");
            check(v.cloudDensity == 1 && v.cloudCoverage == 0 && v.cloudWind == 1 && v.cloudScale == 1 && v.cloudShadows && v.cloudSteps == 48 && v.clouds, "The cloud defaults");

            // A preset sets what costs time and leaves the cloud look alone.
            v.cloudDensity = 1.5;
            v.cloudWind = 2;
            SpaceSettings.preset(0);
            check(SpaceSettings.matchesPreset(0) && !v.cloudShadows && v.cloudSteps == 24, "Fast turns shadows off and uses the fewest cloud steps");
            check(v.cloudDensity == 1.5 && v.cloudWind == 2, "A preset leaves how the clouds look as the player set it");
            SpaceSettings.preset(2);
            check(SpaceSettings.matchesPreset(2) && v.cloudShadows && v.cloudSteps == 64, "High quality turns shadows on with the most cloud steps");

            // Every cloud setting is limited to its range, and bad numbers fall back to the default.
            v.cloudDensity = 99;
            v.cloudCoverage = Double.NaN;
            v.cloudWind = -4;
            v.cloudScale = 0;
            v.checked();
            check(v.cloudDensity == 2 && v.cloudCoverage == 0 && v.cloudWind == 0 && v.cloudScale == .5, "Out-of-range cloud settings are brought back into range");

            // Everything the clouds depend on is in the key that decides when a cached sky is rebuilt.
            var before = v.cloudKey();
            v.cloudScale = 1.25;
            check(!before.equals(v.cloudKey()), "Changing the block size changes the cloud key");

            SpaceSettings.preset(0);
            var path = FabricLoader.getInstance().getConfigDir().resolve("nullspace-space.json");
            try
            {
                String text = java.nio.file.Files.readString(path);
                check(text.contains("cloudDensity") && text.contains("cloudCoverage") && text.contains("cloudWind") && text.contains("cloudScale") && text.contains("cloudShadows"), "The cloud settings are saved: " + text);
            }
            catch (java.io.IOException error)
            {
                throw new AssertionError("The settings file is written", error);
            }
            SpaceSettings.defaults();
        });

        c.setScreen(() -> new SpaceSettingsScreen(null));
        c.waitTicks(5);
        c.takeScreenshot("nullspace-space-settings-top");
        for (int i = 0; i < 12; i++)
        {
            c.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2., mc.gui.screen().height / 2., 0, -3));
            c.waitTick();
        }
        c.waitTicks(3);
        c.takeScreenshot("nullspace-space-settings-bottom");
        c.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        ClientTestEvidence.passed(getClass());
    }
}
