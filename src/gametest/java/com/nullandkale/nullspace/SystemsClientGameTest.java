package com.nullandkale.nullspace;

import com.nullandkale.nullspace.client.*;
import com.nullandkale.nullspace.client.scene.*;
import com.nullandkale.nullspace.dev.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** Role-specific controller session, real powered camera lease, and an independent GPU capture. */
public final class SystemsClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = context.worldBuilder().create()) {
      var base =
          world
              .getServer()
              .computeOnServer(
                  server -> {
                    try {
                      return SphereLab.ensure(server, ShipGallery.ensure(server)).base();
                    } catch (java.io.IOException e) {
                      throw new java.io.UncheckedIOException(e);
                    }
                  });
      var engineBacking =
          world
              .getServer()
              .computeOnServer(server -> server.overworld().getBlockState(base.offset(13, 6, 21)));
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.overworld();
                level.setBlock(
                    base.offset(10, 6, 20),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    18);
                level.setBlock(
                    base.offset(10, 6, 21),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    18);
                for (int x = 14; x <= 19; x++)
                  level.setBlock(
                      base.offset(x, 6, 21),
                      new net.minecraft.world.level.block.Block[] {
                            ShipBlocks.GENERATOR,
                            ShipBlocks.BATTERY,
                            ShipBlocks.AIR_SUPPLY,
                            ShipBlocks.LOCAL_SCANNER,
                            ShipBlocks.LOCAL_SCANNER,
                            ShipBlocks.MONITOR
                          }
                          [x - 14].defaultBlockState()
                          .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                      18);
                level.setBlock(
                    base.offset(21, 6, 21),
                    ShipBlocks.MONITOR
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                    18);
                level.setBlock(
                    base.offset(19, 7, 21),
                    ShipBlocks.MONITOR
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                    18);
                level.setBlock(
                    base.offset(16, 7, 21),
                    ShipBlocks.CAMERA
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                    18);
                level.setBlock(
                    base.offset(17, 7, 21),
                    ShipBlocks.CAMERA
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                    18);
                level.setBlock(
                    base.offset(12, 6, 21),
                    ShipBlocks.WEAPON
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                    18);
                level.setBlock(
                    base.offset(13, 6, 21),
                    ShipBlocks.ENGINE
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                    18);
                level.setBlock(
                    base.offset(15, 6, 20),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    18);
                level.setBlock(
                    SphereLab.controller(base),
                    ShipBlocks.COMPUTER
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                    18);
                FuelSystem.provision(level, base.offset(14, 6, 21), 64);
                var ship = ShipTopology.resolve(level, SphereLab.controller(base));
                ShipSystems.upgrade(level, ship);
                ship.getCompoundOrEmpty("systems")
                    .putIntArray("requests", new int[] {1, 2, 1, 1, 2, 0});
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(
                    level,
                    base.getX() + 16.5,
                    base.getY() + 16,
                    base.getZ() + 23.5,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
              });
      world.getConnection().waitForChunksRender();
      context.waitTicks(20);
      world
          .getServer()
          .runOnServer(
              server ->
                  ShipSystems.action(
                      world.getConnection().getServerPlayer(),
                      SphereLab.controller(base),
                      com.nullandkale.nullspace.terminal.ComputerTerminal.Action.SYSTEMS));
      context.waitFor(
          c -> c.gui.screen() instanceof TerminalScreen t && t.status().contains("Generation 8"),
          200);
      world
          .getServer()
          .runOnServer(
              server ->
                  ShipSystems.action(
                      world.getConnection().getServerPlayer(),
                      SphereLab.controller(base),
                      com.nullandkale.nullspace.terminal.ComputerTerminal.Action.CONFIGURE,
                      "rooms"));
      context.waitFor(
          c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("ROOMS"), 100);
      context.waitTicks(25);
      context.runOnClient(
          c -> {
            if (!(c.gui.screen() instanceof TerminalScreen t) || !t.status().startsWith("ROOMS"))
              throw new AssertionError("Automatic readings must retain the selected room page");
          });
      context.takeScreenshot("nullspace-room-telemetry");
      world
          .getServer()
          .runOnServer(
              server ->
                  ShipSystems.action(
                      world.getConnection().getServerPlayer(),
                      SphereLab.controller(base),
                      com.nullandkale.nullspace.terminal.ComputerTerminal.Action.SYSTEMS));
      context.waitFor(
          c -> c.gui.screen() instanceof TerminalScreen t && t.status().contains("Generation 8"),
          100);
      world
          .getServer()
          .runOnServer(
              server ->
                  ShipTopology.resolve(server.overworld(), SphereLab.controller(base))
                      .getCompoundOrEmpty("systems")
                      .putBoolean("supplyDisabled", true));
      context.waitFor(
          c -> c.gui.screen() instanceof TerminalScreen t && t.status().contains("Generation 0"),
          200);
      world
          .getServer()
          .runOnServer(
              server ->
                  ShipTopology.resolve(server.overworld(), SphereLab.controller(base))
                      .getCompoundOrEmpty("systems")
                      .putBoolean("supplyDisabled", false));
      context.waitFor(
          c -> c.gui.screen() instanceof TerminalScreen t && t.status().contains("Generation 8"),
          200);
      context.takeScreenshot("nullspace-systems-controller");
      context.runOnClient(c -> c.gui.setScreen(null));
      context.waitTicks(40);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var owner = SceneService.observer(p);
                var direct = ShipTopology.resolve(server.overworld(), SphereLab.controller(base));
                var blocks = ShipTopology.discover(server.overworld(), direct);
                if (owner == null
                    || !owner.getStringOr("id", "").equals(direct.getStringOr("id", ""))
                    || !blocks.contains(base.offset(19, 6, 21)))
                  throw new AssertionError(
                      "Display membership: player="
                          + p.position()
                          + " observer="
                          + (owner == null ? "none" : owner.getStringOr("id", ""))
                          + " owner="
                          + direct.getStringOr("id", "")
                          + " base="
                          + ShipStore.pos(direct, "base")
                          + " monitorConnected="
                          + blocks.contains(base.offset(19, 6, 21)));
              });
      context.runOnClient(
          c -> {
            if (MonitorRenderer.preparedCount() == 0)
              throw new AssertionError("No installed display: " + MonitorRenderer.status());
          });
      world
          .getServer()
          .runOnServer(
              server ->
                  world
                      .getConnection()
                      .getServerPlayer()
                      .teleportTo(
                          server.overworld(),
                          base.getX() + 18.5,
                          base.getY() + 6,
                          base.getZ() + 19.5,
                          java.util.Set.of(),
                          0,
                          0,
                          true));
      world.getConnection().waitForChunksRender();
      context.waitTicks(10);
      context.getInput().lookAt(base.offset(19, 6, 21));
      context.waitTicks(10);
      context.takeScreenshot("nullspace-placed-monitor");
      var monitorFrames = new long[2];
      context.runOnClient(
          c -> {
            if (MonitorRenderer.preparedCount() != 2)
              throw new AssertionError(
                  "Two separate monitor walls must remain installed and prepared");
            for (int slot = 0; slot < 2; slot++)
              monitorFrames[slot] = MonitorRenderer.capturedFrames(slot);
          });
      context.waitTicks(10);
      context.runOnClient(
          c -> {
            for (int slot = 0; slot < 2; slot++)
              if (MonitorRenderer.capturedFrames(slot) <= monitorFrames[slot])
                throw new AssertionError("Both staggered monitor captures must advance");
            if (MonitorRenderer.lastCaptureCount() > 1)
              throw new AssertionError("Steady monitor work must stay at one capture per frame");
          });
      context.runOnClient(c -> c.options.bobView().set(true));
      context.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_D);
      try {
        context.waitTicks(6);
        context.runOnClient(
            c -> {
              if (c.gameRenderer.gameRenderState()
                          .levelRenderState
                          .cameraRenderState
                          .entityRenderState
                          .bob
                      <= 0
                  || MonitorRenderer.preparedCount() == 0)
                throw new AssertionError(
                    "Moving monitor check needs native view bobbing and an installed display");
            });
        context.takeScreenshot("nullspace-placed-monitor-moving");
      } finally {
        context.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_D);
      }
      // Exercise native block interaction and the typed menu packets, not direct service calls.
      int[][] devices = {{12, 6}, {13, 6}, {14, 6}, {15, 6}, {16, 6}, {17, 6}, {16, 7}, {19, 6}};
      String[] modes = {
        "weapon",
        "engine",
        "generator",
        "battery",
        "air_supply",
        "door_control",
        "camera",
        "monitor"
      };
      for (int i = 0; i < devices.length; i++) {
        var pos = base.offset(devices[i][0], devices[i][1], 21);
        String mode = modes[i];
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  p.teleportTo(
                      server.overworld(),
                      pos.getX() + .5,
                      base.getY() + 6,
                      pos.getZ() - 2,
                      java.util.Set.of(),
                      0,
                      0,
                      true);
                });
        context.waitFor(
            c ->
                c.player
                        .position()
                        .distanceToSqr(
                            new net.minecraft.world.phys.Vec3(
                                pos.getX() + .5, base.getY() + 6, pos.getZ() - 2))
                    < .1,
            100);
        context.waitTicks(4);
        context.getInput().lookAt(pos);
        context.waitTicks(2);
        try {
          context.waitFor(
              c ->
                  c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                      && hit.getBlockPos().equals(pos),
              100);
        } catch (AssertionError miss) {
          context.takeScreenshot("equipment-missed-" + mode);
          context.runOnClient(
              c -> {
                throw new AssertionError(
                    "Cannot target "
                        + mode
                        + " at "
                        + pos
                        + " player="
                        + c.player.position()
                        + " hit="
                        + c.hitResult
                        + " rotation="
                        + c.player.getYRot()
                        + ","
                        + c.player.getXRot(),
                    miss);
              });
          throw miss;
        }
        context
            .getInput()
            .pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
        context.waitFor(
            c ->
                mode.equals("door_control")
                    ? c.gui.screen() instanceof TerminalScreen
                    : c.gui.screen() instanceof MachineScreen screen
                        && screen.getMenu().reading.getStringOr("mode", "").equals(mode),
            100);
        if (mode.equals("battery")) {
          MachineTestUi.click(context, 40, 24);
          context.waitFor(
              c ->
                  c.gui.screen() instanceof MachineScreen screen
                      && !screen.getMenu().reading.getBooleanOr("enabled", true),
              100);
          context.waitTicks(3);
          MachineTestUi.click(context, 40, 24);
          context.waitFor(
              c ->
                  c.gui.screen() instanceof MachineScreen screen
                      && screen.getMenu().reading.getBooleanOr("enabled", false),
              100);
        }
        if (mode.equals("air_supply")) context.takeScreenshot("nullspace-equipment-air");
        if (mode.equals("monitor")) break;
        context.runOnClient(c -> c.gui.setScreen(null));
        context.waitTicks(3);
      }
      context.takeScreenshot("nullspace-equipment-monitor");
      context.waitFor(c -> c.gui.screen() instanceof MachineScreen m && m.previewFrames() > 0, 400);
      MachineTestUi.clickLabel(context, "Next camera");
      context.waitFor(
          c ->
              c.gui.screen() instanceof MachineScreen screen
                  && !screen.getMenu().reading.getStringOr("feed", "").isBlank(),
          100);
      context.waitTicks(4);
      context.waitFor(
          c -> c.gui.screen() instanceof MachineScreen m && m.previewStatus().contains("feed 2"),
          200);
      context.takeScreenshot("nullspace-monitor-centered-feed");
      MachineTestUi.clickLabel(context, "View fullscreen");
      context.waitFor(c -> c.gui.screen() instanceof ViewportScreen, 200);
      context.waitTicks(45);
      context.runOnClient(
          c -> {
            if (!((ViewportScreen) c.gui.screen()).status().contains("feed 2"))
              throw new AssertionError("Keepalive must preserve the selected monitor channel");
          });
      var counts = new long[2];
      context.runOnClient(
          c -> {
            var screen = (ViewportScreen) c.gui.screen();
            screen.renderFrame();
            var v = screen.viewport();
            counts[0] = v.renderedFrames();
            counts[1] = v.reusedFrames();
            screen.renderFrame();
            if (v.renderedFrames() != counts[0] || v.reusedFrames() <= counts[1])
              throw new AssertionError("An unchanged camera frame must reuse its GPU target");
          });
      context.waitTicks(20);
      context.runOnClient(
          c -> {
            var v = ((ViewportScreen) c.gui.screen()).viewport();
            if (v.renderedFrames() - counts[0] > 25)
              throw new AssertionError(
                  "A fixed camera's player animation must be bounded to tick rate");
            counts[0] = v.renderedFrames();
            ((ViewportScreen) c.gui.screen())
                .keyPressed(
                    new net.minecraft.client.input.KeyEvent(
                        com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT, 0, 0));
          });
      context.waitFor(
          c -> ((ViewportScreen) c.gui.screen()).viewport().renderedFrames() > counts[0], 100);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(
                    server.overworld(),
                    base.getX() + 17.5,
                    base.getY() + 6,
                    base.getZ() + 18,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
              });
      context.waitFor(c -> ((ViewportScreen) c.gui.screen()).viewport().playerCount() > 0, 200);
      context.waitTicks(5);
      var withPlayer = cameraPixels(context);
      context.runOnClient(c -> c.player.setInvisible(true));
      context.waitFor(c -> ((ViewportScreen) c.gui.screen()).viewport().playerCount() == 0, 100);
      var withoutPlayer = cameraPixels(context);
      int playerPixels = 0;
      for (int i = 0; i < withPlayer.length; i++)
        if (withPlayer[i] != withoutPlayer[i]) playerPixels++;
      context.runOnClient(c -> c.player.setInvisible(false));
      context.waitFor(c -> ((ViewportScreen) c.gui.screen()).viewport().playerCount() > 0, 100);
      if (playerPixels < 100)
        throw new AssertionError(
            "Native full-body player must change visible camera pixels, got " + playerPixels);
      var variation = new java.util.concurrent.atomic.AtomicInteger(-1);
      context.runOnClient(
          c ->
              net.minecraft.client.Screenshot.takeScreenshot(
                  ((ViewportScreen) c.gui.screen()).viewport().target,
                  image -> {
                    try {
                      int first = image.getPixel(0, 0), different = 0;
                      for (int y = 0; y < image.getHeight(); y += 3)
                        for (int x = 0; x < image.getWidth(); x += 3)
                          if (image.getPixel(x, y) != first) different++;
                      variation.set(different);
                    } finally {
                      image.close();
                    }
                  }));
      context.waitFor(c -> variation.get() >= 0, 200);
      context.runOnClient(
          c -> {
            if (NullSpaceClient.sceneVolumes().reused() == 0
                || NullSpaceClient.sceneVolumes().size() > 64
                || ((ViewportScreen) c.gui.screen()).viewport().cachedSubjects()
                    > SceneService.MAX_SUBJECTS)
              throw new AssertionError(
                  "Real frame channels must reuse decoded geometry within bounded caches");
          });
      if (variation.get() < 100)
        throw new AssertionError(
            "Camera must render actual source geometry, not an empty GPU target: "
                + variation.get());
      context.takeScreenshot("nullspace-camera-feed");
      var exported = new java.nio.file.Path[1];
      var existing = new java.util.HashSet<java.nio.file.Path>();
      context.runOnClient(
          c -> {
            exported[0] = c.gameDirectory.toPath().resolve("screenshots");
            try {
              java.nio.file.Files.createDirectories(exported[0]);
              try (var files = java.nio.file.Files.list(exported[0])) {
                files.forEach(existing::add);
              }
            } catch (java.io.IOException error) {
              throw new java.io.UncheckedIOException(error);
            }
            ((ViewportScreen) c.gui.screen())
                .keyPressed(
                    new net.minecraft.client.input.KeyEvent(
                        com.mojang.blaze3d.platform.InputConstants.KEY_F2, 0, 0));
          });
      context.waitFor(
          c -> {
            try (var files = java.nio.file.Files.list(exported[0])) {
              return files.anyMatch(
                  path ->
                      !existing.contains(path)
                          && path.getFileName().toString().startsWith("viewport-")
                          && path.toFile().length() > 0);
            } catch (java.io.IOException error) {
              throw new java.io.UncheckedIOException(error);
            }
          },
          200);

      context.runOnClient(
          c -> {
            var view = (ViewportScreen) c.gui.screen();
            if (view.viewport() == null
                || view.viewport().target.getColorTexture() == null
                || view.viewport().target.getDepthTexture() == null)
              throw new AssertionError("Viewport must own color and depth targets");
            c.gui.setScreen(null);
          });
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.overworld();
                var ship = ShipTopology.resolve(level, SphereLab.controller(base));
                level.setBlock(base.offset(13, 6, 21), engineBacking, 18);
                ShipService.inspect(level, SphereLab.controller(base));
                if (!ship.getStringOr("id", "")
                    .equals(
                        ShipTopology.resolve(level, SphereLab.controller(base)).getStringOr("id", "")))
                  throw new AssertionError("Helm and systems console must share ship identity");
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(
                    level,
                    base.getX() + 16.5,
                    base.getY() + 16,
                    base.getZ() + 23.5,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
              });
      world.getConnection().waitForChunksRender();
      context.waitFor(
          c ->
              c.player != null
                  && c.player
                          .position()
                          .distanceTo(
                              new net.minecraft.world.phys.Vec3(
                                  base.getX() + 16.5, base.getY() + 16, base.getZ() + 23.5))
                      < .25,
          100);
      context.waitTicks(4);
      context.getInput().lookAt(SphereLab.controller(base));
      context.waitTicks(2);
      context.waitFor(
          c ->
              c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                  && hit.getBlockPos().equals(SphereLab.controller(base)),
          100);
      context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
      context.waitFor(c -> c.gui.screen() instanceof TerminalScreen t && t.shipController(), 200);
      context.waitTicks(12);
      ShipControlTestUi.action(
          context, com.nullandkale.nullspace.navigation.ShipControl.Action.LAUNCH);
      context.waitFor(
          c ->
              c.level != null
                  && c.level.dimension().equals(TravelService.SPACE)
                  && c.gui.screen() == null,
          6000);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var space = p.level();
                var own = ShipStore.get(server).at(space, p.blockPosition());
                var origin = ShipStore.pos(own, "base");
                var otherBase = new BlockPos(16384, 95, 0);
                SphereLab.buildShip(space, otherBase);
                var other = new net.minecraft.nbt.CompoundTag();
                other.putString("id", java.util.UUID.randomUUID().toString());
                other.putString("dimension", TravelService.SPACE.identifier().toString());
                other.putLong("base", otherBase.above().asLong());
                other.putLong("controller", SphereLab.controller(otherBase).asLong());
                other.putDouble("simX", 80);
                other.putDouble("simZ", 180);
                other.putDouble("simYaw", 30);
                other.put("crew", new net.minecraft.nbt.ListTag());
                ShipStore.get(server).ships().add(other);
                p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                p.teleportTo(
                    space,
                    origin.getX() + 16.5,
                    origin.getY() + 18,
                    origin.getZ() + 42.5,
                    java.util.Set.of(),
                    -15,
                    18,
                    true);
              });
      context.waitFor(c -> SpaceSceneRenderer.viewport() != null, 200);
      context.waitTicks(40);
      variation.set(-1);
      context.runOnClient(
          c ->
              net.minecraft.client.Screenshot.takeScreenshot(
                  SpaceSceneRenderer.viewport().target,
                  image -> {
                    try {
                      int first = image.getPixel(0, 0), different = 0;
                      for (int y = 0; y < image.getHeight(); y += 3)
                        for (int x = 0; x < image.getWidth(); x += 3)
                          if (image.getPixel(x, y) != first) different++;
                      variation.set(different);
                    } finally {
                      image.close();
                    }
                  }));
      context.waitFor(c -> variation.get() >= 0, 200);
      if (variation.get() < 100)
        throw new AssertionError(
            "EVA space scene must contain real remote geometry and celestial bodies");
      context.takeScreenshot("nullspace-space-scene");
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                for (int x = -3; x <= 3; x++)
                  for (int y = -2; y <= 3; y++)
                    p.level()
                        .setBlock(
                            p.blockPosition().offset(x, y, 6),
                            net.minecraft.world.level.block.Blocks.STAINED_GLASS
                                .cyan()
                                .defaultBlockState(),
                            18);
              });
      world.getConnection().waitForChunksRender();
      var originalSize = new int[2];
      context.runOnClient(
          c -> {
            originalSize[0] = c.getWindow().getScreenWidth();
            originalSize[1] = c.getWindow().getScreenHeight();
          });
      try {
        for (int[] size : new int[][] {{641, 361}, {713, 413}}) {
          var previousWidth = new int[1];
          context.runOnClient(
              c -> {
                previousWidth[0] = c.getWindow().getWidth();
                c.getWindow().setWindowed(size[0], size[1]);
              });
          context.waitFor(
              c ->
                  c.gameRenderer.mainRenderTarget().width != previousWidth[0]
                      && SpaceSceneRenderer.computeReady()
                      && SpaceSceneRenderer.viewport().compute().depth().texture().getWidth(0)
                          == c.gameRenderer.mainRenderTarget().width,
              200);
          context.runOnClient(
              c -> {
                if (org.lwjgl.opengl.GL43C.glGetError() != org.lwjgl.opengl.GL43C.GL_NO_ERROR)
                  throw new AssertionError("Native world resize left an OpenGL error");
              });
        }
      } finally {
        context.runOnClient(c -> c.getWindow().setWindowed(originalSize[0], originalSize[1]));
      }
      context.waitTicks(5);
      var originalTransparency = new boolean[1];
      context.runOnClient(c -> originalTransparency[0] = c.options.improvedTransparency().get());
      try {
        for (boolean oit : new boolean[] {false, true}) {
          var before = new long[1];
          context.runOnClient(
              c -> {
                before[0] =
                    com.nullandkale.nullspace.client.scene.compute.NativeVolumeUniforms.binds();
                c.options.improvedTransparency().set(oit);
              });
          world.getConnection().waitForChunksRender();
          context.waitTicks(20);
          context.runOnClient(
              c -> {
                if (com.nullandkale.nullspace.client.scene.compute.NativeVolumeUniforms.binds()
                    <= before[0])
                  throw new AssertionError(
                      "Native volume shader was not used for transparency mode " + oit);
                if (org.lwjgl.opengl.GL43C.glGetError() != org.lwjgl.opengl.GL43C.GL_NO_ERROR)
                  throw new AssertionError("Native transparency left an OpenGL error");
              });
          context.takeScreenshot(oit ? "nullspace-space-oit" : "nullspace-space-classic");
        }
      } finally {
        context.runOnClient(c -> c.options.improvedTransparency().set(originalTransparency[0]));
      }
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var ship = SceneService.observer(p);
                var monitor =
                    ShipTopology.discover(p.level(), ship).stream()
                        .filter(pos -> p.level().getBlockState(pos).is(ShipBlocks.MONITOR))
                        .findFirst()
                        .orElseThrow();
                p.teleportTo(
                    p.level(),
                    monitor.getX() + .5,
                    monitor.getY(),
                    monitor.getZ() - 2,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
                SceneService.open(p, monitor);
              });
      context.waitFor(
          c ->
              c.gui.screen() instanceof ViewportScreen view
                  && view.viewport() != null
                  && view.viewport().compute() != null,
          200);
      context.waitTicks(5);
      context.takeScreenshot("nullspace-space-camera-compute");
      var spaceExports = new java.util.HashSet<java.nio.file.Path>();
      context.runOnClient(
          c -> {
            try (var files = java.nio.file.Files.list(exported[0])) {
              files.forEach(spaceExports::add);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
            ((ViewportScreen) c.gui.screen())
                .keyPressed(
                    new net.minecraft.client.input.KeyEvent(
                        com.mojang.blaze3d.platform.InputConstants.KEY_F2, 0, 0));
          });
      context.waitFor(
          c -> {
            try (var files = java.nio.file.Files.list(exported[0])) {
              return files.anyMatch(
                  path ->
                      !spaceExports.contains(path)
                          && path.getFileName().toString().startsWith("viewport-")
                          && path.toFile().length() > 0);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          },
          200);
      context.runOnClient(c -> c.gui.setScreen(null));
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var ship =
                    ShipStore.get(server)
                        .at(p.level(), ShipStore.pos(SceneService.observer(p), "controller"));
                var roof = ShipStore.pos(ship, "base").offset(16, 31, 16);
                for (int dx = -1; dx <= 1; dx++)
                  for (int dz = -1; dz <= 1; dz++)
                    p.level()
                        .setBlock(
                            roof.offset(dx, 0, dz),
                            net.minecraft.world.level.block.Blocks.QUARTZ_BLOCK.defaultBlockState(),
                            18);
                p.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(ShipBlocks.HELMET));
                p.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.CHEST,
                    new net.minecraft.world.item.ItemStack(ShipBlocks.CHEST));
                p.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.LEGS,
                    new net.minecraft.world.item.ItemStack(ShipBlocks.LEGS));
                p.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.FEET,
                    new net.minecraft.world.item.ItemStack(ShipBlocks.BOOTS));
                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                p.teleportTo(
                    p.level(),
                    roof.getX() + .5,
                    roof.getY() + 1,
                    roof.getZ() + .5,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
              });
      context.waitFor(
          c ->
              c.player.onGround()
                  && c.player.getAbilities().mayfly
                  && !c.player.getAbilities().flying,
          200);
      var groundY = new double[1];
      context.runOnClient(c -> groundY[0] = c.player.getY());
      for (int tick = 0; tick < 40; tick++) {
        context.waitTicks(1);
        context.runOnClient(
            c -> {
              if (c.player.getAbilities().flying || Math.abs(c.player.getY() - groundY[0]) > .01)
                throw new AssertionError(
                    "Standing on a vacuum floor must not oscillate flight/height");
            });
      }
      context.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_SPACE);
      try {
        context.waitFor(
            c -> c.player.getAbilities().flying && c.player.getY() > groundY[0] + .2, 100);
      } finally {
        context.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_SPACE);
      }
    }
    ClientTestEvidence.passed(getClass());
  }

  private static void awaitStatus(ClientGameTestContext context, String expected) {
    try {
      context.waitFor(
          c -> c.gui.screen() instanceof TerminalScreen t && t.status().contains(expected), 100);
    } catch (AssertionError failed) {
      context.takeScreenshot("equipment-switch-failed");
      context.runOnClient(
          c -> {
            throw new AssertionError(
                "Expected "
                    + expected
                    + "; screen="
                    + c.gui.screen()
                    + "; status="
                    + (c.gui.screen() instanceof TerminalScreen t ? t.status() : "none"),
                failed);
          });
      throw failed;
    }
  }

  private static int[] cameraPixels(ClientGameTestContext context) {
    var result = new java.util.concurrent.atomic.AtomicReference<int[]>();
    context.runOnClient(
        c ->
            net.minecraft.client.Screenshot.takeScreenshot(
                ((ViewportScreen) c.gui.screen()).viewport().target,
                image -> {
                  try {
                    var pixels = new int[image.getWidth() * image.getHeight()];
                    for (int y = 0; y < image.getHeight(); y++)
                      for (int x = 0; x < image.getWidth(); x++)
                        pixels[y * image.getWidth() + x] = image.getPixel(x, y);
                    result.set(pixels);
                  } finally {
                    image.close();
                  }
                }));
    context.waitFor(c -> result.get() != null, 100);
    return result.get();
  }
}
