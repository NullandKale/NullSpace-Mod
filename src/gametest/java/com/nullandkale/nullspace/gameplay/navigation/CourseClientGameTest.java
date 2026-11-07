package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;

/** Real Engage buttons, automatic launch/jump/berth selection, undock and return landing. */
public final class CourseClientGameTest implements FabricClientGameTest {
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      var vessels = new CompoundTag[2];
      String[] id = {""};
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.overworld();
                var floor = new BlockPos(128, 100, 0);
                for (int x = -1; x <= 32; x++)
                  for (int z = -1; z <= 32; z++)
                    level.setBlock(
                        floor.offset(x, 0, z),
                        (x == -1 || x == 32 || z == -1 || z == 32
                                ? Blocks.COBBLESTONE
                                : Blocks.GRASS_BLOCK)
                            .defaultBlockState(),
                        18);
                var computer = floor.offset(-1, 1, 0);
                level.setBlock(computer, PadComputers.BLOCK.defaultBlockState(), 18);
                String registered;
                try {
                  registered =
                      PadComputers.register(
                          level, computer, new PadScanner.Pad(128, 159, 100, 0, 31), "Course Home");
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
                if (!registered.startsWith("Registered")) throw new AssertionError(registered);
                vessels[0] = StationGameTest.fixture(level, floor.above(), true);
                id[0] = vessels[0].getStringOr("id", "");
                var space = server.getLevel(TravelService.SPACE);
                vessels[1] =
                    StationGameTest.fixture(space, new BlockPos(730000, 96, 730000), false);
                vessels[1].putString("localSpace", "sol_luna");
                vessels[1].putString("home", "Course Outpost");
                vessels[1].putDouble("simX", -320);
                vessels[1].putDouble("simZ", -280);
                var p = world.getConnection().getServerPlayer();
                var at = StationGameTest.boardingPoint(level, vessels[0]);
                p.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
                ShipControl.open(p, WorldStore.pos(vessels[0], "controller"), "Course round trip");
              });
      world.getConnection().waitForChunksRender();
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
      ShipControlTestUi.destination(c, world, "Course Outpost", "Stations");
      c.takeScreenshot("nullspace-course-outbound");
      ShipControlTestUi.action(c, ShipControl.Action.ENGAGE);
      // Keep real journey time short in automation; every transfer and docking tick stays real.
      world.getServer().runOnServer(server -> {});
      for (int i = 0; i < 220; i++) {
        c.waitTicks(10);
        boolean arrived =
            world
                .getServer()
                .computeOnServer(
                    server -> {
                      var ship = WorldStore.get(server).repository().find(id[0]);
                      if (JourneyService.cruising(ship)) {
                        var j = ship.getCompoundOrEmpty("journey");
                        j.putInt("elapsed", j.getIntOr("duration", 0));
                      }
                      if (ship.getCompoundOrEmpty("course")
                          .getStringOr("state", "")
                          .equals("PAUSED")) throw new AssertionError(CourseService.summary(ship));
                      return ship.getCompoundOrEmpty("course")
                          .getStringOr("state", "")
                          .equals("ARRIVED");
                    });
        if (arrived) break;
        if (i == 219) throw new AssertionError("Outbound course timed out");
      }
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var ship = SceneService.observer(p);
                if (!ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED")
                    || !ship.getCompoundOrEmpty("dock")
                        .getStringOr("target", "")
                        .equals(vessels[1].getStringOr("id", "")))
                  throw new AssertionError(
                      "Engage must select a real free berth at the chosen station");
                ShipControl.open(p, WorldStore.pos(ship, "controller"), "Return home");
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
      c.waitTicks(12);
      ShipControlTestUi.destination(c, world, "Course Home", "Pads");
      c.takeScreenshot("nullspace-course-return");
      ShipControlTestUi.action(c, ShipControl.Action.ENGAGE);
      for (int i = 0; i < 220; i++) {
        c.waitTicks(10);
        boolean arrived =
            world
                .getServer()
                .computeOnServer(
                    server -> {
                      var ship = WorldStore.get(server).repository().find(id[0]);
                      if (JourneyService.cruising(ship)) {
                        var j = ship.getCompoundOrEmpty("journey");
                        j.putInt("elapsed", j.getIntOr("duration", 0));
                      }
                      if (ship.getCompoundOrEmpty("course")
                          .getStringOr("state", "")
                          .equals("PAUSED")) throw new AssertionError(CourseService.summary(ship));
                      return ship.getCompoundOrEmpty("course")
                          .getStringOr("state", "")
                          .equals("ARRIVED");
                    });
        if (arrived) break;
        if (i == 219) throw new AssertionError("Return course timed out");
      }
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var ship = WorldStore.get(server).repository().find(id[0]);
                if (p.level() != server.overworld()
                    || ship.contains("dock")
                    || !ship.getStringOr("homeId", "")
                        .equals(
                            PadRepository.get(server)
                                .dimension(server.overworld().dimension().identifier().toString())
                                .stream()
                                .filter(pad -> pad.name().equals("Course Home"))
                                .findFirst()
                                .orElseThrow()
                                .id()))
                  throw new AssertionError(
                      "Return must undock, jump and land on the selected home pad");
              });
      c.takeScreenshot("nullspace-course-home");
    }
    ClientTestEvidence.passed(getClass());
  }
}
