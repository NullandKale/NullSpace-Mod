package com.nullandkale.nullspace;

import static org.lwjgl.opengl.GL43C.*;

import com.nullandkale.nullspace.client.scene.SceneViewport;
import com.nullandkale.nullspace.client.scene.compute.*;
import com.nullandkale.nullspace.scene.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Numerical GPU assertions and actual native draws, independent of decorative screenshots. */
public final class ComputeSceneClientGameTest implements FabricClientGameTest {
  private static SpaceDefinition definition(long revision, List<SpaceDefinition.Volume> volumes) {
    return new SpaceDefinition(
        "test:gpu",
        revision,
        42,
        1,
        0,
        List.of(
            new SpaceDefinition.Body(
                "planet", 1, new Vec3(0, 0, 100), Vec3.ZERO, 25, 0xff2161aa, 0, 0, 4, .03),
            new SpaceDefinition.Body(
                "sun", 0, new Vec3(-60, 50, 60), Vec3.ZERO, 8, 0xffffbb88, 12, 0, 3, 0),
            new SpaceDefinition.Body(
                "moon", 2, new Vec3(24, 0, 70), Vec3.ZERO, 8, 0xffaaaaaa, 0, 0, 5, 0),
            new SpaceDefinition.Body(
                "rock", 3, new Vec3(-24, -4, 55), Vec3.ZERO, 5, 0xff998877, 0, .17, 91, 0)),
        volumes,
        List.of());
  }

  private static CompoundTag frame(SpaceDefinition definition, int[] blocks) {
    var s = new CompoundTag();
    s.putBoolean("universe", true);
    s.putString("status", "LIVE");
    s.putString("observer", "test");
    s.put("catalog", SpaceDefinitionCodec.encode(definition));
    s.putDouble(
        "simulationTime",
        0); /* Freeze simulation age in numerical fixtures: shader compilation time must not animate the comparison. */
    s.putLong("receivedNanos", Long.MAX_VALUE);
    var subjects = new ListTag();
    var subject = new CompoundTag();
    subject.putString("id", "test");
    subject.putString("geometryId", "gpu-fixture");
    subject.putIntArray("blocks", blocks);
    var light = new byte[32768];
    Arrays.fill(light, (byte) 15);
    subject.putByteArray("light", light);
    subjects.add(subject);
    s.put("subjects", subjects);
    return s;
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static double starContrast(float[] pixels, int width, int height) {
    double peak = 0;
    for (int y = 0; y < height; y++)
      for (int x = 1; x < width - 1; x++) {
        int p = (y * width + x) * 4;
        peak = Math.max(peak, Math.abs(pixels[p - 4] - 2 * pixels[p] + pixels[p + 4]));
      }
    return peak;
  }

  private static void checkDaytimeStars() {
    boolean clouds = com.nullandkale.nullspace.client.SpaceSettings.get().clouds;
    com.nullandkale.nullspace.client.SpaceSettings.get().clouds = false;
    int width = 129, height = 97;
    var projection =
        new org.joml.Matrix4f()
            .perspective((float) Math.toRadians(70), (float) width / height, 1000000, .05f, true)
            .lookAt(0, 0, 0, -1, 0, 0, 0, 1, 0);
    double[] contrast = new double[3];
    float[] clearSpace = null;
    try (var view = new SceneViewport(width, height)) {
      double[] elevations = {-.3, -.06, .7};
      for (int phase = 0; phase < elevations.length; phase++) {
        double elevation = elevations[phase];
        var sun =
            new SpaceDefinition.Body(
                "sun",
                0,
                new Vec3(0, Math.sin(elevation) * 1000, Math.cos(elevation) * 1000),
                Vec3.ZERO,
                10,
                0xffffd07a,
                12,
                0,
                3,
                0);
        var scene =
            frame(
                new SpaceDefinition(
                    "test:daytime-stars", phase + 1, 42, 1, 0, List.of(sun), List.of(), List.of()),
                new int[32768]);
        view.render(scene, 0, 0, 70);
        view.compute().surfaces(scene, Vec3.ZERO, projection);
        var airless = view.compute().inspectRadiance();
        if (clearSpace == null) clearSpace = airless;
        else
          for (int p = 0; p < airless.length; p++)
            check(
                Math.abs(clearSpace[p] - airless[p]) < .0001,
                "Stars in space and airless views do not depend on sun elevation");
        scene.putBoolean("surfaceAtmosphere", true);
        view.compute().surfaces(scene, Vec3.ZERO, projection);
        contrast[phase] = starContrast(view.compute().inspectRadiance(), width, height);
      }
    }
    com.nullandkale.nullspace.client.SpaceSettings.get().clouds = clouds;
    com.nullandkale.nullspace.NullSpace.LOGGER.info(
        "Atmosphere star contrast: {}", java.util.Arrays.toString(contrast));
    check(contrast[0] > .01, "The night fixture contains visible star detail");
    check(
        contrast[1] < contrast[0] * .8 && contrast[1] > contrast[0] * .1,
        "Stars fade gradually through atmospheric twilight");
    check(
        contrast[2] < contrast[0] * .05,
        "Atmospheric daytime sky contains no bright star speckles");
  }

  private static void checkSurfaceWeather() {
    var sun =
        new SpaceDefinition.Body(
            "sun", 0, new Vec3(0, 100, 100), Vec3.ZERO, 12, 0xffffd07a, 12, 0, 3, 0);
    var scene =
        frame(
            new SpaceDefinition("test:weather", 1, 42, 1, 0, List.of(sun), List.of(), List.of()),
            new int[32768]);
    var projection =
        new org.joml.Matrix4f()
            .perspective((float) Math.toRadians(70), 1, 1000000, .05f, true)
            .lookAt(0, 0, 0, 0, 1, 1, 0, 1, 0);
    int pixel = (16 * 33 + 16) * 4;
    try (var view = new SceneViewport(33, 33)) {
      view.render(scene, 0, 0, 70);
      view.compute().surfaces(scene, Vec3.ZERO, projection);
      var space = view.compute().inspectRadiance();
      scene.putBoolean("surfaceAtmosphere", true);
      view.compute().surfaces(scene, Vec3.ZERO, projection);
      var clear = view.compute().inspectRadiance();
      check(
          clear[pixel + 2] > space[pixel + 2] * 2,
          "Clear daytime sun must retain bright HDR emission through the atmosphere");
      long resets = view.compute().historyResets();
      scene.putDouble("surfaceRain", 1);
      view.compute().surfaces(scene, Vec3.ZERO, projection);
      var rain = view.compute().inspectRadiance();
      check(
          rain[pixel] < clear[pixel] * .1 && rain[pixel + 2] < clear[pixel + 2] * .1,
          "Fully overcast skies must obscure the bright celestial disk");
      check(
          view.compute().historyResets() > resets,
          "Weather changes reject stale clear-sky history");
      scene.putDouble("surfaceThunder", 1);
      view.compute().surfaces(scene, Vec3.ZERO, projection);
      var thunder = view.compute().inspectRadiance();
      check(thunder[pixel] < rain[pixel] * .9, "Thunderstorms darken the overcast sky");
      scene.putBoolean("surfaceAtmosphere", false);
      view.compute().surfaces(scene, Vec3.ZERO, projection);
      var airless = view.compute().inspectRadiance();
      check(
          Math.abs(airless[pixel] - space[pixel]) < .01,
          "Airless surfaces and space ignore atmospheric weather and sun boosting");
      scene.putBoolean("surfaceAtmosphere", true);
      scene.getListOrEmpty("subjects").getCompound(0).orElseThrow().putDouble("pitch", 180);
      view.compute().surfaces(scene, Vec3.ZERO, projection);
      check(
          view.compute().inspectRadiance()[pixel] < thunder[pixel] * .1,
          "Nighttime storm skies must remain dark");
      var level = net.minecraft.client.Minecraft.getInstance().level;
      float oldRain = level.getRainLevel(1), oldThunder = level.getThunderLevel(1);
      try {
        scene.getListOrEmpty("subjects").getCompound(0).orElseThrow().putDouble("pitch", 0);
        scene.putBoolean("planetSky", true);
        scene.putString("dimension", level.dimension().identifier().toString());
        scene.putDouble("surfaceRain", 0);
        scene.putDouble("surfaceThunder", 0);
        level.setRainLevel(1);
        level.setThunderLevel(1);
        view.compute().surfaces(scene, Vec3.ZERO, projection);
        check(
            Math.abs(view.compute().inspectRadiance()[pixel] - thunder[pixel]) < .005,
            "A live planetary viewport samples client weather rather than stale scene fields");
        scene.putString("dimension", "nullspace:unrelated-surface");
        view.compute().surfaces(scene, Vec3.ZERO, projection);
        check(
            view.compute().inspectRadiance()[pixel] > 1,
            "Local rain cannot darken a camera rendering another dimension");
      } finally {
        level.setRainLevel(oldRain);
        level.setThunderLevel(oldRain > 0 ? oldThunder / oldRain : 0);
      }
    }
  }

  @Override
  public void runTest(ClientGameTestContext context) {
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.runOnClient(
          client -> {
            String previousJitter = System.getProperty("nullspace.compute.noJitter");
            System.setProperty("nullspace.compute.noJitter", "true");
            String previousScale = System.getProperty("nullspace.compute.volumeScale");
            System.setProperty("nullspace.compute.volumeScale", "0.5");
            try {
              int live = SpaceComputeRenderer.liveViews();
              checkDaytimeStars();
              checkSurfaceWeather();
              String brute = System.getProperty("nullspace.compute.bruteForce");
              var gas =
                  new SpaceDefinition.Volume(
                      "gas",
                      new Vec3(0, 0, 50),
                      new Vec3(25, 25, 25),
                      Vec3.ZERO,
                      0xff6655cc,
                      .5,
                      .08,
                      .4,
                      .1,
                      44,
                      0);
              var dust =
                  new SpaceDefinition.Volume(
                      "dust",
                      new Vec3(3, 0, 54),
                      new Vec3(17, 20, 19),
                      Vec3.ZERO,
                      0xffb47842,
                      .2,
                      .05,
                      .1,
                      -.1,
                      92,
                      0);
              try (var view = new SceneViewport(65, 49);
                  var reference = new SceneViewport(65, 49)) {
                var scene = frame(definition(1, List.of(gas, dust)), new int[32768]);
                view.render(scene, 0, 0, 70);
                float[] accelerated = view.compute().inspectHits();
                int center = (24 * 65 + 32) * 4;
                check(
                    Math.abs(accelerated[center] - 75) < .01 && accelerated[center + 1] == 0,
                    "Analytic GPU center hit must be the planet at distance 75");
                System.setProperty("nullspace.compute.bruteForce", "true");
                reference.render(scene, 0, 0, 70);
                float[] expected = reference.compute().inspectHits();
                for (int i = 0; i < expected.length; i += 4) {
                  check(
                      Math.abs(expected[i] - accelerated[i]) < .02
                          && expected[i + 1] == accelerated[i + 1],
                      "GPU BVH/brute-force disagreement at " + i / 4);
                }
                System.setProperty("nullspace.compute.bruteForce", "false");
                float[] prefix = view.compute().inspectPrefixes();
                int centerOptical =
                    ((SpaceComputeRenderer.SLICES - 1) * 25 * 33 + 12 * 33 + 16) * 4;
                check(
                    prefix[centerOptical + 3] > 0 && prefix[centerOptical + 3] < .99,
                    "Bounded gas must produce finite nonzero attenuation");
                var reversed = frame(definition(2, List.of(dust, gas)), new int[32768]);
                reference.render(reversed, 0, 0, 70);
                float[] reversedPrefix = reference.compute().inspectPrefixes();
                for (int i = 0; i < prefix.length; i++)
                  check(
                      Math.abs(prefix[i] - reversedPrefix[i]) < .004,
                      "Overlapping volumes depend on definition order at " + i);
                var projection =
                    new org.joml.Matrix4f()
                        .perspective((float) Math.toRadians(70), 65f / 49, 1000000, .05f, true)
                        .lookAt(0, 0, 0, 0, 0, 1, 0, 1, 0);
                view.compute().surfaces(scene, Vec3.ZERO, projection);
                long reused = view.compute().historyReuses();
                view.compute().surfaces(scene, Vec3.ZERO, projection);
                check(
                    view.compute().historyReuses() > reused,
                    "Stable view should reuse temporal history");
                long resets = view.compute().historyResets();
                view.compute().surfaces(scene, new Vec3(50, 0, 0), projection);
                check(view.compute().historyResets() > resets, "Camera cut must reject history");
                check(
                    !SpaceComputeRenderer.reload(
                        Map.of("surface", "#version 430\nthis_is_intentionally_invalid")),
                    "Bad reload must be rejected");
                view.compute().surfaces(scene, Vec3.ZERO, projection);
                check(
                    SpaceComputeRenderer.reload(),
                    "Valid reload must replace the complete shader set");
                long channelResets = view.compute().historyResets();
                var switched = SceneViewport.cameraView(scene);
                switched.putInt("selection", 1);
                view.compute().surfaces(switched, Vec3.ZERO, projection);
                check(
                    view.compute().historyResets() > channelResets,
                    "Channel changes must reset history even at the same camera pose");
                long fovResets = view.compute().historyResets();
                view.compute()
                    .surfaces(
                        scene,
                        Vec3.ZERO,
                        new org.joml.Matrix4f()
                            .perspective((float) Math.toRadians(90), 65f / 49, 1000000, .05f, true)
                            .lookAt(0, 0, 0, 0, 0, 1, 0, 1, 0));
                check(view.compute().historyResets() > fovResets, "FOV changes must reset history");
                view.render(scene, 5, 0, 70);
                view.render(scene, 10, 0, 70);
                check(
                    view.compute().stats().bytes() < 32L * 1024 * 1024,
                    "Small compute targets must respect memory budget");
                NullSpace.LOGGER.info(
                    "Compute image regression and BVH reference passed: {}",
                    view.compute().stats());
              } finally {
                if (brute == null) System.clearProperty("nullspace.compute.bruteForce");
                else System.setProperty("nullspace.compute.bruteForce", brute);
              }
              // Native walking bob translates the eye. It must never swing compute
              // rays toward that tiny near-plane offset or roll space around the hull.
              try (var walking = new SceneViewport(33, 33)) {
                var planet =
                    new SpaceDefinition.Body(
                        "stationary",
                        1,
                        new Vec3(0, 0, 100),
                        Vec3.ZERO,
                        25,
                        0xff2161aa,
                        0,
                        0,
                        4,
                        0);
                var scene =
                    frame(
                        new SpaceDefinition(
                            "test:walk-bob", 1, 42, 1, 0, List.of(planet), List.of(), List.of()),
                        new int[32768]);
                boolean zeroOne =
                    com.mojang.blaze3d.systems.RenderSystem.getDevice()
                        .getDeviceInfo()
                        .isZZeroToOne();
                var nativeProjection =
                    new org.joml.Matrix4f()
                        .perspective((float) Math.toRadians(70), 1, 1024, .05f, zeroOne);
                var nativeView = new org.joml.Matrix4f().rotationY((float) Math.PI);
                for (int i = 0; i < 12; i++) {
                  float phase = i * (float) Math.PI / 6;
                  var effects =
                      new org.joml.Matrix4f()
                          .translate(
                              (float) Math.sin(phase) * .2f,
                              -Math.abs((float) Math.cos(phase)) * .15f,
                              0)
                          .rotateZ((float) Math.sin(phase) * .05f)
                          .rotateX(Math.abs((float) Math.cos(phase)) * .04f);
                  var original =
                      new org.joml.Matrix4f(nativeProjection).mul(effects).mul(nativeView);
                  var aligned =
                      SceneCamera.compose(
                          nativeProjection,
                          new org.joml.Matrix4f(nativeProjection).mul(effects),
                          nativeView,
                          new org.joml.Quaterniond(),
                          zeroOne);
                  var shift = aligned.eyeOffset();
                  var eye = new Vec3(shift.x, shift.y, shift.z);
                  var inverse = new org.joml.Matrix4f(original).invert();
                  var near = inverse.transformProject(new org.joml.Vector3f(0, 0, 1));
                  var far = inverse.transformProject(new org.joml.Vector3f(0, 0, .5f));
                  var direction = far.sub(near).normalize();
                  double expected =
                      SpaceShapes.intersect(
                          eye, new Vec3(direction.x, direction.y, direction.z), planet, 0, 1e6);
                  scene.putDouble("cameraX", eye.x);
                  scene.putDouble("cameraY", eye.y);
                  scene.putDouble("cameraZ", eye.z);
                  walking.render(scene, 0, 0, 70, aligned.projection());
                  float[] hits = walking.compute().inspectHits();
                  int center = (16 * 33 + 16) * 4;
                  check(
                      hits[center + 1] == 0 && Math.abs(hits[center] - expected) < .02,
                      "Walking-bob GPU ray diverged from native hull camera at phase "
                          + i
                          + ": "
                          + hits[center]
                          + " vs "
                          + expected);
                }
              }
              try (var stars = new SceneViewport(65, 49)) {
                String saved = System.getProperty("nullspace.compute.noJitter");
                System.clearProperty("nullspace.compute.noJitter");
                try {
                  var scene =
                      frame(
                          new SpaceDefinition(
                              "test:stable-stars", 1, 42, 1, 0, List.of(), List.of(), List.of()),
                          new int[32768]);
                  stars.render(scene, 0, 0, 70);
                  var projection =
                      new org.joml.Matrix4f()
                          .perspective((float) Math.toRadians(70), 65f / 49, 1000000, .05f, true)
                          .lookAt(0, 0, 0, 0, 0, 1, 0, 1, 0);
                  stars.compute().surfaces(scene, Vec3.ZERO, projection);
                  float[] stable = stars.compute().inspectRadiance();
                  for (int f = 0; f < 4; f++) {
                    stars.compute().surfaces(scene, Vec3.ZERO, projection);
                    float[] next = stars.compute().inspectRadiance();
                    for (int i = 0; i < stable.length; i++)
                      check(
                          stable[i] == next[i],
                          "Stationary stars must not shimmer under default sampling at " + i);
                  }
                } finally {
                  if (saved == null) System.clearProperty("nullspace.compute.noJitter");
                  else System.setProperty("nullspace.compute.noJitter", saved);
                }
              }
              try (var large = new SceneViewport(65, 49)) {
                var original = definition(30, List.of());
                var shift = new Vec3(1e11, -1e11, 1e11);
                var shifted =
                    original.bodies().stream()
                        .map(
                            b ->
                                new SpaceDefinition.Body(
                                    b.id(),
                                    b.kind(),
                                    b.center().add(shift),
                                    b.velocity(),
                                    b.radius(),
                                    b.color(),
                                    b.emission(),
                                    b.roughness(),
                                    b.seed(),
                                    b.atmosphere(),
                                    b.rotationAxis(),
                                    b.spin()))
                        .toList();
                var scene =
                    frame(
                        new SpaceDefinition(
                            original.id(), 30, 42, 1, 0, shifted, List.of(), List.of()),
                        new int[32768]);
                scene.putDouble("cameraX", shift.x);
                scene.putDouble("cameraY", shift.y);
                scene.putDouble("cameraZ", shift.z);
                large.render(scene, 0, 0, 70);
                int center = (24 * 65 + 32) * 4;
                check(
                    Math.abs(large.compute().inspectHits()[center] - 75) < .02,
                    "GPU camera-relative precision must survive 1e11 origins");
                var sunDirection = new Vec3(-60, 50, 60).normalize();
                var projection =
                    new org.joml.Matrix4f()
                        .perspective((float) Math.toRadians(70), 65f / 49, 1000000, .05f, true)
                        .lookAt(
                            0,
                            0,
                            0,
                            (float) sunDirection.x,
                            (float) sunDirection.y,
                            (float) sunDirection.z,
                            0,
                            1,
                            0);
                large.compute().surfaces(scene, shift, projection);
                float[] hit = large.compute().inspectHits();
                check(
                    hit[center + 1] == 1
                        && Math.abs(hit[center] - (new Vec3(-60, 50, 60).length() - 8)) < .03,
                    "Nonzero body indices must retain std430 material/position offsets");
              }
              try (var moving = new SceneViewport(33, 33)) {
                var comet =
                    new SpaceDefinition.Body(
                        "moving-comet",
                        4,
                        new Vec3(0, 0, 30),
                        new Vec3(0, 0, 1),
                        2,
                        0xffaabbcc,
                        0,
                        .1,
                        9,
                        0,
                        new Vec3(1, 2, 3),
                        .2);
                var scene =
                    frame(
                        new SpaceDefinition(
                            "test:motion", 1, 42, 1, 0, List.of(comet), List.of(), List.of()),
                        new int[32768]);
                moving.render(scene, 0, 0, 70);
                float first = moving.compute().inspectHits()[(16 * 33 + 16) * 4];
                long resets = moving.compute().historyResets();
                scene = SceneViewport.cameraView(scene);
                scene.putDouble("simulationTime", 10);
                moving.render(scene, 0, 0, 70);
                float advanced = moving.compute().inspectHits()[(16 * 33 + 16) * 4];
                double expected =
                    SpaceShapes.intersect(Vec3.ZERO, new Vec3(0, 0, 1), comet, 10, 1e6);
                check(
                    advanced > first + 8 && Math.abs(advanced - expected) < .02,
                    "Moving rotating comet must match the authoritative procedural shape");
                check(
                    moving.compute().stats().volumes() == 1,
                    "Moving comet must generate one bounded tail");
                check(
                    moving.compute().historyResets() > resets,
                    "A simulation time discontinuity must reject temporal history");
              }
              check(
                  SpaceComputeRenderer.liveViews() == live,
                  "Closed views must release renderer ownership");
              int[] hull = new int[32768];
              for (int y = 2; y <= 8; y++)
                for (int z = 2; z <= 8; z++)
                  for (int x = 2; x <= 8; x++)
                    if (x == 2 || x == 8 || y == 2 || y == 8 || z == 2 || z == 8)
                      hull[x + z * 32 + y * 1024] = Block.getId(Blocks.STONE.defaultBlockState());
              var enclosedGas =
                  new SpaceDefinition.Volume(
                      "inside",
                      new Vec3(5.5, 5.5, 5.5),
                      new Vec3(20, 20, 20),
                      Vec3.ZERO,
                      0xff9955aa,
                      1,
                      10,
                      .2,
                      0,
                      8,
                      0);
              try (var view = new SceneViewport(33, 33)) {
                var closed = frame(definition(5, List.of(enclosedGas)), hull);
                closed.putDouble("cameraX", 5.5);
                closed.putDouble("cameraY", 5.5);
                closed.putDouble("cameraZ", 5.5);
                view.render(closed, 0, 0, 70);
                float[] optical = view.compute().inspectPrefixes();
                int middle = ((SpaceComputeRenderer.SLICES - 1) * 17 * 17 + 8 * 17 + 8) * 4;
                check(
                    Math.abs(optical[middle + 3] - 1) < .001,
                    "Outdoor gas must be excluded from sealed cabin cells");
                int[] breached = hull.clone();
                breached[5 + 8 * 32 + 5 * 1024] = 0;
                var open = frame(definition(6, List.of(enclosedGas)), breached);
                open.putDouble("cameraX", 5.5);
                open.putDouble("cameraY", 5.5);
                open.putDouble("cameraZ", 5.5);
                view.render(open, 0, 0, 70);
                check(
                    view.compute().inspectPrefixes()[middle + 3] < .99,
                    "A hull breach must invalidate the sealed visual mask");
              }
              check(glGetError() == GL_NO_ERROR, "Compute scene test left an OpenGL error");
            } finally {
              if (previousJitter == null) System.clearProperty("nullspace.compute.noJitter");
              else System.setProperty("nullspace.compute.noJitter", previousJitter);
              if (previousScale == null) System.clearProperty("nullspace.compute.volumeScale");
              else System.setProperty("nullspace.compute.volumeScale", previousScale);
            }
          });
      var surviving = new SceneViewport[1];
      var reload =
          new java.util.concurrent.atomic.AtomicReference<
              java.util.concurrent.CompletableFuture<Void>>();
      context.runOnClient(
          client -> {
            surviving[0] = new SceneViewport(81, 47);
            surviving[0].render(frame(definition(40, List.of()), new int[32768]), 0, 0, 70);
            reload.set(client.reloadResourcePacks());
          });
      context.waitFor(client -> reload.get().isDone(), 6000);
      context.runOnClient(
          client -> {
            try {
              reload.get().join();
              surviving[0].render(frame(definition(41, List.of()), new int[32768]), 0, 0, 85);
              check(glGetError() == GL_NO_ERROR, "Resource reload/resize left an OpenGL error");
            } finally {
              surviving[0].close();
            }
          });
      context.waitTicks(5);
      context.takeScreenshot("nullspace-compute-regression-world");
      var benchmark = new SceneViewport[1];
      var fixture = new CompoundTag[1];
      var monitors = new SceneViewport[2];
      var samples = new ArrayList<double[]>();
      var quadAllocations = new long[3];
      context.runOnClient(
          client -> {
            benchmark[0] = new SceneViewport(640, 360);
            var definition =
                definition(
                    50,
                    List.of(
                        new SpaceDefinition.Volume(
                            "nebula",
                            new Vec3(0, 0, 50),
                            new Vec3(60, 40, 80),
                            Vec3.ZERO,
                            0xff6655cc,
                            .3,
                            .03,
                            .2,
                            .1,
                            44,
                            0)));
            fixture[0] = frame(definition, new int[32768]);
            int[] hull = new int[32768];
            for (int y = 0; y < 32; y++)
              for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                  if (x == 0 || x == 31 || z == 0 || z == 31 || y % 8 == 0 || y == 31)
                    hull[x + z * 32 + y * 1024] =
                        Block.getId(
                            (y > 2 && y < 7 ? Blocks.GLASS : Blocks.IRON_BLOCK)
                                .defaultBlockState());
            for (int i = 0; i < 2; i++) {
              var subject = ((CompoundTag) fixture[0].getListOrEmpty("subjects").getFirst()).copy();
              subject.putString("id", "ship-" + i);
              subject.putString("geometryId", "benchmark-hull-" + i);
              subject.putIntArray("blocks", hull);
              subject.putDouble("x", i == 0 ? -43 : 25);
              subject.putDouble("y", -8);
              subject.putDouble("z", i == 0 ? 55 : 95);
              subject.putDouble("yaw", i == 0 ? 20 : -20);
              fixture[0].getListOrEmpty("subjects").add(subject);
              monitors[i] = new SceneViewport(320, 180);
              monitors[i].render(fixture[0], i == 0 ? -10 : 10, 0, 70);
            }
          });
      try {
        for (int i = 0; i < 70; i++) {
          int frame = i;
          context.runOnClient(
              client -> {
                benchmark[0].render(fixture[0], frame * .1f, 0, 70);
                int selected = frame % 2;
                monitors[selected].render(
                    fixture[0], frame * .1f + (selected == 0 ? -10 : 10), 0, 70);
                if (frame == 25) {
                  quadAllocations[0] = benchmark[0].transformedQuadsCreated();
                  quadAllocations[1] = monitors[0].transformedQuadsCreated();
                  quadAllocations[2] = monitors[1].transformedQuadsCreated();
                }
                if (frame > 25) {
                  check(
                      benchmark[0].transformedQuadsCreated() == quadAllocations[0]
                          && monitors[0].transformedQuadsCreated() == quadAllocations[1]
                          && monitors[1].transformedQuadsCreated() == quadAllocations[2],
                      "Moving views must reuse transformed quad storage after warming up");
                }
                var s = benchmark[0].compute().stats();
                var a = monitors[0].compute().stats();
                var b = monitors[1].compute().stats();
                if (frame >= 20 && s.surfaceMs() > 0)
                  samples.add(
                      new double[] {
                        s.surfaceMs(),
                        s.volumeMs(),
                        s.resolveMs(),
                        s.cpuMs(),
                        a.surfaceMs()
                            + a.atmosphereMs()
                            + a.volumeMs()
                            + a.resolveMs()
                            + b.surfaceMs()
                            + b.atmosphereMs()
                            + b.volumeMs()
                            + b.resolveMs(),
                        (selected == 0 ? a : b).surfaceMs()
                            + (selected == 0 ? a : b).atmosphereMs()
                            + (selected == 0 ? a : b).volumeMs()
                            + (selected == 0 ? a : b).resolveMs(),
                        s.volumeIntegrationMs(),
                        s.volumeReconstructionMs(),
                        s.atmosphereMs()
                      });
              });
          context.waitTicks(1);
        }
        context.runOnClient(
            client -> {
              try {
                var report = new com.google.gson.JsonObject();
                report.addProperty("gpu", glGetString(GL_RENDERER));
                report.addProperty("driver", glGetString(GL_VERSION));
                report.addProperty("width", 640);
                report.addProperty("height", 360);
                report.addProperty("samples", samples.size());
                report.addProperty(
                    "fixture",
                    "sun, planet with atmosphere, moon, rough rock, nebula, two 32-cube four-deck"
                        + " hulls, moving camera, two 320x180 monitor captures staggered one per"
                        + " frame");
                report.addProperty("volume_width", benchmark[0].compute().volumeWidth());
                report.addProperty("volume_height", benchmark[0].compute().volumeHeight());
                report.addProperty("resident_bytes", benchmark[0].compute().stats().bytes());
                report.addProperty(
                    "all_views_resident_bytes",
                    benchmark[0].compute().stats().bytes()
                        + monitors[0].compute().stats().bytes()
                        + monitors[1].compute().stats().bytes());
                String[] names = {
                  "surface_ms",
                  "volume_ms",
                  "resolve_ms",
                  "cpu_submission_ms",
                  "two_monitors_capture_ms",
                  "scheduled_monitors_ms",
                  "volume_integration_ms",
                  "volume_reconstruction_ms",
                  "atmosphere_ms"
                };
                for (int j = 0; j < names.length; j++) {
                  int field = j;
                  double[] ordered = samples.stream().mapToDouble(a -> a[field]).sorted().toArray();
                  if (ordered.length > 0) {
                    report.addProperty(names[j] + "_median", ordered[ordered.length / 2]);
                    report.addProperty(
                        names[j] + "_p95", ordered[(int) ((ordered.length - 1) * .95)]);
                  }
                }
                var path = client.gameDirectory.toPath().resolve("compute-performance.json");
                java.nio.file.Files.writeString(
                    path,
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                NullSpace.LOGGER.info("Compute performance report {}", report);
              } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            });
        var captured = new java.util.concurrent.atomic.AtomicBoolean();
        context.runOnClient(
            client ->
                net.minecraft.client.Screenshot.takeScreenshot(
                    benchmark[0].target,
                    image -> {
                      try {
                        image.writeToFile(
                            client.gameDirectory.toPath().resolve("compute-populated-scene.png"));
                        captured.set(true);
                      } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                      } finally {
                        image.close();
                      }
                    }));
        context.waitFor(client -> captured.get(), 200);
      } finally {
        context.runOnClient(
            client -> {
              benchmark[0].close();
              for (var monitor : monitors) if (monitor != null) monitor.close();
            });
      }
    }
    ClientTestEvidence.passed(getClass());
  }
}
