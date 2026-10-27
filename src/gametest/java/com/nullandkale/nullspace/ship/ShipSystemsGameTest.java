package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.dev.SphereLab;
import com.nullandkale.nullspace.systems.*;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class ShipSystemsGameTest {
  @GameTest
  public void equipmentDiagnosticsSwitchesAndSessions(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(9216, 64, 9216));
    var helm = ShipTestFixture.build(level, base);
    var store = ShipStore.get(level.getServer());
    var ship = new net.minecraft.nbt.CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", level.dimension().identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", helm.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    store.ships().add(ship);
    var generator = base.offset(1, 3, 6);
    var battery = base.offset(2, 3, 6);
    level.setBlock(generator, ShipBlocks.GENERATOR.defaultBlockState(), 18);
    level.setBlock(battery, ShipBlocks.BATTERY.defaultBlockState(), 18);
    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(base.getX() + 2.5, base.getY() + 3, base.getZ() + 4.5);
    ctx.assertTrue(
        ShipSystems.equipmentStatus(level, generator).contains("LEGACY BUS"),
        "Legacy hardware must explain how to enable systems");
    ShipSystems.upgrade(level, ship);
    ShipSystems.tick(level, ship);
    var system = ship.getCompoundOrEmpty("systems");
    system.putBoolean("supplyDisabled", true);
    ShipSystems.tick(level, ship);
    ShipSystems.use(player, generator);
    ctx.assertTrue(
        system.getBooleanOr("supplyDisabled", false),
        "Inspecting a generator must not silently restart it");
    ShipSystems.equipmentAction(player, generator, ComputerTerminal.Action.DEVICE_RESET);
    ShipSystems.tick(level, ship);
    ctx.assertTrue(
        ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 1,
        "Explicit manual restart recovers controls");
    var session =
        ComputerTerminal.issue(
            player,
            generator,
            "",
            "generator",
            Integer.toString(ShipSystems.revision(level, ship)));
    boolean wrongAction = false;
    try {
      ComputerTerminal.authorize(
          player,
          new ComputerTerminal.Request(
              session.session(),
              ComputerTerminal.Action.FIRE,
              "",
              "",
              ShipSystems.revision(level, ship)));
    } catch (IllegalArgumentException expected) {
      wrongAction = true;
    }
    ctx.assertTrue(wrongAction, "Equipment sessions cannot acquire helm actions");
    var stranger = ctx.makeMockServerPlayerInLevel();
    stranger.teleportTo(player.getX(), player.getY(), player.getZ());
    boolean stolen = false;
    try {
      ComputerTerminal.authorize(
          stranger,
          new ComputerTerminal.Request(
              session.session(), ComputerTerminal.Action.DEVICE_REFRESH, "", ""));
    } catch (IllegalArgumentException expected) {
      stolen = true;
    }
    ctx.assertTrue(stolen, "Equipment sessions must be player bound");
    int revision = ShipSystems.revision(level, ship);
    ShipSystems.equipmentAction(player, generator, ComputerTerminal.Action.DEVICE_DISABLE);
    boolean stale = false;
    try {
      ComputerTerminal.authorize(
          player,
          new ComputerTerminal.Request(
              session.session(), ComputerTerminal.Action.DEVICE_ENABLE, "", "", revision));
    } catch (IllegalArgumentException expected) {
      stale = true;
    }
    ctx.assertTrue(stale, "Concurrent equipment edits must reject stale revisions");
    ctx.assertTrue(
        ShipSystems.equipmentStatus(level, generator).contains("OFF (disabled)"),
        "Disabled equipment must explain its condition");
    var localPanel = ShipSystems.equipmentStatus(level, generator);
    ctx.assertTrue(
        !localPanel.contains(generator.toShortString())
            && !localPanel.contains(ship.getStringOr("id", "").substring(0, 8))
            && !localPanel.toUpperCase(Locale.ROOT).contains("GENERATOR"),
        "Equipment readings must omit coordinates, UUIDs and the repeated header name");
    boolean wrongHardware = false;
    try {
      ShipSystems.equipmentAction(
          player, base.offset(0, 3, 3), ComputerTerminal.Action.DEVICE_RESET);
    } catch (IllegalArgumentException expected) {
      wrongHardware = true;
    }
    ctx.assertTrue(wrongHardware, "Engine cannot invoke a supply reset");
    level.setBlock(generator, ShipBlocks.AIR_SUPPLY.defaultBlockState(), 18);
    boolean replaced = false;
    try {
      ComputerTerminal.authorize(
          player,
          new ComputerTerminal.Request(
              session.session(), ComputerTerminal.Action.DEVICE_REFRESH, "", ""));
    } catch (IllegalArgumentException expected) {
      replaced = true;
    }
    ctx.assertTrue(replaced, "Replacing equipment closes the old hardware's session");
    var isolated = base.offset(7, 10, 7);
    level.setBlock(isolated, ShipBlocks.CAMERA.defaultBlockState(), 18);
    ctx.assertTrue(
        ShipSystems.equipmentStatus(level, isolated).contains("DISCONNECTED"),
        "Detached equipment must report disconnection");
    var outside = base.offset(64, 0, 64);
    level.setBlock(outside, ShipBlocks.MONITOR.defaultBlockState(), 18);
    ctx.assertTrue(
        ShipSystems.equipmentStatus(level, outside).contains("no recognized ship"),
        "Unregistered equipment must explain setup instead of ignoring the click");
    level.getServer().getPlayerList().remove(player);
    level.getServer().getPlayerList().remove(stranger);
    ctx.succeed();
  }

  @GameTest(maxTicks = 180)
  public void poweredLabIsValidAndDoesNotRegenerate(GameTestHelper ctx) throws java.io.IOException {
    var server = ctx.getLevel().getServer();
    var level = server.overworld();
    var origin = com.nullandkale.nullspace.dev.SystemsLab.ensure(server);
    for (int plot = 23; plot <= 24; plot++) {
      var base = com.nullandkale.nullspace.dev.SystemsLab.base(origin, plot);
      ShipService.inspect(level, SphereLab.controller(base));
      ctx.assertTrue(
          ShipSystems.enabled(ShipTopology.resolve(level, SphereLab.controller(base))),
          "Both lab ships must use real powered systems");
    }
    var controller =
        SphereLab.controller(com.nullandkale.nullspace.dev.SystemsLab.base(origin, 23));
    var old = level.getBlockState(controller);
    try {
      level.setBlock(controller, Blocks.AIR.defaultBlockState(), 18);
      ctx.assertTrue(
          com.nullandkale.nullspace.dev.SystemsLab.ensure(server).equals(origin)
              && level.getBlockState(controller).isAir(),
          "Lab visits must preserve an edited/departed helm");
    } finally {
      level.setBlock(controller, old, 18);
    }
    ctx.succeed();
  }

  @GameTest(maxTicks = 180)
  public void separateControllersPowerDoorsAndJournaledResources(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var server = level.getServer();
    var base = ctx.absolutePos(new BlockPos(6144, 64, 6144));
    String name = "Systems " + UUID.randomUUID().toString().substring(0, 8);
    SphereLab.buildPad(level, base, name);
    SphereLab.buildShip(level, base);
    var helm = SphereLab.controller(base);
    level.setBlock(
        helm,
        ShipBlocks.HELM
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
        18);
    for (int x = 14; x <= 19; x++)
      level.setBlock(
          base.offset(x, 6, 21),
          new Block[] {
                ShipBlocks.GENERATOR,
                ShipBlocks.BATTERY,
                ShipBlocks.AIR_SUPPLY,
                ShipBlocks.DOOR_CONTROL,
                ShipBlocks.SYSTEMS_CONTROLLER,
                ShipBlocks.MONITOR
              }
              [x - 14].defaultBlockState()
              .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
          18);
    var console = base.offset(18, 6, 21);
    var ship = ShipTopology.resolve(level, console);
    String id = ship.getStringOr("id", "");
    ctx.assertTrue(
        id.equals(ShipTopology.resolve(level, helm).getStringOr("id", "")),
        "Helm and systems controller must share identity");
    ShipSystems.upgrade(level, ship);
    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(base.getX() + 18.5, base.getY() + 6, base.getZ() + 19.5);
    for (int x = base.getX() >> 4; x <= (base.getX() + 31) >> 4; x++)
      for (int z = base.getZ() >> 4; z <= (base.getZ() + 31) >> 4; z++)
        level.setChunkForced(x, z, true);
    var space = server.getLevel(TravelService.SPACE);
    int free = 0;
    for (int slot = 0; free < 3; slot++) {
      int x = slot * TravelService.SPACE_SPACING;
      if (TravelService.spaceSlotFree(server, slot)) {
        for (int dx = 0; dx <= 1; dx++)
          for (int dz = 0; dz <= 1; dz++) space.setChunkForced((x >> 4) + dx, dz, true);
        free++;
      }
    }
    ctx.runAfterDelay(
        20,
        () -> {
          var before = ShipStore.get(server).at(level, helm);
          var systems = before.getCompoundOrEmpty("systems").copy();
          ctx.assertTrue(
              ShipSystems.power(level, before, PowerBus.System.CONTROLS) == 1,
              "Physical supply must power both controller roles");
          var former = new net.minecraft.nbt.CompoundTag();
          String formerId = UUID.randomUUID().toString();
          former.putString("id", formerId);
          former.putString("dimension", level.dimension().identifier().toString());
          former.putLong("base", base.offset(4096, 0, 4096).asLong());
          var oldCrew = new net.minecraft.nbt.ListTag();
          var oldMember = new net.minecraft.nbt.CompoundTag();
          oldMember.putString("id", player.getUUID().toString());
          oldCrew.add(oldMember);
          former.put("crew", oldCrew);
          ShipStore.get(server).ships().add(former);
          TravelService.transfer(player, helm, "", ShipService.preflight(level, helm));
          var previous =
              ShipStore.get(server).ships().stream()
                  .map(t -> (net.minecraft.nbt.CompoundTag) t)
                  .filter(t -> t.getStringOr("id", "").equals(formerId))
                  .findFirst()
                  .orElseThrow();
          ctx.assertTrue(
              previous.getListOrEmpty("crew").isEmpty(),
              "Boarding a different ship must clear its stale former crew assignment atomically"
                  + " with transfer");
          var moved = ShipStore.get(server).at(space, player.blockPosition());
          TravelService.transfer(
              player,
              ShipStore.pos(moved, "controller"),
              name,
              ShipService.preflight(space, ShipStore.pos(moved, "controller")));
          var after = ShipStore.get(server).at(level, helm);
          ctx.assertTrue(
              after.getStringOr("id", "").equals(id)
                  && after.getCompoundOrEmpty("systems").equals(systems),
              "Round trip must preserve device IDs, energy, allocations and atmosphere exactly");
          var session =
              ComputerTerminal.issue(
                  player,
                  console,
                  "",
                  "systems",
                  Integer.toString(ShipSystems.revision(level, after)));
          boolean stale = false;
          try {
            ComputerTerminal.authorize(
                player,
                new ComputerTerminal.Request(
                    session.session(), ComputerTerminal.Action.POWER_AIR, "", "", -1));
          } catch (IllegalArgumentException e) {
            stale = true;
          }
          ctx.assertTrue(stale, "Stale systems revision must be rejected");
          ShipSystems.action(player, console, ComputerTerminal.Action.OPEN_DOORS);
          ctx.runAfterDelay(
              16,
              () -> {
                var doors =
                    ShipTopology.discover(level, ShipStore.get(server).at(level, helm)).stream()
                        .filter(
                            p ->
                                level.getBlockState(p).getBlock() instanceof DoorBlock
                                    && level.getBlockState(p).getValue(DoorBlock.HALF)
                                        == DoubleBlockHalf.LOWER)
                        .toList();
                ctx.assertTrue(
                    doors.size() == 12
                        && doors.stream()
                            .allMatch(p -> level.getBlockState(p).getValue(DoorBlock.OPEN)),
                    "Powered controller must open ten room doors and both airlock doors");
                ShipSystems.action(player, console, ComputerTerminal.Action.CLOSE_DOORS);
                ctx.runAfterDelay(
                    16,
                    () -> {
                      ctx.assertTrue(
                          doors.stream()
                              .noneMatch(p -> level.getBlockState(p).getValue(DoorBlock.OPEN)),
                          "Close all must close real doors");
                      ctx.assertTrue(
                          ShipScan.validDoor(level, doors.getFirst()),
                          "Closed real door must retain pressure seal");
                      var vessel = ShipStore.get(server).at(level, helm);
                      var devices = vessel.getCompoundOrEmpty("systems").getListOrEmpty("devices");
                      var door =
                          (net.minecraft.nbt.CompoundTag)
                              devices.stream()
                                  .filter(
                                      t ->
                                          ((net.minecraft.nbt.CompoundTag) t)
                                              .getStringOr("type", "")
                                              .endsWith("_door"))
                                  .findFirst()
                                  .orElseThrow();
                      var shortId = door.getStringOr("id", "").substring(0, 8);
                      ShipSystems.action(
                          player,
                          console,
                          ComputerTerminal.Action.CONFIGURE,
                          "group lock add " + shortId);
                      ShipSystems.action(
                          player, console, ComputerTerminal.Action.CONFIGURE, "doors lock open");
                      ctx.runAfterDelay(
                          2,
                          () -> {
                            var selected =
                                ShipStore.pos(vessel, "base")
                                    .offset(BlockPos.of(door.getLongOr("position", 0)));
                            ctx.assertTrue(
                                level.getBlockState(selected).getValue(DoorBlock.OPEN)
                                    && doors.stream()
                                            .filter(
                                                p ->
                                                    level.getBlockState(p).getValue(DoorBlock.OPEN))
                                            .count()
                                        == 1,
                                "Named group must move only its stable door ID");
                            ShipSystems.action(
                                player, console, ComputerTerminal.Action.CLOSE_DOORS);
                            var session2 =
                                ComputerTerminal.issue(
                                    player,
                                    console,
                                    "",
                                    "systems",
                                    Integer.toString(ShipSystems.revision(level, vessel)));
                            ComputerTerminal.close(player, session2.session());
                            ctx.runAfterDelay(
                                2,
                                () -> {
                                  ctx.assertTrue(
                                      level.getBlockState(selected).getValue(DoorBlock.OPEN),
                                      "Closing the terminal cancels queued door work before it can"
                                          + " resume");
                                  ShipSystems.action(
                                      player,
                                      console,
                                      ComputerTerminal.Action.CONFIGURE,
                                      "power controls 0");
                                  ctx.runAfterDelay(
                                      2,
                                      () -> {
                                        boolean blackout = false;
                                        try {
                                          ShipSystems.action(
                                              player, console, ComputerTerminal.Action.OPEN_DOORS);
                                        } catch (IllegalArgumentException expected) {
                                          blackout = true;
                                        }
                                        ctx.assertTrue(
                                            blackout, "Unpowered controls must reject actions");
                                        var generator =
                                            vessel
                                                .getCompoundOrEmpty("systems")
                                                .getListOrEmpty("devices")
                                                .stream()
                                                .map(t -> (net.minecraft.nbt.CompoundTag) t)
                                                .filter(
                                                    d ->
                                                        d.getStringOr("type", "")
                                                            .equals("nullspace:generator"))
                                                .findFirst()
                                                .orElseThrow();
                                        generator.putBoolean("enabled", false);
                                        ShipSystems.tick(level, vessel);
                                        ShipSystems.equipmentAction(
                                            player,
                                            base.offset(14, 6, 21),
                                            ComputerTerminal.Action.DEVICE_RESET);
                                        ctx.runAfterDelay(
                                            2,
                                            () -> {
                                              ctx.assertTrue(
                                                  ShipSystems.power(
                                                              level,
                                                              vessel,
                                                              PowerBus.System.CONTROLS)
                                                          == 1
                                                      && ShipSystems.summary(level, vessel)
                                                          .contains("Generation 8"),
                                                  "Local supply reset must recover an intentionally"
                                                      + " unpowered console");
                                              level.setBlock(
                                                  base.offset(15, 6, 22),
                                                  ShipBlocks.BATTERY.defaultBlockState(),
                                                  18);
                                              ShipSystems.tick(level, vessel);
                                              var system = vessel.getCompoundOrEmpty("systems");
                                              system.putBoolean("supplyDisabled", true);
                                              var batteries =
                                                  system.getListOrEmpty("devices").stream()
                                                      .map(t -> (net.minecraft.nbt.CompoundTag) t)
                                                      .filter(
                                                          d ->
                                                              d.getStringOr("type", "")
                                                                  .equals("nullspace:battery"))
                                                      .toList();
                                              ctx.assertTrue(
                                                  batteries.size() == 2,
                                                  "Fixture must have two physical batteries");
                                              batteries.get(0).putInt("energy", 100);
                                              batteries.get(1).putInt("energy", 0);
                                              ShipSystems.tick(level, vessel);
                                              ctx.assertTrue(
                                                  ShipSystems.power(
                                                          level, vessel, PowerBus.System.ENGINES)
                                                      == 0,
                                                  "An empty second battery cannot double the other"
                                                      + " battery's discharge rate");
                                              system.putBoolean("supplyDisabled", false);
                                              system.putIntArray("requests", new int[6]);
                                              batteries.get(0).putInt("energy", 1200);
                                              batteries.get(1).putInt("energy", 0);
                                              ShipSystems.tick(level, vessel);
                                              ctx.assertTrue(
                                                  batteries.stream()
                                                              .mapToInt(
                                                                  b -> b.getIntOr("energy", 0))
                                                              .sum()
                                                          == 1204
                                                      && ShipSystems.summary(level, vessel)
                                                          .contains("Battery 1204"),
                                                  "A full second cell cannot double charging rate"
                                                      + " or make bus accounting disagree with"
                                                      + " stored energy");
                                              var helmState = level.getBlockState(helm);
                                              level.setBlock(
                                                  helm, Blocks.AIR.defaultBlockState(), 18);
                                              system.putIntArray(
                                                  "requests", new int[] {1, 2, 1, 3, 1, 0});
                                              ShipSystems.tick(level, vessel);
                                              ctx.assertTrue(
                                                  ShipSystems.power(
                                                          level, vessel, PowerBus.System.AIR)
                                                      > 0,
                                                  "Destroying the helm must not stop surviving"
                                                      + " connected life support");
                                              level.setBlock(helm, helmState, 18);
                                              var supply = base.offset(14, 6, 21);
                                              var supplyState = level.getBlockState(supply);
                                              level.setBlock(
                                                  supply, Blocks.AIR.defaultBlockState(), 18);
                                              ctx.assertTrue(
                                                  ShipSystems.power(
                                                              level,
                                                              vessel,
                                                              PowerBus.System.ENGINES)
                                                          == 3
                                                      && ShipSystems.summary(level, vessel)
                                                          .contains("Generation 0"),
                                                  "Generator destruction must recompute against"
                                                      + " surviving charged batteries immediately");
                                              for (var cell : batteries)
                                                level.setBlock(
                                                    ShipStore.pos(vessel, "base")
                                                        .offset(
                                                            BlockPos.of(
                                                                cell.getLongOr("position", 0))),
                                                    Blocks.AIR.defaultBlockState(),
                                                    18);
                                              ctx.assertTrue(
                                                  ShipSystems.power(
                                                              level,
                                                              vessel,
                                                              PowerBus.System.ENGINES)
                                                          == 0
                                                      && ShipSystems.power(
                                                              level,
                                                              vessel,
                                                              PowerBus.System.CONTROLS)
                                                          == 0,
                                                  "Destroying all supply hardware must revoke stale"
                                                      + " grants before the next systems tick");
                                              level.setBlock(supply, supplyState, 18);
                                              server.getPlayerList().remove(player);
                                              ctx.succeed();
                                            });
                                      });
                                });
                          });
                    });
              });
        });
  }
}
