package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.pads.PadStatus;
import com.nullandkale.nullspace.gameplay.scene.SceneSnapshots;
import com.nullandkale.nullspace.gameplay.scene.SceneVolumes;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.space.VisualSnapshot;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;

public final class OwnershipGameTest {
  @GameTest(maxTicks = 120)
  public void cachedCatalogAndBoundedAdvisoryInspection(GameTestHelper ctx) throws Exception {
    var level = ctx.getLevel();
    var server = level.getServer();
    var base = ctx.absolutePos(new BlockPos(39000, 70, 39000));
    for (int x = -1; x <= 8; x++)
      for (int z = -1; z <= 8; z++)
        level.setBlock(
            base.offset(x, 0, z),
            (x < 0 || x > 7 || z < 0 || z > 7 ? Blocks.COBBLESTONE : Blocks.DIRT)
                .defaultBlockState(),
            18);
    var controller = base.offset(-1, 1, 3);
    level.setBlock(controller, PadComputers.BLOCK.defaultBlockState(), 18);
    var pad =
        new PadRegistry.Pad(
            UUID.randomUUID().toString(),
            "Budget",
            level.dimension().identifier().toString(),
            controller,
            new PadScanner.Pad(
                base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7));
    var repo = PadRepository.get(server);
    long reads = repo.reads();
    for (int i = 0; i < 1000; i++) {
      PadRegistry.read(server);
      repo.dimension(pad.dimension());
      repo.id(pad.id());
    }
    ctx.assertTrue(repo.reads() == reads, "Catalog lookup performs no file reads");
    var pads = new ArrayList<PadRegistry.Pad>();
    for (int i = 0; i < 128; i++) {
      var p =
          new PadRegistry.Pad(
              UUID.randomUUID().toString(),
              "Budget " + i,
              pad.dimension(),
              controller,
              pad.bounds());
      pads.add(p);
      ctx.assertTrue(
          PadRegistry.state(server, p).state() == PadRegistry.State.UNKNOWN,
          "First status is advisory UNKNOWN");
    }
    for (int x = (base.getX() - 1) >> 4; x <= (base.getX() + 8) >> 4; x++)
      for (int z = (base.getZ() - 1) >> 4; z <= (base.getZ() + 8) >> 4; z++)
        level.setChunkForced(x, z, true);
    boolean[] completed = {false};
    int[] polls = {0};
    ctx.onEachTick(
        () -> {
          if (completed[0]) return;
          ctx.assertTrue(
              PadStatus.lastReads(server) <= PadStatus.READS_PER_TICK,
              "The entire catalog respects one global read budget");
          if (++polls[0] == 1)
            ctx.assertTrue(
                PadRegistry.state(server, pads.getLast()).state() == PadRegistry.State.UNKNOWN,
                "Unprocessed work remains UNKNOWN");
          // Other concurrent GameTests legitimately reload the server-wide catalog. Keep requesting
          // advisory work, just as a live destinations page does, rather than assuming no reloads.
          boolean ready = true;
          for (var p : pads)
            ready &= PadRegistry.state(server, p).state() == PadRegistry.State.READY;
          if (!ready) return;
          level.setBlock(base.offset(3, 15, 3), Blocks.GLASS.defaultBlockState(), 18);
          ctx.assertTrue(
              PadRegistry.state(server, pads.getFirst()).state() == PadRegistry.State.UNKNOWN,
              "Real edits invalidate cached status before reuse");
          completed[0] = true;
          ctx.succeed();
        });
  }

  @GameTest
  public void warmRuntimeRebindsAirAndDevicesToReplacementRecords(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(40000, 70, 40000));
    var controller = ShipTestFixture.build(level, base);
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", level.dimension().identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", controller.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    ShipSystems.upgrade(level, ship);
    int before = ShipSystems.revision(level, ship);
    var replacement = ship.copy();
    int[] air = replacement.getCompoundOrEmpty("systems").getIntArray("air").orElseThrow();
    air[Atmosphere.index(4, 2, 3)] = 123;
    replacement.getCompoundOrEmpty("systems").putIntArray("air", air);
    ctx.assertTrue(
        ShipSystems.pressure(level, replacement, 4, 2, 3) == 123
            && ShipSystems.revision(level, replacement) > before,
        "A recovered record must not reuse the old air array or devices");
    ctx.succeed();
  }

  @GameTest
  public void shipAndTerrainShareBoundedVisualUpdateTags(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(41000, 70, 41000));
    level.setBlock(base, Blocks.OAK_SIGN.defaultBlockState(), 18);
    var snapshot =
        SceneSnapshots.capture(level, base, new HullScan.Bounds(base, base.offset(31, 31, 31)));
    var decoded = SceneVolumes.decode(snapshot.geometry());
    var visuals = decoded.getListOrEmpty("blockEntities");
    ctx.assertTrue(
        visuals.size() == 1
            && ((CompoundTag) visuals.getFirst())
                .getCompoundOrEmpty("data")
                .getStringOr("id", "")
                .equals("minecraft:sign"),
        "Ship snapshots carry the same visual block entities as terrain");
    VisualSnapshot.validate(visuals, 32768);
    ctx.succeed();
  }

  @GameTest
  public void denseVisualSourcesNeverProducePacketsTheirOwnClientRejects(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(42000, 70, 42000));
    var collector = new VisualSnapshot.Collector();
    for (int i = 0; i < 64; i++) {
      var p = base.offset(i % 8, 0, i / 8);
      level.setBlock(p, Blocks.OAK_SIGN.defaultBlockState(), 18);
      collector.add(level, p, i);
    }
    VisualSnapshot.validate(collector.entities(), 32768);
    ctx.assertTrue(
        !collector.entities().isEmpty()
            && collector.entities().sizeInBytes() <= VisualSnapshot.MAX_BYTES,
        "Producer and consumer account for the complete visual wrapper budget");
    ctx.succeed();
  }
}
