package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.shipyard.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import org.joml.Vector3d;

public final class DockingPresetGameTest {
  @GameTest(environment = "nullspace:docking_presets")
  public void capturedTunnelKeepsBlackoutBoardingSafeAndPreservesExistingBlocks(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship =
        com.nullandkale.nullspace.ship.StationGameTest.fixture(
            level, new BlockPos(2213000, 96, 2200000), true);
    var station =
        com.nullandkale.nullspace.ship.StationGameTest.fixture(
            level, new BlockPos(2214000, 96, 2200000), false);
    var player = ctx.makeMockServerPlayerInLevel();
    try {
      var source = DockingService.firstPort(level, ship);
      var target = DockingService.ports(level, station).getFirst();
      var at = source.anchors().getFirst();
      player.teleportTo(level, at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), 0, 0, true);
      DockingService.request(
          player,
          ship,
          station.getStringOr("id", ""),
          target.controller().subtract(ShipStore.pos(station, "base")));
      var dock = ship.getCompoundOrEmpty("dock");
      dock.putDouble("gap", 4.05);
      pose(ship, DockingService.matingPose(ship, source, station, target, 4.05));
      dock.putString("state", "CLAMPED");
      var existing = source.outer().below().relative(source.facing());
      level.setBlock(
          existing, net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
      DockingService.tickMotion(server, ship);
      ctx.assertTrue(
          dock.contains("tunnel")
              && DockingTunnel.sections(level, ship) == 1
              && DockingTunnel.sections(level, station) == 1,
          "Both storage frames have persistent, rendered boarding collars");
      var cells =
          dock.getCompoundOrEmpty("tunnel").getCompoundOrEmpty("source").getListOrEmpty("blocks");
      var floor =
          cells.stream()
              .map(t -> (CompoundTag) t)
              .filter(t -> t.getStringOr("kind", "").equals("floor"))
              .findFirst()
              .orElseThrow();
      var step = ShipStore.pos(floor, "at").above();
      ctx.assertTrue(
          !ShipService.inspect(level, ShipStore.pos(ship, "controller"))
              .scan()
              .blocks()
              .contains(ShipStore.pos(floor, "at")),
          "A docked ship can still be inspected; temporary collars are not captured as hull or"
              + " loose cargo");
      player.teleportTo(step.getX() + .5, step.getY(), step.getZ() + .5);
      boolean blocked = false;
      try {
        DockingService.perform(player, ship, ShipStore.pos(ship, "controller"), "DOCK_UNDOCK");
      } catch (IllegalArgumentException occupied) {
        blocked = occupied.getMessage().contains("inside a cabin");
      }
      ctx.assertTrue(blocked, "A tunnel cannot retract under a passenger");
      player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
      for (var vessel : List.of(ship, station)) {
        vessel.getCompoundOrEmpty("systems").putIntArray("requests", new int[6]);
        ShipSystems.tick(level, vessel);
        ctx.assertTrue(
            ShipSystems.power(level, vessel, PowerBus.System.CONTROLS) == 0,
            "Fixture is fully unpowered");
      }
      DockingService.tickMotion(server, ship);
      boolean noPropulsion = false;
      try {
        DockingService.perform(player, ship, source.controller(), "DOCK_UNDOCK");
      } catch (IllegalArgumentException expected) {
        noPropulsion = expected.getMessage().contains("Your ship needs");
      }
      ctx.assertTrue(
          noPropulsion
              && dock.getStringOr("state", "").equals("CLAMPED")
              && dock.contains("tunnel"),
          "An unpowered source cannot retract the safe connection and begin departure");
      DockingService.perform(player, ship, source.controller(), "DOCK_BOARD");
      ctx.assertTrue(
          DockingService.busy(player),
          "A captured connection permits boarding without either ship's power");
      ShipStore.get(server)
          .list("stationBoarding")
          .removeIf(
              t -> ((CompoundTag) t).getStringOr("player", "").equals(player.getUUID().toString()));
      // A delayed movement packet can reach the collar's end stop between server ticks.
      for (var vessel : List.of(ship, station)) {
        var port = vessel == ship ? source : target;
        var end =
            net.minecraft.world.phys.Vec3.atBottomCenterOf(port.outer())
                .add(DockingMotion.normal(port.facing()).scale(3.2));
        ShipDoors.setOpen(level, port.outer(), false);
        player.teleportTo(end.x, end.y, end.z);
        DockingService.walkThrough(server);
        ctx.assertTrue(!DockingService.busy(player), "A closed door still prevents boarding");
        ShipDoors.setOpen(level, port.outer(), true);
        var side = DockingMotion.normal(port.facing().getClockWise()).scale(2);
        player.teleportTo(end.x + side.x, end.y, end.z + side.z);
        DockingService.walkThrough(server);
        ctx.assertTrue(!DockingService.busy(player), "Passengers beside the collar cannot board");
        player.teleportTo(end.x, end.y, end.z);
        DockingService.walkThrough(server);
        ctx.assertTrue(
            DockingService.busy(player),
            "A passenger at either collar end stop boards during a blackout after delayed"
                + " movement");
        ShipStore.get(server)
            .list("stationBoarding")
            .removeIf(
                t ->
                    ((CompoundTag) t)
                        .getStringOr("player", "")
                        .equals(player.getUUID().toString()));
        ShipDoors.setOpen(level, port.outer(), false);
      }
      player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
      // Cancel an interrupted departure to exercise durable collar removal.
      dock.putString("state", "UNDOCKING");
      DockingService.perform(player, ship, source.controller(), "DOCK_CANCEL");
      ctx.assertTrue(
          level.getBlockState(ShipStore.pos(floor, "at")).isAir()
              && level
                  .getBlockState(existing)
                  .is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK),
          "Retraction removes only temporary collar blocks, retaining the original hull");
    } finally {
      server.getPlayerList().remove(player);
      ShipStore.get(server).ships().remove(ship);
      ShipStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:docking_presets")
  public void compactDockAcceptsADoorPlacedFacingIntoTheCabin(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship =
        com.nullandkale.nullspace.ship.StationGameTest.fixture(
            level, new BlockPos(2210000, 96, 2200000), true);
    var station =
        com.nullandkale.nullspace.ship.StationGameTest.fixture(
            level, new BlockPos(2211000, 96, 2200000), false);
    var player = ctx.makeMockServerPlayerInLevel();
    try {
      var original = DockingService.firstPort(level, ship);
      for (var door : List.of(original.outer(), original.outer().above()))
        level.setBlock(
            door,
            level
                .getBlockState(door)
                .setValue(
                    net.minecraft.world.level.block.DoorBlock.FACING,
                    original.facing().getOpposite()),
            18);
      var inwardDoor = DockingService.firstPort(level, ship);
      ctx.assertTrue(
          inwardDoor.facing() == original.facing()
              && inwardDoor.anchors().equals(original.anchors()),
          "The Docking Base defines the exterior and cabin even when the door faces inward");
      var at = com.nullandkale.nullspace.ship.StationGameTest.boardingPoint(level, ship);
      player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
      var target = DockingService.ports(level, station).getFirst();
      var requested =
          DockingService.request(
              player,
              ship,
              station.getStringOr("id", ""),
              target.controller().subtract(ShipStore.pos(station, "base")));
      ctx.assertTrue(
          requested.ready() && ship.contains("dock"),
          "An inward-facing ordinary door can start docking");
      ShipStore.get(server).ships().remove(station);
      DockingService.tickMotion(server, ship);
      ctx.assertTrue(
          !ship.contains("dock")
              && ship.getStringOr("flightStatus", "")
                  .equals("Target unavailable; maneuver cancelled")
              && ship.getIntOr("thrustDemand", -1) == 0,
          "A failure after starting stops safely and retains the logged reason without an open"
              + " controller");
    } finally {
      server.getPlayerList().remove(player);
      ShipStore.get(server).ships().remove(ship);
      ShipStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }

  private static void pose(CompoundTag ship, ScenePose pose) {
    ship.putDouble("simX", pose.position().x);
    ship.putDouble("simY", pose.position().y);
    ship.putDouble("simZ", pose.position().z);
    var angles = pose.orientation().getEulerAnglesYXZ(new Vector3d());
    ship.putDouble("simYaw", Math.toDegrees(angles.y));
    ship.putDouble("simPitch", Math.toDegrees(angles.x));
    ship.putDouble("simRoll", Math.toDegrees(angles.z));
  }

  @GameTest(environment = "nullspace:docking_presets", maxTicks = 400)
  public void allDeliveredHullsClampAndBackAwayWithoutEntryStepsOrCamerasColliding(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var station =
        com.nullandkale.nullspace.ship.StationGameTest.fixture(
            level, new BlockPos(2200000, 96, 2200000), false);
    station.putDouble("simX", 3000);
    station.putDouble("simY", 1000);
    station.putDouble("simZ", -3000);
    var target = DockingService.ports(level, station).getFirst();
    var player = ctx.makeMockServerPlayerInLevel();
    int i = 0;
    for (var preset : ShipBlueprints.PRESETS) {
      var base = new BlockPos(2201024 + i++ * 128, 96, 2200000);
      var ship = new CompoundTag();
      ship.putString("id", UUID.randomUUID().toString());
      ship.putString("dimension", TravelService.SPACE.identifier().toString());
      ship.putString("localSpace", "sol_terra");
      ship.putLong("base", base.asLong());
      ship.putLong("controller", base.offset(preset.helm()).asLong());
      ship.putInt("width", 32);
      ship.putInt("depth", 32);
      ship.putInt("height", 32);
      ship.put("crew", new ListTag());
      ShipStore.get(server).ships().add(ship);
      for (var e : ShipBlueprints.blocks(preset).entrySet())
        level.setBlock(base.offset(e.getKey()), e.getValue(), 18);
      for (var p : ShipBlueprints.blocks(preset).keySet())
        FuelSystem.provision(level, base.offset(p), 64);
      ShipSystems.upgrade(level, ship);
      ship.getCompoundOrEmpty("systems")
          .putIntArray("requests", new int[] {1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
      ShipSystems.tick(level, ship);
      var helm = ShipStore.pos(ship, "controller");
      var front =
          helm.relative(
              level
                  .getBlockState(helm)
                  .getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING));
      player.teleportTo(
          level, front.getX() + .5, front.getY(), front.getZ() + .5, Set.of(), 0, 0, true);
      var ready =
          DockingService.request(
              player,
              ship,
              station.getStringOr("id", ""),
              target.controller().subtract(ShipStore.pos(station, "base")));
      ctx.assertTrue(ready.ready(), preset.name() + ": " + ready.message());
      var source = DockingService.firstPort(level, ship);
      var d = ship.getCompoundOrEmpty("dock");
      var goal =
          DockingService.matingPose(ship, source, station, target, d.getDoubleOr("gap", .05));
      var normal =
          SceneService.pose(station)
              .toUniverse(DockingMotion.normal(target.facing()))
              .subtract(SceneService.pose(station).position());
      pose(ship, new ScenePose(goal.position().add(normal.scale(2)), goal.orientation()));
      d.putString("state", "CONNECTING");
      for (int step = 0;
          step < 10 && !ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED");
          step++) DockingService.tickMotion(server, ship);
      ctx.assertTrue(
          ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"),
          preset.name() + " clamps: " + ship.getStringOr("flightStatus", ""));
      DockingService.perform(player, ship, helm, "DOCK_UNDOCK");
      for (int step = 0; step < 8; step++) DockingService.tickMotion(server, ship);
      ctx.assertTrue(
          ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("UNDOCKING")
              && SceneService.pose(ship).position().distanceTo(goal.position()) > 1,
          preset.name() + " backs away without collision");
      DockingService.perform(player, ship, helm, "DOCK_CANCEL");
      ShipStore.get(server).ships().remove(ship);
    }
    ShipStore.get(server).ships().remove(station);
    server.getPlayerList().remove(player);
    ctx.succeed();
  }
}
