package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.testing.TestZones;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;

public final class NavigationGameTest {
  @GameTest
  public void arrivalPlacementAvoidsShipsButReusesCoordinatesAcrossRegions(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var store = WorldStore.get(server);
    var own = new CompoundTag();
    own.putString("id", UUID.randomUUID().toString());
    own.putString("dimension", TravelService.SPACE.identifier().toString());
    own.putLong("base", new BlockPos(0, 96, 0).asLong());
    own.putString("localSpace", TestZones.otherSystem(server));
    var first = NavigationService.arrivalOrigin(server, own);
    var neighbor = own.copy();
    neighbor.putString("id", UUID.randomUUID().toString());
    neighbor.putDouble("simX", first.x);
    neighbor.putDouble("simY", first.y);
    neighbor.putDouble("simZ", first.z);
    store.ships().add(neighbor);
    try {
      var second = NavigationService.arrivalOrigin(server, own);
      ctx.assertTrue(
          first.distanceTo(second) >= 160, "Arrival cannot overlap a ship already in that region");
      neighbor.putString("localSpace", "sol_luna");
      ctx.assertTrue(
          NavigationService.arrivalOrigin(server, own).equals(first),
          "Independent regions can reuse simulated coordinates");
      ctx.assertTrue(
          second.add(16, 16, 16).length() < FlightDynamics.LOCAL_RADIUS,
          "Arrival remains inside the local-flight boundary");
    } finally {
      store.ships().remove(neighbor);
    }
    ctx.succeed();
  }

  @GameTest(maxTicks = 80)
  public void pilotLeaseRejectsStolenReplayAndUnknownInputsAndStopsOnRelease(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var anchor = ctx.absolutePos(new BlockPos(27000, 96, 27000));
    var base = new BlockPos(anchor.getX(), 96, anchor.getZ());
    var helm = ShipTestFixture.build(level, base);
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", TravelService.SPACE.identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", helm.asLong());
    ship.putDouble("simX", -2500);
    ship.putDouble("simY", 0);
    ship.putDouble("simZ", -2500);
    WorldStore.get(server).ships().add(ship);
    var p = ctx.makeMockServerPlayerInLevel();
    p.teleportTo(
        level, base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5, Set.of(), 0, 0, true);
    var stranger = ctx.makeMockServerPlayerInLevel();
    stranger.teleportTo(
        level, base.getX() + 4.5, base.getY() + 3, base.getZ() + 4.5, Set.of(), 0, 0, true);
    var lease = NavigationService.open(p, helm, true);
    boolean denied = false;
    try {
      NavigationService.open(stranger, helm, true);
    } catch (IllegalArgumentException expected) {
      denied = true;
    }
    ctx.assertTrue(
        denied
            && !NavigationService.input(stranger, new NavigationService.Input(lease, 0, 1, false)),
        "Another player cannot take over or spoof this lease");
    ctx.assertTrue(
        NavigationService.input(
            p, new NavigationService.Input(lease, 5, FlightDynamics.FORWARD, false)),
        "Owner's fresh input must be accepted");
    ctx.assertTrue(
        !NavigationService.input(p, new NavigationService.Input(lease, 5, 0, false))
            && !NavigationService.input(p, new NavigationService.Input(lease, 6, 8192, false)),
        "Replays and unknown controls cannot change flight");
    var original = p.position();
    ctx.runAfterDelay(
        10,
        () -> {
          try {
            ctx.assertTrue(
                Math.abs(
                        WorldStore.get(server)
                                .at(level, p.blockPosition())
                                .getDoubleOr("simX", -2500)
                            + 2500)
                    > .1,
                "Server ticks apply valid thrust");
            ctx.assertTrue(
                p.position().distanceToSqr(original) < .01,
                "Crew remains stationary in physical storage");
            ctx.assertTrue(
                NavigationService.input(p, new NavigationService.Input(lease, 7, 0, true)),
                "Owner can release controls");
            var current = WorldStore.get(server).at(level, p.blockPosition());
            ctx.assertTrue(
                current.getDoubleOr("simVX", 1) == 0 && current.getDoubleOr("simVZ", 1) == 0,
                "Release stops velocity");
            ctx.succeed();
          } finally {
            NavigationService.closeShip(server, ship.getStringOr("id", ""));
            server.getPlayerList().remove(p);
            server.getPlayerList().remove(stranger);
            WorldStore.get(server)
                .ships()
                .removeIf(
                    t ->
                        ((CompoundTag) t).getStringOr("id", "").equals(ship.getStringOr("id", "")));
          }
        });
  }

  @GameTest
  public void regionsHaveStableDefinitionsAndLegacyShipsShareTerra(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var legacy = new CompoundTag();
    var other = new CompoundTag();
    ctx.assertTrue(LocalSpaces.together(legacy, other), "Existing saves default to Terra");
    other.putString("localSpace", "sol_luna");
    ctx.assertTrue(
        !LocalSpaces.together(legacy, other), "Separate orbital regions cannot share visibility");
    var ids = new HashSet<String>();
    for (var destination : LocalSpaces.DESTINATIONS) {
      var encoded = SceneCatalog.region(server, destination.id());
      var decoded = SpaceDefinitionCodec.decode(encoded);
      ctx.assertTrue(
          ids.add(decoded.id()) && !decoded.bodies().isEmpty(),
          "Every route must have its own valid celestial definition");
      ctx.assertTrue(
          SceneCatalog.region(server, destination.id()) == encoded,
          "Reading a region reuses its persisted definition");
    }
    ctx.succeed();
  }

  @GameTest
  public void navigationSessionsRejectForgedRoutesAndCrossControllerActions(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(23000, 64, 23000));
    var helm = ShipTestFixture.build(level, base);
    var p = ctx.makeMockServerPlayerInLevel();
    p.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
    var open = ComputerTerminal.issue(p, helm, "Ready", "navigation", LocalSpaces.HOME);
    ctx.assertTrue(
        open.destinations().size() == LocalSpaces.destinations(level.getServer()).size(),
        "Navigation exposes the finite server route catalog");
    for (var request :
        List.of(
            new ComputerTerminal.Request(
                open.session(), ComputerTerminal.Action.FAST_TRAVEL, "fake_route", ""),
            new ComputerTerminal.Request(open.session(), ComputerTerminal.Action.LAUNCH, "", ""))) {
      boolean rejected = false;
      try {
        ComputerTerminal.authorize(p, request);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      ctx.assertTrue(rejected, "Only this controller's issued route/action may be used");
    }
    ctx.assertTrue(
        ComputerTerminal.authorize(
                p,
                new ComputerTerminal.Request(
                    open.session(), ComputerTerminal.Action.FAST_TRAVEL, "sol_luna", ""))
            .argument()
            .equals("sol_luna"),
        "Valid route retains stable address");
    level.getServer().getPlayerList().remove(p);
    ctx.succeed();
  }
}
