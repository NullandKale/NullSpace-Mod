package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.computer.ComputerImageCodec;
import com.nullandkale.nullspace.model.computer.ScriptLanguage;
import com.nullandkale.nullspace.model.computer.VirtualComputer;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;

/** Opt-in only: prepare and verify execute in separate JVMs against the same disposable save. */
public final class CrashRecoveryGameTest {
  @GameTest(maxTicks = 400)
  public void restart(GameTestHelper ctx) throws java.io.IOException {
    String mode = System.getProperty("nullspace.crash.mode", "");
    if (mode.isEmpty()) {
      ctx.succeed();
      return;
    }
    var server = ctx.getLevel().getServer();
    var planet = server.overworld();
    boolean landing = System.getProperty("nullspace.crash.direction", "launch").equals("landing");
    var space = server.getLevel(TravelService.SPACE);
    var file = server.getWorldPath(LevelResource.ROOT).resolve("crash-manifest.nbt");
    var base = new BlockPos(4096, 96, 4096);
    var planetController = base.offset(6, 3, 3);
    var spaceController = new BlockPos(6, 98, 3);
    var source = landing ? space : planet;
    var target = landing ? planet : space;
    var controller = landing ? spaceController : planetController;
    var targetController = landing ? planetController : spaceController;
    var sourceMin = landing ? new BlockPos(0, 96, 0) : base.above();
    var targetMin = landing ? base.above() : new BlockPos(0, 96, 0);
    planet.setChunkForced(256, 256, true);
    space.setChunkForced(0, 0, true);
    if (mode.equals("prepare")) {
      ShipTestFixture.build(planet, base);
      PadComputers.register(
          planet,
          base.offset(-1, 1, 3),
          new PadScanner.Pad(4096, 4103, 96, 4096, 4103),
          "Crash Home");
      for (int x = 2; x <= 5; x++)
        planet.setBlock(
            base.offset(x, 3, 5),
            new net.minecraft.world.level.block.Block[] {
                  ModBlocks.GENERATOR,
                  ModBlocks.BATTERY,
                  ModBlocks.AIR_SUPPLY,
                  ModBlocks.LOCAL_SCANNER
                }
                [x - 2].defaultBlockState(),
            18);
      var initial = ShipTopology.resolve(planet, planetController);
      ShipSystems.upgrade(planet, initial);
      var player = ctx.makeMockServerPlayerInLevel();
      player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
      player.setYRot(63);
      player.setXRot(17);
      player.getInventory().add(new ItemStack(Items.EMERALD, 11));
      var creeper =
          net.minecraft.world.entity.EntityTypes.CREEPER.create(
              planet, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
      creeper.setNoAi(true);
      creeper.setHealth(13);
      creeper.setPos(base.getX() + 1.5, base.getY() + 3, base.getZ() + 3.5);
      planet.addFreshEntity(creeper);
      var dropped =
          new net.minecraft.world.entity.item.ItemEntity(
              planet,
              base.getX() + 2.5,
              base.getY() + 3,
              base.getZ() + 4.5,
              new ItemStack(Items.GOLD_INGOT, 3));
      planet.addFreshEntity(dropped);
      var manifest = new CompoundTag();
      manifest.putString("player", player.getUUID().toString());
      manifest.putString("creeper", creeper.getUUID().toString());
      manifest.putString("item", dropped.getUUID().toString());
      WorldStore.write(file, manifest);
      afterEntitiesLoaded(
          ctx,
          planet,
          space,
          base,
          () -> {
            if (landing) {
              var launched =
                  TravelService.transfer(
                      player, planetController, "", ShipService.inspect(planet, planetController));
              ctx.assertTrue(
                  launched.outcome() == TravelService.Outcome.COMPLETED,
                  "Landing fixture launch must complete before crash setup: " + launched.message());
            }
            var vessel = WorldStore.get(server).at(source, controller);
            ctx.assertTrue(vessel != null, "Crash setup needs its authoritative source ship");
            var marker = sourceMin.offset(1, 5, 1);
            source.setBlock(
                marker, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(), 18);
            var offline = new CompoundTag();
            offline.putString("id", UUID.randomUUID().toString());
            offline.putDouble("x", 4.5);
            offline.putDouble("y", 2);
            offline.putDouble("z", 3.5);
            offline.putFloat("yaw", -27);
            offline.putFloat("pitch", 5);
            vessel.getListOrEmpty("crew").add(offline);
            manifest.putString("offline", offline.getStringOr("id", ""));
            var computer = new VirtualComputer((name, args) -> 0);
            for (int i = 0; i < 4; i++)
              computer.files.write("/home/crew/cargo" + i, "\u661f\uD83D\uDE80".repeat(5461));
            computer.files.write("/home/crew/job.py", "x = 7\nsleep(50)\nx = 8");
            computer.run("/home/crew/job.py", ScriptLanguage.Syntax.PYTHON, true);
            computer.tick(false, true);
            var disk = new CompoundTag();
            disk.putString("id", UUID.randomUUID().toString());
            disk.putString("owner", player.getUUID().toString());
            disk.put("image", ComputerImageCodec.encode(computer.save()));
            var computers = new CompoundTag();
            computers.put(Long.toString(BlockPos.ZERO.asLong()), disk);
            vessel.put("computers", computers);
            manifest.put("systems", vessel.getCompoundOrEmpty("systems").copy());
            WorldStore.write(file, manifest);
            WorldStore.get(server).save();
            server.saveEverything(true, true, true);
            String stage = System.getProperty("nullspace.crash.stage");
            TravelService.failureInjector =
                point -> {
                  if (point.equals(stage)) {
                    server.saveEverything(true, true, true);
                    Runtime.getRuntime().halt(86);
                  }
                };
            WorldStore.writeObserver =
                progress -> {
                  if (stage.equals("TEMP_" + progress.stage())
                      && progress.file().getParent().getFileName().toString().equals("transfers")
                      && progress.bytes() > 0) Runtime.getRuntime().halt(86);
                };
            TravelService.transfer(
                player,
                controller,
                landing ? "Crash Home" : "",
                ShipService.inspect(source, controller));
            throw new AssertionError("Expected real JVM termination at " + stage);
          });
    } else {
      var manifest = WorldStore.read(file);
      boolean committed = System.getProperty("nullspace.crash.stage").equals("COMMITTED");
      afterEntitiesLoaded(
          ctx,
          planet,
          space,
          base,
          () -> {
            TravelService.recover(server);
            TravelService.recover(server);
            var authoritative = committed ? target : source;
            var pos = committed ? targetController : controller;
            ctx.assertTrue(
                authoritative.getBlockState(pos).is(ModBlocks.COMPUTER),
                "Restart must select one authoritative ship");
            ctx.assertTrue(
                (committed ? source : target)
                    .getBlockState(committed ? controller : targetController)
                    .isAir(),
                "Restart must remove the other copy");
            var vessel = WorldStore.get(server).at(authoritative, pos);
            ctx.assertTrue(
                vessel != null
                    && vessel
                        .getCompoundOrEmpty("systems")
                        .equals(manifest.getCompoundOrEmpty("systems")),
                "Real restart must preserve device identities, power configuration, stored energy,"
                    + " and finite room air exactly");
            var computerRecord =
                vessel
                    .getCompoundOrEmpty("computers")
                    .getCompoundOrEmpty(Long.toString(BlockPos.ZERO.asLong()));
            var restoredComputer = new VirtualComputer((name, args) -> 0);
            restoredComputer.restore(ComputerImageCodec.decode(computerRecord.get("image")));
            ctx.assertTrue(
                restoredComputer
                        .files
                        .read("/home/crew/cargo3")
                        .equals("\u661f\uD83D\uDE80".repeat(5461))
                    && restoredComputer.processes.size() == 1
                    && restoredComputer.processes.values().iterator().next().job().pc == 2,
                "Fresh JVM recovery preserves a large Unicode computer disk and sleeping detached"
                    + " program counter");
            var min = committed ? targetMin : sourceMin;
            ctx.assertTrue(
                authoritative
                    .getBlockState(min.offset(1, 5, 1))
                    .is(net.minecraft.world.level.block.Blocks.GLASS),
                "Edited hull survives restart");
            ctx.assertTrue(
                vessel.getListOrEmpty("crew").stream()
                    .anyMatch(
                        t ->
                            ((CompoundTag) t)
                                .getStringOr("id", "")
                                .equals(manifest.getStringOr("offline", ""))),
                "Offline crew assignment survives recovery");
            var bounds = new HullScan.Bounds(min, min.offset(31, 31, 31));
            var entities = HullEntities.inBounds(authoritative, bounds);
            for (String key : List.of("creeper", "item")) {
              UUID id = UUID.fromString(manifest.getStringOr(key, ""));
              ctx.assertTrue(
                  entities.stream().filter(e -> e.getUUID().equals(id)).count() == 1,
                  "Restart must preserve one " + key + " UUID");
              ctx.assertTrue(
                  HullEntities.inBounds(
                          committed ? source : target,
                          new HullScan.Bounds(
                              committed ? sourceMin : targetMin,
                              (committed ? sourceMin : targetMin).offset(31, 31, 31)))
                      .stream()
                      .noneMatch(e -> e.getUUID().equals(id)),
                  "Restart must not duplicate " + key);
            }
            var chest =
                (net.minecraft.world.level.block.entity.ChestBlockEntity)
                    authoritative.getBlockEntity(min.offset(2, 2, 2));
            ctx.assertTrue(chest.getItem(0).getCount() == 7, "Restart must preserve chest cargo");
            var cookie =
                net.minecraft.server.network.CommonListenerCookie.createInitial(
                    new com.mojang.authlib.GameProfile(
                        UUID.fromString(manifest.getStringOr("player", "")), "test-mock-player"),
                    false);
            var player =
                new net.minecraft.server.level.ServerPlayer(
                    server, authoritative, cookie.gameProfile(), cookie.clientInformation());
            // 26.3 loads player data in PrepareSpawnTask, before placeNewPlayer.
            var data =
                server
                    .getPlayerList()
                    .loadPlayerData(
                        new net.minecraft.server.players.NameAndId(cookie.gameProfile()))
                    .orElseThrow();
            var input =
                net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess(), data);
            player.load(input);
            var saved =
                input
                    .read(net.minecraft.server.level.ServerPlayer.SavedPosition.MAP_CODEC)
                    .orElseThrow();
            var rotation = saved.rotation().orElseThrow();
            player.snapTo(saved.position().orElseThrow(), rotation.x, rotation.y);
            var connection =
                new net.minecraft.network.Connection(
                    net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            new io.netty.channel.embedded.EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            ctx.runAfterDelay(
                2,
                () -> {
                  ctx.assertTrue(
                      WorldStore.get(server).list("targets").stream()
                          .noneMatch(
                              t ->
                                  ((CompoundTag) t)
                                      .getStringOr("id", "")
                                      .equals(player.getUUID().toString())),
                      "Arrival correction must run after native login registration and consume its"
                          + " pending target");
                  ctx.assertTrue(
                      player.getInventory().countItem(Items.EMERALD) == 11,
                      "Real restart/rejoin must preserve player inventory");
                  ctx.assertTrue(
                      player.level() == authoritative,
                      "Real restart/rejoin must resolve authoritative arrival");
                  ctx.assertTrue(
                      Math.abs(player.getYRot() - 63) < .01
                          && Math.abs(player.getXRot() - 17) < .01,
                      "Online passenger facing must survive restart");
                  server.getPlayerList().remove(player);
                  try {
                    java.nio.file.Files.writeString(
                        java.nio.file.Path.of(System.getProperty("nullspace.crash.result")),
                        "passed");
                  } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                  }
                  ctx.succeed();
                });
          });
    }
  }

  private static void afterEntitiesLoaded(
      GameTestHelper ctx,
      net.minecraft.server.level.ServerLevel planet,
      net.minecraft.server.level.ServerLevel space,
      BlockPos base,
      Runnable action) {
    var ground = new HullScan.Bounds(base.above(), base.above().offset(31, 31, 31));
    var orbit = new HullScan.Bounds(new BlockPos(0, 96, 0), new BlockPos(31, 127, 31));
    int[] ticks = {0};
    boolean[] started = {false};
    ctx.onEachTick(
        () -> {
          if (started[0] || ++ticks[0] < 20) return;
          if (!HullEntities.loaded(planet, ground) || !HullEntities.loaded(space, orbit)) return;
          started[0] = true;
          action.run();
        });
  }
}
