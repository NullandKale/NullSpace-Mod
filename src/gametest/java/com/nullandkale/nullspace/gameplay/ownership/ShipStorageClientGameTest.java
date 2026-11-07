package com.nullandkale.nullspace.gameplay.ownership;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.input.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Actual death/respawn UI, then recall at the planet's native Pad Computer. */
public final class ShipStorageClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      var pad = new BlockPos(0, 90, 0);
      var computer = pad.offset(-1, 1, 3);
      var id = new String[1];
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.overworld();
                for (int x = -1; x <= 12; x++)
                  for (int z = -1; z <= 12; z++)
                    level.setBlock(
                        pad.offset(x, 0, z),
                        (x == -1 || z == -1 || x == 12 || z == 12
                                ? Blocks.COBBLESTONE
                                : Blocks.DIRT)
                            .defaultBlockState(),
                        18);
                level.setBlock(computer, PadComputers.BLOCK.defaultBlockState(), 18);
                try {
                  PadComputers.register(
                      level, computer, new PadScanner.Pad(0, 11, 90, 0, 11), "Recovery pad");
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
                var space = server.getLevel(TravelService.SPACE);
                var ship = StationGameTest.fixture(space, new BlockPos(0, 96, 0), true);
                id[0] = ship.getStringOr("id", "");
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.SURVIVAL);
                var member = new CompoundTag();
                member.putString("id", p.getUUID().toString());
                ship.getListOrEmpty("crew").add(member);
                p.getInventory().add(new ItemStack(Items.DIAMOND, 7));
                var at = StationGameTest.boardingPoint(space, ship);
                p.teleportTo(space, at.x, at.y, at.z, Set.of(), 0, 0, true);
              });
      world.getConnection().waitForChunksRender();
      c.waitTicks(20);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                p.hurtServer(p.level(), p.damageSources().genericKill(), Float.MAX_VALUE);
              });
      c.waitFor(mc -> mc.gui.screen() instanceof DeathScreen, 200);
      c.waitTicks(25);
      c.runOnClient(
          mc -> {
            var screen = mc.gui.screen();
            screen.mouseClicked(
                new MouseButtonEvent(
                    screen.width / 2.0,
                    screen.height / 4.0 + 82,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)),
                false);
          });
      c.waitFor(
          mc ->
              mc.player != null
                  && mc.player.isAlive()
                  && mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD),
          400);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(
                    server.overworld(),
                    computer.getX() + .5,
                    computer.getY(),
                    computer.getZ() + .5,
                    Set.of(),
                    0,
                    0,
                    true);
              });
      world.getConnection().waitForChunksRender();
      c.waitTicks(8);
      c.getInput().lookAt(computer);
      c.waitTicks(4);
      c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
      c.waitFor(
          mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("PAD"),
          200);
      c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 4, 23));
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(1).contains("MY SHIPS"),
          200);
      c.takeScreenshot("nullspace-ship-recall");
      c.waitTicks(4);
      c.runOnClient(
          mc -> {
            var screen = (TerminalScreen) mc.gui.screen();
            ShipControlTestUi.click(screen, 4, 6);
            ShipControlTestUi.click(screen, 4, 18);
          });
      world
          .getServer()
          .waitFor(
              server ->
                  WorldStore.get(server)
                      .repository()
                      .find(id[0])
                      .getStringOr("dimension", "")
                      .equals("minecraft:overworld"),
              400);
      c.waitFor(
          mc -> mc.gui.screen() instanceof TerminalScreen t && t.status().contains("Ship recalled"),
          200);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var ship = WorldStore.get(server).repository().find(id[0]);
                if (p.level() != server.overworld()
                    || p.distanceToSqr(computer.getX() + .5, computer.getY(), computer.getZ() + .5)
                        > 4)
                  throw new AssertionError("Recall must not teleport the respawned player");
                if (p.getInventory().countItem(Items.DIAMOND) != 0
                    || !ship.getListOrEmpty("crew").isEmpty())
                  throw new AssertionError(
                      "Recovery must not resurrect the old inventory or crew assignment");
                if (WorldStore.get(server).ships().stream()
                        .filter(t -> ((CompoundTag) t).getStringOr("id", "").equals(id[0]))
                        .count()
                    != 1) throw new AssertionError("Recall duplicated the ship");
              });
      c.takeScreenshot("nullspace-ship-recalled");
      // Playtest 2 bug: storing the ship from beside its pad closed and reopened My ships and
      // left the player stuck in it. Store, then check the screen stays usable and closes.
      c.runOnClient(
          mc -> {
            var screen = (TerminalScreen) mc.gui.screen();
            ShipControlTestUi.click(screen, 4, 6);
            ShipControlTestUi.click(screen, 21, 18);
          });
      world
          .getServer()
          .waitFor(
              server ->
                  WorldStore.get(server).repository().find(id[0]).getBooleanOr("stored", false),
              400);
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof TerminalScreen t
                  && t.buffer().row(1).contains("MY SHIPS")
                  && t.status().contains("stored"),
          200);
      c.takeScreenshot("nullspace-ship-stored");
      c.runOnClient(
          mc ->
              mc.gui
                  .screen()
                  .keyPressed(
                      new net.minecraft.client.input.KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
      c.waitFor(mc -> mc.gui.screen() == null, 60);
      c.waitTicks(60);
      c.runOnClient(
          mc -> {
            if (mc.gui.screen() != null)
              throw new AssertionError(
                  "My ships reopened after closing: " + mc.gui.screen().getClass().getSimpleName());
          });
      ClientTestEvidence.passed(getClass());
    }
  }
}
