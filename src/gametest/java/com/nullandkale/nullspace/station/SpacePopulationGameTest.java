package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;

public final class SpacePopulationGameTest {
  @GameTest(environment = "nullspace:space_population", maxTicks = 900)
  public void enteringSpacePopulatesDockablePlacesOnceAndPreservesChangesAfterReload(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var space = server.getLevel(TravelService.SPACE);
    // P9: a zone the chart gives a station or derelict.
    String address =
        GalaxyPlan.population(StarSystems.galaxy(server)).keySet().stream()
            .filter(z -> !z.equals(LocalSpaces.HOME))
            .findFirst()
            .orElseThrow();
    var source =
        com.nullandkale.nullspace.ship.StationGameTest.fixture(
            space, new BlockPos(1300000, 96, 1300000), true);
    source.putString("localSpace", address);
    var cookie =
        net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), "orbital-explorer"), false);
    var player =
        new net.minecraft.server.level.ServerPlayer(
            server, ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
    var connection =
        new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
    new io.netty.channel.embedded.EmbeddedChannel(connection);
    server.getPlayerList().placeNewPlayer(connection, player, cookie);
    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
    var at = com.nullandkale.nullspace.ship.StationGameTest.boardingPoint(space, source);
    player.teleportTo(space, at.x, at.y, at.z, Set.of(), 0, 0, true);
    var sites = SpacePopulation.plan(server, address);
    Runnable[] poll = {null};
    poll[0] =
        () -> {
          var store = ShipStore.get(server);
          var stations = sites.stream().map(s -> store.repository().find(s.id())).toList();
          if (stations.stream().anyMatch(s -> s != null && !StationService.ready(s)))
            ctx.assertTrue(
                !TravelService.active(server),
                "Background population never installs a global travel/boarding barrier");
          if (stations.stream().anyMatch(s -> s == null || !StationService.ready(s))) {
            ctx.runAfterDelay(5, () -> poll[0].run());
            return;
          }
          for (var station : stations) {
            ctx.assertTrue(
                station.getBooleanOr("worldGenerated", false),
                "Entry, not a controller action, created each place");
            ctx.assertTrue(
                !DockingService.ports(space, station).isEmpty(),
                "Every automatically built station or salvage ship has a usable berth");
            ctx.assertTrue(
                !StationGraph.inspect(space, station).nodes().isEmpty(), "Doors form a room graph");
            ctx.assertTrue(
                ShipStore.pos(station, "base").getX() >= 6000 * TravelService.SPACE_SPACING,
                "Ambient storage does not consume the next player launch slot");
          }
          ctx.assertTrue(
              DockingService.destinations(player, source, false).stream()
                      .filter(
                          d ->
                              stations.stream()
                                  .anyMatch(s -> s.getStringOr("id", "").equals(d.vessel())))
                      .count()
                  == stations.stream().mapToInt(s -> DockingService.ports(space, s).size()).sum(),
              "Navigation discovers all local berths without generating content");
          var station = stations.getFirst();
          var core = ShipStore.pos(station, "controller");
          space.setBlock(core, Blocks.AIR.defaultBlockState(), 18);
          var generators =
              ShipSystems.connected(space, station).stream()
                  .filter(p -> space.getBlockState(p).is(ShipBlocks.GENERATOR))
                  .toList();
          for (var generator : generators) {
            if (space.getBlockEntity(generator) instanceof net.minecraft.world.Container inventory)
              inventory.clearContent();
          }
          var expectedIds =
              stations.stream()
                  .map(s -> s.getStringOr("id", ""))
                  .collect(java.util.stream.Collectors.toSet());
          store.save();
          ShipStore.forget(server);
          SpacePopulation.discover(server, address);
          ctx.runAfterDelay(
              25,
              () -> {
                var loaded = ShipStore.get(server);
                var actualIds =
                    loaded.ships().stream()
                        .map(t -> (CompoundTag) t)
                        .filter(
                            s ->
                                s.getBooleanOr("worldGenerated", false)
                                    && LocalSpaces.address(s).equals(address))
                        .map(s -> s.getStringOr("id", ""))
                        .toList();
                ctx.assertTrue(
                    actualIds.size() == expectedIds.size()
                        && new HashSet<>(actualIds).equals(expectedIds),
                    "Reload preserves the exact region inventory without duplicates; other regions"
                        + " may populate independently");
                ctx.assertTrue(
                    space.getBlockState(core).isAir(),
                    "Revisiting does not rebuild a broken controller");
                for (var generator : generators)
                  ctx.assertTrue(
                      !(space.getBlockEntity(generator)
                              instanceof net.minecraft.world.Container inventory)
                          || inventory.isEmpty(),
                      "Revisiting never refills fuel");
                // Intentionally remove a registered place. Its saved inventory is a tombstone, not
                // a respawn timer.
                loaded
                    .ships()
                    .removeIf(
                        t -> ((CompoundTag) t).getStringOr("id", "").equals(sites.getLast().id()));
                loaded.save();
                ctx.runAfterDelay(
                    25,
                    () -> {
                      ctx.assertTrue(
                          ShipStore.get(server).repository().find(sites.getLast().id()) == null,
                          "A dismantled place stays removed");
                      server.getPlayerList().remove(player);
                      cleanup(space, source);
                      for (var s : stations) cleanup(space, s);
                      ShipStore.get(server).section("spacePopulation").remove(address);
                      ShipStore.get(server).save();
                      ctx.succeed();
                    });
              });
        };
    ctx.runAfterDelay(5, poll[0]);
  }

  private static void cleanup(net.minecraft.server.level.ServerLevel level, CompoundTag station) {
    var store = ShipStore.get(level.getServer());
    store
        .ships()
        .removeIf(
            t -> ((CompoundTag) t).getStringOr("id", "").equals(station.getStringOr("id", "")));
    for (var at :
        BlockPos.betweenClosed(ShipStore.bounds(station).min(), ShipStore.bounds(station).max()))
      if (!level.getBlockState(at).isAir()) {
        level.removeBlockEntity(at);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
      }
  }
}
