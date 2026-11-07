package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

/** P9: stand on each planet type's surface and look around, for visual review of the terrain. */
public final class GalaxySurfacesClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      for (var type : GalaxyPlan.SURFACE_POOL.keySet()) {
        String slot = GalaxyPlan.surfaceSlot(type, 0);
        world
            .getServer()
            .runOnServer(
                server -> {
                  var key =
                      ResourceKey.create(
                          Registries.DIMENSION, Identifier.parse("nullspace:" + slot));
                  var level = server.getLevel(key);
                  if (level == null) throw new AssertionError("Missing surface " + slot);
                  var chunk = level.getChunk(4, 4);
                  int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8) + 2;
                  var p = world.getConnection().getServerPlayer();
                  p.setGameMode(GameType.SPECTATOR);
                  p.teleportTo(level, 72.5, y + 6, 72.5, Set.of(), 225, 20, true);
                });
        world.getConnection().waitForChunksRender();
        c.waitTicks(30);
        c.takeScreenshot("nullspace-surface-" + type.name().toLowerCase(Locale.ROOT));
      }
      ClientTestEvidence.passed(getClass());
    }
  }
}
