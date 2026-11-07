package com.nullandkale.nullspace;

import com.nullandkale.nullspace.generation.VillagePorts;
import com.nullandkale.nullspace.ship.ShipStore;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

/**
 * P8 in a real world: walk up to a generated village that was chosen for a port, let the normal
 * chunk loading find it, and look at the port that was built beside it.
 */
public final class VillagePortClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    // Real terrain and villages: the default preset with a fixed seed.
    try (var world =
        c.worldBuilder()
            .setUseConsistentSettings(false)
            .adjustSettings(s -> s.setSeed("nullspace-village-ports"))
            .create()) {
      world.getConnection().waitForChunksRender();
      // Find a village whose start chunk is chosen for a port.
      BlockPos village =
          world
              .getServer()
              .computeOnServer(
                  server -> {
                    var level = server.overworld();
                    for (int ring = 0; ring < 40; ring++) {
                      var origin = new BlockPos(ring * 640, 0, (ring % 5) * 640);
                      var found =
                          level.findNearestMapStructure(StructureTags.VILLAGE, origin, 50, false);
                      if (found != null
                          && VillagePorts.chosen(level.getSeed(), ChunkPos.containing(found)))
                        return found;
                    }
                    return null;
                  });
      if (village == null) throw new AssertionError("No village chosen for a port was found");
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.SPECTATOR);
                p.teleportTo(
                    server.overworld(), village.getX(), 120, village.getZ(), Set.of(), 0, 60, true);
              });
      String key = Long.toString(ChunkPos.containing(village).pack());
      long[] computer = {0};
      for (int wait = 0; wait < 60 && computer[0] == 0; wait++) {
        c.waitTicks(20);
        computer[0] =
            world
                .getServer()
                .computeOnServer(
                    server -> {
                      var record = ShipStore.get(server).section("villagePorts").getCompoundOrEmpty(key);
                      if (record.getStringOr("state", "").equals("no_site")) return -1L;
                      return record.getLongOr("computer", 0);
                    });
      }
      if (computer[0] == -1L) {
        // Some villages sit on water or cliffs; the decision is still recorded once.
        ClientTestEvidence.passed(getClass());
        return;
      }
      if (computer[0] == 0) throw new AssertionError("The chosen village never got a port");
      var at = BlockPos.of(computer[0]);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                // Above and behind the hut, looking over it to the pad.
                p.teleportTo(
                    server.overworld(), at.getX() - 10, at.getY() + 12, at.getZ() - 14, Set.of(),
                    -35, 35, true);
              });
      world.getConnection().waitForChunksRender();
      c.waitTicks(20);
      c.takeScreenshot("nullspace-village-port");
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                // Inside the service hut.
                p.setGameMode(GameType.CREATIVE);
                p.teleportTo(
                    server.overworld(), at.getX() - 2.5, at.getY(), at.getZ() + 0.5, Set.of(),
                    90, 10, true);
              });
      c.waitTicks(20);
      c.takeScreenshot("nullspace-village-port-hut");
      ClientTestEvidence.passed(getClass());
    }
  }
}
