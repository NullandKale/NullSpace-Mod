package com.nullandkale.nullspace.worldgen.ships;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYard;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

/** Exterior review and real, unassisted walking up and down the explorer stairwell. */
public final class ManufacturerRoleClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      var presets =
          ShipBlueprints.PRESETS.stream()
              .filter(p -> ManufacturerRoleShips.contains(p.id()))
              .toList();
      world
          .getServer()
          .runOnServer(
              server -> {
                server
                    .getCommands()
                    .performPrefixedCommand(server.createCommandSourceStack(), "time set day");
                server
                    .getCommands()
                    .performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                int i = 0;
                for (var preset : presets) {
                  var base = new BlockPos(i++ * 40, -59, 0);
                  for (var e : ShipBlueprints.blocks(preset).entrySet())
                    server.overworld().setBlock(base.offset(e.getKey()), e.getValue(), 18);
                }
                world.getConnection().getServerPlayer().setGameMode(GameType.SPECTATOR);
              });
      for (int i = 0; i < presets.size(); i++) {
        var base = new BlockPos(i * 40, -59, 0);
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  p.teleportTo(
                      server.overworld(),
                      base.getX() - 15.5,
                      base.getY() + 20,
                      -15.5,
                      Set.of(),
                      0,
                      0,
                      true);
                });
        world.getConnection().waitForChunksRender();
        c.waitTicks(10);
        c.getInput().lookAt(base.offset(12, 3, 15));
        c.waitTicks(10);
        c.takeScreenshot("nullspace-" + presets.get(i).id());
      }
      var base = new BlockPos(200, -59, 0);
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.overworld();
                try {
                  ShipYard.buildPad(level, base.below(), 32, 32, "Explorer walking");
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
                var preset = ShipBlueprints.get("bastion_pioneer");
                var ship = ShipTopology.resolve(level, base.offset(preset.helm()));
                for (var at : ShipSystems.connected(level, ship))
                  FuelSystem.provision(level, at, 64);
                ShipSystems.upgrade(level, ship);
                ship.getCompoundOrEmpty("systems")
                    .putIntArray(
                        "requests", new int[] {1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.SURVIVAL);
                p.teleportTo(
                    level, base.getX() + 15.5, base.getY() + 1, 17.5, Set.of(), 0, 0, true);
              });
      c.waitFor(
          mc ->
              mc.player != null
                  && !mc.player.isSpectator()
                  && !mc.player.getAbilities().flying
                  && mc.player.distanceToSqr(215.5, -58, 17.5) < 1,
          150);
      c.waitTicks(8);
      c.getInput().lookAt(0, 0);
      c.getInput().holdKey(InputConstants.KEY_W);
      try {
        c.waitFor(mc -> mc.player.getY() > base.getY() + 5.8 && mc.player.getZ() > 22.5, 200);
      } finally {
        c.getInput().releaseKey(InputConstants.KEY_W);
      }
      c.getInput().lookAt(base.offset(12, 7, 17));
      c.waitTicks(8);
      c.takeScreenshot("nullspace-explorer-crew-deck");
      c.getInput().lookAt(180, 0);
      c.getInput().holdKey(InputConstants.KEY_W);
      try {
        c.waitFor(mc -> mc.player.getY() < base.getY() + 1.2 && mc.player.getZ() < 17.7, 200);
      } finally {
        c.getInput().releaseKey(InputConstants.KEY_W);
      }
      c.getInput().lookAt(base.offset(10, 2, 25));
      c.waitTicks(8);
      c.takeScreenshot("nullspace-explorer-engineering-deck");
      world.getServer().runOnServer(server -> WorldStore.get(server).save());
      ClientTestEvidence.passed(getClass());
    }
  }
}
