package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.computer.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;

public final class ComputerIntegrationGameTest {
  private static CompoundTag disk(VirtualComputer computer) {
    var data = new CompoundTag();
    data.putString("id", UUID.randomUUID().toString());
    data.putString("owner", new UUID(0, 0).toString());
    data.put("image", ComputerImageCodec.encode(computer.save()));
    return data;
  }

  private static VirtualComputer restore(CompoundTag record) {
    var computer = new VirtualComputer((n, a) -> 0);
    computer.restore(ComputerImageCodec.decode(record.get("image")));
    return computer;
  }

  @GameTest
  public void guestEditsRequestAnOrdinarySaveWithoutAnExplicitCheckpoint(GameTestHelper ctx)
      throws Exception {
    var level = ctx.getLevel();
    var server = level.getServer();
    var store = ShipStore.get(server);
    var pos = ctx.absolutePos(new BlockPos(94000, 80, 94000));
    level.setBlock(pos, ShipBlocks.TERMINAL.defaultBlockState(), 18);
    var player = ctx.makeMockServerPlayerInLevel();
    String key = level.dimension().identifier() + "/" + pos.asLong();
    try {
      player.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
      ComputerService.open(player, pos);
      ComputerService.flush(server);
      store.save();
      var session = ComputerService.session(player);
      ComputerService.input(
          player, new ComputerService.Input(session, "echo durable_edit > ordinary\r", false));
      ComputerService.flush(server);
      store.saveIfRequested(20);
      var durable =
          ShipStore.read(store.directory.resolve("ships.nbt"))
              .getCompoundOrEmpty("standaloneComputers")
              .getCompoundOrEmpty(key);
      ctx.assertTrue(
          restore(durable)
              .files
              .files()
              .getOrDefault("/home/crew/ordinary", "")
              .equals("durable_edit\n"),
          "A guest edit schedules its own ordinary save rather than waiting for an unrelated world"
              + " mutation");
    } finally {
      store.section("standaloneComputers").remove(key);
      ComputerService.replaced(server, ShipRepository.Mutation.RECOVERY);
      server.getPlayerList().remove(player);
    }
    ctx.succeed();
  }

  @GameTest
  public void corruptDisksAndMetadataRemainReadOnlyThroughOpenFlushAndRehydration(
      GameTestHelper ctx) throws Exception {
    var level = ctx.getLevel();
    var server = level.getServer();
    var store = ShipStore.get(server);
    var player = ctx.makeMockServerPlayerInLevel();
    var keys = new ArrayList<String>();
    try {
      for (int kind = 0; kind < 5; kind++) {
        var pos = ctx.absolutePos(new BlockPos(85000 + kind * 48, 80, 85000));
        level.setBlock(pos, ShipBlocks.TERMINAL.defaultBlockState(), 18);
        var computer = new VirtualComputer((n, a) -> 0);
        computer.files.write("/home/crew/precious", "\u661f\uD83D\uDE80".repeat(100));
        var data = disk(computer);
        switch (kind) {
          case 0 -> data.getCompoundOrEmpty("image").putByteArray("utf8", new byte[] {1, 2});
          case 1 -> data.getCompoundOrEmpty("image").putInt("version", 999);
          case 2 -> data.put("image", ComputerImageCodec.encode("{invalid guest json"));
          case 3 -> data.putString("owner", "malformed-owner");
          default -> {} // A healthy disk, for comparison.
        }
        var original = data.copy();
        String key = level.dimension().identifier() + "/" + pos.asLong();
        keys.add(key);
        store.section("standaloneComputers").put(key, data);
        ComputerService.replaced(server, ShipRepository.Mutation.RECOVERY);
        player.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        ComputerService.open(player, pos);
        var session = ComputerService.session(player);
        ctx.assertTrue(!session.equals(new UUID(0, 0)), "Saved computer opens with a real session");
        ComputerService.input(
            player, new ComputerService.Input(session, "echo changed > precious\r", false));
        ComputerService.flush(server);
        ComputerService.input(player, new ComputerService.Input(session, "", true));
        store.save();
        var durable =
            ShipStore.read(store.directory.resolve("ships.nbt"))
                .getCompoundOrEmpty("standaloneComputers")
                .getCompoundOrEmpty(key);
        if (kind < 4)
          ctx.assertTrue(
              original.equals(durable),
              "Corrupt image/metadata is preserved byte-for-byte through use and save");
        else
          ctx.assertTrue(
              durable.get("image") instanceof CompoundTag
                  && restore(durable).files.read("/home/crew/precious").equals("changed\n"),
              "A healthy disk stays writable through the real service");
        store.replace(store.snapshot(), ShipRepository.Mutation.RECOVERY);
        ComputerService.open(player, pos);
        ComputerService.flush(server);
        if (kind < 4)
          ctx.assertTrue(
              original.equals(store.section("standaloneComputers").getCompoundOrEmpty(key)),
              "Rehydration cannot replace a damaged disk with a fresh one");
        if (kind == 0) {
          var vessel = new CompoundTag();
          vessel.putString("id", UUID.randomUUID().toString());
          vessel.putString("dimension", level.dimension().identifier().toString());
          vessel.putLong("base", pos.asLong());
          vessel.putLong("controller", pos.asLong());
          vessel.putInt("width", 1);
          vessel.putInt("depth", 1);
          vessel.putInt("height", 1);
          store.ships().add(vessel);
          try {
            ComputerService.flush(server);
            ctx.assertTrue(
                !store.section("standaloneComputers").contains(key)
                    && original.equals(
                        vessel.getCompoundOrEmpty("computers").getCompoundOrEmpty("0")),
                "Adopting a damaged standalone computer into a ship preserves its original record");
          } finally {
            store.ships().remove(vessel);
          }
        }
      }
    } finally {
      for (String key : keys) store.section("standaloneComputers").remove(key);
      ComputerService.replaced(server, ShipRepository.Mutation.RECOVERY);
      server.getPlayerList().remove(player);
    }
    ctx.succeed();
  }

  @GameTest
  public void actualRecoveryRebindsLiveComputersPowerAirAndDoorQueues(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var server = level.getServer();
    var base = ctx.absolutePos(new BlockPos(86000, 80, 86000));
    var helm = ShipTestFixture.build(level, base);
    level.setBlock(base.offset(1, 3, 6), ShipBlocks.GENERATOR.defaultBlockState(), 18);
    level.setBlock(base.offset(2, 3, 6), ShipBlocks.BATTERY.defaultBlockState(), 18);
    level.setBlock(base.offset(4, 3, 6), ShipBlocks.COMPUTER.defaultBlockState(), 18);
    var terminal = base.offset(3, 3, 6);
    level.setBlock(terminal, ShipBlocks.TERMINAL.defaultBlockState(), 18);
    var ship = new CompoundTag();
    String id = UUID.randomUUID().toString();
    ship.putString("id", id);
    ship.putString("dimension", level.dimension().identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", helm.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    var store = ShipStore.get(server);
    store.ships().add(ship);
    ShipSystems.upgrade(level, ship);
    var player = ctx.makeMockServerPlayerInLevel();
    try {
      player.teleportTo(terminal.getX() + .5, terminal.getY(), terminal.getZ() + .5);
      ComputerService.open(player, terminal);
      var oldSession = ComputerService.session(player);
      ComputerService.input(
          player, new ComputerService.Input(oldSession, "echo before > log\r", false));
      ComputerService.flush(server);
      var devices = ship.getCompoundOrEmpty("systems").getListOrEmpty("devices");
      var battery =
          devices.stream()
              .map(t -> (CompoundTag) t)
              .filter(d -> d.getStringOr("type", "").equals("nullspace:battery"))
              .findFirst()
              .orElseThrow();
      battery.putInt("energy", 777);
      var checkpoint = store.snapshot();
      var expected = ship.getCompoundOrEmpty("systems").copy();
      ComputerService.input(
          player, new ComputerService.Input(oldSession, "echo after > log\r", false));
      ComputerService.flush(server);
      ShipSystems.automation(level, ship, terminal, "doors all open");
      battery.putInt("energy", 1);
      store.replace(checkpoint, ShipRepository.Mutation.RECOVERY);
      var recovered = store.repository().find(id);
      ctx.assertTrue(
          recovered != ship && ComputerService.session(player).equals(new UUID(0, 0)),
          "Actual root recovery revokes old computer sessions");
      ShipSystems.connected(level, recovered);
      ShipSystems.pressure(level, recovered, 4, 2, 3);
      ShipSystems.power(level, recovered, PowerBus.System.CONTROLS);
      ctx.assertTrue(
          expected.equals(recovered.getCompoundOrEmpty("systems")),
          "Rebound air and power retain exact device IDs, energy and air");
      ComputerService.input(
          player, new ComputerService.Input(oldSession, "echo stale > log\r", false));
      ComputerService.open(player, terminal);
      ComputerService.flush(server);
      var local = terminal.subtract(base.above()).asLong();
      var restored =
          restore(
              recovered.getCompoundOrEmpty("computers").getCompoundOrEmpty(Long.toString(local)));
      ctx.assertTrue(
          restored.files.read("/home/crew/log").equals("before\n"),
          "Rehydrated computer uses recovered disk, not the old live machine");
      ShipSystems.tick(level, recovered);
      ctx.assertTrue(
          !level.getBlockState(base.offset(3, 3, 0)).getValue(DoorBlock.OPEN),
          "Pre-recovery queued door work cannot run against recovered state");
      ctx.assertTrue(
          ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
                  .map(t -> (CompoundTag) t)
                  .filter(d -> d.getStringOr("type", "").equals("nullspace:battery"))
                  .findFirst()
                  .orElseThrow()
                  .getIntOr("energy", 0)
              == 1,
          "Recovered power simulation cannot mutate stale borrowed records");
    } finally {
      server.getPlayerList().remove(player);
    }
    ctx.succeed();
  }

  @GameTest(maxTicks = 120)
  public void manyComputersShareGlobalBudgetsWithoutStarvation(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var server = level.getServer();
    var base = ctx.absolutePos(new BlockPos(87000, 80, 87000));
    var helm = ShipTestFixture.build(level, base);
    level.setBlock(base.offset(1, 3, 6), ShipBlocks.GENERATOR.defaultBlockState(), 18);
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", level.dimension().identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", helm.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    var map = new CompoundTag();
    for (int i = 0; i < 20; i++) {
      var terminal = base.offset(1 + i % 5, 3, 1 + i / 5);
      level.setBlock(terminal, ShipBlocks.TERMINAL.defaultBlockState(), 18);
      var computer = new VirtualComputer((n, a) -> 0);
      computer.files.write("/home/crew/cpu.py", "x = 0\nwhile True:\n    x = x + 1\n");
      computer.files.write(
          "/home/crew/io.py", "n = 0\nwhile True:\n    ship.pressure(4,2,3)\n    n = n + 1\n");
      computer.run("/home/crew/cpu.py", ScriptLanguage.Syntax.PYTHON, true);
      computer.run("/home/crew/io.py", ScriptLanguage.Syntax.PYTHON, true);
      map.put(Long.toString(terminal.subtract(base.above()).asLong()), disk(computer));
    }
    for (int x = base.getX() >> 4; x <= (base.getX() + 7) >> 4; x++)
      for (int z = base.getZ() >> 4; z <= (base.getZ() + 7) >> 4; z++)
        level.setChunkForced(x, z, true);
    ship.put("computers", map);
    ShipStore.get(server).ships().add(ship);
    ShipSystems.upgrade(level, ship);
    ComputerService.replaced(server, ShipRepository.Mutation.RECOVERY);
    int[] maxima = new int[2];
    ctx.onEachTick(
        () -> {
          var usage = ComputerService.usage(server);
          ctx.assertTrue(
              usage.instructions() <= 4096 && usage.deviceCalls() <= 64 && usage.inspections() <= 2,
              "One global CPU and device budget bounds all computers");
          maxima[0] = Math.max(maxima[0], usage.instructions());
          maxima[1] = Math.max(maxima[1], usage.deviceCalls());
        });
    ctx.runAfterDelay(
        60,
        () -> {
          ComputerService.flush(server);
          var live = ShipStore.get(server).repository().find(ship.getStringOr("id", ""));
          for (var key : map.keySet()) {
            var restored = restore(live.getCompoundOrEmpty("computers").getCompoundOrEmpty(key));
            ctx.assertTrue(
                restored.processes.values().stream()
                    .anyMatch(
                        p -> ScriptLanguage.number(p.job().variables.getOrDefault("x", 0)) > 0),
                "Every computer gets CPU time");
            ctx.assertTrue(
                restored.processes.values().stream()
                    .anyMatch(
                        p -> ScriptLanguage.number(p.job().variables.getOrDefault("n", 0)) > 0),
                "Every computer eventually gets device time: " + restored.save());
          }
          ctx.assertTrue(
              maxima[0] > 0 && maxima[1] == 64,
              "The test must actually saturate the server device budget");
          ShipStore.get(server).ships().remove(live);
          ComputerService.replaced(server, ShipRepository.Mutation.RECOVERY);
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:cold_computer", maxTicks = 100)
  public void detachedComputerComputesWhileColdThenResumesItsWaitingWorldAction(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var base = new BlockPos(7400000, 100, 7400000);
    var terminal = base.offset(3, 3, 6);
    level.setBlock(terminal, ShipBlocks.TERMINAL.defaultBlockState(), 18);
    var missing = base.offset(31, 0, 31);
    ctx.assertTrue(!level.hasChunkAt(missing), "Test starts with a genuinely cold hull corner");
    var ship = new CompoundTag();
    String id = UUID.randomUUID().toString();
    ship.putString("id", id);
    ship.putString("dimension", TravelService.SPACE.identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", base.offset(6, 3, 3).asLong());
    ship.putInt("width", 32);
    ship.putInt("depth", 32);
    var computer = new VirtualComputer((n, a) -> 0);
    computer.files.write(
        "/home/crew/cold.py",
        "x = 41\nx = x + 1\nship.power('engines',0)\nx = x + 1\nwhile True:\n    sleep(1)\n");
    computer.run("/home/crew/cold.py", ScriptLanguage.Syntax.PYTHON, true);
    var map = new CompoundTag();
    String key = Long.toString(terminal.subtract(base.above()).asLong());
    map.put(key, disk(computer));
    ship.put("computers", map);
    ShipStore.get(server).ships().add(ship);
    ComputerService.replaced(server, ShipRepository.Mutation.RECOVERY);
    ctx.runAfterDelay(
        12,
        () -> {
          ComputerService.flush(server);
          var live = ShipStore.get(server).repository().find(id);
          var waiting = restore(live.getCompoundOrEmpty("computers").getCompoundOrEmpty(key));
          var job = waiting.processes.values().iterator().next().job();
          ctx.assertTrue(
              ScriptLanguage.number(job.variables.get("x")) == 42 && job.pc == 2,
              "Detached pure computation runs but the world instruction cannot advance while cold");
          ctx.assertTrue(
              !level.hasChunkAt(missing), "Computer scheduling and flushing do not load the hull");
          ShipTestFixture.build(level, base);
          level.setBlock(terminal, ShipBlocks.TERMINAL.defaultBlockState(), 18);
          level.setBlock(base.offset(1, 3, 6), ShipBlocks.GENERATOR.defaultBlockState(), 18);
          for (int x = base.getX() >> 4; x <= missing.getX() >> 4; x++)
            for (int z = base.getZ() >> 4; z <= missing.getZ() >> 4; z++) {
              level.getChunk(x, z);
              level.setChunkForced(x, z, true);
            }
          ShipSystems.upgrade(level, live);
          ctx.runAfterDelay(
              36,
              () -> {
                ComputerService.flush(server);
                var resumed =
                    restore(
                        ShipStore.get(server)
                            .repository()
                            .find(id)
                            .getCompoundOrEmpty("computers")
                            .getCompoundOrEmpty(key));
                ctx.assertTrue(
                    ScriptLanguage.number(
                            resumed.processes.values().iterator().next().job().variables.get("x"))
                        == 43,
                    "Loading the hull resumes the waiting instruction and its continuation exactly"
                        + " once: "
                        + resumed.save());
                ctx.assertTrue(
                    ShipStore.get(server)
                            .repository()
                            .find(id)
                            .getCompoundOrEmpty("systems")
                            .getIntArray("requests")
                            .orElseThrow()[PowerBus.System.ENGINES.ordinal()]
                        == 0,
                    "The deferred action reaches the real ship systems API");
                ShipStore.get(server).ships().remove(ShipStore.get(server).repository().find(id));
                ComputerService.replaced(server, ShipRepository.Mutation.RECOVERY);
                ctx.succeed();
              });
        });
  }
}
