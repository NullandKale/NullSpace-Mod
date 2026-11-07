package com.nullandkale.nullspace.dev;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;

/** Actual walk from the plaza, then a GUI round trip to a blank pad in the populated gallery. */
public final class ShipGalleryClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = context.worldBuilder().create()) {
      var gallery =
          world
              .getServer()
              .computeOnServer(
                  server -> {
                    try {
                      return ShipGallery.ensure(server);
                    } catch (java.io.IOException error) {
                      throw new java.io.UncheckedIOException(error);
                    }
                  });
      var base = gallery.base(1);
      var door = base.offset(5, 2, 3);
      var controller = gallery.controller(1);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                p.teleportTo(
                    server.overworld(),
                    base.getX() + 5.5,
                    base.getY() + 1,
                    base.getZ() + .5,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
                var level = p.level();
                ((DoorBlock) level.getBlockState(door).getBlock())
                    .setOpen(null, level, level.getBlockState(door), door, true);
              });
      world.getConnection().waitForChunksRender();
      context.getInput().lookAt(0, 0);
      context.getInput().holdKey(InputConstants.KEY_W);
      try {
        context.waitFor(c -> c.player != null && c.player.getZ() > base.getZ() + 5, 200);
      } finally {
        context.getInput().releaseKey(InputConstants.KEY_W);
      }
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                if (p.getY() < base.getY() + 1.95)
                  throw new AssertionError("Must walk up the entry stairs onto the ship floor");
                var level = p.level();
                ((DoorBlock) level.getBlockState(door).getBlock())
                    .setOpen(null, level, level.getBlockState(door), door, false);
              });
      context.getInput().lookAt(controller);
      context.waitTicks(2);
      context.takeScreenshot("nullspace-gallery-controller-approach");
      context.waitFor(
          c ->
              c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                  && hit.getBlockPos().equals(controller),
          40);
      context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
      context.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 200);
      ShipControlTestUi.action(context, ShipControl.Action.INSPECT);
      context.waitFor(
          c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Ship ready"),
          200);
      context.takeScreenshot("nullspace-gallery-scout-terminal");
      pause(context);
      ShipControlTestUi.action(context, ShipControl.Action.LAUNCH);
      context.waitFor(
          c ->
              c.level != null
                  && c.level.dimension().equals(TravelService.SPACE)
                  && c.gui.screen() == null,
          6000);
      var spaceComputer =
          world
              .getServer()
              .computeOnServer(
                  server -> {
                    var p = world.getConnection().getServerPlayer();
                    return WorldStore.pos(
                        WorldStore.get(server).at(p.level(), p.blockPosition()), "controller");
                  });
      world.getConnection().waitForChunksRender();
      context.getInput().lookAt(spaceComputer);
      context.waitTicks(2);
      context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
      context.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 200);
      ShipControlTestUi.destination(context, gallery.name(ShipGallery.PLOTS.get(12)), "Pads");
      ShipControlTestUi.action(context, ShipControl.Action.LAND);
      context.waitFor(
          c ->
              c.level != null
                  && c.level.dimension().equals(Level.OVERWORLD)
                  && c.gui.screen() == null,
          6000);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var destination = gallery.base(13);
                if (Math.abs(p.getX() - (destination.getX() + 5.5)) > 2)
                  throw new AssertionError("Ship must land on selected blank pad");
                if (!server
                    .overworld()
                    .getBlockState(gallery.controller(13))
                    .is(ModBlocks.COMPUTER))
                  throw new AssertionError("Blank pad must receive the actual ship");
                try {
                  ShipGallery.ensure(server);
                } catch (java.io.IOException error) {
                  throw new java.io.UncheckedIOException(error);
                }
                if (!server.overworld().getBlockState(controller).isAir())
                  throw new AssertionError("Visiting the gallery must not respawn a departed ship");
                p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                p.teleportTo(
                    server.overworld(),
                    gallery.origin().getX() + 43,
                    gallery.origin().getY() + 55,
                    gallery.origin().getZ() - 15,
                    java.util.Set.of(),
                    0,
                    39,
                    true);
              });
      context.runOnClient(
          c -> {
            c.options.renderDistance().set(12);
            c.options.broadcastOptions();
            if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
          });
      try {
        // Wait for this gallery, rather than every distant background chunk at view distance 12.
        context.waitFor(
            c ->
                c.level.hasChunkAt(gallery.origin())
                    && c.level.hasChunkAt(
                        gallery.origin().offset(ShipGallery.WIDTH - 1, 0, ShipGallery.DEPTH - 1)),
            600);
        context.waitTicks(40);
        context.takeScreenshot("nullspace-gallery-overview");
      } finally {
        context.runOnClient(
            c -> {
              if (c.gui.hud.isHidden()) c.gui.hud.toggle();
            });
        context.restoreDefaultGameOptions();
      }
    }
    ClientTestEvidence.passed(getClass());
  }

  private static void key(ClientGameTestContext ctx, int key) {
    ctx.runOnClient(c -> ((TerminalScreen) c.gui.screen()).keyPressed(new KeyEvent(key, 0, 0)));
  }

  private static void pause(ClientGameTestContext ctx) {
    long now = System.nanoTime();
    ctx.waitFor(c -> System.nanoTime() - now > 600_000_000L);
  }
}
