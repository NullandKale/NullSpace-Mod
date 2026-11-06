package com.nullandkale.nullspace;

import com.nullandkale.nullspace.client.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** One world covers the shared equipment layout, real item slots and direct power controls. */
public final class EquipmentUiClientGameTest implements FabricClientGameTest {
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      String[] id = {""};
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.getLevel(TravelService.SPACE);
                var ship =
                    com.nullandkale.nullspace.ship.StationGameTest.fixture(
                        level, new BlockPos(0, 96, 0), true);
                id[0] = ship.getStringOr("id", "");
                for (var pair :
                    List.of(
                        Map.entry(new BlockPos(3, 1, 1), ShipBlocks.BATTERY),
                        Map.entry(new BlockPos(4, 1, 1), ShipBlocks.WEAPON),
                        Map.entry(new BlockPos(5, 1, 1), ShipBlocks.CAMERA),
                        Map.entry(new BlockPos(6, 1, 1), ShipBlocks.MONITOR),
                        Map.entry(new BlockPos(7, 1, 1), ShipBlocks.DOCKING_BASE),
                        Map.entry(new BlockPos(8, 1, 1), ShipBlocks.LOCAL_SCANNER)))
                  level.setBlock(
                      ShipStore.pos(ship, "base").offset(pair.getKey()),
                      pair.getValue()
                          .defaultBlockState()
                          .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                      18);
                ShipSystems.tick(level, ship);
                world
                    .getConnection()
                    .getServerPlayer()
                    .setGameMode(net.minecraft.world.level.GameType.CREATIVE);
              });
      for (String mode :
          List.of(
              "generator",
              "engine",
              "battery",
              "air_supply",
              "weapon",
              "camera",
              "monitor",
              "docking_base",
              "local_scanner")) {
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  var level = server.getLevel(TravelService.SPACE);
                  var ship = ShipStore.get(server).repository().find(id[0]);
                  var at =
                      ShipSystems.connected(level, ship).stream()
                          .filter(
                              pos ->
                                  net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                      .getKey(level.getBlockState(pos).getBlock())
                                      .getPath()
                                      .equals(mode))
                          .findFirst()
                          .orElseThrow();
                  p.teleportTo(
                      level, at.getX() + .5, at.getY(), at.getZ() + 1.5, Set.of(), 0, 0, true);
                  MachineMenus.open(p, at);
                });
        c.waitFor(
            mc ->
                mc.gui.screen() instanceof MachineScreen m
                    && m.getMenu().reading.getStringOr("mode", "").equals(mode),
            100);
        c.waitTicks(4);
        c.runOnClient(
            mc -> {
              var s = (MachineScreen) mc.gui.screen();
              if (s.children().stream()
                  .anyMatch(
                      w ->
                          w instanceof net.minecraft.client.gui.components.Button b
                              && b.getMessage().getString().equals("Advanced")))
                throw new AssertionError("Equipment screens must not require Advanced");
              for (var slot : s.getMenu().slots)
                if (slot.isActive()
                    && (slot.x < 0
                        || slot.y < 0
                        || slot.x + 16 > s.panelWidth()
                        || slot.y + 16 > s.panelHeight()))
                  throw new AssertionError("Active item slots must fit the shared panel");
            });
        if (mode.equals("engine")) {
          int before =
              c.computeOnClient(
                  mc ->
                      ((MachineScreen) mc.gui.screen())
                          .getMenu()
                          .reading
                          .getIntOr("allocation", -1));
          MachineTestUi.click(c, 92, 229);
          c.waitFor(
              mc ->
                  mc.gui.screen() instanceof MachineScreen m
                      && m.getMenu().reading.getIntOr("allocation", -1) == before + 1,
              100);
        }
        c.takeScreenshot("nullspace-ui-batch-" + mode);
      }
    }
    ClientTestEvidence.passed(getClass());
  }
}
