package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.shipyard.*;
import com.nullandkale.nullspace.systems.*;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.*;

public final class ShipYardGameTest {
  private record Fixture(BlockPos base, BlockPos beacon, ServerPlayer player) {}

  private static Fixture fixture(GameTestHelper ctx, int slot, int width, int depth) {
    var level = ctx.getLevel();
    var base = new BlockPos(210000 + slot * 128, 70, 240000);
    for (int x = -1; x <= width; x++)
      for (int z = -1; z <= depth; z++) level.getChunk(base.offset(x, 0, z));
    try {
      ShipYard.buildPad(
          level, base, width, depth, "YardTest " + UUID.randomUUID().toString().substring(0, 8));
    } catch (java.io.IOException error) {
      throw new RuntimeException(error);
    }
    if (slot >= 10)
      for (int x = (base.getX() - 1) >> 4; x <= (base.getX() + width) >> 4; x++)
        for (int z = (base.getZ() - 1) >> 4; z <= (base.getZ() + depth) >> 4; z++)
          level
              .getChunkSource()
              .addTicketWithRadius(
                  net.minecraft.server.level.TicketType.PORTAL,
                  new net.minecraft.world.level.ChunkPos(x, z),
                  2);
    var beacon = base.offset(-1, 1, 1);
    level.setBlock(beacon, ShipBlocks.SHIP_BEACON.defaultBlockState(), 18);
    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(
        level, base.getX() - 2.5, base.getY() + 1, base.getZ() + 1.5, Set.of(), 0, 0, true);
    return new Fixture(base, beacon, player);
  }

  @FunctionalInterface
  private interface Checked {
    void run() throws Exception;
  }

  private static void later(GameTestHelper ctx, Checked action) {
    ctx.runAtTickTime(
        25,
        () -> {
          try {
            action.run();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  private static void rejects(GameTestHelper ctx, Runnable action, String text) {
    try {
      action.run();
      throw new AssertionError("Expected rejection: " + text);
    } catch (IllegalArgumentException expected) {
      ctx.assertTrue(
          expected.getMessage().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)),
          "Friendly rejection should explain " + text + ": " + expected.getMessage());
    }
  }

  @GameTest(maxTicks = 500)
  public void everyBlueprintIsConnectedSealedWalkableAndHasClearEngines(GameTestHelper ctx) {
    var level = ctx.getLevel();
    int i = 0;
    for (var p : ShipBlueprints.PRESETS) {
      var f = fixture(ctx, i++, 32, 32);
      var pad = PadComputers.inspect(level, f.beacon()).pad();
      var at = ShipBlueprints.origin(p, pad, 32);
      var plan = ShipBlueprints.blocks(p);
      ctx.assertTrue(plan.equals(ShipBlueprints.blocks(p)), "Blueprints are deterministic");
      for (var entry : plan.entrySet())
        level.setBlock(at.offset(entry.getKey()), entry.getValue(), 18);
      var scan = ShipScan.inspect(level, at.offset(p.helm()), pad, true);
      ctx.assertTrue(scan.blocks().size() == plan.size(), "Every block is attached on " + p.name());
      ctx.assertTrue(
          scan.pressure().breathable(scan.boarding()),
          "Bridge has sealed standing room on " + p.name());
      ctx.assertTrue(
          scan.blocks().stream()
                  .filter(pos -> ShipBlocks.navigation(level.getBlockState(pos)))
                  .count()
              == 1,
          "Each preset has exactly one helm");
      for (var block :
          List.of(
              ShipBlocks.GENERATOR,
              ShipBlocks.BATTERY,
              ShipBlocks.AIR_SUPPLY,
              ShipBlocks.DOOR_CONTROL,
              ShipBlocks.TERMINAL,
              ShipBlocks.LOCAL_SCANNER,
              ShipBlocks.LONG_RANGE_SCANNER,
              ShipBlocks.CAMERA,
              ShipBlocks.MONITOR))
        ctx.assertTrue(
            scan.blocks().stream().anyMatch(pos -> level.getBlockState(pos).is(block)),
            p.name() + " includes " + ShipMessages.block(block));
    }
    ctx.succeed();
  }

  @GameTest(maxTicks = 500)
  public void exactSizePadsKeepComputersOnAllFourEdgesDisconnectedFromTheHull(GameTestHelper ctx) {
    var level = ctx.getLevel();
    int slot = 20;
    for (var preset : ShipBlueprints.PRESETS) {
      var f = fixture(ctx, slot++, preset.width(), preset.depth());
      for (var local :
          List.of(
              new BlockPos(preset.width() / 2, 1, -1),
              new BlockPos(preset.width(), 1, preset.depth() / 2),
              new BlockPos(preset.width() / 2, 1, preset.depth())))
        level.setBlock(f.base().offset(local), PadComputers.BLOCK.defaultBlockState(), 18);
      var pad = PadComputers.inspect(level, f.beacon()).pad();
      var origin = ShipBlueprints.origin(preset, pad, 32);
      for (var entry : ShipBlueprints.blocks(preset).entrySet())
        level.setBlock(origin.offset(entry.getKey()), entry.getValue(), 18);
      var scan = ShipScan.inspect(level, origin.offset(preset.helm()), pad, true);
      ctx.assertTrue(
          scan.blocks().stream()
              .noneMatch(
                  at ->
                      level.getBlockState(at).is(PadComputers.BLOCK)
                          || level.getBlockState(at).is(ShipBlocks.SHIP_BEACON)),
          "Minimum-sized " + preset.name() + " never captures its ground controllers");
    }
    ctx.succeed();
  }

  @GameTest(maxTicks = 500)
  public void freeDeliveryCreatesOnePersistentPoweredShipAndNeverReplacesIt(GameTestHelper ctx)
      throws java.io.IOException {
    var f = fixture(ctx, 10, 16, 18);
    later(
        ctx,
        () -> {
          var p = f.player();
          int xp = p.experienceLevel;
          var ship = ShipYardService.deliver(p, f.beacon(), "mule");
          ctx.assertTrue(
              p.experienceLevel == xp && p.getInventory().isEmpty(),
              "No money, experience or materials are consumed");
          ctx.assertTrue(ShipSystems.enabled(ship), "Delivered ships have finite systems enabled");
          ctx.assertTrue(
              ship.getStringOr("blueprint", "").equals("mule"), "Selected design is persisted");
          ctx.assertTrue(
              ShipStore.get(p.level().getServer()).list("shipyardOrders").isEmpty(),
              "Completed order is cleared after saving terrain");
          var before = ship.copy();
          rejects(ctx, () -> ShipYardService.deliver(p, f.beacon(), "sparrow"), "clear");
          ctx.assertTrue(before.equals(ship), "A second call preserves the delivered ship");
          var saved =
              ShipStore.read(ShipStore.get(p.level().getServer()).directory.resolve("ships.nbt"));
          ctx.assertTrue(
              saved.getListOrEmpty("ships").stream()
                  .anyMatch(
                      tag ->
                          ((net.minecraft.nbt.CompoundTag) tag)
                              .getStringOr("id", "")
                              .equals(ship.getStringOr("id", ""))),
              "Ship identity survives real compressed NBT");
          ctx.succeed();
        });
  }

  @GameTest(maxTicks = 100)
  public void obstructionsFluidsEntitiesAndBadSizesRejectBeforeAnyConstruction(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var f = fixture(ctx, 11, 12, 16);
    later(
        ctx,
        () -> {
          rejects(ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "atlas"), "needs");
          var obstruction = f.base().offset(4, 60, 4);
          level.setBlock(obstruction, Blocks.GLASS.defaultBlockState(), 18);
          rejects(ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "sparrow"), "Glass");
          ctx.assertTrue(
              level.getBlockState(obstruction).is(Blocks.GLASS), "High glass is preserved");
          level.setBlock(obstruction, Blocks.AIR.defaultBlockState(), 18);
          var wet = f.base().offset(4, 1, 4);
          level.setBlock(wet, Blocks.WATER.defaultBlockState(), 18);
          rejects(ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "sparrow"), "Water");
          level.setBlock(wet, Blocks.AIR.defaultBlockState(), 18);
          var pig =
              EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
          pig.setPos(wet.getX() + .5, wet.getY(), wet.getZ() + .5);
          level.addFreshEntity(pig);
          rejects(ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "sparrow"), "Pig");
          pig.discard();
          ctx.assertTrue(
              level.getBlockState(wet).isAir(), "Rejected deliveries place no hull blocks");
          ctx.succeed();
        });
  }

  @GameTest(maxTicks = 500)
  public void interruptedDeliveryResumesFromSavedIntentWithoutDuplicateShips(GameTestHelper ctx)
      throws java.io.IOException {
    var f = fixture(ctx, 12, 12, 16);
    later(
        ctx,
        () -> {
          var old = ShipYardService.placementObserver;
          try {
            ShipYardService.placementObserver =
                count -> {
                  if (count == 7)
                    throw new IllegalStateException("injected placement interruption");
                };
            try {
              ShipYardService.deliver(f.player(), f.beacon(), "sparrow");
              throw new AssertionError("Expected injected interruption");
            } catch (IllegalStateException expected) {
              ctx.assertTrue(expected.getMessage().contains("injected"), "Placement seam reached");
            }
          } finally {
            ShipYardService.placementObserver = old;
          }
          var store = ShipStore.get(f.player().level().getServer());
          var disk = ShipStore.read(store.directory.resolve("ships.nbt"));
          ctx.assertTrue(
              !disk.getListOrEmpty("shipyardOrders").isEmpty(),
              "Intent reaches disk before placing blocks");
          var ship = ShipYardService.deliver(f.player(), f.beacon(), "sparrow");
          String order = ship.getStringOr("shipyardOrder", "");
          ShipYardService.recover(f.player().level().getServer());
          ctx.assertTrue(
              store.ships().stream()
                      .filter(
                          t ->
                              ((net.minecraft.nbt.CompoundTag) t)
                                  .getStringOr("shipyardOrder", "")
                                  .equals(order))
                      .count()
                  == 1,
              "Repeated recovery does not duplicate a completed ship");
          ctx.succeed();
        });
  }

  @GameTest
  public void beaconSessionsRejectForgedCatalogsCrossPlayerUseAndHelmActions(GameTestHelper ctx) {
    var f = fixture(ctx, 13, 12, 16);
    var opened = ComputerTerminal.issue(f.player(), f.beacon(), "Ready", "shipyard", "sparrow");
    ctx.assertTrue(
        opened.destinations().size() == ShipBlueprints.PRESETS.size(),
        "Server owns all offered designs");
    rejects(
        ctx,
        () ->
            ComputerTerminal.authorize(
                f.player(),
                new ComputerTerminal.Request(
                    opened.session(), ComputerTerminal.Action.LAUNCH, "", "", -1)),
        "controller");
    rejects(
        ctx,
        () ->
            ComputerTerminal.authorize(
                f.player(),
                new ComputerTerminal.Request(
                    opened.session(), ComputerTerminal.Action.SHIP_BUY, "forged", "", -1)),
        "catalog");
    var other = ctx.makeMockServerPlayerInLevel();
    rejects(
        ctx,
        () ->
            ComputerTerminal.authorize(
                other,
                new ComputerTerminal.Request(
                    opened.session(), ComputerTerminal.Action.SHIP_BUY, "sparrow", "", -1)),
        "session");
    var accepted =
        ComputerTerminal.authorize(
            f.player(),
            new ComputerTerminal.Request(
                opened.session(), ComputerTerminal.Action.SHIP_QUOTE, "mule", "", -1));
    ctx.assertTrue(
        accepted.argument().equals("mule"),
        "Rejected forgeries do not consume the legitimate session");
    f.player().level().setBlock(f.beacon(), Blocks.STONE.defaultBlockState(), 18);
    rejects(
        ctx,
        () ->
            ComputerTerminal.authorize(
                f.player(),
                new ComputerTerminal.Request(
                    opened.session(), ComputerTerminal.Action.SHIP_BUY, "sparrow", "", -1)),
        "changed");
    ctx.succeed();
  }

  @GameTest(maxTicks = 1000)
  public void showroomIsAdditiveHasSixLaunchableShipsAndPreservesEditsOnRevisit(GameTestHelper ctx)
      throws java.io.IOException {
    var server = ctx.getLevel().getServer();
    var yard = ShipYard.ensure(server);
    var level = server.overworld();
    for (int index = 0; index < ShipBlueprints.PRESETS.size(); index++) {
      var base = yard.pad(index);
      var preset = ShipBlueprints.PRESETS.get(index);
      var pad =
          new PadScanner.Pad(
              base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31);
      ShipScan.inspect(
          level, ShipBlueprints.origin(preset, pad, 32).offset(preset.helm()), pad, true);
    }
    for (int index = 0; index < 3; index++) {
      var base = yard.delivery(index);
      ctx.assertTrue(
          level.getBlockState(base.offset(10, 1, 10)).isAir(), "Delivery bay starts empty");
      ctx.assertTrue(
          level.getBlockState(base.offset(-1, 1, 1)).is(ShipBlocks.SHIP_BEACON),
          "Delivery bay includes beacon");
    }
    var edited = yard.delivery(0).offset(2, 1, 2);
    level.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
    ctx.assertTrue(
        ShipYard.ensure(server).equals(yard)
            && level.getBlockState(edited).is(Blocks.DIAMOND_BLOCK),
        "Visits never regenerate player changes");
    ctx.succeed();
  }

  @GameTest(maxTicks = 100)
  public void facingNeverChangesThePadAndStandingInsidePreventsDelivery(GameTestHelper ctx) {
    var f = fixture(ctx, 14, 12, 16);
    later(
        ctx,
        () -> {
          var level = ctx.getLevel();
          var bounds = PadComputers.inspect(level, f.beacon()).pad();
          for (var direction :
              List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            level.setBlock(
                f.beacon(),
                ShipBlocks.SHIP_BEACON
                    .defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, direction),
                18);
            ctx.assertTrue(
                PadComputers.inspect(level, f.beacon()).pad().equals(bounds),
                "Beacon facing is cosmetic");
          }
          f.player()
              .teleportTo(
                  level,
                  f.base().getX() + 3.5,
                  f.base().getY() + 1,
                  f.base().getZ() + 3.5,
                  Set.of(),
                  0,
                  0,
                  true);
          rejects(ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "sparrow"), "Move");
          f.player()
              .teleportTo(
                  level,
                  f.base().getX() - 2.5,
                  f.base().getY() + 1,
                  f.base().getZ() + 1.5,
                  Set.of(),
                  0,
                  0,
                  true);
          level.setBlock(f.base().offset(-1, 0, 4), Blocks.AIR.defaultBlockState(), 18);
          rejects(
              ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "sparrow"), "cobblestone");
          ctx.succeed();
        });
  }

  @GameTest(maxTicks = 500)
  public void failedIntentSaveNeverStartsBuildingAndCanBeRetried(GameTestHelper ctx) {
    var f = fixture(ctx, 16, 12, 16);
    later(
        ctx,
        () -> {
          var store = ShipStore.get(f.player().level().getServer());
          var file = store.directory.resolve("ships.nbt");
          store.save();
          var previous = ShipStore.writeObserver;
          try {
            ShipStore.writeObserver =
                progress -> {
                  if (progress.file().equals(file))
                    throw new IllegalStateException("intent disk failure");
                };
            try {
              ShipYardService.deliver(f.player(), f.beacon(), "sparrow");
              throw new AssertionError("Delivery must stop when the intent cannot be saved");
            } catch (IllegalStateException expected) {
              ctx.assertTrue(
                  expected.getMessage().equals("intent disk failure"),
                  "Original save failure is reported");
            }
          } finally {
            ShipStore.writeObserver = previous;
          }
          ctx.assertTrue(
              store.list("shipyardOrders").isEmpty(), "Failed intent is removed from memory");
          var preset = ShipBlueprints.get("sparrow");
          var origin =
              ShipBlueprints.origin(
                  preset, PadComputers.inspect(f.player().level(), f.beacon()).pad(), 32);
          for (var at : ShipBlueprints.blocks(preset).keySet())
            ctx.assertTrue(
                f.player().level().getBlockState(origin.offset(at)).isAir(),
                "No ship blocks are placed before a durable intent");
          var delivered = ShipYardService.deliver(f.player(), f.beacon(), "sparrow");
          ctx.assertTrue(
              delivered.getStringOr("blueprint", "").equals("sparrow"),
              "Buyer can retry after storage recovers");
          ctx.succeed();
        });
  }

  @GameTest(maxTicks = 500)
  public void failedOrderRemovalRetainsIntentAndReusesCommittedIdentity(GameTestHelper ctx) {
    var f = fixture(ctx, 17, 12, 16);
    later(
        ctx,
        () -> {
          var server = f.player().level().getServer();
          var store = ShipStore.get(server);
          var file = store.directory.resolve("ships.nbt");
          var previousWrite = ShipStore.writeObserver;
          var previousDelivery = ShipYardService.deliveryObserver;
          try {
            ShipYardService.deliveryObserver =
                stage -> {
                  if (stage == ShipYardService.DeliveryStage.WORLD_SAVED)
                    ShipStore.writeObserver =
                        progress -> {
                          if (progress.file().equals(file))
                            throw new IllegalStateException("completion disk failure");
                        };
                };
            try {
              ShipYardService.deliver(f.player(), f.beacon(), "sparrow");
              throw new AssertionError("Completion write failure must reach the buyer");
            } catch (IllegalStateException expected) {
              ctx.assertTrue(
                  expected.getMessage().equals("completion disk failure"),
                  "Completion failure is reported");
            }
          } finally {
            ShipStore.writeObserver = previousWrite;
            ShipYardService.deliveryObserver = previousDelivery;
          }
          ctx.assertTrue(
              store.list("shipyardOrders").size() == 1
                  && ShipStore.read(file).getListOrEmpty("shipyardOrders").size() == 1,
              "A failed final save retains the order in memory and on disk");
          var order = (net.minecraft.nbt.CompoundTag) store.list("shipyardOrders").getFirst();
          var preset = ShipBlueprints.get("sparrow");
          var helm = ShipStore.pos(order, "origin").offset(preset.helm());
          String id = store.at(f.player().level(), helm).getStringOr("id", "");
          var delivered = ShipYardService.deliver(f.player(), f.beacon(), "sparrow");
          ctx.assertTrue(
              delivered.getStringOr("id", "").equals(id),
              "Resuming a committed hull reuses its ship identity");
          ctx.assertTrue(
              store.list("shipyardOrders").isEmpty()
                  && ShipStore.read(file).getListOrEmpty("shipyardOrders").isEmpty(),
              "Successful retry clears the durable intent");
          ctx.succeed();
        });
  }

  @GameTest(maxTicks = 500)
  public void recoveryPreservesConflictingBuildsAndResumesOnlyTheOriginalOrder(GameTestHelper ctx) {
    var f = fixture(ctx, 15, 12, 16);
    later(
        ctx,
        () -> {
          var previous = ShipYardService.placementObserver;
          try {
            ShipYardService.placementObserver =
                count -> {
                  if (count == 7) throw new IllegalStateException("interruption");
                };
            try {
              ShipYardService.deliver(f.player(), f.beacon(), "sparrow");
              throw new AssertionError("Missing interruption");
            } catch (IllegalStateException expected) {
            }
          } finally {
            ShipYardService.placementObserver = previous;
          }
          var p = ShipBlueprints.get("sparrow");
          var origin =
              ShipBlueprints.origin(p, PadComputers.inspect(ctx.getLevel(), f.beacon()).pad(), 32);
          var conflicting = origin.offset(ShipBlueprints.blocks(p).keySet().iterator().next());
          ctx.getLevel().setBlock(conflicting, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
          rejects(ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "skylark"), "pending");
          rejects(ctx, () -> ShipYardService.deliver(f.player(), f.beacon(), "sparrow"), "Diamond");
          ctx.assertTrue(
              ctx.getLevel().getBlockState(conflicting).is(Blocks.DIAMOND_BLOCK),
              "Resume never erases a player's conflicting build");
          ctx.getLevel().setBlock(conflicting, Blocks.AIR.defaultBlockState(), 18);
          ShipYardService.recover(f.player().level().getServer());
          ctx.assertTrue(
              ShipStore.get(f.player().level().getServer())
                      .at(f.player().level(), origin.offset(p.helm()))
                  != null,
              "Startup recovery can finish the restored order");
          ctx.succeed();
        });
  }
}
