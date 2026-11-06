package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.shipyard.ShipRooms;
import com.nullandkale.nullspace.station.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class JourneyGameTest {
  @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 800)
  public void investigateStartsDockingWithoutEngageAndDisengageKeepsItCancelled(GameTestHelper ctx)
      throws Exception {
    var c = ConcurrentShipActionsGameTest.client(ctx, 48);
    c.board();
    var server = ctx.getLevel().getServer();
    var own = c.fixture().ship();
    var target =
        StationGameTest.fixture(c.fixture().space, new BlockPos(750000, 96, 750000), false);
    var journey = JourneyService.plan(server, own, "sol_luna", 2000);
    journey.putString(
        "encounter", "e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
    journey.putInt("contactAt", 0);
    JourneyService.register(server, journey);
    own.put("journey", journey);
    own.putString("localSpace", journey.getStringOr("corridor", ""));
    String siteId = journey.getStringOr("encounter", "");
    target.putString("localSpace", siteId);
    target.putDouble("simX", 0);
    target.putDouble("simZ", 200);
    var site = ShipStore.get(server).section("encounters").getCompoundOrEmpty(siteId);
    site.putString("vessel", target.getStringOr("id", ""));
    site.putString("state", "placed");
    int[] cancelledAt = {-1}, stoppedAt = {-1};
    ctx.onEachTick(
        () -> {
          acknowledge(c);
          var ship = c.fixture().ship();
          if (ship.getCompoundOrEmpty("course").getStringOr("state", "").equals("PAUSED"))
            throw new AssertionError(CourseService.summary(ship));
          if (cancelledAt[0] < 0 && ship.contains("dock")) {
            ctx.assertTrue(
                !NavigationService.pending(server, c.fixture().id)
                    && LocalSpaces.address(ship).equals(siteId),
                "Docking starts after the arrival handshake");
            ctx.assertTrue(
                CourseService.active(ship)
                    && ship.getCompoundOrEmpty("dock")
                        .getStringOr("target", "")
                        .equals(target.getStringOr("id", "")),
                "Investigate approaches the actual encounter without another Engage or Dock input");
            CourseService.disengage(server, ship);
            cancelledAt[0] = server.getTickCount();
          } else if (cancelledAt[0] >= 0 && !ship.contains("dock") && !CourseService.active(ship)) {
            if (stoppedAt[0] < 0) stoppedAt[0] = server.getTickCount();
            if (server.getTickCount() - stoppedAt[0] < 20) return;
            try {
              ctx.assertTrue(
                  !JourneyService.cruising(ship),
                  "Cancelling docking does not resume the paid journey");
              ctx.assertTrue(
                  ship.getCompoundOrEmpty("course").getStringOr("state", "").equals("STOPPED"),
                  "Automatic docking stays cancelled");
              ctx.succeed();
            } finally {
              ShipStore.get(server)
                  .ships()
                  .removeIf(
                      t ->
                          ((CompoundTag) t).getStringOr("id", "").equals(c.fixture().id)
                              || ((CompoundTag) t)
                                  .getStringOr("id", "")
                                  .equals(target.getStringOr("id", "")));
              ShipStore.get(server).section("encounters").remove(siteId);
              c.close();
            }
          }
        });
    NavigationService.investigate(c.player(), ShipStore.pos(own, "controller"));
  }

  @GameTest(environment = "nullspace:encounter_retry", maxTicks = 300)
  public void encounterSaveFailureKeepsThePlanAndBacksOffWithoutCrashingTheServer(
      GameTestHelper ctx) throws Exception {
    var c = ConcurrentShipActionsGameTest.client(ctx, 44);
    var server = ctx.getLevel().getServer();
    var j = JourneyService.plan(server, c.fixture().ship(), "sol_luna", 2000);
    j.putString("encounter", "e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
    j.putInt("contactAt", 0);
    JourneyService.register(server, j);
    c.fixture().ship().put("journey", j);
    boolean[] done = {false};
    ctx.onEachTick(
        () -> {
          if (done[0] || server.getTickCount() % 20 != 0 || StationService.building(server)) return;
          done[0] = true;
          int[] calls = {0};
          var previous = ShipStore.writeObserver;
          try {
            ShipStore.writeObserver =
                progress -> {
                  calls[0]++;
                  throw new IllegalStateException("Injected encounter save failure");
                };
            EncounterSites.tick(server);
            ctx.assertTrue(
                calls[0] > 0, "Encounter construction reached the durable registration write");
            var site =
                ShipStore.get(server)
                    .section("encounters")
                    .getCompoundOrEmpty(j.getStringOr("encounter", ""));
            ctx.assertTrue(
                site.getStringOr("state", "").equals("planned"),
                "Failed registration retains an unconsumed encounter plan");
            int failures = calls[0];
            EncounterSites.tick(server);
            ctx.assertTrue(calls[0] == failures, "A failed site does not retry every tick");
          } finally {
            ShipStore.writeObserver = previous;
            removeVessels(server, List.of(c.fixture().id));
            ShipStore.get(server).section("encounters").remove(j.getStringOr("encounter", ""));
            c.close();
          }
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 300)
  public void twoCrewsShareTheSameSignalButKeepIndependentSavedProgress(GameTestHelper ctx)
      throws Exception {
    var a = ConcurrentShipActionsGameTest.client(ctx, 41);
    var b = ConcurrentShipActionsGameTest.client(ctx, 42);
    a.board();
    b.board();
    var server = ctx.getLevel().getServer();
    boolean[] offline = {false}, done = {false};
    int[] started = {-1}, elapsed = {-1};
    ctx.onEachTick(
        () -> {
          if (done[0]) return;
          if (!offline[0]) acknowledge(a);
          acknowledge(b);
          if (!offline[0]
              && JourneyService.cruising(a.fixture().ship())
              && JourneyService.cruising(b.fixture().ship())
              && !NavigationService.pending(server)) {
            var ja = a.fixture().ship().getCompoundOrEmpty("journey");
            var jb = b.fixture().ship().getCompoundOrEmpty("journey");
            ctx.assertTrue(
                ja.getStringOr("encounter", "a").equals(jb.getStringOr("encounter", "b")),
                "Crews on the same route discover the same persistent encounter");
            ctx.assertTrue(
                !LocalSpaces.together(a.fixture().ship(), b.fixture().ship()),
                "Independent FTL corridors do not expose unrelated ships to each other");
            elapsed[0] = ja.getIntOr("elapsed", 0);
            a.close();
            offline[0] = true;
            started[0] = server.getTickCount();
          } else if (offline[0] && server.getTickCount() - started[0] >= 80) {
            ctx.assertTrue(
                a.fixture().ship().getCompoundOrEmpty("journey").getIntOr("elapsed", -1)
                    == elapsed[0],
                "Empty ships pause while their crew is offline");
            ctx.assertTrue(
                b.fixture().ship().getCompoundOrEmpty("journey").getIntOr("elapsed", 0)
                    > elapsed[0],
                "The other crew keeps travelling");
            ShipStore.get(server).save();
            ShipStore.forget(server);
            ctx.assertTrue(
                a.fixture().ship().getCompoundOrEmpty("journey").getIntOr("elapsed", -1)
                    == elapsed[0],
                "Disk reload retains the offline crew's exact checkpoint");
            removeVessels(server, List.of(a.fixture().id, b.fixture().id));
            b.close();
            done[0] = true;
            ctx.succeed();
          }
        });
    ctx.runAfterDelay(
        20,
        () -> {
          NavigationService.jump(
              a.player(), ShipStore.pos(a.fixture().ship(), "controller"), "sol_luna");
          NavigationService.jump(
              b.player(), ShipStore.pos(b.fixture().ship(), "controller"), "sol_luna");
        });
  }

  @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 600)
  public void allThreeEncounterTypesHaveSafeDocksAccessibleFiniteSuppliesAndRealCrops(
      GameTestHelper ctx) throws Exception {
    var c = ConcurrentShipActionsGameTest.client(ctx, 43);
    var server = ctx.getLevel().getServer();
    var level = c.fixture().space;
    var vessels = new ArrayList<CompoundTag>();
    for (int kind = 0; kind < 3; kind++) {
      var vessel =
          StationService.queue(
              server,
              UUID.randomUUID().toString(),
              "Encounter fixture " + kind,
              LocalSpaces.HOME,
              new net.minecraft.world.phys.Vec3(0, 0, 2000 + kind * 300),
              "",
              EncounterSites.plan(kind, kind),
              false);
      vessel.putBoolean("salvage", true);
      vessel.putInt("encounterKind", kind);
      vessel.putLong("encounterSeed", kind);
      vessels.add(vessel);
    }
    Runnable[] poll = {null};
    poll[0] =
        () -> {
          if (vessels.stream().anyMatch(s -> !StationService.ready(s))) {
            ctx.runAfterDelay(5, poll[0]);
            return;
          }
          for (var vessel : vessels) {
            var port = DockingService.ports(level, vessel).getFirst();
            var pressure = new ShipScan.LivePressure(level, ShipStore.bounds(vessel));
            ctx.assertTrue(
                port.anchors().stream().anyMatch(p -> pressure.breathable(p.above())),
                "Every encounter preserves a sealed docking entrance");
            int food = 0, fuel = 0, upgrades = 0, crops = 0;
            for (var at :
                BlockPos.betweenClosed(
                    ShipStore.bounds(vessel).min(), ShipStore.bounds(vessel).max())) {
              if (level.getBlockState(at).is(Blocks.WHEAT)) crops++;
              if (!level.getBlockState(at).is(Blocks.BARREL)) continue;
              var chest = (Container) level.getBlockEntity(at);
              ctx.assertTrue(
                  StationService.mayUse(c.player(), at) && StationService.mayEdit(c.player(), at),
                  "Salvage supplies and hardware are accessible in survival");
              for (int i = 0; i < chest.getContainerSize(); i++) {
                var stack = chest.getItem(i);
                if (stack.is(Items.BREAD)) food += stack.getCount();
                if (stack.is(Items.COAL)) fuel += stack.getCount();
                if (stack.is(ShipBlocks.SYSTEM_UPGRADE)) upgrades += stack.getCount();
              }
            }
            int kind = vessel.getIntOr("encounterKind", -1);
            ctx.assertTrue(
                fuel > 0 && (kind != 0 || food > 0 && crops == 48) && (kind != 2 || upgrades == 2),
                "Encounter rewards are real finite items and growing blocks for role " + kind);
            if (kind != 0) {
              var rooms =
                  ShipRooms.rooms(
                      EncounterSites.design(kind, vessel.getLongOr("encounterSeed", 1)));
              int stocked = 0;
              for (var room : rooms) {
                if (room.supplies() == null) continue;
                var supplies =
                    (Container)
                        level.getBlockEntity(ShipStore.pos(vessel, "base").offset(room.supplies()));
                ctx.assertTrue(
                    supplies != null, "Each resource room has its own physical container");
                if (!supplies.isEmpty()) stocked++;
                if (room.use() == ShipRooms.Use.ENGINEERING)
                  ctx.assertTrue(
                      supplies.getItem(0).is(Items.COAL), "Fuel belongs beside engineering");
                if (room.use() == ShipRooms.Use.GALLEY)
                  ctx.assertTrue(supplies.getItem(0).is(Items.BREAD), "Food belongs in the galley");
                supplies.clearContent();
              }
              ctx.assertTrue(stocked >= 3, "Exploration rewards are spread across useful rooms");
              EncounterSites.stock(level, vessel);
              for (var room : rooms)
                if (room.supplies() != null)
                  ctx.assertTrue(
                      ((Container)
                              level.getBlockEntity(
                                  ShipStore.pos(vessel, "base").offset(room.supplies())))
                          .isEmpty(),
                      "A second stocking call cannot refill looted rooms");
              if (kind == 2) {
                // Older saved construction plans do not contain the new room lockers.
                var missing =
                    rooms.stream()
                        .filter(r -> r.use() == ShipRooms.Use.GALLEY)
                        .findFirst()
                        .orElseThrow();
                level.setBlock(
                    ShipStore.pos(vessel, "base").offset(missing.supplies()),
                    Blocks.AIR.defaultBlockState(),
                    18);
                vessel.remove("encounterStocked");
                EncounterSites.stock(level, vessel);
                int legacyFuel = 0, legacyUpgrades = 0;
                for (var at :
                    BlockPos.betweenClosed(
                        ShipStore.bounds(vessel).min(), ShipStore.bounds(vessel).max())) {
                  if (!level.getBlockState(at).is(Blocks.BARREL)) continue;
                  var supplies = (Container) level.getBlockEntity(at);
                  for (int slot = 0; slot < supplies.getContainerSize(); slot++) {
                    var item = supplies.getItem(slot);
                    if (item.is(Items.COAL)) legacyFuel += item.getCount();
                    if (item.is(ShipBlocks.SYSTEM_UPGRADE)) legacyUpgrades += item.getCount();
                  }
                }
                ctx.assertTrue(
                    legacyFuel == 8 && legacyUpgrades == 2,
                    "An older unfinished hull receives its original supplies in an existing"
                        + " container");
                EncounterSites.stock(level, vessel);
                ctx.assertTrue(
                    vessel.getBooleanOr("encounterStocked", false),
                    "Older plans also save the no-refill marker");
              }
            }
          }
          var ids = new ArrayList<>(vessels.stream().map(v -> v.getStringOr("id", "")).toList());
          ids.add(c.fixture().id);
          removeVessels(server, ids);
          c.close();
          ctx.succeed();
        };
    ctx.runAfterDelay(20, poll[0]);
  }

  private static void removeVessels(net.minecraft.server.MinecraftServer server, List<String> ids) {
    var store = ShipStore.get(server);
    for (String id : ids) {
      var vessel = store.repository().find(id);
      if (vessel == null) continue;
      var level = ShipStore.level(server, vessel.getStringOr("dimension", ""));
      var bounds = ShipStore.bounds(vessel);
      for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
        if (level.getBlockState(at).isAir()) continue;
        level.removeBlockEntity(at);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
      }
    }
    store.ships().removeIf(t -> ids.contains(((CompoundTag) t).getStringOr("id", "")));
    store.requestSave();
  }

  private static void acknowledge(ConcurrentShipActionsGameTest.Client c) {
    c.connection().flushChannel();
    c.channel().runPendingTasks();
    Object packet;
    while ((packet = c.channel().readOutbound()) != null) {
      if (packet instanceof ClientboundCustomPayloadPacket custom
          && custom.payload() instanceof TravelPackets.Progress progress
          && !progress.done())
        NavigationService.ready(
            c.player(), new TravelPackets.Ready(progress.id(), !progress.dimension().isEmpty()));
      io.netty.util.ReferenceCountUtil.release(packet);
    }
    for (var tag : ShipStore.get(c.player().level().getServer()).list("stationBoarding")) {
      var board = (CompoundTag) tag;
      if (board.getStringOr("player", "").equals(c.player().getUUID().toString()))
        DockingService.ready(
            c.player(),
            new TravelPackets.Ready(
                UUID.fromString(board.getStringOr("token", "")),
                board.getBooleanOr("committed", false)));
    }
  }

  @GameTest(environment = "nullspace:concurrent_ship_actions", maxTicks = 1600)
  public void savedJourneyCanStopDockCollectFuelAndResumeWithoutPayingAgain(GameTestHelper ctx)
      throws Exception {
    var c = ConcurrentShipActionsGameTest.client(ctx, 40);
    var server = ctx.getLevel().getServer();
    var level = c.fixture().space;
    c.board();
    int[] phase = {-1}, paused = {0}, fuel = {0};
    String[] siteId = {""}, vesselId = {""};
    BlockPos[] cargo = {null};
    int[] previous = {-2}, ticks = {0};
    ctx.onEachTick(
        () -> {
          acknowledge(c);
          var ship = c.fixture().ship();
          if (previous[0] != phase[0]) {
            com.nullandkale.nullspace.NullSpace.LOGGER.info("Journey test phase {}", phase[0]);
            previous[0] = phase[0];
          }
          if (++ticks[0] == 1590)
            ctx.assertTrue(
                false,
                "Journey stuck at phase "
                    + phase[0]
                    + " dock="
                    + ship.getCompoundOrEmpty("dock")
                    + " journey="
                    + ship.getCompoundOrEmpty("journey"));
          if (phase[0] == 0
              && JourneyService.cruising(ship)
              && !NavigationService.pending(server, c.fixture().id)) {
            ctx.assertTrue(
                !TravelService.locked(level, ShipStore.pos(ship, "controller")),
                "Cruise releases physical block interaction locks");
            ctx.assertTrue(
                Math.abs(ship.getDoubleOr("simYaw", 0) - 37) < 1e-8,
                "Entering FTL preserves the ship's heading rather than snapping it to zero");
            var j = ship.getCompoundOrEmpty("journey");
            j.putInt("kind", 1);
            siteId[0] = j.getStringOr("encounter", "");
            var site = ShipStore.get(server).section("encounters").getCompoundOrEmpty(siteId[0]);
            site.putInt("kind", 1);
            j.putInt("contactAt", 0);
            j.putInt("duration", 4000);
            j.remove("selection"); // Compatibility with the earliest saved journey records.
            ShipStore.get(server).save();
            ShipStore.forget(server);
            ctx.assertTrue(
                JourneyService.cruising(c.fixture().ship()),
                "Disk reload retains cruise state and paid route");
            phase[0] = 1;
          } else if (phase[0] == 1) {
            var site = ShipStore.get(server).section("encounters").getCompoundOrEmpty(siteId[0]);
            var vessel = ShipStore.get(server).repository().find(site.getStringOr("vessel", ""));
            if (vessel == null || !StationService.ready(vessel)) return;
            vesselId[0] = vessel.getStringOr("id", "");
            ctx.assertTrue(
                JourneyService.stopReason(server, ship).isEmpty(),
                "Built signal is available to investigate");
            NavigationService.investigate(c.player(), ShipStore.pos(ship, "controller"));
            phase[0] = 2;
          } else if (phase[0] == 2 && !NavigationService.pending(server, c.fixture().id)) {
            // Keep this departure/loot regression on its original clear approach fixture rather
            // than making its success depend on the randomized encounter arrival position.
            ship.putDouble("simX", 0);
            ship.putDouble("simY", 0);
            ship.putDouble("simZ", 460);
            ctx.assertTrue(
                !JourneyService.cruising(ship) && LocalSpaces.address(ship).equals(siteId[0]),
                "Dropout enters the saved encounter and pauses travel");
            var selectedDock = ShipDestination.selected(ship);
            ctx.assertTrue(
                selectedDock != null
                    && selectedDock.kind() == ShipDestination.Kind.PORT
                    && selectedDock.reference().equals(vesselId[0]),
                "Investigate selects the encountered vessel's dock without another destination"
                    + " search");
            var journey = ship.getCompoundOrEmpty("journey");
            journey.remove("encounterDockSelected");
            ship.put("destination", ShipDestination.region(server, "sol_luna").save());
            ctx.assertTrue(
                JourneyService.selectEncounterDock(c.player(), ship)
                    && ShipDestination.selected(ship).reference().equals(vesselId[0]),
                "An older paused journey can select its dock without resetting the world");
            ship.put("destination", ShipDestination.region(server, "sol_luna").save());
            ctx.assertTrue(
                !JourneyService.selectEncounterDock(c.player(), ship)
                    && ShipDestination.selected(ship).region().equals("sol_luna"),
                "The one-time selection respects later deliberate destination changes");
            ship.put("destination", selectedDock.save());
            paused[0] = ship.getCompoundOrEmpty("journey").getIntOr("elapsed", -1);
            ctx.assertTrue(
                CourseService.active(ship),
                "The encounter course automatically continues into docking");
            phase[0] = 3;
          } else if (phase[0] == 3
              && ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED")) {
            var port = DockingService.firstPort(level, ship);
            var at = port.anchors().getFirst();
            c.player().teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            DockingService.perform(c.player(), ship, port.controller(), "DOCK_BOARD");
            phase[0] = 4;
          } else if (phase[0] == 4
              && !DockingService.busy(c.player())
              && SceneService.observer(c.player()).getStringOr("id", "").equals(vesselId[0])) {
            var vessel = ShipStore.get(server).repository().find(vesselId[0]);
            var rubble = ShipStore.pos(vessel, "base").offset(12, 1, 10);
            ctx.assertTrue(
                StationService.mayEdit(c.player(), rubble),
                "Unclaimed freighter permits cargo access repairs");
            level.setBlock(rubble, Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(rubble.above(), Blocks.AIR.defaultBlockState(), 18);
            var engineering =
                ShipRooms.rooms(
                        EncounterSites.design(
                            vessel.getIntOr("encounterKind", 1),
                            vessel.getLongOr("encounterSeed", 1)))
                    .stream()
                    .filter(r -> r.use() == ShipRooms.Use.ENGINEERING)
                    .findFirst()
                    .orElseThrow();
            var fuelLocker = ShipStore.pos(vessel, "base").offset(engineering.supplies());
            var supplies = (Container) level.getBlockEntity(fuelLocker);
            ctx.assertTrue(
                StationService.mayUse(c.player(), fuelLocker)
                    && supplies != null
                    && supplies.getItem(0).is(Items.COAL),
                "Engineering's fuel locker is usable by a visitor");
            c.player().getInventory().add(supplies.removeItem(0, 8));
            supplies.setChanged();
            cargo[0] = fuelLocker;
            ctx.assertTrue(
                cargo[0] != null
                    && c.player()
                        .getInventory()
                        .contains(new net.minecraft.world.item.ItemStack(Items.COAL)),
                "Actual coal transfers from cargo into player inventory");
            ShipStore.get(server).save();
            ctx.assertTrue(
                ship.getCompoundOrEmpty("journey").getIntOr("elapsed", -2) == paused[0],
                "Docking and looting do not consume journey progress");
            var port = DockingService.ports(level, vessel).getFirst();
            var at = port.anchors().getFirst();
            c.player().teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            DockingService.perform(c.player(), vessel, port.controller(), "DOCK_BOARD");
            phase[0] = 5;
          } else if (phase[0] == 5
              && !DockingService.busy(c.player())
              && SceneService.observer(c.player()) == ship) {
            c.board();
            var view = ShipControl.open(c.player(), ShipStore.pos(ship, "controller"), "Ready");
            ctx.assertTrue(
                view.location().contains("Luna") && view.details().contains("Undock"),
                "The docked controller previews the original destination and automatic departure");
            ctx.assertTrue(
                view.buttons().stream()
                    .anyMatch(b -> b.action() == ShipControl.Action.ENGAGE && b.enabled()),
                "Engage is available on the ship while docked at an encounter: "
                    + view.buttons().stream()
                        .filter(b -> b.action() == ShipControl.Action.ENGAGE)
                        .findFirst()
                        .orElseThrow()
                        .reason());
            var result =
                ShipControl.input(
                    c.player(),
                    new ShipControl.Input(view.session(), ShipControl.Action.ENGAGE, ""));
            ctx.assertTrue(
                result.startsWith("Course engaged"),
                "One Engage accepts the complete departure: " + result);
            phase[0] = 6;
          } else if (phase[0] == 6 && !ship.contains("dock")) {
            fuel[0] = FuelSystem.available(level, ship);
            phase[0] = 7;
          } else if (phase[0] == 7
              && !NavigationService.pending(server, c.fixture().id)
              && JourneyService.cruising(ship)) {
            ctx.assertTrue(
                fuel[0] - FuelSystem.available(level, ship) < 500,
                "Resume charges no second 2000-unit jump fee");
            ctx.assertTrue(
                ShipDestination.selected(ship).region().equals("sol_luna"),
                "Resume restores the paid destination even for an older journey without a saved"
                    + " selection");
            ctx.assertTrue(
                ship.getCompoundOrEmpty("journey").getIntOr("elapsed", -2) >= paused[0]
                    && !JourneyService.contact(ship),
                "Progress survives the stop and the contact is consumed");
            var j = ship.getCompoundOrEmpty("journey");
            j.putInt("elapsed", j.getIntOr("duration", 0));
            phase[0] = 8;
          } else if (phase[0] == 8
              && !JourneyService.active(ship)
              && !NavigationService.pending(server, c.fixture().id)) {
            ctx.assertTrue(
                LocalSpaces.address(ship).equals("sol_luna"),
                "Automatic arrival completes the original destination");
            ctx.assertTrue(
                ((Container) level.getBlockEntity(cargo[0])).getItem(0).getCount() == 24,
                "Collected supplies never restock on resume");
            phase[0] = 9;
            removeVessels(server, List.of(c.fixture().id, vesselId[0]));
            c.close();
            ctx.succeed();
          }
        });
    ctx.runAfterDelay(
        20,
        () -> {
          c.fixture().ship().putDouble("simYaw", 37);
          NavigationService.jump(
              c.player(), ShipStore.pos(c.fixture().ship(), "controller"), "sol_luna");
          phase[0] = 0;
        });
  }
}
