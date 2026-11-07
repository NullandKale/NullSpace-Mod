package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.combat.ShipCombat;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.scene.SceneVolumes;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class StationExpansionGameTest {
  public static CompoundTag harbor(ServerLevel level, BlockPos base) {
    var plan = StationBlueprint.build(StationLayout.harbor());
    var s = new CompoundTag();
    s.putString("id", UUID.randomUUID().toString());
    s.putString("kind", "station");
    s.putString("dimension", level.dimension().identifier().toString());
    s.putString("home", "Full-size Harbor");
    s.putString("localSpace", "sol_terra");
    s.putString("access", "public");
    s.putLong("base", base.asLong());
    s.putLong("controller", base.offset(plan.core()).asLong());
    s.putInt("width", 128);
    s.putInt("depth", 128);
    s.putInt("height", 32);
    s.putDouble("simX", -3000);
    s.putDouble("simZ", -3000);
    s.put("crew", new ListTag());
    WorldStore.get(level.getServer()).ships().add(s);
    StationSpace.chunks(level, s);
    for (var e : plan.blocks().entrySet())
      level.setBlock(base.offset(e.getKey()), e.getValue(), 18);
    for (var at : plan.blocks().keySet()) FuelSystem.provision(level, base.offset(at), 64);
    ShipSystems.upgrade(level, s);
    s.getCompoundOrEmpty("systems").putIntArray("requests", new int[] {1, 4, 1, 0, 2, 0});
    ShipSystems.tick(level, s);
    return s;
  }

  private static net.minecraft.world.phys.AABB berthEnvelope(
      DockingService.Port port, BlockPos base, int sourceX) {
    var destination =
        Vec3.atCenterOf(port.controller().subtract(base))
            .add(0, 1, 0)
            .add(DockingMotion.normal(port.facing()).scale(.5));
    var pose =
        DockingMotion.mate(
            new Vec3(sourceX + .5, 3.5, 0),
            Direction.NORTH,
            ScenePose.identity(),
            destination,
            port.facing(),
            .05);
    double minX = Double.POSITIVE_INFINITY,
        minY = minX,
        minZ = minX,
        maxX = -minX,
        maxY = maxX,
        maxZ = maxX;
    for (int x : new int[] {0, 32})
      for (int y : new int[] {0, 32})
        for (int z : new int[] {0, 32}) {
          var p = pose.toUniverse(new Vec3(x, y, z));
          minX = Math.min(minX, p.x);
          minY = Math.min(minY, p.y);
          minZ = Math.min(minZ, p.z);
          maxX = Math.max(maxX, p.x);
          maxY = Math.max(maxY, p.y);
          maxZ = Math.max(maxZ, p.z);
        }
    return new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
  }

  @GameTest(environment = "nullspace:station_expansion", maxTicks = 400)
  public void fullRingHasSuppliedRoomsFarPortsCompleteGeometryAndCollision(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = new BlockPos(1700000, 80, 1700000);
    var station = harbor(level, base);
    ctx.assertTrue(
        DockingService.ports(level, station).size() == 8, "Eight usable outward-facing berths");
    ctx.assertTrue(
        ShipSystems.power(level, station, PowerBus.System.AIR) == 28 * SpatialAir.SUPPLY_POWER,
        "All 28 pumps receive normal power: "
            + ShipSystems.power(level, station, PowerBus.System.AIR));
    for (var room : StationLayout.harbor().rooms())
      ctx.assertTrue(
          ShipSystems.breathable(level, station, base.offset(room.origin()).offset(8, 4, 8)),
          "Every room has finite breathable air");
    var ports = DockingService.ports(level, station);
    for (var a : ports)
      for (var b : ports)
        if (a != b)
          for (int ax : new int[] {1, 30})
            for (int bx : new int[] {1, 30})
              ctx.assertTrue(
                  !berthEnvelope(a, base, ax).intersects(berthEnvelope(b, base, bx)),
                  "All eight 32-block berth envelopes fit even with offset ship doors");
    var graph = StationGraph.inspect(level, station);
    var docks = graph.nodes().stream().filter(StationGraph.Node::dock).toList();
    ctx.assertTrue(
        !graph.route(docks.getFirst().id(), docks.getLast().id()).isEmpty(),
        "Doors connect berths across render-section boundaries");
    var packet = new CompoundTag();
    var subjects = new ListTag();
    subjects.add(SceneService.subject(level, station));
    packet.put("subjects", subjects);
    var expanded = new SceneVolumes.Cache().expand(packet);
    ctx.assertTrue(
        expanded.getListOrEmpty("subjects").size() == 16,
        "All sixteen render sections are present");
    var last = expanded.getListOrEmpty("subjects").getCompound(15).orElseThrow();
    ctx.assertTrue(
        Arrays.stream(last.getIntArray("blocks").orElseThrow()).anyMatch(i -> i != 0),
        "Far corner is captured, not clipped at 32 blocks");
    for (int i = 0; i < 8; i++) {
      var visiting = new CompoundTag();
      visiting.putString("id", "visitor" + i);
      visiting.putLong("base", base.offset(256 + i * 64, 0, 0).asLong());
      SceneService.addSubject(subjects, level, visiting);
    }
    ctx.assertTrue(
        subjects.size() == 9
            && new SceneVolumes.Cache().expand(packet).getListOrEmpty("subjects").size() == 24,
        "A station and all eight visiting ships fit the scene budget");
    var hit =
        ShipCombat.trace(
            level,
            station,
            SceneService.pose(station).toUniverse(new Vec3(160, 4, 88)),
            new Vec3(-1, 0, 0),
            200);
    ctx.assertTrue(
        hit != null && hit.local().x > 120, "Authoritative ray collision hits the far hull");
    WorldStore.get(level.getServer()).ships().remove(station);
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:station_expansion", maxTicks = 400)
  public void expansionPreservesIdentityAirFuelAndBlocksAndRejectsObstruction(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = new BlockPos(1710000, 80, 1710000);
    var station = StationGameTest.fixture(level, base, false);
    var player = ctx.makeMockServerPlayerInLevel();
    station.putString("owner", player.getUUID().toString());
    player.teleportTo(base.getX() + 8.5, base.getY() + 3, base.getZ() + 8.5);
    var id = station.getStringOr("id", "");
    long quantity =
        ShipSystems.rooms(level, station).stream()
            .mapToLong(ShipSystems.RoomReading::quantity)
            .sum();
    int fuel = FuelSystem.available(level, station);
    var marker = base.offset(40, 3, 40);
    level.setBlock(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
    boolean rejected = false;
    try {
      StationService.expand(player, station);
    } catch (IllegalArgumentException e) {
      rejected = e.getMessage().contains("obstructed");
    }
    ctx.assertTrue(
        rejected
            && station.getIntOr("width", 32) == 32
            && level.getBlockState(marker).is(Blocks.DIAMOND_BLOCK),
        "Expansion refuses occupied space without changing it");
    level.setBlock(marker, Blocks.AIR.defaultBlockState(), 18);
    StationService.expand(player, station);
    ctx.assertTrue(
        station.getStringOr("id", "").equals(id)
            && WorldStore.bounds(station).contains(base.offset(127, 31, 127)),
        "One stable identity owns the expanded area");
    ctx.assertTrue(
        quantity
            == ShipSystems.rooms(level, station).stream()
                .mapToLong(ShipSystems.RoomReading::quantity)
                .sum(),
        "Expansion conserves stored air");
    ctx.assertTrue(
        fuel == FuelSystem.available(level, station), "Expansion does not refill or consume fuel");
    var copy = station.copy();
    ctx.assertTrue(
        copy.getCompoundOrEmpty("systems").getIntArray("air").orElseThrow().length
            == 128 * 32 * 128,
        "Saved air covers the full station");
    ctx.assertTrue(
        StationService.mayEdit(player, base.offset(120, 3, 120)),
        "Owner can build beyond the old boundary");
    WorldStore.get(level.getServer()).ships().remove(station);
    ctx.succeed();
  }
}
