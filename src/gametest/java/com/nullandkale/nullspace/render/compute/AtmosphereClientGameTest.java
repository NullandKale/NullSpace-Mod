package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import com.nullandkale.nullspace.model.space.SpaceDefinition;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.scene.SceneViewport;
import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL43C.*;

/** Real GPU transport, weather, cache/reload assertions and reproducible visual reference atlas. */
public final class AtmosphereClientGameTest implements FabricClientGameTest
{
    private static final int W = 256, H = 144;

    private static void check(boolean ok, String message)
    {
        if (!ok)
            throw new AssertionError(message);
    }

    private static CompoundTag scene(AtmosphereProfile profile, double elevation, long revision)
    {
        var sun = new SpaceDefinition.Body("sun", 0, new Vec3(0, Math.sin(elevation) * 100000, Math.cos(elevation) * 100000), Vec3.ZERO, 1200, 0xffffd07a, 12, 0, 4, 0);
        var scene = new CompoundTag();
        scene.putBoolean("universe", true);
        scene.putBoolean("surfaceAtmosphere", true);
        scene.putString("observer", "test");
        scene.put("catalog", SpaceDefinitionCodec.encode(new SpaceDefinition("test:atmosphere", revision, 42, 1, 0, List.of(sun), List.of(), List.of())));
        scene.put("atmosphereProfile", profile.encode());
        scene.putDouble("atmosphereY", -profile.radius() - .05);
        scene.putLong("receivedNanos", Long.MAX_VALUE);
        var subjects = new ListTag();
        var observer = new CompoundTag();
        observer.putString("id", "test");
        observer.putIntArray("blocks", new int[32768]);
        subjects.add(observer);
        scene.put("subjects", subjects);
        return scene;
    }

    private static Matrix4f projection(float pitch)
    {
        return new Matrix4f().perspective((float) Math.toRadians(80), (float) W / H, 1000000, .05f, true).lookAt(0, 0, 0, 0, (float) Math.sin(pitch), (float) Math.cos(pitch), 0, 1, 0);
    }

    private static void finite(float[] values, boolean transmission)
    {
        for (int p = 0; p < values.length; p++)
        {
            check(Float.isFinite(values[p]) && values[p] >= 0, "Atmosphere images remain finite and nonnegative at " + p);
            if (transmission)
                check(values[p] <= 1.001, "Optical transmission is bounded by one");
        }
    }

    private static double brightness(float[] pixels)
    {
        double sum = 0;
        for (int i = 0; i < pixels.length; i += 4)
            sum += pixels[i] + pixels[i + 1] + pixels[i + 2];
        return sum / (pixels.length / 4);
    }

    private static void tile(BufferedImage atlas, int index, String title, float[] data)
    {
        finite(data, false);
        int x = index % 4 * W, y = index / 4 * (H + 24);
        for (int py = 0; py < H; py++)
            for (int px = 0; px < W; px++)
            {
                int p = ((H - py - 1) * W + px) * 4, rgb = 0;
                for (int c = 0; c < 3; c++)
                {
                    double linear = Math.max(0, data[p + c]);
                    int value = (int) Math.round(255 * Math.pow(linear / (1 + linear), 1 / 2.2));
                    rgb = rgb << 8 | Math.clamp(value, 0, 255);
                }
                atlas.setRGB(x + px, y + 24 + py, rgb);
            }
        var g = atlas.createGraphics();
        try
        {
            g.setColor(Color.WHITE);
            g.drawString(title, x + 5, y + 17);
        }
        finally
        {
            g.dispose();
        }
    }

    private static void aerialPerspective(SceneViewport view, AtmosphereProfile profile)
    {
        var frame = scene(profile, .8, 100);
        var matrix = projection(0);
        SpaceSettings.get().clouds = false;
        view.compute().surfaces(frame, Vec3.ZERO, matrix);
        var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
        float[] transmission = new float[2], light = new float[2];
        for (int i = 0; i < 2; i++)
        {
            float distance = i == 0 ? .1f : 50;
            float z = matrix.transformProject(new org.joml.Vector3f(0, 0, distance)).z;
            if (!com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne())
                z = z * .5f + .5f;
            encoder.clearDepthTexture(view.target.getDepthTexture(), z);
            view.compute().volumes(view.target.getDepthTexture(), matrix);
            var pixels = view.compute().inspectPrefixes();
            finite(pixels, false);
            int w = view.compute().volumeWidth(), h = view.compute().volumeHeight();
            int p = ((SpaceComputeRenderer.SLICES - 1) * w * h + (h / 2) * w + w / 2) * 4;
            transmission[i] = pixels[p + 3];
            light[i] = pixels[p] + pixels[p + 1] + pixels[p + 2];
        }
        check(transmission[0] > .98 && transmission[1] < transmission[0] * .8, "Native depth clips aerial extinction to the actual object, rather than a full sky ray: T=" + Arrays.toString(transmission) + " L=" + Arrays.toString(light));
        check(light[1] > light[0] * 5, "Distant terrain receives greater atmospheric in-scattering");
        encoder.clearDepthTexture(view.target.getDepthTexture(), 0);
        view.compute().volumes(view.target.getDepthTexture(), matrix);
        var sky = view.compute().inspectPrefixes();
        for (int p = 0; p < sky.length; p += 4)
            check(sky[p] == 0 && sky[p + 1] == 0 && sky[p + 2] == 0 && sky[p + 3] == 1, "The HDR sky is not attenuated/scattered a second time by the native world pass");
    }

    private static void flatWorldWeather(SceneViewport view, AtmosphereProfile profile)
    {
        var frame = scene(profile, .8, 110);
        frame.putBoolean("planetSky", true);
        frame.putDouble("surfaceRadius", profile.radius());
        frame.putDouble("surfaceCenterY", -profile.radius() - .05);
        frame.getListOrEmpty("subjects").getCompound(0).orElseThrow().putLong("base", new net.minecraft.core.BlockPos(0, 65, 0).asLong());
        SpaceSettings.get().clouds = false;
        view.compute().surfaces(frame, new Vec3(0, 1.62, 0), projection(.5f));
        float[] origin = view.compute().inspectRadiance();
        int center = (H / 2 * W + W / 2) * 4;
        check(origin[center + 2] > origin[center] * 1.1 && origin[center + 2] > .1, "Flat-world baseline must contain a bright blue daytime sky");
        for (var eye : List.of(new Vec3(886.242943, 1.62, -81.684514), new Vec3(30000000, 1.62, -30000000)))
        {
            view.compute().surfaces(frame, eye, projection(.5f));
            check(Arrays.equals(origin, view.compute().inspectRadiance()), "Daylight sky is unchanged by walking to World 5's coordinates or the world border");
        }
        var eye = new Vec3(886.242943, 1.62, -81.684514);
        frame.putDouble("surfaceRain", 1);
        view.compute().surfaces(frame, eye, projection(.5f));
        var overcast = view.compute().inspectRadiance();
        check(brightness(view.compute().inspectSkyTransmission()) < .2, "Rain forces an opaque overcast even when the clear-weather cloud option is off");
        SpaceSettings.get().clouds = true;
        view.compute().surfaces(frame, eye, projection(.5f));
        check(Arrays.equals(overcast, view.compute().inspectRadiance()), "Cloud preferences cannot disable rainy-weather clouds");
        frame.putDouble("surfaceRain", 0);
        SpaceSettings.get().clouds = false;
        view.compute().surfaces(frame, eye, projection(.5f));
        check(Arrays.equals(origin, view.compute().inspectRadiance()), "Clear weather restores the daytime sky after rain without stale optical history");
    }

    @Override
    public void runTest(ClientGameTestContext context)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = context.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            context.runOnClient(client ->
            {
                boolean oldClouds = SpaceSettings.get().clouds, oldTemporal = SpaceSettings.get().temporal;
                int oldSteps = SpaceSettings.get().cloudSteps;
                SpaceSettings.get().clouds = false;
                SpaceSettings.get().temporal = false;
                try (var view = new SceneViewport(W, H); var other = new SceneViewport(65, 49))
                {
                    var profile = AtmosphereProfile.temperate(6360, 100. / 6360);
                    var frame = scene(profile, .8, 1);
                    view.render(frame, 0, 0, 80);
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.3f));
                    var table = view.compute().inspectTransmittance();
                    view.compute().surfaces(frame, Vec3.ZERO, projection(1.2f));
                    var daytimeSky = view.compute().inspectRadiance();
                    int center = (H / 2 * W + W / 2) * 4;
                    check(daytimeSky[center + 2] > daytimeSky[center] * 1.1, "Sol's authored orange disk must still illuminate a blue daytime atmosphere");
                    finite(table, true);
                    double worst = 0;
                    for (int y : new int[]{0, 8, 24, 48, 62})
                        for (int x : new int[]{4, 32, 96, 192, 255})
                        {
                            double altitude = Math.pow(y / 63., 2) * profile.height();
                            double r = profile.radius() + Math.max(.0001, altitude);
                            double horizon = -Math.sqrt(Math.max(0, 1 - profile.radius() * profile.radius() / (r * r)));
                            double mu = horizon + (1 - horizon) * Math.pow(x / 255., 2);
                            var expected = profile.transmittance(r - profile.radius(), mu, 8192);
                            int p = (y * 256 + x) * 4;
                            worst = Math.max(worst, Math.max(Math.abs(table[p] - expected.x), Math.max(Math.abs(table[p + 1] - expected.y), Math.abs(table[p + 2] - expected.z))));
                        }
                    check(worst < .012, "GPU transmittance agrees with independent 8192-step double reference: " + worst);
                    long updates = view.compute().atmosphereUpdates();
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.5f));
                    check(view.compute().atmosphereUpdates() == updates, "Turning the camera reuses its spherical sky lookup");
                    int shared = SpaceComputeRenderer.sharedAtmospheres();
                    other.render(frame, 0, 0, 80);
                    check(SpaceComputeRenderer.sharedAtmospheres() == shared, "Monitors share planet-only optical tables");
                    view.compute().surfaces(frame, new Vec3(0, 35, 0), projection(.3f));
                    check(view.compute().atmosphereUpdates() > updates, "Altitude changes rebuild the camera's sky lookup");
                    aerialPerspective(view, profile);
                    flatWorldWeather(view, profile);

                    var atlas = new BufferedImage(W * 4, (H + 24) * 4, BufferedImage.TYPE_INT_RGB);
                    double[] elevations = {1.2, .15, .015, -.035, -.12, -1.2};
                    String[] names = {"Noon", "Low sun", "Sunset", "Early twilight", "Late twilight", "Midnight"};
                    double noon = 0, night = 0;
                    for (int i = 0; i < elevations.length; i++)
                    {
                        frame = scene(profile, elevations[i], i + 2);
                        view.compute().surfaces(frame, Vec3.ZERO, projection(.12f));
                        var pixels = view.compute().inspectRadiance();
                        finite(pixels, false);
                        tile(atlas, i, names[i] + " / clear", pixels);
                        if (i == 0)
                            noon = brightness(pixels);
                        if (i == 5)
                            night = brightness(pixels);
                    }
                    check(noon > night * 10, "Planet shadow makes midnight darker than daylight");
                    frame = scene(profile, .8, 10);
                    SpaceSettings.get().clouds = true;
                    SpaceSettings.get().cloudSteps = 48;
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.3f));
                    float[] clear = view.compute().inspectRadiance();
                    tile(atlas, 6, "Noon / clouds", clear);
                    frame.putDouble("surfaceRain", 1);
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.3f));
                    float[] rain = view.compute().inspectRadiance();
                    tile(atlas, 7, "Rain / overcast", rain);
                    frame.putDouble("surfaceThunder", 1);
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.3f));
                    float[] storm = view.compute().inspectRadiance();
                    tile(atlas, 8, "Thunderstorm", storm);
                    check(brightness(storm) < brightness(rain), "Thunder darkens cloud interiors");
                    double trans = brightness(view.compute().inspectSkyTransmission());
                    check(trans < 1.5, "Overcast clouds attenuate background light, not just recolor it");
                    finite(view.compute().inspectSkyTransmission(), true);
                    view.compute().surfaces(frame, new Vec3(0, 38, 0), projection(.12f));
                    tile(atlas, 9, "Inside storm clouds", view.compute().inspectRadiance());
                    view.compute().surfaces(frame, new Vec3(0, 70, 0), projection(-.3f));
                    tile(atlas, 10, "Above storm clouds", view.compute().inspectRadiance());
                    frame = scene(profile, .025, 11);
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.12f));
                    tile(atlas, 11, "Sunset / clouds", view.compute().inspectRadiance());
                    view.compute().surfaces(frame, new Vec3(0, 180, 0), projection(-.6f));
                    tile(atlas, 12, "Orbit / atmosphere limb", view.compute().inspectRadiance());
                    SpaceSettings.get().clouds = false;
                    var red = new AtmosphereProfile(profile.radius(), profile.height(), new Vec3(profile.rayleigh().z, profile.rayleigh().y, profile.rayleigh().x), profile.mie(), profile.absorption(), profile.rayleighHeight(), profile.mieHeight(), .3, 0);
                    frame = scene(red, .8, 12);
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.3f));
                    tile(atlas, 13, "Alternate scattering profile", view.compute().inspectRadiance());
                    frame.putBoolean("surfaceAtmosphere", false);
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.3f));
                    tile(atlas, 14, "Airless / day", view.compute().inspectRadiance());
                    check(!view.compute().atmosphereReady(), "Airless frames release atmospheric rendering state");
                    var noAir = view.compute().inspectRadiance();
                    frame.putDouble("surfaceRain", 1);
                    frame.putDouble("surfaceThunder", 1);
                    view.compute().surfaces(frame, Vec3.ZERO, projection(.3f));
                    check(Arrays.equals(noAir, view.compute().inspectRadiance()), "Airless views ignore planetary rain");
                    frame = scene(profile, -.035, 13);
                    view.compute().surfaces(frame, new Vec3(0, 70, 0), projection(.12f));
                    tile(atlas, 15, "Twilight / high altitude", view.compute().inspectRadiance());
                    float[] before = view.compute().inspectRadiance();
                    for (String pass : List.of("atmosphere_transmittance", "atmosphere_multiple", "atmosphere_sky"))
                    {
                        check(!SpaceComputeRenderer.reload(Map.of(pass, "#version 430\ninvalid_shader")), "Bad " + pass + " retains all working programs");
                        view.compute().surfaces(frame, new Vec3(0, 70, 0), projection(.12f));
                        check(Arrays.equals(before, view.compute().inspectRadiance()), "Failed atmosphere reload preserves working images");
                    }
                    for (int i = 0; i < 6; i++)
                    {
                        var variant = new AtmosphereProfile(profile.radius(), profile.height(), profile.rayleigh(), profile.mie(), profile.absorption(), profile.rayleighHeight(), profile.mieHeight(), .2 + i * .1, .38);
                        view.compute().surfaces(scene(variant, .3, 20 + i), Vec3.ZERO, projection(.3f));
                        check(SpaceComputeRenderer.sharedAtmospheres() <= 4, "Planet optical cache remains bounded across regions");
                    }
                    // Re-enter an evicted profile through another already allocated camera.
                    other.render(scene(profile, .8, 40), 0, 0, 80);
                    finite(other.compute().inspectRadiance(), false);
                    view.compute().surfaces(scene(profile, .8, 45), new Vec3(0, -profile.radius() - .05, 0), projection(.3f));
                    finite(view.compute().inspectRadiance(), false);
                    check(glGetError() == GL_NO_ERROR, "Atmosphere rendering leaves no OpenGL errors");
                    var dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
                    try
                    {
                        Files.createDirectories(dir);
                        ImageIO.write(atlas, "png", dir.resolve("nullspace-atmosphere-reference-atlas.png").toFile());
                        Files.writeString(dir.resolve("nullspace-atmosphere-metrics.json"), "{\"transmittanceMaxAbsoluteError\":" + worst + ",\"noonRadiance\":" + noon + ",\"nightRadiance\":" + night + ",\"rainRadiance\":" + brightness(rain) + ",\"stormRadiance\":" + brightness(storm) + ",\"skyUpdates\":" + view.compute().atmosphereUpdates() + "}");
                    }
                    catch (java.io.IOException e)
                    {
                        throw new IllegalStateException(e);
                    }
                }
                finally
                {
                    SpaceSettings.get().clouds = oldClouds;
                    SpaceSettings.get().temporal = oldTemporal;
                    SpaceSettings.get().cloudSteps = oldSteps;
                }
            });
            // End-to-end native terrain, glass, weather and the actual CloudRenderer mixin.
            world.getServer().runOnServer(server ->
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 9502");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                var level = server.overworld();
                for (int x = -20; x <= 20; x++)
                    for (int z = -12; z <= 40; z++)
                        level.setBlock(new net.minecraft.core.BlockPos(886 + x, 64, -82 + z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 18);
                for (int x = -3; x <= 3; x++)
                    for (int y = 65; y <= 70; y++)
                        level.setBlock(new net.minecraft.core.BlockPos(886 + x, y, -70), net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(), 18);
                for (int x : new int[]{-8, 8})
                    for (int y = 65; y < 75; y++)
                        level.setBlock(new net.minecraft.core.BlockPos(886 + x, y, -58), net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(), 18);
                var player = world.getConnection().getServerPlayer();
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                player.teleportTo(level, 886.5, 65, -81.5, Set.of(), 0, -15, true);
            });
            context.waitFor(client -> SpaceSceneRenderer.planetSkyReady() && SpaceSceneRenderer.viewport().compute().atmosphereReady(), 300);
            context.waitTicks(25);
            var samples = new ArrayList<double[]>();
            for (int i = 0; i < 32; i++)
            {
                context.runOnClient(client ->
                {
                    var s = SpaceSceneRenderer.viewport().compute().stats();
                    samples.add(new double[]{s.atmosphereMs(), s.surfaceMs(), s.volumeMs(), s.cpuMs()});
                });
                context.waitTicks(1);
            }
            context.runOnClient(client ->
            {
                var report = new com.google.gson.JsonObject();
                report.addProperty("gpu", glGetString(GL_RENDERER));
                report.addProperty("surfaceWidth", SpaceSettings.width());
                report.addProperty("surfaceHeight", SpaceSettings.height());
                report.addProperty("samples", samples.size());
                report.addProperty("scope", "Native clear-weather scene: sky update, celestial surface, volume passes and CPU" + " surface submission; excludes native terrain/GUI draws");
                String[] names = {"atmosphere_ms", "surface_ms", "volume_ms", "cpu_submission_ms"};
                for (int i = 0; i < names.length; i++)
                {
                    int column = i;
                    double[] sorted = samples.stream().mapToDouble(a -> a[column]).sorted().toArray();
                    report.addProperty(names[i] + "_median", sorted[sorted.length / 2]);
                    report.addProperty(names[i] + "_p95", sorted[(int) ((sorted.length - 1) * .95)]);
                }
                try
                {
                    Files.writeString(FabricLoader.getInstance().getGameDir().resolve("native-atmosphere-performance.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                }
                catch (java.io.IOException e)
                {
                    throw new IllegalStateException(e);
                }
            });
            context.takeScreenshot("nullspace-atmosphere-native-terrain-glass");
            world.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather thunder"));
            context.waitTicks(110);
            boolean[] cloudPreference = {true};
            context.runOnClient(client ->
            {
                cloudPreference[0] = SpaceSettings.get().clouds;
                SpaceSettings.get().clouds = false;
            });
            try
            {
                context.waitTicks(3);
                context.runOnClient(client ->
                {
                    var compute = SpaceSceneRenderer.viewport().compute();
                    var transmission = compute.inspectSkyTransmission();
                    double total = 0;
                    for (int p = transmission.length / 2; p < transmission.length; p += 4)
                        total += transmission[p] + transmission[p + 1] + transmission[p + 2];
                    check(total / (transmission.length / 8) < .2, "Actual native rain at World 5 coordinates must obscure the sky with clouds");
                });
                context.takeScreenshot("nullspace-atmosphere-native-thunderstorm");
            }
            finally
            {
                context.runOnClient(client -> SpaceSettings.get().clouds = cloudPreference[0]);
            }
        }
        context.runOnClient(client -> check(SpaceComputeRenderer.sharedAtmospheres() == 0, "Disconnect releases the shared planetary lookup cache"));
        ClientTestEvidence.passed(getClass());
    }
}
