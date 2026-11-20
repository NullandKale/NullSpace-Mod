package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.model.optics.AtmosphereProfile;
import com.nullandkale.nullspace.model.space.SpaceDefinition;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.scene.SceneViewport;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;

import java.util.*;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import static org.lwjgl.opengl.GL43C.*;

/**
 * Opt-in (run with {@code -Dnullspace.perf.graphics=true}; it does nothing otherwise): how much GPU time each Space Graphics
 * setting costs on the machine it runs on. Each setting is changed alone from the defaults, on two scenes: a planet sky with
 * clouds, and space with a nebula, planets and two hulls. Timings come from the renderer's own GPU timestamp queries and are
 * evidence for choosing the presets, never a pass or fail threshold. A laptop GPU changes its clock speed as it warms, so every
 * setting is measured three times, each time right between two runs of the defaults, and the ratio to those defaults is the
 * result to read. The report is written to graphics-settings-benchmark.json and .tsv in the game directory.
 */
public final class GraphicsSettingsBenchmarkClientGameTest implements FabricClientGameTest
{
    private static final int WARMUP = 10, SAMPLES = 24, REPEATS = Integer.getInteger("nullspace.perf.graphics.repeats", 3);
    /** sky, space or both (the default). */
    private static final String SCENES = System.getProperty("nullspace.perf.graphics.scene", "both");

    /** One setting change, and which scenes it can affect. */
    private record Case(String group, String label, boolean sky, boolean space, int width, Consumer<SpaceSettings.Values> apply)
    {}

    /** The setting's GPU time, the defaults' time measured right beside it, and the ratio of the two (the number to trust). */
    private record Result(String group, String label, String scene, int width, double gpuMs, double baselineMs, double ratio, double ratioLow, double ratioHigh)
    {}

    private static Case sky(String group, String label, Consumer<SpaceSettings.Values> apply)
    {
        return new Case(group, label, true, false, 960, apply);
    }

    private static Case space(String group, String label, Consumer<SpaceSettings.Values> apply)
    {
        return new Case(group, label, false, true, 960, apply);
    }

    private static Case both(String group, String label, int width, Consumer<SpaceSettings.Values> apply)
    {
        return new Case(group, label, true, true, width, apply);
    }

    private static List<Case> cases()
    {
        var out = new ArrayList<Case>();
        out.add(both("Baseline", "Defaults (Balanced)", 960, v ->
        {}));
        for (int i = 0; i < 3; i++)
        {
            int preset = i;
            out.add(both("Presets", new String[]{"Fast", "Balanced", "High quality"}[i], new int[]{640, 960, 1280}[i], v ->
            {
                SpaceSettings.preset(preset);
                var p = SpaceSettings.get();
                v.surfaceWidth = p.surfaceWidth;
                v.volumeScale = p.volumeScale;
                v.volumeSteps = p.volumeSteps;
                v.surfaceSamples = p.surfaceSamples;
                v.cloudSteps = p.cloudSteps;
                v.cloudShadows = p.cloudShadows;
            }));
        }
        for (int w : new int[]{640, 960, 1280})
            out.add(both("Surface resolution", w + " x " + w * 9 / 16, w, v -> v.surfaceWidth = w));
        for (int w : new int[]{640, 960, 1280, 1920, 2560})
            out.add(space("Camera feed resolution", w + " x " + w * 9 / 16, v -> v.cameraWidth = w, w));
        for (double scale : new double[]{.125, .25, .5, 1})
            out.add(space("Volume resolution", Math.round(scale * 100) + "%", v -> v.volumeScale = scale));
        for (int steps : new int[]{8, 16, 24, 32})
            out.add(space("Volume quality", steps + " steps", v -> v.volumeSteps = steps));
        for (int samples : new int[]{1, 4})
            out.add(both("Antialiasing", samples == 1 ? "Off" : "4 samples", 960, v -> v.surfaceSamples = samples));
        out.add(both("Temporal reconstruction", "Off", 960, v -> v.temporal = false));
        out.add(space("Stochastic volume sampling", "On", v -> v.stochastic = true));
        out.add(sky("Clouds", "Clear-weather clouds off", v -> v.clouds = false));
        for (int steps : new int[]{16, 32, 48, 64})
            out.add(sky("Cloud quality", steps + " steps", v -> v.cloudSteps = steps));
        out.add(sky("Cloud shadows", "Off", v -> v.cloudShadows = false));
        for (double coverage : new double[]{-.5, 0, .5})
            out.add(sky("Cloud coverage", (coverage > 0 ? "+" : "") + Math.round(coverage * 100) + "%", v -> v.cloudCoverage = coverage));
        for (double density : new double[]{.25, 1, 2})
            out.add(sky("Cloud density", Math.round(density * 100) + "%", v -> v.cloudDensity = density));
        for (double scale : new double[]{.5, 1, 2})
            out.add(sky("Cloud block size", Math.round(scale * 100) + "%", v -> v.cloudScale = scale));
        return out;
    }

    private static Case space(String group, String label, Consumer<SpaceSettings.Values> apply, int width)
    {
        return new Case(group, label, false, true, width, apply);
    }

    private static CompoundTag spaceFrame()
    {
        var definition = ComputeSceneClientGameTest.definition(50, List.of(new SpaceDefinition.Volume("nebula", new Vec3(0, 0, 50), new Vec3(60, 40, 80), Vec3.ZERO, 0xff6655cc, .3, .03, .2, .1, 44, 0)));
        var frame = ComputeSceneClientGameTest.frame(definition, new int[32768]);
        int[] hull = new int[32768];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    if (x == 0 || x == 31 || z == 0 || z == 31 || y % 8 == 0 || y == 31)
                        hull[x + z * 32 + y * 1024] = Block.getId((y > 2 && y < 7 ? Blocks.GLASS : Blocks.IRON_BLOCK).defaultBlockState());
        for (int i = 0; i < 2; i++)
        {
            var subject = ((CompoundTag) frame.getListOrEmpty("subjects").getFirst()).copy();
            subject.putString("id", "ship-" + i);
            subject.putString("geometryId", "benchmark-hull-" + i);
            subject.putIntArray("blocks", hull);
            subject.putDouble("x", i == 0 ? -43 : 25);
            subject.putDouble("y", -8);
            subject.putDouble("z", i == 0 ? 55 : 95);
            subject.putDouble("yaw", i == 0 ? 20 : -20);
            frame.getListOrEmpty("subjects").add(subject);
        }
        return frame;
    }

    /** The median GPU time in milliseconds of one scene with one settings change, over a fresh viewport. */
    private static double measure(ClientGameTestContext c, boolean isSky, int width, Consumer<SpaceSettings.Values> apply, CompoundTag skyFrame, CompoundTag spaceFrame, int frames)
    {
        c.runOnClient(mc ->
        {
            SpaceSettings.defaults();
            apply.accept(SpaceSettings.get());
            // The clouds drift with real time, which changes how much cloud is in view and so the cost; hold them still.
            if (isSky)
                SpaceSettings.get().cloudWind = 0;
            SpaceSettings.save();
        });
        var view = new SceneViewport[1];
        c.runOnClient(mc -> view[0] = new SceneViewport(width, width * 9 / 16));
        var gpu = new ArrayList<Double>();
        for (int frame = 0; frame < frames; frame++)
        {
            int i = frame;
            c.runOnClient(mc ->
            {
                // The camera moves a little every frame, so the sky is rebuilt each time, which is the costly case.
                if (isSky)
                {
                    // The compute renderer exists after the first full render.
                    if (view[0].compute() == null)
                        view[0].render(skyFrame, 0, 0, 80);
                    view[0].compute().surfaces(skyFrame, new Vec3(0, .05 * (i % 20), 0), AtmosphereClientGameTest.projection(.3f));
                }
                else
                    view[0].render(spaceFrame, i * .1f, 0, 70);
                var s = view[0].compute().stats();
                double total = s.surfaceMs() + s.atmosphereMs() + s.volumeMs() + s.resolveMs();
                if (i >= Math.min(WARMUP, frames / 2) && total > 0)
                    gpu.add(total);
            });
            c.waitTicks(1);
        }
        c.runOnClient(mc -> view[0].close());
        var ordered = gpu.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return ordered.length == 0 ? -1 : ordered[ordered.length / 2];
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null || !Boolean.getBoolean("nullspace.perf.graphics"))
        {
            ClientTestEvidence.passed(getClass());
            return;
        }
        var results = new ArrayList<Result>();
        var machine = new String[2];
        try (var world = c.worldBuilder().create())
        {
            ClientWaits.chunksRendered(world.getConnection());
            var skyFrame = AtmosphereClientGameTest.scene(AtmosphereProfile.temperate(6360, 100. / 6360), .8, 1);
            var spaceFrame = spaceFrame();
            c.runOnClient(mc ->
            {
                machine[0] = glGetString(GL_RENDERER);
                machine[1] = glGetString(GL_VERSION);
            });
            // Let the GPU reach its working clock speed before anything is measured.
            measure(c, true, 960, v ->
            {}, skyFrame, spaceFrame, 60);
            measure(c, false, 960, v ->
            {}, skyFrame, spaceFrame, 60);
            for (var test : cases())
            {
                for (boolean isSky : new boolean[]{true, false})
                {
                    if (isSky && !test.sky || !isSky && !test.space || isSky && SCENES.equals("space") || !isSky && SCENES.equals("sky"))
                        continue;
                    // A viewport is limited to 2048 pixels wide, so the largest camera feed size is measured at that.
                    int width = Math.min(test.width, 2048);
                    var values = new ArrayList<Double>();
                    var bases = new ArrayList<Double>();
                    var ratios = new ArrayList<Double>();
                    for (int repeat = 0; repeat < REPEATS; repeat++)
                    {
                        double before = measure(c, isSky, 960, v ->
                        {}, skyFrame, spaceFrame, WARMUP + SAMPLES);
                        double value = measure(c, isSky, width, test.apply, skyFrame, spaceFrame, WARMUP + SAMPLES);
                        double after = measure(c, isSky, 960, v ->
                        {}, skyFrame, spaceFrame, WARMUP + SAMPLES);
                        double base = (before + after) / 2;
                        values.add(value);
                        bases.add(base);
                        ratios.add(value / base);
                    }
                    java.util.Collections.sort(values);
                    java.util.Collections.sort(bases);
                    java.util.Collections.sort(ratios);
                    results.add(new Result(test.group, test.label, isSky ? "planet sky" : "space", width, values.get(REPEATS / 2), bases.get(REPEATS / 2), ratios.get(REPEATS / 2), ratios.getFirst(), ratios.getLast()));
                }
            }
            c.runOnClient(mc ->
            {
                SpaceSettings.defaults();
                var report = new JsonObject();
                report.addProperty("gpu", machine[0]);
                report.addProperty("driver", machine[1]);
                report.addProperty("warmupFrames", WARMUP);
                report.addProperty("sampleFrames", SAMPLES);
                report.addProperty("repeats", REPEATS);
                var rows = new JsonArray();
                var tsv = new StringBuilder("group\tsetting\tscene\twidth\tgpu_ms\tdefaults_ms\tratio\tratio_low\tratio_high\n");
                for (var r : results)
                {
                    var row = new JsonObject();
                    row.addProperty("group", r.group);
                    row.addProperty("setting", r.label);
                    row.addProperty("scene", r.scene);
                    row.addProperty("width", r.width);
                    row.addProperty("gpuMs", r.gpuMs);
                    row.addProperty("defaultsMs", r.baselineMs);
                    row.addProperty("ratio", r.ratio);
                    row.addProperty("ratioLow", r.ratioLow);
                    row.addProperty("ratioHigh", r.ratioHigh);
                    rows.add(row);
                    tsv.append(r.group).append('\t').append(r.label).append('\t').append(r.scene).append('\t').append(r.width).append('\t').append(String.format(Locale.ROOT, "%.3f\t%.3f\t%.3f\t%.3f\t%.3f", r.gpuMs, r.baselineMs, r.ratio, r.ratioLow, r.ratioHigh)).append('\n');
                }
                report.add("results", rows);
                try
                {
                    var dir = mc.gameDirectory.toPath();
                    java.nio.file.Files.writeString(dir.resolve("graphics-settings-benchmark.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
                    java.nio.file.Files.writeString(dir.resolve("graphics-settings-benchmark.tsv"), tsv.toString());
                }
                catch (java.io.IOException error)
                {
                    throw new java.io.UncheckedIOException(error);
                }
                System.out.println("GFXBENCH report written: " + results.size() + " measurements on " + machine[0]);
            });
        }
        ClientTestEvidence.passed(getClass());
    }
}
