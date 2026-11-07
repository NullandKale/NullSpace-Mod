package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Real expansion/build buttons, full-size construction, remote rendering and far-side boarding. */
public final class StationExpansionClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = c.worldBuilder().create()) {
      var station = new CompoundTag[1];
      var ship = new CompoundTag[1];
      var doors = new BlockPos[2];
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.CREATIVE);
                station[0] =
                    StationService.queue(
                        server,
                        UUID.randomUUID().toString(),
                        "Full-size Harbor",
                        "sol_terra",
                        new Vec3(-3000, 0, -2800),
                        p.getUUID().toString(),
                        StationBlueprint.foundation(),
                        true);
                station[0].putBoolean("buildPaid", true);
                p.getInventory().add(new ItemStack(ModBlocks.STATION_MODULE, 28));
              });
      world.getServer().waitFor(server -> StationService.ready(station[0]), 600);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var core = WorldStore.pos(station[0], "controller");
                p.teleportTo(
                    server.getLevel(TravelService.SPACE),
                    core.getX() + .5,
                    core.getY(),
                    core.getZ() + 1.5,
                    Set.of(),
                    180,
                    0,
                    true);
                StationService.open(p, core, "");
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
      c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 50, 4));
      world.getServer().waitFor(server -> station[0].getIntOr("width", 32) == 128, 200);
      c.waitFor(
          mc -> mc.gui.screen() instanceof TerminalScreen t && t.buffer().row(4).contains("1/16"),
          200);
      c.takeScreenshot("nullspace-expanded-station-core");
      c.runOnClient(mc -> ShipControlTestUi.click((TerminalScreen) mc.gui.screen(), 43, 12));
      world.getServer().waitFor(server -> station[0].contains("stationBuild"), 200);
      c.runOnClient(mc -> mc.gui.screen().onClose());
      world.getServer().waitFor(server -> StationService.ready(station[0]), 1600);
      world
          .getServer()
          .runOnServer(
              server -> {
                if (station[0].getCompoundOrEmpty("roomPieces").size() != 28)
                  throw new AssertionError("All 28 room kits must be recorded");
                var p = world.getConnection().getServerPlayer();
                var base = WorldStore.pos(station[0], "base");
                p.setGameMode(GameType.SPECTATOR);
                p.teleportTo(
                    p.level(),
                    base.getX() + 64,
                    base.getY() + 92,
                    base.getZ() - 36,
                    Set.of(),
                    0,
                    0,
                    true);
              });
      c.runOnClient(
          mc -> {
            mc.options.renderDistance().set(12);
            mc.options.broadcastOptions();
          });
      c.waitFor(
          mc ->
              mc.player != null
                  && mc.player.isSpectator()
                  && mc.player.distanceToSqr(64, 188, -36) < 1,
          200);
      var base = world.getServer().computeOnServer(server -> WorldStore.pos(station[0], "base"));
      c.getInput().lookAt(base.offset(64, 4, 64));

      c.waitFor(
          mc ->
              mc.level != null
                  && java.util.stream.Stream.of(
                          base.offset(8, 4, 8),
                          base.offset(120, 4, 8),
                          base.offset(8, 4, 120),
                          base.offset(120, 4, 120))
                      .allMatch(
                          at -> {
                            long section =
                                net.minecraft.core.SectionPos.asLong(
                                    at.getX() >> 4, at.getY() >> 4, at.getZ() >> 4);
                            return mc.levelRenderer.visibleSections().stream()
                                .anyMatch(
                                    rendered ->
                                        rendered.getSectionNode() == section
                                            && rendered.getSectionMesh().hasRenderableLayers());
                          }),
          600);
      c.waitTicks(30);
      c.takeScreenshot("nullspace-full-station-ring");
      world
          .getServer()
          .waitFor(
              server ->
                  ShipSystems.breathable(
                      server.getLevel(TravelService.SPACE), station[0], base.offset(126, 4, 104)),
              2400);

      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.getLevel(TravelService.SPACE);
                ship[0] = StationGameTest.fixture(level, new BlockPos(4096, 96, 0), true);
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.CREATIVE);
                station[0]
                    .getListOrEmpty("crew")
                    .removeIf(
                        tag ->
                            ((CompoundTag) tag)
                                .getStringOr("id", "")
                                .equals(p.getUUID().toString()));
                var member = new CompoundTag();
                member.putString("id", p.getUUID().toString());
                ship[0].getListOrEmpty("crew").add(member);
                p.teleportTo(level, 4130, 112, 16, Set.of(), 90, 0, true);
                doors[0] = DockingService.firstPort(level, ship[0]).controller();
                doors[1] =
                    DockingService.ports(level, station[0]).stream()
                        .filter(
                            port ->
                                port.controller().getX() == base.getX() + 127
                                    && port.controller().getZ() == base.getZ() + 104)
                        .findFirst()
                        .orElseThrow()
                        .controller();
              });
      c.waitFor(
          mc ->
              mc.player != null
                  && mc.player.distanceToSqr(4130, 112, 16) < 1
                  && mc.level.hasChunkAt(new BlockPos(4096, 96, 0)),
          200);
      c.getInput().lookAt(new BlockPos(3960, 100, 64));
      c.waitTicks(50);
      c.takeScreenshot("nullspace-full-station-remote");
      world
          .getServer()
          .waitFor(
              server ->
                  ShipSystems.breathable(
                      server.getLevel(TravelService.SPACE),
                      station[0],
                      doors[1].above().west().above()),
              2400);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                var at = StationGameTest.boardingPoint(p.level(), ship[0]);
                p.setGameMode(GameType.SURVIVAL);
                p.teleportTo(p.level(), at.x, at.y, at.z, Set.of(), 0, 0, true);
              });
      c.waitTicks(10);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                ShipService.open(
                    p, WorldStore.pos(ship[0], "controller"), "Choose the far-side station port");
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen t && t.shipController(), 200);
      ShipControlTestUi.destination(c, world, "127,2,104", "Ports");
      c.takeScreenshot("nullspace-far-station-port-discovery");
      ShipControlTestUi.action(c, ShipControl.Action.DOCK);
      world
          .getServer()
          .waitFor(
              server ->
                  ship[0].getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"),
              1800);
      StationClientGameTest.walkThrough(c, world, ship[0], doors[0]);
      c.waitFor(
          mc ->
              mc.player != null
                  && mc.level != null
                  && mc.player.getX() > base.getX() + 96
                  && mc.player.getX() < base.getX() + 128
                  && mc.level.hasChunkAt(base.offset(127, 3, 104)),
          200);
      c.waitTicks(12);
      c.takeScreenshot("nullspace-full-station-far-berth");
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                if (!WorldStore.bounds(station[0]).contains(p.blockPosition())
                    || p.getX() < base.getX() + 96)
                  throw new AssertionError("Boarded far side of station");
              });
      StationClientGameTest.walkThrough(c, world, station[0], doors[1]);
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                ShipService.open(p, WorldStore.pos(ship[0], "controller"), "");
              });
      c.waitFor(mc -> mc.gui.screen() instanceof TerminalScreen, 200);
      ShipControlTestUi.action(c, ShipControl.Action.UNDOCK);
      world.getServer().waitFor(server -> !ship[0].contains("dock"), 1200);
      world.getServer().runOnServer(server -> WorldStore.get(server).save());
      ClientTestEvidence.passed(getClass());
    }
  }
}
