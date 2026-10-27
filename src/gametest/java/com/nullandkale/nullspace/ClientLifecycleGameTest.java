package com.nullandkale.nullspace;

import static org.lwjgl.opengl.GL43C.*;

import com.nullandkale.nullspace.client.scene.SceneViewport;
import com.nullandkale.nullspace.client.scene.compute.*;
import com.nullandkale.nullspace.computer.*;
import com.nullandkale.nullspace.server.*;
import com.nullandkale.nullspace.ship.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;

/** Repeated real allocations/deletions and sequential integrated-server lifetimes. */
public final class ClientLifecycleGameTest implements FabricClientGameTest {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private record Handle(String kind, int id) {
    boolean live() {
      return switch (kind) {
        case "texture" -> glIsTexture(id);
        case "buffer" -> glIsBuffer(id);
        case "sampler" -> glIsSampler(id);
        default -> glIsQuery(id);
      };
    }
  }

  private static List<Handle> handles(SpaceComputeRenderer view) {
    var result = new ArrayList<Handle>();
    try {
      for (var f : SpaceComputeRenderer.class.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        var value = f.get(view);
        if (f.getType().getSimpleName().equals("Image") && value != null) {
          var id = value.getClass().getDeclaredMethod("id");
          id.setAccessible(true);
          result.add(new Handle("texture", (int) id.invoke(value)));
        } else if (Set.of("bodyBuffer", "nodeBuffer", "sceneBuffer", "computeSampler")
            .contains(f.getName())) {
          result.add(
              new Handle(f.getName().equals("computeSampler") ? "sampler" : "buffer", (int) value));
        } else if (f.getName().equals("queries")) {
          for (int[] row : (int[][]) value)
            for (int id : row) if (glIsQuery(id)) result.add(new Handle("query", id));
        }
      }
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
    return result;
  }

  @Override
  public void runTest(ClientGameTestContext context) {
    if (System.getProperty("nullspace.local.role") != null) return;
    var key = new ServerContext.Key<String>();
    var previous = new AtomicReference<MinecraftServer>();
    for (int worldNumber = 0; worldNumber < 3; worldNumber++) {
      final int number = worldNumber;
      try (var world = context.worldBuilder().create()) {
        world.getConnection().waitForChunksRender();
        world
            .getServer()
            .runOnServer(
                server -> {
                  check(
                      previous.get() != server,
                      "Each world must own a different integrated server");
                  if (previous.get() != null)
                    check(
                        !ServerContext.isActive(previous.get()),
                        "Stopped worlds cannot retain their service context");
                  check(
                      ServerContext.get(server).existing(key) == null,
                      "A new world cannot inherit another world's runtime state");
                  ServerContext.get(server).put(key, "world-" + number);
                  var store = ShipStore.get(server);
                  check(
                      store.repository().find("lifecycle-same-id") == null,
                      "Identical ship IDs cannot leak across worlds");
                  var ship = new CompoundTag();
                  ship.putString("id", "lifecycle-same-id");
                  ship.putString("dimension", "minecraft:overworld");
                  ship.putLong("base", new BlockPos(-300, 100, -300).asLong());
                  store.ships().add(ship);
                  store.document("lifecycle-marker", StringTag.valueOf("world-" + number));
                  var terminal = new BlockPos(310, 100, 310);
                  server
                      .overworld()
                      .setBlock(terminal, ShipBlocks.TERMINAL.defaultBlockState(), 18);
                  var player = server.getPlayerList().getPlayers().getFirst();
                  player.teleportTo(terminal.getX() + .5, terminal.getY(), terminal.getZ() + .5);
                  ComputerService.open(player, terminal);
                  var session = ComputerService.session(player);
                  check(
                      !session.equals(new UUID(0, 0)),
                      "Lifecycle test needs an actual active computer session");
                  ComputerService.input(
                      player,
                      new ComputerService.Input(
                          session, "echo shutdown_flush > lifecycle\r", false));
                  previous.set(server);
                });
        context.runOnClient(
            client -> {
              int baseline = ResourceGroup.liveEntries(), views = SpaceComputeRenderer.liveViews();
              var scene = new CompoundTag();
              scene.putBoolean("universe", true);
              var subjects = new ListTag();
              var subject = new CompoundTag();
              subject.putString("id", "lifecycle");
              subject.putIntArray("blocks", new int[32768]);
              subjects.add(subject);
              scene.put("subjects", subjects);
              scene.put(
                  "catalog",
                  com.nullandkale.nullspace.scene.SpaceDefinitionCodec.encode(
                      new com.nullandkale.nullspace.scene.SpaceDefinition(
                          "test:soak", 1, 42, 1, 0, List.of(), List.of(), List.of())));
              for (int cycle = 0; cycle < 12; cycle++) {
                var handles = new ArrayList<Handle>();
                var first = new SceneViewport(33 + cycle, 25 + cycle);
                var second = new SceneViewport(65 - cycle, 49 - cycle);
                try {
                  first.render(scene, 0, 0, 60 + cycle);
                  second.render(scene, 45, 10, 80);
                  handles.addAll(handles(first.compute()));
                  handles.addAll(handles(second.compute()));
                  check(
                      handles.stream().filter(h -> h.kind.equals("texture")).count() >= 20,
                      "Observe actual allocated texture handles");
                  for (var handle : handles)
                    check(
                        handle.live(),
                        "Observed GL resource must be live before closing: " + handle);
                } finally {
                  first.close();
                  second.close();
                  first.close();
                  second.close();
                }
                // RenderPearl may defer deletion until its queued command stream is drained.
                com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().submit();
                for (var handle : handles)
                  check(!handle.live(), "Closing views must delete actual GL resources: " + handle);
                check(
                    ResourceGroup.liveEntries() == baseline
                        && SpaceComputeRenderer.liveViews() == views,
                    "Repeated allocations return ownership counters to baseline");
              }
              check(glGetError() == GL_NO_ERROR, "GPU lifecycle soak leaves no OpenGL errors");
            });
      }
      check(
          !ServerContext.isActive(previous.get()), "Closing the world removes its server context");
      try {
        var saved =
            ShipStore.read(
                previous
                    .get()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("nullspace/ships.nbt"));
        check(
            saved.getStringOr("lifecycle-marker", "").equals("world-" + number),
            "Server stop flushes pending metadata");
        var map = saved.getCompoundOrEmpty("standaloneComputers");
        boolean found = false;
        for (String id : map.keySet()) {
          var computer = new VirtualComputer((n, a) -> 0);
          computer.restore(ComputerImageCodec.decode(map.getCompoundOrEmpty(id).get("image")));
          found |=
              computer
                  .files
                  .files()
                  .getOrDefault("/home/crew/lifecycle", "")
                  .equals("shutdown_flush\n");
        }
        check(
            found,
            "Closing the world persists the live computer disk without an explicit test flush");
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    }
    ClientTestEvidence.passed(getClass());
  }
}
