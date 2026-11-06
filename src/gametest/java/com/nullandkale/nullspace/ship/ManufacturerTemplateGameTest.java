package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.shipyard.*;
import com.nullandkale.nullspace.station.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;

public final class ManufacturerTemplateGameTest {
  @GameTest
  public void everyDeliverableShipUsesItsEnginesToDefineTheBow(GameTestHelper ctx) {
    for (var preset : ShipBlueprints.PRESETS) {
      var exhausts =
          ShipBlueprints.blocks(preset).values().stream()
              .filter(s -> s.is(ShipBlocks.ENGINE))
              .map(s -> s.getValue(HorizontalDirectionalBlock.FACING))
              .toList();
      ctx.assertTrue(
          !exhausts.isEmpty()
              && com.nullandkale.nullspace.navigation.ShipHeading.fromExhausts(exhausts)
                  == Direction.NORTH,
          preset.name() + " must fly toward its bow, regardless of its side-mounted controller");
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:manufacturer_save", maxTicks = 80)
  public void savingUsesAFrozenDocumentAndRetainsChangesMadeDuringTheWrite(GameTestHelper ctx)
      throws Exception {
    var store = ShipStore.get(ctx.getLevel().getServer());
    store.document("manufacturerSaveProbe", net.minecraft.nbt.IntTag.valueOf(1));
    var observer = ShipStore.writeObserver;
    boolean[] changed = {false};
    try {
      ShipStore.writeObserver =
          progress -> {
            if (!changed[0] && progress.file().getFileName().toString().equals("ships.nbt")) {
              changed[0] = true;
              store.document("manufacturerSaveProbe", net.minecraft.nbt.IntTag.valueOf(2));
            }
          };
      store.save();
    } finally {
      ShipStore.writeObserver = observer;
    }
    ctx.assertTrue(
        changed[0]
            && ShipStore.read(store.directory.resolve("ships.nbt"))
                    .getIntOr("manufacturerSaveProbe", -1)
                == 1,
        "The committed file is the complete original snapshot, not changing live metadata");
    ctx.runAfterDelay(
        25,
        () -> {
          try {
            ctx.assertTrue(
                ShipStore.read(store.directory.resolve("ships.nbt"))
                        .getIntOr("manufacturerSaveProbe", -1)
                    == 2,
                "A change during IO retains its request and reaches the next automatic save");
            store.document("manufacturerSaveProbe", null);
            ctx.succeed();
          } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
          }
        });
  }

  @GameTest(environment = "nullspace:manufacturer_templates", maxTicks = 300)
  public void manufacturerScoutsHaveDistinctHullProfilesAndReachableSealedRooms(GameTestHelper ctx)
      throws Exception {
    var level = ctx.getLevel();
    int index = 0;
    var footprints = new HashSet<Set<BlockPos>>();
    for (var preset : ShipBlueprints.PRESETS) {
      if (!ManufacturerShips.contains(preset.id()) || ManufacturerRoleShips.contains(preset.id()))
        continue;
      var base = new BlockPos(600000 + index++ * 4096, 80, 600000);
      ShipYard.buildPad(level, base, 32, 32, "Manufacturer test " + preset.id());
      var pad =
          new PadScanner.Pad(
              base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31);
      var origin = ShipBlueprints.origin(preset, pad, 32);
      var plan = ShipBlueprints.blocks(preset);
      var floor = new HashSet<BlockPos>();
      for (var e : plan.entrySet()) {
        level.setBlock(origin.offset(e.getKey()), e.getValue(), 18);
        if (e.getKey().getY() == 0) floor.add(e.getKey());
      }
      ctx.assertTrue(
          footprints.add(floor), "Manufacturer hulls are different geometry, not paint swaps");
      var scan = ShipScan.inspect(level, origin.offset(preset.helm()), pad, true);
      ctx.assertTrue(
          scan.blocks().size() == plan.size(), "Every pod and mounted part is face-connected");
      var ship = ShipTopology.resolve(level, origin.offset(preset.helm()));
      for (var at : scan.blocks()) FuelSystem.provision(level, at, 64);
      ShipSystems.upgrade(level, ship);
      ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[] {1, 4, 1, 3, 2, 0});
      ShipSystems.tick(level, ship);
      var port = DockingService.firstPort(level, ship);
      ctx.assertTrue(
          DockingService.ports(level, ship).size() == 1 && port.anchors().size() >= 2,
          "Scout has one usable powered dock");
      var rear =
          origin.offset(
              preset.id().equals("rivet_bolt") ? new BlockPos(8, 1, 17) : new BlockPos(9, 1, 18));
      ctx.assertTrue(
          scan.pressure().breathable(rear.above()), "Separate rear room has a sealed hull");
      ctx.assertTrue(
          reachable(level, port.anchors().getFirst(), rear),
          "Dock can reach rear workshop through real doorway");
      ctx.assertTrue(
          ShipSystems.breathable(level, ship, rear.above()), "Powered interior retains finite air");
      for (var room : ShipRooms.rooms(preset.id())) {
        ctx.assertTrue(
            scan.pressure().breathable(origin.offset(room.walk()).above())
                && reachable(level, port.anchors().getFirst(), origin.offset(room.walk())),
            preset.name() + " has a safe walking route to " + room.name());
        if (room.supplies() != null)
          ctx.assertTrue(
              level.getBlockEntity(origin.offset(room.supplies()))
                  instanceof net.minecraft.world.Container,
              "Resource nooks have actual storage");
      }
    }
    ctx.assertTrue(index == 3, "One review scout per manufacturer");
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:manufacturer_showcase", maxTicks = 300)
  public void compactShowcaseBuildsOnceAndConnectsBothBerthsToAllManufacturerRooms(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var space = server.getLevel(TravelService.SPACE);
    var source = StationGameTest.fixture(space, new BlockPos(950000, 96, 950000), true);
    source.putDouble("simX", 4000);
    source.putDouble("simZ", 4000);
    var p = ctx.makeMockServerPlayerInLevel();
    var boarding = StationGameTest.boardingPoint(space, source);
    p.teleportTo(space, boarding.x, boarding.y, boarding.z, Set.of(), 0, 0, true);
    var station = StationService.showcase(p);
    ctx.assertTrue(
        StationService.showcase(p) == station, "Repeated requests keep one station identity");
    ctx.onEachTick(
        () -> {
          if (!StationService.ready(station)) return;
          var base = ShipStore.pos(station, "base");
          var ports = DockingService.ports(space, station);
          ctx.assertTrue(ports.size() == 2, "Compact station has two opposite usable berths");
          var graph = StationGraph.inspect(space, station);
          ctx.assertTrue(
              graph.nodes().size() == 5,
              "Three room doors and two dock nodes come from physical blocks");
          for (var port : ports)
            for (var room : ManufacturerStation.ROOMS.entrySet()) {
              var at = base.offset(room.getValue());
              ctx.assertTrue(
                  reachable(space, port.anchors().getFirst(), at),
                  "Berth reaches " + room.getKey());
              ctx.assertTrue(
                  ShipSystems.breathable(space, station, at.above()),
                  "Room is sealed and supplied: " + room.getKey());
            }
          for (var at : ShipSystems.connected(space, station))
            ctx.assertTrue(
                ShipStore.bounds(station).contains(at), "Showcase fits one 32-block allocation");
          var edited = base.offset(6, 3, 15);
          space.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
          ctx.assertTrue(
              StationService.showcase(p) == station
                  && space.getBlockState(edited).is(Blocks.DIAMOND_BLOCK),
              "Revisiting never regenerates the showroom or erases edits");
          ctx.succeed();
        });
  }

  private static boolean reachable(
      net.minecraft.server.level.ServerLevel level, BlockPos from, BlockPos to) {
    var seen = new HashSet<BlockPos>();
    var queue = new ArrayDeque<BlockPos>();
    queue.add(from);
    while (!queue.isEmpty() && seen.size() < 4096) {
      var at = queue.removeFirst();
      if (!seen.add(at)) continue;
      if (at.equals(to)) return true;
      for (var d : Direction.Plane.HORIZONTAL) {
        var next = at.relative(d);
        if (!level.getBlockState(next.below()).isCollisionShapeFullBlock(level, next.below()))
          continue;
        var low = level.getBlockState(next);
        var high = level.getBlockState(next.above());
        boolean door = low.getBlock() instanceof DoorBlock && ShipDoors.framed(level, next);
        if (door
            || low.getCollisionShape(level, next).isEmpty()
                && high.getCollisionShape(level, next.above()).isEmpty()) queue.add(next);
      }
    }
    return false;
  }
}
