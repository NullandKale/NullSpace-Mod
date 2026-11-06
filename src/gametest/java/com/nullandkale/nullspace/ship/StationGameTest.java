package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.station.*;
import com.nullandkale.nullspace.systems.*;
import com.nullandkale.nullspace.terminal.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

public final class StationGameTest {
  @GameTest(environment = "nullspace:stations")
  public void stationCoreStartsFromSneakBlockOrAirAndConsumesTheUsedHandOnly(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var ship = fixture(level, new BlockPos(760000, 96, 760000), true);
    var player = survivalPlayer(ctx);
    var at = boardingPoint(level, ship);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    player.setItemInHand(
        net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ShipBlocks.STATION_CORE, 2));
    player.setItemInHand(
        net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(ShipBlocks.STATION_CORE));
    var floor = player.blockPosition().below();
    var hit =
        new net.minecraft.world.phys.BlockHitResult(
            Vec3.atCenterOf(floor), Direction.UP, floor, false);
    var existing =
        ShipStore.get(server).ships().stream()
            .map(t -> ((CompoundTag) t).getStringOr("id", ""))
            .collect(java.util.stream.Collectors.toSet());
    try {
      ctx.assertTrue(
          StationService.stationStartTarget(level, floor),
          "Ordinary hull/floor blocks can start a station");
      ctx.assertTrue(
          !StationService.stationStartTarget(level, ShipStore.pos(ship, "controller")),
          "Interactive controllers are excluded");
      var callback = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker();
      callback.interact(player, level, net.minecraft.world.InteractionHand.OFF_HAND, hit);
      ctx.assertTrue(
          ShipStore.get(server).ships().size() == existing.size(),
          "Ordinary right click does not start a station");
      player.setShiftKeyDown(true);
      callback.interact(player, level, net.minecraft.world.InteractionHand.OFF_HAND, hit);
      ctx.assertTrue(
          ShipStore.get(server).ships().size() == existing.size() + 1,
          "Shift-right-click on a non-interactive block starts construction");
      ctx.assertTrue(
          player.getOffhandItem().isEmpty() && player.getMainHandItem().getCount() == 2,
          "Consume the core in the hand actually used, not another stack");
      player
          .getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE)
          .setBaseValue(.1);
      player.setXRot(0);
      ctx.assertTrue(
          player.pick(player.blockInteractionRange(), 0, false).getType()
              == net.minecraft.world.phys.HitResult.Type.MISS,
          "Air interaction fixture targets empty space within reach");
      net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT
          .invoker()
          .interact(player, level, net.minecraft.world.InteractionHand.MAIN_HAND);
      ctx.assertTrue(
          ShipStore.get(server).ships().size() == existing.size() + 2
              && player.getMainHandItem().getCount() == 1,
          "Shift-right-click in empty space starts construction and consumes one core");
      ctx.succeed();
    } finally {
      server.getPlayerList().remove(player);
      ShipStore.get(server)
          .ships()
          .removeIf(t -> !existing.contains(((CompoundTag) t).getStringOr("id", "")) || t == ship);
    }
  }

  private static net.minecraft.server.level.ServerPlayer survivalPlayer(GameTestHelper ctx) {
    var cookie =
        net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), "station-builder"), false);
    var player =
        new net.minecraft.server.level.ServerPlayer(
            ctx.getLevel().getServer(),
            ctx.getLevel(),
            cookie.gameProfile(),
            cookie.clientInformation());
    var connection =
        new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
    new io.netty.channel.embedded.EmbeddedChannel(connection);
    ctx.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    return player;
  }

  private static void cleanup(ServerLevel level, CompoundTag s) {
    ShipStore.get(level.getServer())
        .ships()
        .removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(s.getStringOr("id", "")));
    for (var pos : BlockPos.betweenClosed(ShipStore.bounds(s).min(), ShipStore.bounds(s).max()))
      if (!level.getBlockState(pos).isAir()) {
        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
      }
  }

  public static CompoundTag fixture(ServerLevel level, BlockPos base, boolean ship) {
    StationBlueprint.Plan plan;
    if (ship) {
      var preset = com.nullandkale.nullspace.shipyard.ShipBlueprints.get("sparrow");
      plan =
          new StationBlueprint.Plan(
              com.nullandkale.nullspace.shipyard.ShipBlueprints.blocks(preset),
              preset.helm(),
              List.of());
    } else plan = StationBlueprint.build(StationLayout.waypoint());
    var s = new CompoundTag();
    s.putString("id", UUID.randomUUID().toString());
    s.putString("dimension", level.dimension().identifier().toString());
    s.putLong("base", base.asLong());
    s.putLong("controller", base.offset(plan.core()).asLong());
    s.putString("home", ship ? "Test ship" : "Test station");
    s.putString("localSpace", "sol_terra");
    s.putString("kind", ship ? "ship" : "station");
    s.putString("access", "public");
    s.put("crew", new ListTag());
    s.putDouble("simX", ship ? -2800 : -3000);
    s.putDouble("simZ", -2800);
    ShipStore.get(level.getServer()).ships().add(s);
    for (var e : plan.blocks().entrySet())
      level.setBlock(base.offset(e.getKey()), e.getValue(), 18);
    for (var e : plan.blocks().entrySet())
      if (e.getValue().is(ShipBlocks.GENERATOR))
        FuelSystem.provision(level, base.offset(e.getKey()), 64);
    ShipSystems.upgrade(level, s);
    s.getCompoundOrEmpty("systems")
        .putIntArray(
            "requests", ship ? new int[] {1, 4, 1, 3, 2, 0} : new int[] {1, 12, 1, 0, 2, 0});
    ShipSystems.tick(level, s);
    return s;
  }

  public static Vec3 boardingPoint(ServerLevel level, CompoundTag s) {
    var helm = ShipStore.pos(s, "controller");
    var at = helm.relative(level.getBlockState(helm).getValue(HorizontalDirectionalBlock.FACING));
    return new Vec3(at.getX() + .5, at.getY(), at.getZ() + .5);
  }

  @GameTest(environment = "nullspace:stations", maxTicks = 100)
  public void dockingDistinguishesMissingSystemsLowCabinAirAndOutsideCrew(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var base = new BlockPos(280000, 96, 280000);
    var ship = fixture(level, base, true);
    var player = survivalPlayer(ctx);
    var at = boardingPoint(level, ship);
    player.teleportTo(level, at.x, at.y, at.z, Set.of(), 0, 0, true);
    try {
      var systems = ship.getCompoundOrEmpty("systems").copy();
      ship.remove("systems");
      var legacy = DockingService.readiness(player, ship);
      ctx.assertTrue(
          !legacy.ready()
              && legacy.message().contains("systems record")
              && !legacy.message().contains("Recall"),
          "Missing saved systems does not falsely accuse recalled crew of being outside");
      ship.put("systems", systems);
      var replacement = ship.copy();
      int[] air = replacement.getCompoundOrEmpty("systems").getIntArray("air").orElseThrow();
      var eye = BlockPos.containing(player.getEyePosition()).subtract(base);
      air[Atmosphere.index(eye.getX(), eye.getY(), eye.getZ())] = 100;
      replacement.getCompoundOrEmpty("systems").putIntArray("air", air);
      // Publish a new authoritative record, just as recovery does, so no cached air can mask the
      // check.
      ShipStore.get(server).ships().remove(ship);
      ShipStore.get(server).ships().add(replacement);
      var low = DockingService.readiness(player, replacement);
      ctx.assertTrue(
          !low.ready()
              && low.message().contains("is aboard")
              && low.message().contains("10%")
              && low.message().contains(player.getPlainTextName()),
          "An aboard player gets an exact oxygen diagnostic");
      air[Atmosphere.index(eye.getX(), eye.getY(), eye.getZ())] = Atmosphere.FULL;
      ctx.assertTrue(
          DockingService.readiness(player, replacement).ready(),
          "Recalled crew in a breathable cabin can dock");
      player.teleportTo(base.getX() - 3, base.getY() + 1, base.getZ() + 4);
      var outside = DockingService.readiness(player, replacement);
      ctx.assertTrue(
          !outside.ready()
              && outside.message().contains("outside a sealed cabin")
              && outside.message().contains(player.getPlainTextName()),
          "Actual EVA crew are named and remain blocked");
      ctx.succeed();
    } finally {
      server.getPlayerList().remove(player);
      cleanup(level, ship);
    }
  }

  @GameTest(environment = "nullspace:stations", maxTicks = 100)
  public void waypointHasPoweredAirlocksAndConnectedDoorGraph(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(61000, 64, 61000));
    var s = fixture(level, base, false);
    try {
      var ports = DockingService.ports(level, s);
      ctx.assertTrue(ports.size() == 2, "Completed station has two valid berths");
      for (var port : ports) {
        DockingService.readPort(level, s, port.controller(), true);
        ctx.assertTrue(
            ShipSystems.breathable(level, s, port.anchors().getFirst().above()),
            "Docking chamber starts with finite air");
      }
      var graph = StationGraph.inspect(level, s);
      ctx.assertTrue(
          graph.nodes().size() == 6,
          "Four ordinary internal doors and two compact docking doors form graph nodes");
      ctx.assertTrue(
          !graph.route(ports.getFirst().id(), ports.getLast().id()).isEmpty(),
          "Door graph reaches both docks");
      var saved = s.copy();
      ctx.assertTrue(
          StationGraph.inspect(level, s).nodes().stream()
              .map(StationGraph.Node::id)
              .toList()
              .equals(graph.nodes().stream().map(StationGraph.Node::id).toList()),
          "Inspection preserves node identities");
      var broken = ports.getFirst().inner().above(2);
      level.setBlock(broken, Blocks.AIR.defaultBlockState(), 18);
      boolean rejected = false;
      try {
        DockingService.readPort(level, s, ports.getFirst().controller(), true);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      ctx.assertTrue(rejected, "Broken door frame prevents boarding, irrespective of saved graph");
      ctx.succeed();
    } finally {
      cleanup(level, s);
    }
  }

  @GameTest(environment = "nullspace:stations", maxTicks = 100)
  public void stationPermissionsAndTerminalActionsAreServerOwned(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(65000, 64, 65000));
    var s = fixture(level, base, false);
    var p = ctx.makeMockServerPlayerInLevel();
    var other = ctx.makeMockServerPlayerInLevel();
    try {
      s.putString("owner", p.getUUID().toString());
      p.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 5.5);
      other.teleportTo(p.getX(), p.getY(), p.getZ());
      ctx.assertTrue(
          StationService.mayEdit(p, base.offset(4, 3, 4))
              && !StationService.mayEdit(other, base.offset(4, 3, 4)),
          "Public docking does not grant edit rights");
      var open = ComputerTerminal.issue(p, ShipStore.pos(s, "controller"), "", "station_core", "");
      boolean denied = false;
      try {
        ComputerTerminal.authorize(
            other,
            new ComputerTerminal.Request(
                open.session(), ComputerTerminal.Action.STATION_GENERATE, "", ""));
      } catch (IllegalArgumentException expected) {
        denied = true;
      }
      ctx.assertTrue(denied, "Another player cannot reuse a station terminal session");
      denied = false;
      try {
        ComputerTerminal.authorize(
            p,
            new ComputerTerminal.Request(open.session(), ComputerTerminal.Action.LAUNCH, "", ""));
      } catch (IllegalArgumentException expected) {
        denied = true;
      }
      ctx.assertTrue(denied, "Station terminals never authorize launch");
      var ports = DockingService.ports(level, s);
      var port = ports.getFirst();
      ctx.assertTrue(
          StationService.mayUse(other, port.controller())
              && StationService.mayUse(other, port.outer()),
          "Public visitors can inspect and operate compact ports");
      var bulkhead =
          StationGraph.inspect(level, s).nodes().stream()
              .filter(n -> !n.dock())
              .findFirst()
              .orElseThrow()
              .door();
      ctx.assertTrue(
          StationService.useDoor(other, bulkhead) && ShipDoors.open(level.getBlockState(bulkhead)),
          "Ordinary station iron doors operate directly without buttons");
      ShipDoors.setOpen(level, bulkhead, false);
      var visitor = new CompoundTag();
      visitor.putString("id", other.getUUID().toString());
      visitor.putString("returnShip", "visitors-home-ship");
      s.getListOrEmpty("crew").add(visitor);
      s.putString("access", "private");
      ctx.assertTrue(
          !StationService.allowed(other, s)
              && StationService.mayUse(other, port.controller())
              && StationService.mayUse(other, port.outer()),
          "Revoking public access preserves a visitor's return door");
      DockingService.useCompactDoor(other, port.outer());
      ctx.assertTrue(
          ShipDoors.open(level.getBlockState(port.outer())),
          "Returning visitors can still open the compact dock door");
      var menu =
          new MachineMenu(
              1, other.getInventory(), (MachineEntity) level.getBlockEntity(port.controller()));
      menu.broadcastChanges();
      ctx.assertTrue(
          !menu.reading.getBooleanOr("canConfigure", true), "Visitor port panels are read-only");
      menu.edit(other, 0, "");
      ctx.assertTrue(
          ShipSystems.active(s, port.controller()),
          "Visitors cannot disable station docking equipment");
      ctx.succeed();
    } finally {
      level.getServer().getPlayerList().remove(p);
      level.getServer().getPlayerList().remove(other);
      cleanup(level, s);
    }
  }

  @GameTest(environment = "nullspace:stations", maxTicks = 2200)
  public void automaticDockBoardReturnAndUndock(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var origin = ctx.absolutePos(new BlockPos(71000, 96, 71000));
    var a = new BlockPos(origin.getX(), 96, origin.getZ());
    var b = a.offset(512, 0, 0);
    var ship = fixture(level, a, true);
    var station = fixture(level, b, false);
    var p = ctx.makeMockServerPlayerInLevel();
    var start = boardingPoint(level, ship);
    p.teleportTo(level, start.x, start.y, start.z, Set.of(), 0, 0, true);
    p.getInventory().add(new ItemStack(Items.DIAMOND, 7));
    var source = DockingService.firstPort(level, ship);
    var target =
        DockingService.ports(level, station).stream()
            .filter(port -> port.facing() == Direction.EAST)
            .findFirst()
            .orElseThrow();
    DockingService.request(p, ship, station.getStringOr("id", ""), target.controller().subtract(b));
    ctx.assertTrue(DockingService.motionLocked(ship), "Autopilot immediately owns all movement");
    ctx.runAfterDelay(
        1000,
        () -> {
          var current = ShipStore.get(server).repository().find(ship.getStringOr("id", ""));
          ctx.assertTrue(
              current != null
                  && current.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"),
              "Autopilot must capture without any manual input: "
                  + (current == null
                      ? "record missing"
                      : current.getStringOr("flightStatus", "")
                          + " "
                          + current.getCompoundOrEmpty("dock").getStringOr("state", "")
                          + " pose "
                          + current.getDoubleOr("simX", 0)));
          var anchor = source.anchors().getFirst();
          p.teleportTo(anchor.getX() + .5, anchor.getY(), anchor.getZ() + .5);
          p.setYRot(source.facing().toYRot() + 17);
          p.setXRot(-23);
          var outboundLook =
              com.nullandkale.nullspace.navigation.FlightDynamics.rotate(
                  com.nullandkale.nullspace.scene.SceneService.pose(current).orientation(),
                  p.getLookAngle());
          DockingService.action(p, current, source.controller(), "DOCK_BOARD");
          var intent = (CompoundTag) ShipStore.get(server).list("stationBoarding").getFirst();
          DockingService.ready(
              p, new TravelPackets.Ready(UUID.fromString(intent.getStringOr("token", "")), false));
          ctx.runAfterDelay(
              10,
              () -> {
                ctx.assertTrue(
                    ShipStore.bounds(station).contains(p.blockPosition()),
                    "Passenger boards actual station blocks");
                ctx.assertTrue(
                    com.nullandkale.nullspace.navigation.FlightDynamics.rotate(
                                com.nullandkale.nullspace.scene.SceneService.pose(station)
                                    .orientation(),
                                p.getLookAngle())
                            .distanceTo(outboundLook)
                        < 3e-4,
                    "Boarding keeps the passenger looking through the dock, including their head"
                        + " tilt");
                DockingService.ready(
                    p,
                    new TravelPackets.Ready(
                        UUID.fromString(intent.getStringOr("token", "")), true));
                ctx.runAfterDelay(
                    10,
                    () -> {
                      ctx.assertTrue(
                          p.getInventory().countItem(Items.DIAMOND) == 7,
                          "Boarding preserves inventory exactly once");
                      var dest = target.anchors().getFirst();
                      p.teleportTo(dest.getX() + .5, dest.getY(), dest.getZ() + .5);
                      p.setYRot(target.facing().toYRot() - 11);
                      p.setXRot(18);
                      var returnLook =
                          com.nullandkale.nullspace.navigation.FlightDynamics.rotate(
                              com.nullandkale.nullspace.scene.SceneService.pose(station)
                                  .orientation(),
                              p.getLookAngle());
                      DockingService.action(
                          p,
                          ShipStore.get(server).repository().find(station.getStringOr("id", "")),
                          target.controller(),
                          "DOCK_BOARD");
                      var back =
                          (CompoundTag) ShipStore.get(server).list("stationBoarding").getFirst();
                      DockingService.ready(
                          p,
                          new TravelPackets.Ready(
                              UUID.fromString(back.getStringOr("token", "")), false));
                      ctx.runAfterDelay(
                          10,
                          () -> {
                            ctx.assertTrue(
                                ShipStore.bounds(ship).contains(p.blockPosition()),
                                "Return boards the original ship");
                            ctx.assertTrue(
                                com.nullandkale.nullspace.navigation.FlightDynamics.rotate(
                                            com.nullandkale.nullspace.scene.SceneService.pose(
                                                    current)
                                                .orientation(),
                                            p.getLookAngle())
                                        .distanceTo(returnLook)
                                    < 3e-4,
                                "Returning preserves the view in the original ship's frame too");
                            DockingService.ready(
                                p,
                                new TravelPackets.Ready(
                                    UUID.fromString(back.getStringOr("token", "")), true));
                            ctx.runAfterDelay(
                                10,
                                () -> {
                                  var unpoweredTarget =
                                      ShipStore.get(server)
                                          .repository()
                                          .find(station.getStringOr("id", ""));
                                  unpoweredTarget
                                      .getCompoundOrEmpty("systems")
                                      .putIntArray("requests", new int[6]);
                                  ShipSystems.tick(level, unpoweredTarget);
                                  ctx.assertTrue(
                                      ShipSystems.power(
                                              level, unpoweredTarget, PowerBus.System.CONTROLS)
                                          == 0,
                                      "The target is unpowered before departure");
                                  DockingService.action(
                                      p,
                                      ShipStore.get(server)
                                          .repository()
                                          .find(ship.getStringOr("id", "")),
                                      ShipStore.pos(ship, "controller"),
                                      "DOCK_UNDOCK");
                                  ctx.runAfterDelay(
                                      400,
                                      () -> {
                                        try {
                                          ctx.assertTrue(
                                              !ShipStore.get(server)
                                                      .repository()
                                                      .find(ship.getStringOr("id", ""))
                                                      .contains("dock")
                                                  && ShipStore.get(server)
                                                      .repository()
                                                      .find(station.getStringOr("id", ""))
                                                      .getCompoundOrEmpty("dockLinks")
                                                      .isEmpty(),
                                              "Automatic undocking releases the berth after backing"
                                                  + " away");
                                          ctx.assertTrue(
                                              p.getInventory().countItem(Items.DIAMOND) == 7,
                                              "Return never clones cargo");
                                          ctx.succeed();
                                        } finally {
                                          server.getPlayerList().remove(p);
                                          cleanup(level, ship);
                                          cleanup(level, station);
                                        }
                                      });
                                });
                          });
                    });
              });
        });
  }

  @GameTest(environment = "nullspace:station_building", maxTicks = 160)
  public void stationDeploymentIsBoundedIdempotentAndPreservesEdits(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    String id = UUID.randomUUID().toString();
    var plan = StationBlueprint.build(StationLayout.waypoint());
    var station =
        StationService.queue(
            server, id, "Saved station", "sol_luna", new Vec3(-2000, 0, -2000), "", plan, false);
    ctx.assertTrue(
        StationService.queue(
                server, id, "Saved station", "sol_luna", new Vec3(0, 0, 0), "", plan, false)
            == station,
        "A repeated deployment reuses its reserved identity and section");
    ctx.runAfterDelay(
        55,
        () -> {
          try {
            ctx.assertTrue(
                StationService.ready(station), "Bounded placement publishes a completed station");
            var level = server.getLevel(TravelService.SPACE);
            var base = ShipStore.pos(station, "base");
            ctx.assertTrue(
                DockingService.ports(level, station).size() == 2,
                "Saved deployment commissions both ports");
            var edited = base.offset(1, 7, 1);
            level.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
            StationService.queue(
                server, id, "Saved station", "sol_luna", Vec3.ZERO, "", plan, false);
            ctx.assertTrue(
                level.getBlockState(edited).is(Blocks.DIAMOND_BLOCK),
                "Revisiting never repairs over station edits");
            ctx.succeed();
          } finally {
            cleanup(server.getLevel(TravelService.SPACE), station);
          }
        });
  }

  @GameTest(environment = "nullspace:station_cancel", maxTicks = 1800)
  public void cancelledFinalApproachCanAutomaticallyRetry(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var base = new BlockPos(245000, 96, 245000);
    var ship = fixture(level, base, true);
    var station = fixture(level, base.offset(512, 0, 0), false);
    var p = ctx.makeMockServerPlayerInLevel();
    var point = boardingPoint(level, ship);
    p.teleportTo(level, point.x, point.y, point.z, Set.of(), 0, 0, true);
    var target =
        DockingService.ports(level, station).stream()
            .filter(port -> port.facing() == Direction.EAST)
            .findFirst()
            .orElseThrow();
    var port = target.controller().subtract(ShipStore.pos(station, "base"));
    DockingService.request(p, ship, station.getStringOr("id", ""), port);
    boolean[] cancelled = {false};
    int[] polls = {0};
    Runnable[] poll = new Runnable[1];
    poll[0] =
        () -> {
          var phase = ship.getCompoundOrEmpty("dock").getStringOr("state", "");
          double distance =
              com.nullandkale.nullspace.scene.SceneService.pose(ship)
                  .toUniverse(new Vec3(16, 16, 16))
                  .distanceTo(
                      com.nullandkale.nullspace.scene.SceneService.pose(station)
                          .toUniverse(new Vec3(16, 16, 16)));
          if (++polls[0] > 300 || phase.isEmpty() || phase.equals("CLAMPED") && !cancelled[0]) {
            String error =
                "Cancel/retry failed: cancelled="
                    + cancelled[0]
                    + " phase="
                    + phase
                    + " distance="
                    + distance
                    + " status="
                    + ship.getStringOr("flightStatus", "");
            server.getPlayerList().remove(p);
            cleanup(level, ship);
            cleanup(level, station);
            ctx.assertTrue(false, error);
            return;
          }
          if (!cancelled[0] && phase.equals("CONNECTING") && distance < 50) {
            DockingService.action(p, ship, ShipStore.pos(ship, "controller"), "DOCK_CANCEL");
            cancelled[0] = true;
            DockingService.request(p, ship, station.getStringOr("id", ""), port);
          }
          if (cancelled[0] && phase.equals("CLAMPED")) {
            server.getPlayerList().remove(p);
            cleanup(level, ship);
            cleanup(level, station);
            ctx.succeed();
          } else ctx.runAfterDelay(5, () -> poll[0].run());
        };
    ctx.runAfterDelay(5, poll[0]);
  }

  @GameTest(environment = "nullspace:station_consent", maxTicks = 120)
  public void mobileShipDockingRequiresConsentAndRejectsReservationRaces(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var base = new BlockPos(265000, 96, 265000);
    var a = fixture(level, base, true);
    var b = fixture(level, base.offset(512, 0, 0), true);
    var c = fixture(level, base.offset(1024, 0, 0), true);
    b.putDouble("simX", -3000);
    c.putDouble("simX", -2600);
    var pa = ctx.makeMockServerPlayerInLevel();
    var pb = ctx.makeMockServerPlayerInLevel();
    var pc = ctx.makeMockServerPlayerInLevel();
    var players = List.of(pa, pb, pc);
    var ships = List.of(a, b, c);
    try {
      for (int i = 0; i < 3; i++) {
        var point = boardingPoint(level, ships.get(i));
        players.get(i).teleportTo(level, point.x, point.y, point.z, Set.of(), 0, 0, true);
      }
      var port = DockingService.firstPort(level, b).controller().subtract(ShipStore.pos(b, "base"));
      DockingService.request(pa, a, b.getStringOr("id", ""), port);
      ctx.assertTrue(
          a.getCompoundOrEmpty("dock").getStringOr("state", "").equals("WAITING_CONSENT"),
          "Approach never starts before another vessel accepts");
      boolean rejected = false;
      try {
        DockingService.request(pc, c, b.getStringOr("id", ""), port);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      ctx.assertTrue(rejected, "One berth cannot be reserved by two vessels");
      var helm = ShipStore.pos(b, "controller");
      var session = ComputerTerminal.issue(pb, helm, "Incoming request", "docks", "0");
      ComputerTerminal.authorize(
          pb,
          new ComputerTerminal.Request(
              session.session(), ComputerTerminal.Action.DOCK_ACCEPT, "", ""));
      DockingService.action(pb, b, helm, "DOCK_ACCEPT");
      ctx.assertTrue(
          a.getCompoundOrEmpty("dock").getStringOr("state", "").equals("APPROACHING"),
          "Target crew can accept from the helm without entering the locked airlock");
      DockingService.action(pa, a, ShipStore.pos(a, "controller"), "DOCK_CANCEL");
      ctx.assertTrue(
          !a.contains("dock") && !b.contains("dock") && b.getCompoundOrEmpty("dockLinks").isEmpty(),
          "Cancellation releases both movement and berth reservations");
      ctx.succeed();
    } finally {
      players.forEach(server.getPlayerList()::remove);
      ships.forEach(s -> cleanup(level, s));
    }
  }

  @GameTest(environment = "nullspace:station_construction", maxTicks = 1800)
  public void survivalConstructionStartsInSpaceAndBuildsConnectedModules(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var base = new BlockPos(190000, 96, 190000);
    var ship = fixture(level, base, true);
    var p = survivalPlayer(ctx);
    var start = boardingPoint(level, ship);
    p.teleportTo(level, start.x, start.y, start.z, Set.of(), 0, 0, true);
    p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    p.getInventory().add(new ItemStack(ShipBlocks.STATION_CORE));
    p.getInventory().add(new ItemStack(ShipBlocks.STATION_MODULE, 2));
    p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(ShipBlocks.HELMET));
    p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(ShipBlocks.CHEST));
    p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(ShipBlocks.LEGS));
    p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(ShipBlocks.BOOTS));
    var station = StationService.start(p, ship);
    var source = DockingService.firstPort(level, ship);
    ctx.runAfterDelay(
        1100,
        () -> {
          ctx.assertTrue(
              ship.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"),
              "Construction berth captures automatically: " + ship.getStringOr("flightStatus", ""));
          var anchor = source.anchors().getFirst();
          p.teleportTo(anchor.getX() + .5, anchor.getY(), anchor.getZ() + .5);
          DockingService.action(p, ship, source.controller(), "DOCK_CONSTRUCTION");
          var intent = (CompoundTag) ShipStore.get(server).list("stationBoarding").getFirst();
          var token = UUID.fromString(intent.getStringOr("token", ""));
          DockingService.ready(p, new TravelPackets.Ready(token, false));
          ctx.runAfterDelay(
              10,
              () -> {
                ctx.assertTrue(
                    ShipStore.bounds(station).contains(p.blockPosition())
                        && DockingService.constructionAir(p, station),
                    "Suited builder reaches the real platform with finite ship oxygen");
                DockingService.ready(p, new TravelPackets.Ready(token, true));
                ctx.runAfterDelay(
                    10,
                    () -> {
                      StationService.buildRoom(p, station, "arrival 0 0", false);
                      ctx.runAfterDelay(
                          35,
                          () -> {
                            ctx.assertTrue(
                                StationService.ready(station),
                                "Arrival module finishes without losing the construction return"
                                    + " route");
                            StationService.buildRoom(p, station, "engineering 1 0", false);
                            ctx.runAfterDelay(
                                35,
                                () -> {
                                  try {
                                    ctx.assertTrue(
                                        StationService.ready(station)
                                            && DockingService.ports(level, station).size() == 2,
                                        "Engineering joins the existing module through a valid"
                                            + " bulkhead");
                                    ctx.assertTrue(
                                        ShipSystems.power(level, station, PowerBus.System.CONTROLS)
                                            > 0,
                                        "Station systems run without a helm or engines");
                                    ctx.assertTrue(
                                        p.getInventory().countItem(ShipBlocks.STATION_CORE.asItem())
                                                == 0
                                            && p.getInventory().countItem(ShipBlocks.STATION_MODULE)
                                                == 0,
                                        "Core and module kits are consumed exactly once: core="
                                            + p.getInventory()
                                                .countItem(ShipBlocks.STATION_CORE.asItem())
                                            + " kits="
                                            + p.getInventory().countItem(ShipBlocks.STATION_MODULE)
                                            + " creative="
                                            + p.isCreative());
                                    var graph = StationGraph.inspect(level, station);
                                    ctx.assertTrue(
                                        graph.nodes().size() >= 3,
                                        "Player-assembled rooms have real doorway nodes");
                                    ctx.succeed();
                                  } finally {
                                    server.getPlayerList().remove(p);
                                    cleanup(level, ship);
                                    cleanup(level, station);
                                  }
                                });
                          });
                    });
              });
        });
  }
}
