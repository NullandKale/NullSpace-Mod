package com.nullandkale.nullspace;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.client.*;
import com.nullandkale.nullspace.client.scene.*;
import com.nullandkale.nullspace.dev.*;
import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.terminal.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.input.KeyEvent;

public final class NavigationClientGameTest implements FabricClientGameTest {
  private static void choose(ClientGameTestContext c, int index) {
    c.runOnClient(
        mc -> {
          var t = (TerminalScreen) mc.gui.screen();
          while (t.selection() != index) t.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
          t.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
  }

  @Override
  public void runTest(ClientGameTestContext c) {
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      final net.minecraft.core.BlockPos[] launchHelm = new net.minecraft.core.BlockPos[1];
      world.getConnection().waitForChunksRender();
      c.waitTicks(12);
      world
          .getServer()
          .runOnServer(
              server -> {
                try {
                  var base = ShipLab.ensure(server);
                  launchHelm[0] = base.offset(6, 3, 3);
                  var p = world.getConnection().getServerPlayer();
                  p.teleportTo(
                      server.overworld(),
                      base.getX() + 4.5,
                      base.getY() + 3,
                      base.getZ() + 3.5,
                      java.util.Set.of(),
                      0,
                      0,
                      true);
                  ShipService.open(p, base.offset(6, 3, 3), "Navigation test");
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
      world.getConnection().waitForChunksRender();
      c.waitTicks(12);
      world
          .getServer()
          .waitFor(
              server -> {
                var p = world.getConnection().getServerPlayer();
                try {
                  ShipService.preflight(p.level(), launchHelm[0]);
                  return true;
                } catch (IllegalArgumentException loading) {
                  return false;
                }
              },
              200);
      choose(c, 1);
      try {
        c.waitFor(
            mc ->
                mc.level != null
                    && mc.level.dimension().equals(TravelService.SPACE)
                    && mc.gui.screen() == null,
            600);
      } catch (AssertionError error) {
        c.runOnClient(
            mc -> {
              throw new AssertionError(
                  "Launch screen="
                      + mc.gui.screen()
                      + " status="
                      + (mc.gui.screen() instanceof TerminalScreen t ? t.status() : "none"),
                  error);
            });
        throw error;
      }
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                ShipService.open(p, ShipStore.pos(SceneService.observer(p), "controller"), "Ready");
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
      c.waitTicks(12);
      choose(c, 7);
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof ShipPilotScreen s
                  && s.viewport() != null
                  && s.viewport().compute() != null,
          200);
      c.runOnClient(
          mc -> {
            var s = (ShipPilotScreen) mc.gui.screen();
            var target = s.viewport().target;
            if (Math.abs((long) target.width * s.height - (long) target.height * s.width)
                > Math.max(s.width, s.height))
              throw new AssertionError(
                  "Pilot render target must match the whole screen aspect ratio");
          });
      var original =
          world
              .getServer()
              .computeOnServer(
                  server -> {
                    var p = world.getConnection().getServerPlayer();
                    var s = SceneService.observer(p);
                    return new double[] {
                      s.getDoubleOr("simX", 0),
                      s.getDoubleOr("simZ", 0),
                      p.getX(),
                      p.getY(),
                      p.getZ()
                    };
                  });
      c.runOnClient(
          mc ->
              ((ShipPilotScreen) mc.gui.screen())
                  .keyPressed(new KeyEvent(InputConstants.KEY_W, 0, 0)));
      c.waitTicks(30);
      c.runOnClient(
          mc ->
              ((ShipPilotScreen) mc.gui.screen())
                  .keyReleased(new KeyEvent(InputConstants.KEY_W, 0, 0)));
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var s = SceneService.observer(p);
                if (Math.hypot(
                        s.getDoubleOr("simX", 0) - original[0],
                        s.getDoubleOr("simZ", 0) - original[1])
                    < 1) throw new AssertionError("Pilot input must move the simulated ship");
                if (p.position()
                        .distanceToSqr(
                            new net.minecraft.world.phys.Vec3(
                                original[2], original[3], original[4]))
                    > .01) throw new AssertionError("Piloting must retain the physical crew frame");
              });
      var pixels = new java.util.concurrent.atomic.AtomicInteger(-1);
      c.runOnClient(
          mc ->
              net.minecraft.client.Screenshot.takeScreenshot(
                  ((ShipPilotScreen) mc.gui.screen()).viewport().target,
                  image -> {
                    try {
                      int bright = 0, cx = image.getWidth() / 2, cy = image.getHeight() / 2;
                      for (int y = cy - 60; y < cy + 60; y++)
                        for (int x = cx - 80; x < cx + 80; x++) {
                          int color = image.getPixel(x, y);
                          if ((color >> 16 & 255) + (color >> 8 & 255) + (color & 255) > 100)
                            bright++;
                        }
                      pixels.set(bright);
                    } finally {
                      image.close();
                    }
                  }));
      c.waitFor(mc -> pixels.get() >= 0, 100);
      if (pixels.get() < 200)
        throw new AssertionError(
            "Third-person hull must be framed and readable, got "
                + pixels.get()
                + " visible center pixels");
      c.takeScreenshot("nullspace-third-person-flight");
      c.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_H, 0, 0)));
      c.waitTicks(1);
      c.takeScreenshot("nullspace-pilot-controls");
      c.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_H, 0, 0)));
      c.runOnClient(mc -> mc.gui.screen().onClose());
      c.waitTicks(5);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var s = SceneService.observer(p);
                if (s.getDoubleOr("simVX", 0) != 0 || s.getDoubleOr("simVZ", 0) != 0)
                  throw new AssertionError("Releasing helm must stop motion");
                ShipService.open(p, ShipStore.pos(s, "controller"), "Ready");
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 100);
      c.waitTicks(12);
      choose(c, 8);
      c.waitFor(mc -> mc.gui.screen() instanceof ShipPilotScreen s && !s.canPilot(), 100);
      c.runOnClient(mc -> mc.gui.screen().onClose());
      c.waitTicks(3);
      for (String destination : new String[] {"sol_luna", "vega_pelagos", "sol_terra"}) {
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  NavigationService.navigation(
                      p, ShipStore.pos(SceneService.observer(p), "controller"));
                });
        c.waitFor(
            mc ->
                mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(12).contains("Terra"),
            100);
        c.waitTicks(12);
        c.takeScreenshot("nullspace-helm-navigation");
        int index =
            java.util.stream.IntStream.range(0, LocalSpaces.DESTINATIONS.size())
                .filter(i -> LocalSpaces.DESTINATIONS.get(i).id().equals(destination))
                .findFirst()
                .orElseThrow();
        choose(c, index);
        c.waitFor(mc -> mc.gui.screen() instanceof TravelScreen, 100);
        c.waitFor(
            mc ->
                mc.gui.screen() == null
                    && NullSpaceClient.definitions().scene() != null
                    && NullSpaceClient.definitions()
                        .scene()
                        .id()
                        .equals(
                            destination.equals(LocalSpaces.HOME)
                                ? "nullspace:local-space"
                                : "nullspace:" + destination),
            500);
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  if (!destination.equals(LocalSpaces.address(SceneService.observer(p)))
                      || TravelService.busy(p))
                    throw new AssertionError(
                        "Destination metadata and readiness must commit together");
                  if (!destination.equals(LocalSpaces.HOME)) {
                    boolean rejected = false;
                    try {
                      TravelService.request(
                          p,
                          ShipStore.pos(SceneService.observer(p), "controller"),
                          "id:"
                              + PadRegistry.read(server).stream()
                                  .filter(pad -> pad.dimension().equals("minecraft:overworld"))
                                  .findFirst()
                                  .orElseThrow()
                                  .id(),
                          ShipService.preflight(
                              p.level(), ShipStore.pos(SceneService.observer(p), "controller")));
                    } catch (IllegalArgumentException expected) {
                      rejected = expected.getMessage().contains("Terra");
                    }
                    if (!rejected)
                      throw new AssertionError("Remote planet cannot land on Terra pads");
                  }
                });
        c.waitTicks(10);
        c.takeScreenshot("nullspace-arrival-" + destination);
      }
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var s = SceneService.observer(p);
                var origin = ShipStore.pos(s, "base");
                SphereLab.buildShip(p.level(), origin.below());
                var helm = SphereLab.controller(origin.below());
                s.putLong("controller", helm.asLong());
                p.teleportTo(
                    p.level(),
                    helm.getX() + .5,
                    helm.getY(),
                    helm.getZ() - 2,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
                NavigationService.open(p, helm, false);
              });
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof ShipPilotScreen view
                  && view.viewport() != null
                  && view.viewport().cachedSubjects() > 0,
          200);
      c.waitTicks(12);
      c.takeScreenshot("nullspace-third-person-sphere");
      c.runOnClient(
          mc -> {
            var view = (ShipPilotScreen) mc.gui.screen();
            view.mouseDragged(
                new net.minecraft.client.input.MouseButtonEvent(
                    0,
                    0,
                    new net.minecraft.client.input.MouseButtonInfo(
                        InputConstants.MOUSE_BUTTON_LEFT, 0)),
                70,
                20);
            view.mouseScrolled(0, 0, 0, 2);
          });
      c.waitTicks(5);
      c.takeScreenshot("nullspace-third-person-orbit");
      c.runOnClient(mc -> mc.gui.screen().onClose());
    }
    ClientTestEvidence.passed(getClass());
  }
}
