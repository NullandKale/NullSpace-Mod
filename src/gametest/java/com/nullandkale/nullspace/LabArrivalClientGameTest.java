package com.nullandkale.nullspace;

import com.nullandkale.nullspace.client.TerminalScreen;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Reproduce first-time lab placement of a player whose saved login dimension is space. */
public final class LabArrivalClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    String previous = System.getProperty("nullspace.playtest");
    System.setProperty("nullspace.playtest", "true");
    try {
      TestWorldSave save;
      UUID playerId;
      try (var world =
          context
              .worldBuilder()
              .adjustSettings(settings -> settings.setName("NullSpace-Lab"))
              .create()) {
        save = world.getWorldSave();
        if (!save.getSaveDirectory().getFileName().toString().equals("NullSpace-Lab"))
          throw new AssertionError("Regression must use the actual lab login handler");
        playerId =
            world
                .getServer()
                .computeOnServer(server -> world.getConnection().getServerPlayer().getUUID());
        context.waitFor(
            c -> Files.exists(save.getSaveDirectory().resolve("nullspace-lab-boarded-" + playerId)),
            6000);
        launch(context, world, 1);
        world.getServer().runOnServer(LabArrivalClientGameTest::checkTracking);
      }
      // Only this disposable graphical-test save is eligible for marker removal.
      Path root = save.getSaveDirectory().toAbsolutePath().normalize();
      if (!root.startsWith(Path.of(".").toAbsolutePath().normalize().resolve("saves")))
        throw new AssertionError("Unexpected test save location: " + root);
      Files.delete(root.resolve("nullspace-lab-boarded-" + playerId));
      var joinedInSpace = new java.util.concurrent.atomic.AtomicBoolean();
      net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
          (handler, sender, server) -> {
            if (handler.player.getUUID().equals(playerId))
              joinedInSpace.set(handler.player.level().dimension().equals(TravelService.SPACE));
          });
      try (var world = save.open()) {
        context.waitFor(
            c ->
                c.level != null
                    && c.level.dimension().equals(Level.OVERWORLD)
                    && Files.exists(root.resolve("nullspace-lab-boarded-" + playerId)),
            6000);
        context.waitTicks(20);
        world
            .getServer()
            .runOnServer(
                server -> {
                  var player = world.getConnection().getServerPlayer();
                  if (!player.getUUID().equals(playerId))
                    throw new AssertionError("Must reconnect the same player saved in space");
                  if (!joinedInSpace.get())
                    throw new AssertionError(
                        "Regression must enter JOIN in the saved space dimension before the"
                            + " deferred lab teleport");
                  checkTracking(server);
                });
        // Strict passenger validation must succeed after the cross-dimension lab arrival.
        launch(context, world, 2);
        world.getServer().runOnServer(LabArrivalClientGameTest::checkTracking);
      }
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    } finally {
      if (previous == null) System.clearProperty("nullspace.playtest");
      else System.setProperty("nullspace.playtest", previous);
    }
    ClientTestEvidence.passed(getClass());
  }

  private static void launch(ClientGameTestContext context, TestSingleplayerContext world, int plot)
      throws java.io.IOException {
    world
        .getServer()
        .runOnServer(
            server -> {
              var gallery = ShipGallery.ensure(server);
              var base = gallery.base(plot);
              var player = world.getConnection().getServerPlayer();
              player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
              player.teleportTo(
                  server.overworld(),
                  base.getX() + 6.5,
                  base.getY() + 2,
                  base.getZ() + 7.5,
                  Set.of(),
                  0,
                  0,
                  true);
              ShipService.open(
                  player, gallery.controller(plot), "Inspect lab arrival regression ship");
            });
    context.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 6000);
    context.waitTicks(20);
    send(context, ComputerTerminal.Action.INSPECT);
    context.waitFor(
        c -> c.gui.screen() instanceof TerminalScreen t && t.status().startsWith("Ship ready"),
        6000);
    context.waitTicks(20);
    send(context, ComputerTerminal.Action.LAUNCH);
    context.waitFor(
        c ->
            c.level != null
                && c.level.dimension().equals(TravelService.SPACE)
                && c.gui.screen() == null,
        6000);
  }

  private static void send(ClientGameTestContext context, ComputerTerminal.Action action) {
    ShipControlTestUi.action(
        context, com.nullandkale.nullspace.navigation.ShipControl.Action.valueOf(action.name()));
  }

  private static void checkTracking(MinecraftServer server) {
    try {
      var field =
          net.minecraft.server.level.DistanceManager.class.getDeclaredField("playersPerChunk");
      field.setAccessible(true);
      for (var player : server.getPlayerList().getPlayers()) {
        int copies = 0;
        for (var world : server.getAllLevels())
          if (world.getEntity(player.getUUID()) == player) copies++;
        if (copies != 1)
          throw new AssertionError(
              "Player must be registered in exactly one native world, got " + copies);
        var manager = player.level().getChunkSource().chunkMap.getDistanceManager();
        var map = (Map<?, ?>) field.get(manager);
        var entry = (Set<?>) map.get(player.getLastSectionPos().chunk().pack());
        if (entry == null || !entry.contains(player))
          throw new AssertionError("Native distance manager lost the player");
        var pos = player.blockPosition();
        if (ShipEntities.inBounds(
                player.level(), new ShipScan.Bounds(pos.offset(-1, -1, -1), pos.offset(1, 2, 1)))
            .stream()
            .noneMatch(entity -> entity == player))
          throw new AssertionError("Passenger scan must find the player in their actual world");
      }
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }
}
