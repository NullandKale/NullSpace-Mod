package com.nullandkale.nullspace;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.client.TerminalScreen;
import com.nullandkale.nullspace.navigation.ShipControl;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.shipyard.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Native block use, typed purchase packets and a purchased ship's complete launch/return. */
public final class ShipYardClientGameTest implements FabricClientGameTest {
  static void choose(ClientGameTestContext context, int index) {
    if (context.computeOnClient(
        client -> client.gui.screen() instanceof TerminalScreen t && t.shipController())) {
      // Same controls as the other client tests: Manual steps tab, then Launch or Land.
      ShipControlTestUi.action(
          context, index == 1 ? ShipControl.Action.LAUNCH : ShipControl.Action.LAND);
      return;
    }
    context.runOnClient(
        client -> {
          if (client.gui.screen() instanceof com.nullandkale.nullspace.client.ShipyardScreen yard) {
            int x = (yard.width - 360) / 2, y = (yard.height - 220) / 2;
            if (index < ShipBlueprints.PRESETS.size()) {
              for (int page = yard.page(); page < index / 6; page++)
                yard.mouseClicked(
                    new net.minecraft.client.input.MouseButtonEvent(
                        x + 103,
                        y + 178,
                        new net.minecraft.client.input.MouseButtonInfo(
                            InputConstants.MOUSE_BUTTON_LEFT, 0)),
                    false);
            }
            int bx = index < ShipBlueprints.PRESETS.size() ? x + 40 : x + 180;
            int by = index < ShipBlueprints.PRESETS.size() ? y + 34 + (index % 6) * 23 : y + 193;
            yard.mouseClicked(
                new net.minecraft.client.input.MouseButtonEvent(
                    bx,
                    by,
                    new net.minecraft.client.input.MouseButtonInfo(
                        InputConstants.MOUSE_BUTTON_LEFT, 0)),
                false);
            return;
          }
          var terminal = (TerminalScreen) client.gui.screen();
          if (terminal.shipController()) {
            throw new AssertionError("Controller actions are handled before this callback");
          }
          for (int i = 0; i < 10 && terminal.selection() != index; i++)
            terminal.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
          if (terminal.selection() != index)
            throw new AssertionError("Missing terminal choice " + index);
          terminal.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
  }

  @Override
  public void runTest(ClientGameTestContext context) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = context.worldBuilder().create()) {
      var base = new BlockPos(0, -60, 0);
      var beacon = base.offset(-1, 1, 1);
      var purchased = new CompoundTag[1];
      world
          .getServer()
          .runOnServer(
              server -> {
                try {
                  ShipYard.buildPad(server.overworld(), base, 32, 32, "Beacon Test");
                } catch (java.io.IOException failure) {
                  throw new RuntimeException(failure);
                }
                server.overworld().setBlock(beacon, ShipBlocks.SHIP_BEACON.defaultBlockState(), 18);
                server
                    .overworld()
                    .setBlock(
                        base.offset(-3, 0, 1),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                        18);
                var player = world.getConnection().getServerPlayer();
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.teleportTo(
                    server.overworld(), -2.5, base.getY() + 1, 1.5, Set.of(), -90, 25, true);
              });
      world.getConnection().waitForChunksRender();
      context.waitFor(
          client ->
              client.player != null && client.player.distanceToSqr(-2.5, base.getY() + 1, 1.5) < 1,
          150);
      context.waitTicks(10);
      context.getInput().lookAt(beacon);
      context.waitTicks(5);
      context.waitFor(
          client ->
              client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                  && hit.getBlockPos().equals(beacon),
          100);
      context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
      context.waitFor(
          client -> client.gui.screen() instanceof com.nullandkale.nullspace.client.ShipyardScreen,
          150);
      choose(context, 14);
      context.waitFor(
          client ->
              client.gui.screen() instanceof com.nullandkale.nullspace.client.ShipyardScreen t
                  && t.selected().equals("bastion_pioneer")
                  && t.ready(),
          150);
      context.takeScreenshot("nullspace-ship-beacon-catalog");
      context.waitTicks(3);
      choose(context, ShipBlueprints.PRESETS.size());
      context.waitFor(
          client ->
              client.gui.screen() instanceof com.nullandkale.nullspace.client.ShipyardScreen t
                  && t.status().contains("DELIVERED"),
          300);
      world
          .getServer()
          .runOnServer(
              server -> {
                purchased[0] =
                    ShipStore.get(server).ships().stream()
                        .map(t -> (CompoundTag) t)
                        .filter(s -> s.getStringOr("blueprint", "").equals("bastion_pioneer"))
                        .findFirst()
                        .orElseThrow();
                if (!ShipStore.get(server).list("shipyardOrders").isEmpty())
                  throw new AssertionError("Delivery order was not committed");
              });
      context.waitTicks(3);
      choose(context, ShipBlueprints.PRESETS.size());
      context.waitFor(
          client ->
              client.gui.screen() instanceof com.nullandkale.nullspace.client.ShipyardScreen t
                  && t.status().contains("Clear the pad"),
          150);
      context.runOnClient(client -> client.gui.screen().onClose());
      world
          .getServer()
          .runOnServer(
              server -> {
                var ship = purchased[0];
                var helm = ShipStore.pos(ship, "controller");
                var player = world.getConnection().getServerPlayer();
                var inspection = ShipService.inspect(player.level(), helm);
                var at = inspection.scan().boarding();
                player.teleportTo(
                    player.level(),
                    at.getX() + .5,
                    at.getY(),
                    at.getZ() + .5,
                    Set.of(),
                    -90,
                    0,
                    true);
                ShipService.open(player, helm, "Purchased ship ready");
              });
      context.waitFor(
          client -> client.gui.screen() instanceof TerminalScreen t && t.shipController(), 150);
      context.waitTicks(12);
      choose(context, 1);
      context.waitFor(
          client ->
              client.gui.screen() == null
                  && client.level != null
                  && client.level.dimension().equals(TravelService.SPACE),
          400);
      world.getConnection().waitForChunksRender();
      context.takeScreenshot("nullspace-purchased-ship-in-space");
      context.waitTicks(20);
      var dockingVessels = new CompoundTag[2];
      var dockingDoors = new BlockPos[2];
      world
          .getServer()
          .runOnServer(
              server -> {
                var player = world.getConnection().getServerPlayer();
                dockingVessels[0] =
                    ShipStore.get(server).at(player.level(), player.blockPosition());
                if (dockingVessels[0] == null
                    || !dockingVessels[0]
                        .getStringOr("id", "")
                        .equals(purchased[0].getStringOr("id", "")))
                  throw new AssertionError("Purchase identity did not survive launch");
                ShipService.open(
                    player,
                    ShipStore.pos(dockingVessels[0], "controller"),
                    "Choose a station from the destination picker");
              });
      context.waitFor(
          client -> client.gui.screen() instanceof TerminalScreen t && t.shipController(), 150);
      world
          .getServer()
          .waitFor(
              server ->
                  ShipStore.get(server).ships().stream()
                      .map(t -> (CompoundTag) t)
                      .anyMatch(
                          s ->
                              s.getBooleanOr("worldGenerated", false)
                                  && s.getStringOr("populationRole", "").equals("waypoint")
                                  && com.nullandkale.nullspace.station.StationService.ready(s)),
              1000);
      // No generator call or Find button: entering the region creates its persistent places.
      // Stations are chosen at the ship's Scanner block; the controller then shows the course.
      ShipControlTestUi.ensureScanner(world);
      ShipControlTestUi.destination(context, world, "Waypoint Station", "Stations");
      context.takeScreenshot("nullspace-discovered-waypoint-ports");
      // Engage carries out the whole trip; the berth is assigned on final approach.
      ShipControlTestUi.action(
          context, com.nullandkale.nullspace.navigation.ShipControl.Action.ENGAGE);
      world
          .getServer()
          .waitFor(
              server ->
                  dockingVessels[0]
                      .getCompoundOrEmpty("dock")
                      .getStringOr("state", "")
                      .equals("CLAMPED"),
              2000);
      world
          .getServer()
          .runOnServer(
              server -> {
                var dock = dockingVessels[0].getCompoundOrEmpty("dock");
                dockingVessels[1] =
                    ShipStore.get(server).repository().find(dock.getStringOr("target", ""));
                dockingDoors[0] =
                    ShipStore.pos(dockingVessels[0], "base")
                        .offset(BlockPos.of(dock.getLongOr("sourcePort", 0)));
                dockingDoors[1] =
                    ShipStore.pos(dockingVessels[1], "base")
                        .offset(BlockPos.of(dock.getLongOr("targetPort", 0)));
              });
      context.runOnClient(
          client -> {
            if (client.gui.screen() != null) client.gui.screen().onClose();
          });
      StationClientGameTest.walkThrough(context, world, dockingVessels[0], dockingDoors[0]);
      context.waitTicks(12);
      context.takeScreenshot("nullspace-purchased-ship-station-visit");
      StationClientGameTest.walkThrough(context, world, dockingVessels[1], dockingDoors[1]);
      world
          .getServer()
          .runOnServer(
              server -> {
                var player = world.getConnection().getServerPlayer();
                var helm = ShipStore.pos(dockingVessels[0], "controller");
                var front = ShipService.inspect(player.level(), helm).scan().boarding();
                player.teleportTo(
                    player.level(),
                    front.getX() + .5,
                    front.getY(),
                    front.getZ() + .5,
                    Set.of(),
                    -90,
                    0,
                    true);
                ShipService.open(player, helm, "Return from the station");
              });
      context.waitFor(
          client -> client.gui.screen() instanceof TerminalScreen t && t.shipController(), 150);
      ShipControlTestUi.action(
          context, com.nullandkale.nullspace.navigation.ShipControl.Action.UNDOCK);
      world.getServer().waitFor(server -> !dockingVessels[0].contains("dock"), 1200);
      context.runOnClient(
          client -> {
            if (client.gui.screen() != null) client.gui.screen().onClose();
          });
      world
          .getServer()
          .runOnServer(
              server -> {
                var player = world.getConnection().getServerPlayer();
                var ship = ShipStore.get(server).at(player.level(), player.blockPosition());
                if (ship == null
                    || !ship.getStringOr("id", "").equals(purchased[0].getStringOr("id", "")))
                  throw new AssertionError("Purchase identity did not survive launch");
                ShipService.open(
                    player, ShipStore.pos(ship, "controller"), "Return to delivery pad");
              });
      context.waitFor(client -> client.gui.screen() instanceof TerminalScreen, 150);
      ShipControlTestUi.destination(context, "Beacon Test", "Pads");
      choose(context, 3);
      context.waitFor(
          client ->
              client.gui.screen() == null
                  && client.level != null
                  && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD),
          400);
      world
          .getServer()
          .runOnServer(
              server -> {
                var player = world.getConnection().getServerPlayer();
                var ship = ShipStore.get(server).at(player.level(), player.blockPosition());
                if (ship == null || !ship.getStringOr("blueprint", "").equals("bastion_pioneer"))
                  throw new AssertionError("Purchased ship did not return to its home pad");
              });
      context.waitTicks(10);
      var showroom = new ShipYard.Yard[1];
      world
          .getServer()
          .runOnServer(
              server -> {
                server
                    .getCommands()
                    .performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                server
                    .getCommands()
                    .performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                try {
                  var yard = ShipYard.ensure(server);
                  showroom[0] = yard;
                  var at = yard.origin().offset(59, 65, 50);
                  var player = world.getConnection().getServerPlayer();
                  player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                  player.teleportTo(
                      server.overworld(), at.getX(), at.getY(), at.getZ(), Set.of(), 0, 35, true);
                } catch (java.io.IOException failure) {
                  throw new RuntimeException(failure);
                }
              });
      int[] oldDistance = new int[1];
      context.runOnClient(client -> oldDistance[0] = client.options.renderDistance().get());
      try {
        context.runOnClient(
            client -> {
              client.options.renderDistance().set(12);
              client.options.broadcastOptions();
            });
        context.waitFor(
            client ->
                client.level != null
                    && client.level.hasChunkAt(showroom[0].origin())
                    && client.level.hasChunkAt(
                        showroom[0].origin().offset(ShipYard.WIDTH - 1, 0, ShipYard.DEPTH - 1)),
            600);
        context.waitFor(
            client ->
                client.player != null
                    && client.player.distanceToSqr(
                            showroom[0].origin().getX() + 59,
                            showroom[0].origin().getY() + 65,
                            showroom[0].origin().getZ() + 50)
                        < 1,
            200);
        context.getInput().lookAt(showroom[0].origin().offset(59, 0, ShipYard.DEPTH / 2));
        context.waitTicks(5);
        // Wait for the showroom's meshes, not every background section at distance 12.
        context.waitFor(
            client ->
                java.util.stream.IntStream.of(1, 4)
                    .allMatch(
                        index -> {
                          var at = showroom[0].pad(index).offset(15, 3, 16);
                          long section =
                              net.minecraft.core.SectionPos.asLong(
                                  at.getX() >> 4, at.getY() >> 4, at.getZ() >> 4);
                          return client.levelRenderer.visibleSections().stream()
                              .anyMatch(
                                  rendered ->
                                      rendered.getSectionNode() == section
                                          && rendered.getSectionMesh().hasRenderableLayers());
                        }),
            600);
        context.waitTicks(40);
        context.runOnClient(
            client -> {
              if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
        context.takeScreenshot("nullspace-shipyard-showroom");
        context.runOnClient(
            client -> {
              if (client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
      } finally {
        context.runOnClient(client -> client.options.renderDistance().set(oldDistance[0]));
      }
      world
          .getServer()
          .runOnServer(
              server -> {
                com.nullandkale.nullspace.computer.ComputerService.flush(server);
                ShipStore.get(server).save();
              });
    }
    ClientTestEvidence.passed(getClass());
  }
}
