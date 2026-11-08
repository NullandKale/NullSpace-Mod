package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class SceneCacheGameTest {
  @GameTest(maxTicks = 140)
  public void unchangedGeometryAndExternalLightPropagationStayCorrect(GameTestHelper ctx) {
    var level = ctx.getLevel().getServer().getLevel(TravelService.SPACE);
    var base = ctx.absolutePos(new BlockPos(19000, 80, 19000));
    var bounds = new HullScan.Bounds(base, base.offset(31, 31, 31));
    for (int x = base.getX() >> 4; x <= (base.getX() + 31) >> 4; x++)
      for (int z = base.getZ() >> 4; z <= (base.getZ() + 31) >> 4; z++) level.getChunk(x, z);
    for (int x = 0; x < 32; x++)
      for (int z = 0; z < 32; z++)
        level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 18);
    SceneSnapshots.capture(level, base, bounds);
    ctx.runAfterDelay(
        40,
        () -> {
          var first = SceneSnapshots.capture(level, base, bounds);
          var before = SceneSnapshots.stats(level);
          var same = SceneSnapshots.capture(level, base, bounds);
          var after = SceneSnapshots.stats(level);
          ctx.assertTrue(
              first.ready()
                  && same.geometry() == first.geometry()
                  && same.revision() == first.revision(),
              "An unchanged source must share one immutable encoded geometry");
          ctx.assertTrue(
              before.blockReads() == after.blockReads()
                  && before.lightReads() == after.lightReads(),
              "A stationary source must not rescan block or light cells");
          ctx.assertTrue(
              SceneVolumes.bytes(first.geometry()) < Atmosphere.CELLS * 5 / 2,
              "Ordinary source geometry should be smaller than raw block/light arrays");
          var marker = base.offset(5, 5, 5);
          level.setBlock(marker, Blocks.IRON_BLOCK.defaultBlockState(), 18);
          var edited = SceneSnapshots.capture(level, base, bounds);
          ctx.assertTrue(
              edited.revision() > same.revision()
                  && SceneVolumes.decode(edited.geometry())
                          .getIntArray("blocks")
                          .orElseThrow()[Atmosphere.index(5, 5, 5)]
                      == Block.getId(Blocks.IRON_BLOCK.defaultBlockState()),
              "A real block edit must invalidate geometry");
          var noOpBefore = SceneSnapshots.stats(level);
          level.setBlock(marker, Blocks.IRON_BLOCK.defaultBlockState(), 18);
          SceneSnapshots.capture(level, base, bounds);
          ctx.assertTrue(
              SceneSnapshots.stats(level).blockReads() == noOpBefore.blockReads(),
              "A failed/no-op setBlock must not invalidate source geometry");
          var outside = base.offset(-1, 8, 8);
          level.getChunk(outside);
          level.setBlock(outside, Blocks.SEA_LANTERN.defaultBlockState(), 18);
          long blocksBeforeLight = SceneSnapshots.stats(level).blockReads();
          ctx.runAfterDelay(
              25,
              () -> {
                var lit = SceneSnapshots.capture(level, base, bounds);
                var light = SceneVolumes.decode(lit.geometry()).getByteArray("light").orElseThrow();
                ctx.assertTrue(
                    Byte.toUnsignedInt(light[Atmosphere.index(0, 8, 8)]) > 0,
                    "Light from outside the captured block bounds must propagate into the feed");
                ctx.assertTrue(
                    SceneSnapshots.stats(level).blockReads() == blocksBeforeLight,
                    "Light-only updates must not rescan block states");
                level.setBlock(outside, Blocks.AIR.defaultBlockState(), 18);
                ctx.runAfterDelay(
                    25,
                    () -> {
                      var dark = SceneSnapshots.capture(level, base, bounds);
                      ctx.assertTrue(
                          SceneVolumes.decode(dark.geometry())
                                  .getByteArray("light")
                                  .orElseThrow()[Atmosphere.index(0, 8, 8)]
                              == 0,
                          "Removing an external light must darken the cached source");
                      ctx.succeed();
                    });
              });
        });
  }

  @GameTest
  public void selfContainedVolumesReuseArraysAndRejectMalformedData(GameTestHelper ctx) {
    var cache = new SceneVolumes.Cache(2);
    var first = packet("a", 1, 8);
    var expanded = cache.expand(first);
    var a = (CompoundTag) expanded.getListOrEmpty("subjects").getFirst();
    var again = (CompoundTag) cache.expand(packet("a", 1, 8)).getListOrEmpty("subjects").getFirst();
    ctx.assertTrue(
        a.get("blocks") == again.get("blocks")
            && a.get("light") == again.get("light")
            && cache.reused() == 1,
        "Unchanged revisions must share decoded arrays across frame channels");
    ctx.assertTrue(
        !((CompoundTag) first.getListOrEmpty("subjects").getFirst()).contains("blocks"),
        "Client expansion must not mutate a received packet");
    var latest =
        (CompoundTag) cache.expand(packet("a", 2, 19)).getListOrEmpty("subjects").getFirst();
    cache.expand(packet("a", 1, 8));
    var newest =
        (CompoundTag) cache.expand(packet("a", 2, 19)).getListOrEmpty("subjects").getFirst();
    ctx.assertTrue(
        latest.get("blocks") == newest.get("blocks")
            && newest.getIntArray("blocks").orElseThrow()[0] == 19,
        "Late frames must not replace a newer cached revision");
    cache.expand(packet("b", 1, 7));
    cache.expand(packet("c", 1, 3));
    ctx.assertTrue(cache.size() == 2, "Decoded geometry cache must stay bounded");
    var malformed = packet("bad", 1, 4);
    ((CompoundTag) malformed.getListOrEmpty("subjects").getFirst())
        .getCompoundOrEmpty("geometry")
        .putByteArray("lightPacked", new byte[] {0, 16});
    boolean rejected = false;
    try {
      cache.expand(malformed);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    ctx.assertTrue(rejected, "Invalid light payload must be rejected");
    var tooMany = new CompoundTag();
    var subjects = new ListTag();
    for (int i = 0; i <= SceneService.MAX_SUBJECTS; i++) subjects.add(new CompoundTag());
    tooMany.put("subjects", subjects);
    rejected = false;
    try {
      cache.expand(tooMany);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    ctx.assertTrue(rejected, "Oversized subject lists must be rejected");
    int[] unique = new int[Atmosphere.CELLS];
    for (int i = 0; i < unique.length; i++) unique[i] = i;
    var fallback = SceneVolumes.geometry(SceneVolumes.blocks(unique), new byte[Atmosphere.CELLS]);
    ctx.assertTrue(
        fallback.contains("raw")
            && Arrays.equals(
                unique, SceneVolumes.decode(fallback).getIntArray("blocks").orElseThrow()),
        "Incompressible palettes must use a lossless bounded raw fallback");
    cache.clear();
    ctx.assertTrue(
        cache.size() == 0 && cache.decoded() == 0 && cache.reused() == 0,
        "Disconnect clears arrays and counters");
    ctx.succeed();
  }

  @GameTest(maxTicks = 380)
  public void sourceCacheAndLoadOnlyTicketsHaveFiniteLifetimes(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(20000, 80, 20000));
    ctx.assertTrue(
        SceneSnapshots.CAMERA_TICKET.doesLoad()
            && !SceneSnapshots.CAMERA_TICKET.persist()
            && !SceneSnapshots.CAMERA_TICKET.doesSimulate()
            && !SceneSnapshots.CAMERA_TICKET.shouldKeepDimensionActive(),
        "Camera tickets must be load-only and not persist or keep a dimension ticking");
    long oldEvictions = SceneSnapshots.stats(level).evictions();
    for (int i = 0; i < SceneSnapshots.MAX_SOURCES + 1; i++) {
      var min = base.offset(i % 8, (i / 64), i / 8 % 8);
      SceneSnapshots.capture(level, base, new HullScan.Bounds(min, base.offset(31, 31, 31)));
    }
    ctx.assertTrue(
        SceneSnapshots.stats(level).sources() <= SceneSnapshots.MAX_SOURCES
            && SceneSnapshots.stats(level).evictions() > oldEvictions,
        "Source variants must respect the per-level LRU budget");
    ctx.runAfterDelay(
        350,
        () -> {
          ctx.assertTrue(
              SceneSnapshots.stats(level).sources() == 0,
              "Idle camera sources and their chunk subscriptions must expire without another camera"
                  + " request");
          ctx.succeed();
        });
  }

  @GameTest
  public void noOpEditsDoNotChurnEngineeringRevisionsOrDoorIdentity(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(21000, 64, 21000));
    var controller = ShipTestFixture.build(level, base);
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", level.dimension().identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", controller.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    WorldStore.get(level.getServer()).ships().add(ship);
    SystemsUpgrade.upgrade(level, ship);
    int revision = ShipSystems.revision(level, ship);
    var wall = base.offset(0, 4, 4);
    level.setBlock(wall, level.getBlockState(wall), 18);
    ctx.assertTrue(
        ShipSystems.revision(level, ship) == revision,
        "A no-op world edit must not invalidate a terminal revision");
    var door = base.offset(3, 3, 0);
    var device =
        ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
            .map(t -> (CompoundTag) t)
            .filter(
                t ->
                    t.getLongOr("position", Long.MIN_VALUE) == door.subtract(base.above()).asLong())
            .findFirst()
            .orElseThrow();
    var id = device.getStringOr("id", "");
    level.setBlock(
        door,
        level
            .getBlockState(door)
            .setValue(DoorBlock.OPEN, true)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER),
        18);
    ShipSystems.revision(level, ship);
    ctx.assertTrue(
        ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
            .map(t -> (CompoundTag) t)
            .anyMatch(t -> t.getStringOr("id", "").equals(id)),
        "Changing door state must retain stable device identity");
    ctx.succeed();
  }

  private static CompoundTag packet(String id, long revision, int state) {
    int[] blocks = new int[Atmosphere.CELLS];
    Arrays.fill(blocks, state);
    var subject = new CompoundTag();
    subject.putString("id", "test");
    subject.putString("geometryId", id);
    subject.putLong("geometryRevision", revision);
    subject.put(
        "geometry", SceneVolumes.geometry(SceneVolumes.blocks(blocks), new byte[Atmosphere.CELLS]));
    var subjects = new ListTag();
    subjects.add(subject);
    var scene = new CompoundTag();
    scene.put("subjects", subjects);
    return scene;
  }
}
