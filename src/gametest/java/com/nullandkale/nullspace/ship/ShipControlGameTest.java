package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;

public final class ShipControlGameTest {
  @GameTest(environment = "nullspace:ship_control", maxTicks = 120)
  public void movingDestinationAndDisconnectedPilotPauseBeforeStartingMovement(GameTestHelper ctx)
      throws Exception {
    var client = ConcurrentShipActionsGameTest.client(ctx, 47);
    client.board();
    var server = ctx.getLevel().getServer();
    var target =
        StationGameTest.fixture(client.player().level(), new BlockPos(360000, 96, 350000), false);
    String ownId = client.fixture().id;
    var destination =
        ShipDestination.navigationCatalog(client.player(), client.fixture().ship()).stream()
            .filter(
                d ->
                    d.kind() == ShipDestination.Kind.VESSEL
                        && d.reference().equals(target.getStringOr("id", "")))
            .findFirst()
            .orElseThrow();
    ctx.assertTrue(
        CourseService.engage(client.player(), client.fixture().ship(), destination)
            .startsWith("Course engaged"),
        "Course accepted before the target moves");
    target.putString("localSpace", "sol_luna");
    ctx.runAfterDelay(
        20,
        () -> {
          var ship = ShipStore.get(server).repository().find(ownId);
          ctx.assertTrue(
              !CourseService.active(ship)
                  && CourseService.summary(ship).contains("Destination moved"),
              "Moved destination pauses rather than silently following another region");
          ctx.assertTrue(
              !ship.contains("dock") && !NavigationService.pending(server, ownId),
              "No docking or jump started");
          target.putString("localSpace", "sol_terra");
          ctx.assertTrue(
              CourseService.engage(client.player(), ship, destination).startsWith("Course engaged"),
              "A reviewed course can be engaged again");
          client.close();
        });
    ctx.runAfterDelay(
        40,
        () -> {
          try {
            var ship = ShipStore.get(server).repository().find(ownId);
            ctx.assertTrue(
                !CourseService.active(ship)
                    && CourseService.summary(ship).contains("pilot disconnected"),
                "No unattended course continues after its pilot disconnects");
            ctx.succeed();
          } finally {
            ShipStore.get(server)
                .ships()
                .removeIf(
                    t ->
                        ((CompoundTag) t).getStringOr("id", "").equals(ownId)
                            || ((CompoundTag) t)
                                .getStringOr("id", "")
                                .equals(target.getStringOr("id", "")));
          }
        });
  }

  @GameTest(environment = "nullspace:ship_control")
  public void stockScannerUnlocksInterstellarButLocalTravelNeedsNoScannerAndStationsAreGrouped(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var own = StationGameTest.fixture(level, new BlockPos(358000, 96, 350000), true);
    var station = StationGameTest.fixture(level, new BlockPos(359000, 96, 350000), false);
    var player = ctx.makeMockServerPlayerInLevel();
    var at = StationGameTest.boardingPoint(level, own);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    try {
      var catalog = ShipDestination.navigationCatalog(player, own);
      ctx.assertTrue(
          catalog.stream()
                  .filter(
                      d ->
                          d.kind() == ShipDestination.Kind.VESSEL
                              && d.reference().equals(station.getStringOr("id", "")))
                  .count()
              == 1,
          "Two berths appear as one station destination");
      for (var pos : ShipSystems.connected(level, own))
        if (level.getBlockState(pos).is(ShipBlocks.LOCAL_SCANNER))
          ShipSystems.setEnabled(level, own, pos, false);
      ctx.assertTrue(
          CourseService.scannerReason(player, own, "sol_luna").isEmpty(),
          "Local travel does not require a Scanner");
      ctx.assertTrue(
          CourseService.scannerReason(
                  player,
                  own,
                  com.nullandkale.nullspace.navigation.TestZones.otherSystem(level.getServer()))
              .contains("Scanner"),
          "Interstellar travel requires a powered Scanner");
      for (var pos : ShipSystems.connected(level, own))
        if (level.getBlockState(pos).is(ShipBlocks.LOCAL_SCANNER))
          ShipSystems.setEnabled(level, own, pos, true);
      ShipSystems.tick(level, own);
      ctx.assertTrue(
          CourseService.scannerReason(
                  player,
                  own,
                  com.nullandkale.nullspace.navigation.TestZones.otherSystem(level.getServer()))
              .isEmpty(),
          "A stock Scanner suffices without upgrade items");
      var local =
          catalog.stream()
              .filter(d -> d.kind() == ShipDestination.Kind.REGION && d.region().equals("sol_luna"))
              .findFirst()
              .orElseThrow();
      ctx.assertTrue(
          CourseService.engage(player, own, local).startsWith("Course engaged"),
          "A valid course is saved");
      ctx.assertTrue(
          CourseService.active(own)
              && own.getCompoundOrEmpty("course")
                  .getCompoundOrEmpty("destination")
                  .equals(local.save()),
          "Saved course retains final destination identity");
      CourseService.investigate(player, own);
      ctx.assertTrue(
          CourseService.active(own)
              && own.getCompoundOrEmpty("course").getBooleanOr("awaitingEncounter", false),
          "Investigate arms docking at the signal instead of requiring another Engage");
      CourseService.disengage(server, own);
      ctx.assertTrue(
          own.getCompoundOrEmpty("course").getStringOr("state", "").equals("STOPPING"),
          "The pilot can cancel before arrival");
    } finally {
      server.getPlayerList().remove(player);
      ShipStore.get(server).ships().remove(own);
      ShipStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_control")
  public void destinationAcknowledgementDoesNotThrottleTheNextControl(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = StationGameTest.fixture(level, new BlockPos(357000, 96, 350000), true);
    var player = ctx.makeMockServerPlayerInLevel();
    var at = StationGameTest.boardingPoint(level, ship);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    try {
      var view = ShipControl.open(player, ShipStore.pos(ship, "controller"), "Ready");
      var target =
          ShipDestination.catalog(player, ship).stream()
              .filter(d -> d.kind() == ShipDestination.Kind.REGION && d.region().equals("sol_luna"))
              .findFirst()
              .orElseThrow();
      var select = new ShipControl.Input(view.session(), ShipControl.Action.SELECT, target.id());
      ctx.assertTrue(
          ShipControl.input(player, select).startsWith("Destination set:"),
          "The destination is saved before travel");
      ctx.assertTrue(
          ShipControl.input(player, select).contains("Wait a moment"),
          "Repeated selections remain throttled");
      var explain = new ShipControl.Input(view.session(), ShipControl.Action.EXPLAIN, "JUMP");
      ctx.assertTrue(
          ShipControl.input(player, explain).startsWith("Jump is ready"),
          "The next control works in the same server tick as destination selection");
      ctx.assertTrue(
          ShipControl.input(player, explain).contains("Wait a moment"),
          "Repeated controls remain throttled independently");
      ctx.assertTrue(!NavigationService.pending(server), "Explaining readiness starts no travel");
    } finally {
      ShipStore.get(server).ships().remove(ship);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_control")
  public void activeJourneyKeepsItsDestinationAndRemoteEncounterPortsStayOutOfTheChart(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = StationGameTest.fixture(level, new BlockPos(355000, 96, 350000), true);
    var station = StationGameTest.fixture(level, new BlockPos(356000, 96, 350000), false);
    var player = ctx.makeMockServerPlayerInLevel();
    var at = StationGameTest.boardingPoint(level, ship);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    var target =
        new ShipDestination(
            ShipDestination.Kind.REGION, "sol_luna", BlockPos.ZERO, "sol_luna", "Luna", "Orbit");
    ShipDestination.select(player, ship, target);
    var j = JourneyService.plan(server, ship, "sol_luna", 2000);
    JourneyService.register(server, j);
    String encounter = j.getStringOr("encounter", "");
    station.putString("localSpace", encounter);
    ship.put("journey", j);
    try {
      boolean denied = false;
      try {
        ShipDestination.select(
            player,
            ship,
            new ShipDestination(
                ShipDestination.Kind.REGION,
                "sol_ares",
                BlockPos.ZERO,
                "sol_ares",
                "Ares",
                "Orbit"));
      } catch (IllegalArgumentException expected) {
        denied = expected.getMessage().contains("Journey underway");
      }
      ctx.assertTrue(
          denied && ShipDestination.selected(ship).region().equals("sol_luna"),
          "Choosing another place cannot silently change a paid journey's destination");
      ctx.assertTrue(
          ShipDestination.catalog(player, ship).stream()
              .noneMatch(d -> d.reference().equals(station.getStringOr("id", ""))),
          "Unreachable remote encounter ports are not advertised as ordinary jump destinations");
      ship.putString("localSpace", encounter);
      j.putString("state", "STOPPED");
      ctx.assertTrue(
          ShipDestination.catalog(player, ship).stream()
              .anyMatch(d -> d.reference().equals(station.getStringOr("id", ""))),
          "After investigation the ordinary destination picker finds the encounter's real port");
    } finally {
      ShipStore.get(server).ships().remove(ship);
      ShipStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_control")
  public void occupiedDocksRemainDiscoverableAndExplainWhyDockIsDisabled(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var own = StationGameTest.fixture(level, new BlockPos(353000, 96, 350000), true);
    var station = StationGameTest.fixture(level, new BlockPos(354000, 96, 350000), false);
    var player = ctx.makeMockServerPlayerInLevel();
    var at = StationGameTest.boardingPoint(level, own);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    var port = com.nullandkale.nullspace.station.DockingService.ports(level, station).getFirst();
    var links = new CompoundTag();
    links.put(port.id(), new CompoundTag());
    station.put("dockLinks", links);
    try {
      var ports =
          ShipDestination.catalog(player, own).stream()
              .filter(
                  d ->
                      d.kind() == ShipDestination.Kind.PORT
                          && d.reference().equals(station.getStringOr("id", "")))
              .toList();
      ctx.assertTrue(ports.size() == 2, "An occupied berth does not make its station disappear");
      var occupied =
          ports.stream().filter(d -> d.state().equals("Occupied")).findFirst().orElseThrow();
      ctx.assertTrue(
          ShipControl.reason(player, own, occupied, ShipControl.Action.DOCK).contains("occupied"),
          "Explain berth occupancy instead of showing an empty list");
      ShipDestination.select(player, own, occupied);
      var view = ShipControl.open(player, ShipStore.pos(own, "controller"), "");
      var before = own.copy();
      var explanation =
          ShipControl.input(
              player, new ShipControl.Input(view.session(), ShipControl.Action.EXPLAIN, "DOCK"));
      ctx.assertTrue(
          explanation.equals("Dock blocked: Destination port is occupied") && before.equals(own),
          "A disabled click reports the server's real reason without starting a maneuver");
      view = ShipControl.open(player, ShipStore.pos(own, "controller"), "");
      before = own.copy();
      explanation =
          ShipControl.input(
              player, new ShipControl.Input(view.session(), ShipControl.Action.EXPLAIN, "INSPECT"));
      ctx.assertTrue(
          explanation.equals("Inspect is ready now. Press it again to start.")
              && before.equals(own),
          "An explanation cannot execute a control even when it becomes available");
      view = ShipControl.open(player, ShipStore.pos(own, "controller"), "");
      before = own.copy();
      var refused =
          ShipControl.input(
              player, new ShipControl.Input(view.session(), ShipControl.Action.DOCK, ""));
      ctx.assertTrue(
          refused.equals("Dock blocked: Destination port is occupied") && before.equals(own),
          "An actual refused docking request has the same clear diagnostic and changes no ship"
              + " state");
    } finally {
      server.getPlayerList().remove(player);
      ShipStore.get(server).ships().remove(own);
      ShipStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_control")
  public void listingRegisteredRemotePortsDoesNotLoadTheirTerrain(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var own = new CompoundTag();
    own.putString("id", UUID.randomUUID().toString());
    var station = new CompoundTag();
    station.putString("id", UUID.randomUUID().toString());
    station.putString("dimension", TravelService.SPACE.identifier().toString());
    station.putString("kind", "station");
    station.putString("access", "public");
    station.putString("localSpace", "sol_luna");
    var base = new BlockPos(800000, 96, 800000);
    station.putLong("base", base.asLong());
    var systems = new CompoundTag();
    var devices = new net.minecraft.nbt.ListTag();
    var device = new CompoundTag();
    device.putString("type", "nullspace:docking_base");
    device.putLong("position", new BlockPos(4, 5, 6).asLong());
    devices.add(device);
    systems.put("devices", devices);
    station.put("systems", systems);
    ShipStore.get(server).ships().add(station);
    var p = ctx.makeMockServerPlayerInLevel();
    try {
      ctx.assertTrue(!level.hasChunkAt(base), "Remote fixture starts unloaded");
      var ports = com.nullandkale.nullspace.station.DockingService.destinations(p, own, true);
      ctx.assertTrue(
          ports.stream()
              .anyMatch(
                  port ->
                      port.vessel().equals(station.getStringOr("id", ""))
                          && port.state().equals("Unverified")),
          "Registered remote port is selectable before loading");
      ctx.assertTrue(!level.hasChunkAt(base), "Listing destinations must not load remote chunks");
    } finally {
      ShipStore.get(server).ships().remove(station);
      server.getPlayerList().remove(p);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_control")
  public void scannerMarksAndControllerSelectsAndRejectsStolenSessions(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = StationGameTest.fixture(level, new BlockPos(350000, 96, 350000), true);
    var helm = ShipStore.pos(ship, "controller");
    var scanner = helm.above();
    level.setBlock(scanner, ShipBlocks.LONG_RANGE_SCANNER.defaultBlockState(), 18);
    ShipSystems.upgrade(level, ship);
    ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[] {1, 0, 0, 0, 5, 0});
    ShipSystems.tick(level, ship);
    var player = ctx.makeMockServerPlayerInLevel();
    var stranger = ctx.makeMockServerPlayerInLevel();
    var at = StationGameTest.boardingPoint(level, ship);
    for (var p : List.of(player, stranger))
      p.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    try {
      var view = ShipControl.open(player, scanner, "Scanner test");
      ctx.assertTrue(
          view != null && view.scanner() && !view.entries().isEmpty(),
          "Powered scanner discovers destinations");
      var target =
          view.entries().stream()
              .filter(e -> e.region().equals("sol_luna"))
              .findFirst()
              .orElseThrow();
      var stolen =
          ShipControl.input(
              stranger,
              new ShipControl.Input(view.session(), ShipControl.Action.SELECT, target.id()));
      ctx.assertTrue(
          stolen.contains("Reopen") && ShipDestination.selected(ship) == null,
          "Another player cannot use this scanner session");
      var result =
          ShipControl.input(
              player,
              new ShipControl.Input(view.session(), ShipControl.Action.SELECT, target.id()));
      ctx.assertTrue(
          result.startsWith("Destination set:")
              && ShipDestination.selected(ship).id().equals(target.id())
              && !CourseService.active(ship),
          "Scanner sets the destination directly without starting movement");
      var controller = ShipControl.open(player, helm, "Controller test");
      ctx.assertTrue(
          controller.selected().equals(target.id())
              && controller.buttons().stream()
                  .noneMatch(b -> b.action() == ShipControl.Action.BROWSE_SCANNER),
          "Controller sees the destination without exposing the Scanner browser");
      ctx.assertTrue(
          ShipControl.input(
                  player,
                  new ShipControl.Input(
                      controller.session(), ShipControl.Action.SELECT, target.id()))
              .startsWith("Destination set"),
          "Only the controller selects the course");
      controller = ShipControl.open(player, helm, "Controller test");
      var before = ship.copy();
      var forged =
          ShipControl.input(
              player,
              new ShipControl.Input(
                  controller.session(), ShipControl.Action.SELECT, UUID.randomUUID().toString()));
      ctx.assertTrue(
          forged.contains("Choose a destination") && before.equals(ship),
          "Forged destination changes no ship state or resources");
      var scannerAgain = ShipControl.open(player, scanner, "Scanner test");
      var travel =
          ShipControl.input(
              player, new ShipControl.Input(scannerAgain.session(), ShipControl.Action.JUMP, ""));
      ctx.assertTrue(
          travel.contains("Use the ship controller") && !NavigationService.pending(server),
          "A scanner session cannot initiate travel");
      player.teleportTo(at.x + 30, at.y, at.z);
      var distant =
          ShipControl.input(
              player,
              new ShipControl.Input(
                  scannerAgain.session(), ShipControl.Action.SELECT, target.id()));
      ctx.assertTrue(
          distant.contains("out of range"), "Moving away invalidates equipment authority");
    } finally {
      server.getPlayerList().remove(player);
      server.getPlayerList().remove(stranger);
      ShipStore.get(server).ships().remove(ship);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_control")
  public void finalPortRetainsItsRegionAndDoesNotFallBackWhenReserved(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = StationGameTest.fixture(level, new BlockPos(351000, 96, 350000), true);
    var station = StationGameTest.fixture(level, new BlockPos(352000, 96, 350000), false);
    station.putString("localSpace", "sol_luna");
    var player = ctx.makeMockServerPlayerInLevel();
    var at = StationGameTest.boardingPoint(level, ship);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    try {
      var helm = ShipStore.pos(ship, "controller");
      var view = ShipControl.open(player, helm, "Port test");
      var target =
          ShipDestination.catalog(player, ship).stream()
              .filter(
                  d ->
                      d.kind() == ShipDestination.Kind.PORT
                          && d.reference().equals(station.getStringOr("id", "")))
              .findFirst()
              .orElseThrow();
      ctx.assertTrue(
          ShipControl.reason(player, ship, target, ShipControl.Action.JUMP).isEmpty(),
          "Port destination supplies its jump region");
      ctx.assertTrue(
          ShipControl.reason(player, ship, target, ShipControl.Action.DOCK).contains("Jump"),
          "Dock requires the destination region first");
      ShipControl.input(
          player, new ShipControl.Input(view.session(), ShipControl.Action.SELECT, target.id()));
      com.nullandkale.nullspace.station.DockingService.contacts(player, ship);
      ctx.assertTrue(
          ShipDestination.selected(ship).id().equals(target.id()),
          "Legacy local contact listing cannot replace the selected remote destination");
      ship.putString("localSpace", "sol_luna");
      ctx.assertTrue(
          ShipDestination.selected(ship).id().equals(target.id())
              && ShipControl.reason(player, ship, target, ShipControl.Action.JUMP)
                  .contains("Already"),
          "Changing region preserves the final port");
      com.nullandkale.nullspace.station.DockingService.perform(player, ship, helm, "DOCK_NEXT");
      var cycled = ShipDestination.selected(ship);
      ctx.assertTrue(
          cycled.kind() == ShipDestination.Kind.PORT
              && !cycled.id().equals(target.id())
              && ShipControl.open(player, helm, "Legacy port selection")
                  .selected()
                  .equals(cycled.id()),
          "Legacy airlock controls update the same destination seen at the controller");
      ShipDestination.select(player, ship, target);
      station.put("dock", new CompoundTag());
      var reserved = ShipControl.open(player, helm, "Reserved test");
      ctx.assertTrue(
          reserved.selected().equals(target.id()) && reserved.destination().equals(target.name()),
          "Unavailable port stays selected and named");
      var blocked =
          ShipControl.input(
              player, new ShipControl.Input(reserved.session(), ShipControl.Action.DOCK, ""));
      ctx.assertTrue(
          blocked.toLowerCase(Locale.ROOT).contains("unavailable") && !ship.contains("dock"),
          "Reserved port disables docking without choosing a different berth");
      ctx.assertTrue(
          ShipControl.reason(
                  player,
                  ship,
                  new ShipDestination(
                      ShipDestination.Kind.REGION,
                      "sol_luna",
                      BlockPos.ZERO,
                      "sol_luna",
                      "Luna",
                      "Orbit"),
                  ShipControl.Action.LAND)
              .contains("landing pad"),
          "Region-only destinations cannot land");
    } finally {
      server.getPlayerList().remove(player);
      ShipStore.get(server).ships().remove(ship);
      ShipStore.get(server).ships().remove(station);
    }
    ctx.succeed();
  }
}
