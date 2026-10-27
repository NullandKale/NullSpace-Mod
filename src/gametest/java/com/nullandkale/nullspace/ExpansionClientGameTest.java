package com.nullandkale.nullspace;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.client.*;
import com.nullandkale.nullspace.client.scene.*;
import com.nullandkale.nullspace.computer.*;
import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.input.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;

public final class ExpansionClientGameTest implements FabricClientGameTest {
  private static void type(ClientGameTestContext c, String text) {
    c.runOnClient(
        mc -> {
          var s = (TerminalScreen) mc.gui.screen();
          for (char ch : text.toCharArray()) {
            if (ch == '\r') s.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            else s.charTyped(new CharacterEvent(ch));
          }
        });
  }

  private static boolean contains(TerminalScreen t, String text) {
    for (int row = 0; row < 25; row++) if (t.buffer().row(row).contains(text)) return true;
    return false;
  }

  @Override
  public void runTest(ClientGameTestContext c) {
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                    p,
                    new com.nullandkale.nullspace.systems.AirEffects.FlowPacket(
                        p.level().dimension().identifier().toString(),
                        new BlockPos(0, -59, 0),
                        new int[] {1057, 1, 0, 0, 300, 0}));
              });
      c.waitFor(mc -> AirFlowParticles.activeParticles() > 0, 80);
      c.runOnClient(
          mc -> {
            var p =
                mc.particleEngine.createParticle(
                    net.minecraft.core.particles.ParticleTypes.CLOUD, 0, -58, 0, 0, 0, 0);
            if (p != null) p.setLifetime(100);
          });
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                    p,
                    new com.nullandkale.nullspace.systems.AirEffects.FlowPacket(
                        p.level().dimension().identifier().toString(),
                        new BlockPos(0, -59, 0),
                        new int[0]));
              });
      c.waitFor(mc -> AirFlowParticles.activeParticles() == 0, 80);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                    p,
                    new com.nullandkale.nullspace.systems.AirEffects.FlowPacket(
                        p.level().dimension().identifier().toString(),
                        new BlockPos(0, -59, 0),
                        new int[] {1057, 1, 0, 0, 300, 0}));
              });
      c.waitFor(mc -> AirFlowParticles.activeParticles() > 0, 80);
      c.waitTicks(12);
      c.runOnClient(
          mc -> {
            if (AirFlowParticles.activeParticles() != 0)
              throw new AssertionError("Air effects must expire if updates stop");
          });

      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                var at = new BlockPos(8, -59, 8);
                p.level().setBlock(at, ShipBlocks.TERMINAL.defaultBlockState(), 18);
                p.teleportTo(p.level(), 8.5, -59, 6.5, Set.of(), 0, 0, true);
                ComputerService.open(p, at);
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && contains(t, "NullOS"), 200);
      type(c, "echo original > log\r");
      c.waitTicks(4);
      type(c, "cat log\r");
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && contains(t, "original"), 100);
      type(c, "edit test.py\r");
      c.waitTicks(3);
      type(c, "x = 2\rfor i in range(3):\r    x = x + i\rprint(x)\r.\r");
      c.waitTicks(3);
      type(c, "python test.py\r");
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof TerminalScreen t
                  && java.util.stream.IntStream.range(0, 25)
                      .anyMatch(row -> t.buffer().row(row).strip().equals("5")),
          100);
      c.takeScreenshot("nullspace-computer-python");
      type(c, "edit test.sh\r");
      c.waitTicks(3);
      type(c, "i=0\rwhile [ \"$i\" -lt 3 ]; do\recho \"$i\"\ri=$((i+1))\rdone\r.\r");
      c.waitTicks(3);
      type(c, "bash test.sh\r");
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof TerminalScreen t
                  && java.util.stream.IntStream.range(0, 25)
                      .anyMatch(row -> t.buffer().row(row).strip().equals("2")),
          100);
      c.takeScreenshot("nullspace-computer-bash");
      NullSpaceClientGameTest.shiftEscape(c, 1);
      c.waitFor(mc -> mc.gui.screen() == null, 100);
      c.waitTicks(2);
      world
          .getServer()
          .runOnServer(
              server -> {
                var player = world.getConnection().getServerPlayer();
                if (!ComputerService.session(player)
                    .equals(com.nullandkale.nullspace.terminal.ComputerTerminal.NONE))
                  throw new AssertionError(
                      "Shift+Escape must close the actual server computer session");
              });
      world
          .getServer()
          .runOnServer(
              server -> {
                PlanetSurfaces.ensureLanding(server, "sol_luna");
                var level = server.getLevel(PlanetSurfaces.dimension("sol_luna"));
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(level, 16.5, 65, -2.5, Set.of(), 0, 0, true);
                level.setBlock(
                    new BlockPos(16, 67, 0),
                    ShipBlocks.CAMERA
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH),
                    18);
                for (int x = 10; x <= 22; x++)
                  for (int y = 65; y <= 77; y++)
                    level.setBlock(
                        new BlockPos(x, y, 36), Blocks.GOLD_BLOCK.defaultBlockState(), 18);
                level.setBlock(new BlockPos(14, 65, 8), Blocks.CHEST.defaultBlockState(), 18);
                for (int x = 17; x <= 20; x++)
                  for (int z = 7; z <= 10; z++)
                    level.setBlock(new BlockPos(x, 65, z), Blocks.WATER.defaultBlockState(), 18);
                var sheep =
                    net.minecraft.world.entity.EntityTypes.SHEEP.create(
                        level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                sheep.setPos(18.5, 65, 12.5);
                level.addFreshEntity(sheep);
                SceneService.open(p, new BlockPos(16, 67, 0));
              });
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof ViewportScreen v
                  && v.viewport() != null
                  && v.viewport().cachedTerrain() > 4,
          600);
      c.waitTicks(40);
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof ViewportScreen v
                  && v.viewport().blockEntityCount() > 0
                  && v.viewport().entityCount() > 0,
          300);
      c.runOnClient(
          mc -> {
            var v = (ViewportScreen) mc.gui.screen();
            if (v.viewport().compute() == null || TerrainClient.size() > 192)
              throw new AssertionError(
                  "Planet terrain feeds must share the space renderer and bounded section cache");
          });
      c.takeScreenshot("nullspace-planet-terrain-camera");
      c.runOnClient(
          mc -> {
            if (TerrainClient.state("nullspace:sol_luna", new BlockPos(16, 67, 36))
                != Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()))
              throw new AssertionError("Camera must capture terrain beyond the former ship cube");
            mc.gui.setScreen(null);
          });
      c.waitTicks(85);
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.getLevel(PlanetSurfaces.dimension("sol_luna"));
                level.setBlock(new BlockPos(16, 67, 36), Blocks.AIR.defaultBlockState(), 18);
                SceneService.open(world.getConnection().getServerPlayer(), new BlockPos(16, 67, 0));
              });
      c.waitFor(
          mc ->
              mc.gui.screen() instanceof ViewportScreen
                  && TerrainClient.state("nullspace:sol_luna", new BlockPos(16, 67, 36))
                      == Block.getId(Blocks.AIR.defaultBlockState()),
          600);
      c.runOnClient(mc -> mc.gui.setScreen(null));
      c.waitFor(mc -> SpaceSceneRenderer.planetSkyReady(), 200);
      c.takeScreenshot("nullspace-luna-surface-sky");
      c.runOnClient(
          mc ->
              mc.gui.setScreen(
                  new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                      null, mc, mc.options)));
      c.waitTicks(2);
      c.runOnClient(
          mc -> {
            mc.gui.setScreen(new SpaceSettingsScreen(mc.gui.screen()));
          });
      c.waitTicks(2);
      c.takeScreenshot("nullspace-space-graphics-settings");
      c.runOnClient(mc -> mc.gui.setScreen(null));
    }
    ClientTestEvidence.passed(getClass());
  }
}
