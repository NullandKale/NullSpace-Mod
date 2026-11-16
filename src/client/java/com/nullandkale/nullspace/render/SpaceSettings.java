package com.nullandkale.nullspace.render;

import com.nullandkale.nullspace.NullSpace;

import java.nio.file.*;

import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.*;

/** Client-owned graphics choices; never changes simulation or server settings. */
public final class SpaceSettings
{
    public static final class Values
    {
        public int surfaceWidth = 960;
        public int cameraWidth = 960;
        public double volumeScale = .25;
        public int volumeSteps = 16;
        public boolean temporal = true, stochastic = false, planetSky = true, airParticles = true;
        public int surfaceSamples = 4;
        public boolean clouds = true;
        public int cloudSteps = 48;
        /** How the clouds look. Coverage is added to the planet's own (clear weather only), the rest scale what the shader does. */
        public double cloudCoverage = 0, cloudDensity = 1, cloudWind = 1, cloudScale = 1;
        public boolean cloudShadows = true;

        public Values checked()
        {
            surfaceWidth = surfaceWidth <= 640 ? 640 : surfaceWidth <= 960 ? 960 : 1280;
            cameraWidth = cameraWidth <= 640 ? 640 : cameraWidth <= 960 ? 960 : cameraWidth <= 1280 ? 1280 : cameraWidth <= 1920 ? 1920 : 2560;
            volumeScale = Math.clamp(Double.isFinite(volumeScale) ? volumeScale : .5, .125, 1);
            volumeSteps = Math.clamp(volumeSteps, 8, 32);
            surfaceSamples = surfaceSamples >= 4 ? 4 : 1;
            cloudSteps = Math.clamp(cloudSteps, 16, 64);
            cloudCoverage = Math.clamp(Double.isFinite(cloudCoverage) ? cloudCoverage : 0, -.5, .5);
            cloudDensity = Math.clamp(Double.isFinite(cloudDensity) ? cloudDensity : 1, .25, 2);
            cloudWind = Math.clamp(Double.isFinite(cloudWind) ? cloudWind : 1, 0, 3);
            cloudScale = Math.clamp(Double.isFinite(cloudScale) ? cloudScale : 1, .5, 2);
            return this;
        }

        /** Everything the clouds depend on, so a cached sky is rebuilt when any of it changes. */
        public String cloudKey()
        {
            return clouds + ":" + cloudSteps + ":" + cloudCoverage + ":" + cloudDensity + ":" + cloudWind + ":" + cloudScale + ":" + cloudShadows;
        }
    }

    private static Values values = new Values();
    private static boolean loaded;
    private static int revision;

    public static Values get()
    {
        if (!loaded)
        {
            loaded = true;
            try
            {
                var path = path();
                if (Files.isRegularFile(path))
                {
                    var next = new Gson().fromJson(Files.readString(path), Values.class);
                    if (next != null)
                        values = next.checked();
                }
            }
            catch (Exception e)
            {
                NullSpace.LOGGER.warn("Cannot read space graphics settings; using defaults", e);
            }
        }
        return values;
    }

    private static Path path()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("nullspace-space.json");
    }

    public static int revision()
    {
        get();
        return revision;
    }

    public static void save()
    {
        get().checked();
        revision++;
        try
        {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(values));
        }
        catch (Exception e)
        {
            NullSpace.LOGGER.warn("Cannot save space graphics settings", e);
        }
    }

    /** Every setting back to its default. */
    public static void defaults()
    {
        values = new Values();
        loaded = true;
        save();
    }

    public static int width()
    {
        return get().surfaceWidth;
    }

    public static int height()
    {
        return width() * 9 / 16;
    }

    public static int cameraWidth()
    {
        return get().cameraWidth;
    }

    /**
     * Fast, Balanced and High quality, chosen from measurements on an RTX 3070 Laptop GPU (docs/development/graphics-settings-performance.md)
     * for the heaviest view, a nebula in sight: about 2 ms, 6 ms and 22 ms. The nebula's resolution and sample count dominate the cost.
     */
    public static void preset(int preset)
    {
        var v = get();
        v.surfaceWidth = preset == 0 ? 640 : 960;
        v.cameraWidth = preset == 0 ? 640 : preset == 1 ? 960 : 1280;
        v.volumeScale = preset == 2 ? .5 : .25;
        v.volumeSteps = preset == 0 ? 8 : 16;
        v.surfaceSamples = preset == 0 ? 1 : 4;
        v.cloudSteps = preset == 0 ? 24 : preset == 1 ? 48 : 64;
        // Presets set what costs time; how the clouds look (coverage, density, wind, block size) is the player's own choice.
        v.cloudShadows = preset != 0;
        v.stochastic = false;
        v.temporal = true;
        save();
    }

    public static boolean matchesPreset(int p)
    {
        var v = get();
        return v.surfaceWidth == (p == 0 ? 640 : 960) && v.cameraWidth == (p == 0 ? 640 : p == 1 ? 960 : 1280) && v.volumeScale == (p == 2 ? .5 : .25) && v.volumeSteps == (p == 0 ? 8 : 16) && v.surfaceSamples == (p == 0 ? 1 : 4) && v.cloudSteps == (p == 0 ? 24 : p == 1 ? 48 : 64) && v.cloudShadows == (p != 0) && !v.stochastic && v.temporal;
    }

    private SpaceSettings()
    {
    }
}
