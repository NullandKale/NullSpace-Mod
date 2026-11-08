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
        public int cameraWidth = 1280;
        public double volumeScale = .5;
        public int volumeSteps = 16;
        public boolean temporal = true, stochastic = false, planetSky = true, airParticles = true;
        public int surfaceSamples = 4;
        public boolean clouds = true;
        public int cloudSteps = 48;

        public Values checked()
        {
            surfaceWidth = surfaceWidth <= 640 ? 640 : surfaceWidth <= 960 ? 960 : 1280;
            cameraWidth = cameraWidth <= 640 ? 640 : cameraWidth <= 960 ? 960 : cameraWidth <= 1280 ? 1280 : cameraWidth <= 1920 ? 1920 : 2560;
            volumeScale = Math.clamp(Double.isFinite(volumeScale) ? volumeScale : .5, .125, 1);
            volumeSteps = Math.clamp(volumeSteps, 8, 32);
            surfaceSamples = surfaceSamples >= 4 ? 4 : 1;
            cloudSteps = Math.clamp(cloudSteps, 16, 64);
            return this;
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

    public static void preset(int preset)
    {
        var v = get();
        v.surfaceWidth = preset == 0 ? 640 : preset == 1 ? 960 : 1280;
        v.cameraWidth = preset == 0 ? 640 : preset == 1 ? 1280 : 1920;
        v.volumeScale = preset == 0 ? .25 : preset == 1 ? .5 : 1;
        v.volumeSteps = preset == 0 ? 8 : preset == 1 ? 16 : 24;
        v.surfaceSamples = preset == 0 ? 1 : 4;
        v.cloudSteps = preset == 0 ? 24 : preset == 1 ? 48 : 64;
        v.stochastic = false;
        v.temporal = true;
        save();
    }

    public static boolean matchesPreset(int p)
    {
        var v = get();
        return v.surfaceWidth == (p == 0 ? 640 : p == 1 ? 960 : 1280) && v.cameraWidth == (p == 0 ? 640 : p == 1 ? 1280 : 1920) && v.volumeScale == (p == 0 ? .25 : p == 1 ? .5 : 1) && v.volumeSteps == (p == 0 ? 8 : p == 1 ? 16 : 24) && v.surfaceSamples == (p == 0 ? 1 : 4) && v.cloudSteps == (p == 0 ? 24 : p == 1 ? 48 : 64) && !v.stochastic && v.temporal;
    }

    private SpaceSettings()
    {
    }
}
