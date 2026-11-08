package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Reproduces the November 4 remote playtest's construction-room and demolished-port traps. */
public final class DockingRecoveryGameTest {
  private static CompoundTag courier(ServerLevel level, BlockPos base) {
    var preset = ShipBlueprints.get("halcyon_courier");
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("home", "Recovery Courier");
    ship.putString("kind", "ship");
    ship.putString("access", "public");
    ship.putString("dimension", TravelService.SPACE.identifier().toString());
    ship.putString("localSpace", "sol_terra");
    ship.putLong("base", base.asLong());
    ship.putLong("controller", base.offset(preset.helm()).asLong());
    ship.put("crew", new ListTag());
    WorldStore.get(level.getServer()).ships().add(ship);
    for (var e : ShipBlueprints.blocks(preset).entrySet()) {
      level.setBlock(base.offset(e.getKey()), e.getValue(), 18);
      if (e.getValue().is(ModBlocks.GENERATOR))
        FuelSystem.provision(level, base.offset(e.getKey()), 64);
    }
    SystemsUpgrade.upgrade(level, ship);
    ship.getCompoundOrEmpty("systems")
        .putIntArray("requests", new int[] {1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
    ShipSystems.tick(level, ship);
    return ship;
  }

  private static void pose(CompoundTag ship, ScenePose pose) {
    var angles = pose.orientation().getEulerAnglesYXZ(new Vector3d());
    ship.putDouble("simX", pose.position().x);
    ship.putDouble("simY", pose.position().y);
    ship.putDouble("simZ", pose.position().z);
    ship.putDouble("simYaw", Math.toDegrees(angles.y));
    ship.putDouble("simPitch", Math.toDegrees(angles.x));
    ship.putDouble("simRoll", Math.toDegrees(angles.z));
  }

  private static void escape(
      CompoundTag dock, ScenePose goal, CompoundTag station, Direction facing) {
    var end =
        goal.position()
            .add(
                FlightDynamics.rotate(
                        SceneService.pose(station).orientation(), DockingMotion.normal(facing))
                    .scale(112));
    dock.putDouble("escapeX", end.x);
    dock.putDouble("escapeY", end.y);
    dock.putDouble("escapeZ", end.z);
  }

  private static ServerPlayer board(GameTestHelper ctx, ServerLevel level, CompoundTag ship) {
    var player = ctx.makeMockServerPlayerInLevel();
    var at = StationGameTest.boardingPoint(level, ship);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    return player;
  }

  private static CompoundTag capture(
      GameTestHelper ctx,
      ServerLevel level,
      CompoundTag ship,
      CompoundTag station,
      ServerPlayer player,
      DockingPorts.Port target) {
    var ready =
        DockingManeuver.request(
            player,
            ship,
            station.getStringOr("id", ""),
            target.controller().subtract(WorldStore.pos(station, "base")));
    ctx.assertTrue(ready.ready(), ready.message());
    var dock = ship.getCompoundOrEmpty("dock");
    var source = DockingPorts.firstPort(level, ship);
    var goal =
        DockingManeuver.matingPose(ship, source, station, target, dock.getDoubleOr("gap", .05));
    var normal =
        FlightDynamics.rotate(
            SceneService.pose(station).orientation(), DockingMotion.normal(target.facing()));
    pose(ship, new ScenePose(goal.position().add(normal.scale(1)), goal.orientation()));
    dock.putString("state", "CONNECTING");
    for (int i = 0; i < 6 && !dock.getStringOr("state", "").equals("CLAMPED"); i++)
      DockingManeuver.tickMotion(level.getServer(), ship);
    ctx.assertTrue(
        dock.getStringOr("state", "").equals("CLAMPED"),
        "The real final approach clamps before the regression: "
            + ship.getStringOr("flightStatus", ""));
    DockingManeuver.tickMotion(level.getServer(), ship);
    return dock;
  }

  private static void finishDeparture(
      GameTestHelper ctx, ServerLevel level, CompoundTag ship, CompoundTag station) {
    for (int i = 0; i < 340 && ship.contains("dock"); i++)
      DockingManeuver.tickMotion(level.getServer(), ship);
    ctx.assertTrue(
        !ship.contains("dock") && station.getCompoundOrEmpty("dockLinks").isEmpty(),
        "Departure completes and releases the station reservation: "
            + ship.getStringOr("flightStatus", ""));
  }

  @GameTest(environment = "nullspace:docking_presets", maxTicks = 400)
  public void normalUndockSurvivesDemolishedPortsAndLiveCollars(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    for (int damage = 0; damage < 3; damage++) {
      var ship = courier(level, new BlockPos(2300000 + damage * 4096, 96, 2300000));
      var station =
          StationGameTest.fixture(level, new BlockPos(2301024 + damage * 4096, 96, 2300000), false);
      var player = board(ctx, level, ship);
      try {
        var source = DockingPorts.firstPort(level, ship);
        var target = DockingPorts.ports(level, station).getFirst();
        var dock = capture(ctx, level, ship, station, player, target);
        ctx.assertTrue(dock.contains("tunnel"), "A real boarding collar was generated");
        ctx.assertTrue(
            Collections.disjoint(
                ShipSystems.connected(level, ship), DockingTunnel.positions(level)),
            "Temporary collars never become hull or power-bus blocks");
        if (damage != 1) {
          level.setBlock(target.outer(), Blocks.AIR.defaultBlockState(), 18);
          level.setBlock(target.outer().above(), Blocks.AIR.defaultBlockState(), 18);
          level.setBlock(target.controller(), Blocks.AIR.defaultBlockState(), 18);
        }
        if (damage != 0) {
          level.setBlock(source.outer(), Blocks.AIR.defaultBlockState(), 18);
          level.setBlock(source.outer().above(), Blocks.AIR.defaultBlockState(), 18);
          level.setBlock(source.controller(), Blocks.AIR.defaultBlockState(), 18);
        }
        if (damage < 2) {
          dock.remove("escapeX");
          dock.remove("escapeY");
          dock.remove("escapeZ");
        }
        DockingManeuver.tickMotion(server, ship);
        var controller = ShipControl.open(player, WorldStore.pos(ship, "controller"), "Ready");
        var result =
            ShipControl.input(
                player,
                new ControllerPackets.Input(controller.session(), ShipControl.Action.UNDOCK, ""));
        ctx.assertTrue(
            dock.getStringOr("state", "").equals("UNDOCKING"),
            "The ordinary controller can undock with either or both damaged ports: " + result);
        finishDeparture(ctx, level, ship, station);
        ctx.assertTrue(
            DockingTunnel.positions(level).isEmpty(), "No collar blocks remain after release");
      } finally {
        server.getPlayerList().remove(player);
        WorldStore.get(server).ships().remove(ship);
        WorldStore.get(server).ships().remove(station);
      }
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:docking_presets", maxTicks = 400)
  public void finishedConstructionRoomRealignsARecessedCourierBeforePublishingItsDock(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = courier(level, new BlockPos(2320000, 96, 2300000));
    var station = StationGameTest.fixture(level, new BlockPos(2321024, 96, 2300000), false);
    var player = board(ctx, level, ship);
    try {
      station.putString("constructionShip", ship.getStringOr("id", ""));
      var target = DockingPorts.ports(level, station).getFirst();
      var source = DockingPorts.firstPort(level, ship);
      station.putLong(
          "constructionSourcePort",
          source.controller().subtract(WorldStore.pos(ship, "base")).asLong());
      StationConstruction.connectConstruction(server, station);
      var dock = ship.getCompoundOrEmpty("dock");
      // Old platform connections used .05 even for a ship whose nose extends past its door.
      pose(ship, DockingManeuver.matingPose(ship, source, station, target, .05));
      dock.putString("state", "CLAMPED");
      StationConstruction.commissionConstruction(server, station);
      ctx.assertTrue(
          !dock.getBooleanOr("construction", true) && dock.getDoubleOr("gap", 0) > 1,
          "Commissioning recalculates the finished room's real hull separation");
      DockingManeuver.tickMotion(server, ship);
      DockingService.perform(player, ship, WorldStore.pos(ship, "controller"), "DOCK_UNDOCK");
      finishDeparture(ctx, level, ship, station);
    } finally {
      server.getPlayerList().remove(player);
      WorldStore.get(server).ships().remove(ship);
      WorldStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:docking_presets", maxTicks = 400)
  public void anOldPlatformOverlapCanRetreatAlongItsSavedPathAfterStationPortDemolition(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = courier(level, new BlockPos(2330000, 96, 2300000));
    var station = StationGameTest.fixture(level, new BlockPos(2331024, 96, 2300000), false);
    var player = board(ctx, level, ship);
    try {
      var source = DockingPorts.firstPort(level, ship);
      var target = DockingPorts.ports(level, station).getFirst();
      var ready =
          DockingManeuver.request(
              player,
              ship,
              station.getStringOr("id", ""),
              target.controller().subtract(WorldStore.pos(station, "base")));
      ctx.assertTrue(ready.ready(), ready.message());
      var dock = ship.getCompoundOrEmpty("dock");
      dock.putString("state", "CLAMPED");
      dock.remove("gap");
      var oldGoal = DockingManeuver.matingPose(ship, source, station, target, .05);
      pose(ship, oldGoal);
      escape(dock, oldGoal, station, target.facing());
      level.setBlock(target.outer(), Blocks.AIR.defaultBlockState(), 18);
      level.setBlock(target.controller(), Blocks.AIR.defaultBlockState(), 18);
      DockingService.perform(player, ship, WorldStore.pos(ship, "controller"), "DOCK_UNDOCK");
      DockingManeuver.tickMotion(server, ship);
      ctx.assertTrue(
          SceneService.pose(ship).position().distanceTo(oldGoal.position()) > .3,
          "The saved pre-fix overlap can shrink without requiring a rebuilt station port");
      var obstruction = new CompoundTag();
      obstruction.putString("id", UUID.randomUUID().toString());
      obstruction.putString("dimension", TravelService.SPACE.identifier().toString());
      obstruction.putString("localSpace", "sol_terra");
      var obstructionBase = new BlockPos(2332048, 96, 2300000);
      obstruction.putLong("base", obstructionBase.asLong());
      level.setBlock(obstructionBase, Blocks.IRON_BLOCK.defaultBlockState(), 18);
      var current = SceneService.pose(ship);
      var normal =
          FlightDynamics.rotate(
              SceneService.pose(station).orientation(), DockingMotion.normal(target.facing()));
      pose(
          obstruction,
          new ScenePose(current.position().add(normal.scale(50)), current.orientation()));
      WorldStore.get(server).ships().add(obstruction);
      try {
        DockingManeuver.tickMotion(server, ship);
        ctx.assertTrue(
            SceneService.pose(ship).position().distanceTo(current.position()) < 1e-6
                && ship.getStringOr("flightStatus", "").contains("clear the departure corridor"),
            "Recovery cannot drive through a newly obstructing hull");
      } finally {
        WorldStore.get(server).ships().remove(obstruction);
      }
      finishDeparture(ctx, level, ship, station);
    } finally {
      server.getPlayerList().remove(player);
      WorldStore.get(server).ships().remove(ship);
      WorldStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:docking_presets")
  public void plannedRoomsCannotCoverAClampedShipsDepartureAndBlockedDoorsNameTheirBlock(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = courier(level, new BlockPos(2340000, 96, 2300000));
    var station = StationGameTest.fixture(level, new BlockPos(2341024, 96, 2300000), false);
    var player = board(ctx, level, ship);
    try {
      SystemsUpgrade.resizeStation(level, station, 128, 128);
      ShipSystems.tick(level, station);
      var source = DockingPorts.firstPort(level, ship);
      var target =
          DockingPorts.ports(level, station).stream()
              .filter(p -> p.facing() == Direction.EAST)
              .findFirst()
              .orElseThrow();
      capture(ctx, level, ship, station, player, target);
      var inFutureRoom =
          ShipSystems.connected(level, ship).stream()
              .map(
                  p ->
                      BlockPos.containing(
                          SceneService.pose(station)
                              .toLocal(
                                  SceneService.pose(ship)
                                      .toUniverse(
                                          Vec3.atCenterOf(
                                              p.subtract(WorldStore.pos(ship, "base")))))))
              .filter(p -> p.getX() >= 32 && p.getX() < 128 && p.getZ() >= 0 && p.getZ() < 128)
              .findFirst()
              .orElseThrow();
      var plan = Map.of(inFutureRoom, Blocks.QUARTZ_BLOCK.defaultBlockState());
      var problem = StationConstruction.constructionProblem(level, station, plan);
      ctx.assertTrue(
          problem.isPresent()
              && problem.get().message().contains("departure corridor")
              && problem
                  .get()
                  .position()
                  .equals(WorldStore.pos(station, "base").offset(inFutureRoom)),
          "Construction identifies the ship and exact block before consuming a kit or changing"
              + " terrain");
      DockingService.perform(player, ship, WorldStore.pos(ship, "controller"), "DOCK_UNDOCK");
      finishDeparture(ctx, level, ship, station);
      var obstruction = source.outer().relative(source.facing());
      level.setBlock(obstruction, Blocks.COBBLESTONE.defaultBlockState(), 18);
      var denied =
          DockingManeuver.request(
              player,
              ship,
              station.getStringOr("id", ""),
              target.controller().subtract(WorldStore.pos(station, "base")));
      ctx.assertTrue(
          !denied.ready() && denied.message().contains(obstruction.toShortString()),
          "A doorway obstruction is explained with coordinates before docking starts");
    } finally {
      server.getPlayerList().remove(player);
      WorldStore.get(server).ships().remove(ship);
      WorldStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }
}
