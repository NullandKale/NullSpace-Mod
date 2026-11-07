package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.MachineTestUi;
import com.nullandkale.nullspace.ui.screen.MachineScreen;
import com.nullandkale.nullspace.ui.screen.ViewportScreen;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Real inventory packets, Advanced return navigation and independent embedded camera rendering. */
public final class ShipHardwareClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      var positions = new BlockPos[2];
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.getLevel(TravelService.SPACE);
                var ship = StationGameTest.fixture(level, new BlockPos(0, 96, 0), true);
                positions[0] =
                    ShipSystems.connected(level, ship).stream()
                        .filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE))
                        .findFirst()
                        .orElseThrow();
                positions[1] =
                    ShipSystems.connected(level, ship).stream()
                        .filter(p -> level.getBlockState(p).is(ModBlocks.CAMERA))
                        .findFirst()
                        .orElseThrow();
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(
                    level,
                    positions[0].getX() + .5,
                    positions[0].getY(),
                    positions[0].getZ() - 1.5,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
                // Tier III needs three modules including an exotic one.
                p.getInventory().add(new ItemStack(ModBlocks.SYSTEM_UPGRADE));
                p.getInventory().add(new ItemStack(ModBlocks.ADVANCED_UPGRADE));
                p.getInventory().add(new ItemStack(ModBlocks.EXOTIC_UPGRADE));
                MachineMenus.open(p, positions[0]);
              });
      world.getConnection().waitForChunksRender();
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof MachineScreen m
                  && m.getMenu().reading.getStringOr("mode", "").equals("engine"),
          200);
      MachineTestUi.shiftItem(c, ModBlocks.SYSTEM_UPGRADE);
      MachineTestUi.shiftItem(c, ModBlocks.ADVANCED_UPGRADE);
      MachineTestUi.shiftItem(c, ModBlocks.EXOTIC_UPGRADE);
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof MachineScreen m
                  && m.getMenu().reading.getIntOr("tier", 0) == 3,
          200);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var machine = (MachineBlockEntity) p.level().getBlockEntity(positions[0]);
                if (machine.tier() != 3
                    || p.getInventory().countItem(ModBlocks.SYSTEM_UPGRADE) != 0
                    || p.getInventory().countItem(ModBlocks.ADVANCED_UPGRADE) != 0
                    || p.getInventory().countItem(ModBlocks.EXOTIC_UPGRADE) != 0)
                  throw new AssertionError(
                      "Native shift-click must install exactly three real modules");
              });
      c.takeScreenshot("nullspace-engine-upgrades");
      MachineTestUi.click(c, 140, 24);
      c.runOnClient(
          mc -> {
            mc.keyboardHandler.textInput(mc.getWindow().handle(), "Main engine");
            var field =
                ((MachineScreen) mc.gui.screen())
                    .children().stream()
                        .filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                        .map(w -> (net.minecraft.client.gui.components.EditBox) w)
                        .findFirst()
                        .orElseThrow();
            if (!field.getValue().equals("Main engine"))
              throw new AssertionError("Edit name must focus the revealed field for normal typing");
          });
      // Equipment screens no longer open an Advanced terminal; ordinary controls suffice.
      c.runOnClient(mc -> mc.gui.screen().onClose());
      c.waitTicks(4);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var at = positions[1];
                p.teleportTo(at.getX() + .5, at.getY() - 1, at.getZ() + 1.5);
                MachineMenus.open(p, at);
              });
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof MachineScreen m
                  && m.getMenu().reading.getStringOr("mode", "").equals("camera"),
          200);
      c.waitTicks(40);
      c.runOnClient(
          mc -> {
            if (!(mc.gui.screen() instanceof MachineScreen m))
              throw new AssertionError("Camera setup closed: " + mc.gui.screen());
            if (!m.previewStatus().startsWith("LIVE"))
              throw new AssertionError(
                  "Embedded camera: " + m.previewStatus() + " | " + m.getMenu().reading);
          });
      c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen m && m.previewFrames() > 0, 400);
      c.takeScreenshot("nullspace-camera-setup");
      MachineTestUi.click(c, 120, 24);
      c.runOnClient(mc -> mc.keyboardHandler.textInput(mc.getWindow().handle(), "Draft camera"));
      MachineTestUi.clickLabel(c, "View fullscreen");
      c.waitFor(mc -> mc.gui.screen() instanceof ViewportScreen, 200);
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof ViewportScreen v
                  && v.viewport() != null
                  && v.viewport().target.width == SpaceSettings.cameraWidth()
                  && v.viewport().renderedFrames() > 0,
          200);
      c.runOnClient(
          mc -> {
            var screen = (ViewportScreen) mc.gui.screen();
            var target = screen.viewport().target;
            if (Math.abs(
                    target.width / (double) target.height - screen.width / (double) screen.height)
                > .01)
              throw new AssertionError("Fullscreen feed must preserve the display aspect ratio");
          });
      c.takeScreenshot("nullspace-camera-fullscreen");
      c.runOnClient(mc -> mc.gui.screen().onClose());
      c.waitFor(mc -> mc.gui.screen() instanceof MachineScreen, 200);
      c.runOnClient(
          mc -> {
            var field =
                ((MachineScreen) mc.gui.screen())
                    .children().stream()
                        .filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                        .map(w -> (net.minecraft.client.gui.components.EditBox) w)
                        .findFirst()
                        .orElseThrow();
            if (!field.getValue().equals("Draft camera"))
              throw new AssertionError(
                  "Returning from fullscreen must preserve unsaved name input");
          });
      MachineTestUi.clickLabel(c, "View fullscreen");
      c.waitFor(mc -> mc.gui.screen() instanceof ViewportScreen, 200);
      world
          .getServer()
          .runOnServer(
              server ->
                  world
                      .getConnection()
                      .getServerPlayer()
                      .level()
                      .setBlock(
                          positions[1],
                          net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                          18));
      c.runOnClient(mc -> mc.gui.screen().onClose());
      c.waitTicks(25);
      c.runOnClient(
          mc -> {
            if (mc.gui.screen() instanceof MachineScreen)
              throw new AssertionError("Destroyed camera cannot reopen an old inventory");
          });
      ClientTestEvidence.passed(getClass());
    }
  }
}
