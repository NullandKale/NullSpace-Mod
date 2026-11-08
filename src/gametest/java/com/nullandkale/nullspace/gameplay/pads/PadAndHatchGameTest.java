package com.nullandkale.nullspace.gameplay.pads;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;

public final class PadAndHatchGameTest {
  private static PadScanner.Pad pad(BlockPos base) {
    return new PadScanner.Pad(
        base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
  }

  private static void force(net.minecraft.server.level.ServerLevel level, BlockPos base) {
    for (int x = base.getX() >> 4; x <= (base.getX() + 7) >> 4; x++)
      for (int z = base.getZ() >> 4; z <= (base.getZ() + 7) >> 4; z++)
        level.setChunkForced(x, z, true);
  }

  @GameTest
  public void facingIndependentStackedPadsSelectTheirOwnShip(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var low = ctx.absolutePos(new BlockPos(31000, 64, 31000));
    var high = low.above(12);
    var a = ShipTestFixture.build(level, low);
    var b = ShipTestFixture.build(level, high);
    var p = ctx.makeMockServerPlayerInLevel();
    try {
      for (var base : List.of(low, high)) {
        force(level, base);
        var control = base.offset(-1, 1, 3);
        p.teleportTo(base.getX() + .5, base.getY() + 1, base.getZ() + 3.5);
        for (var facing : Direction.Plane.HORIZONTAL) {
          level.setBlock(
              control,
              PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, facing),
              18);
          var result = PadComputers.inspect(p, control);
          ctx.assertTrue(
              result.valid() && result.pad().equals(pad(base)),
              "Controller facing must not select a different pad layer");
        }
        String result =
            PadComputers.register(
                level, control, pad(base), "Stack " + UUID.randomUUID().toString().substring(0, 8));
        ctx.assertTrue(
            result.startsWith("Registered"),
            "Stacked rings must register independently: " + result);
      }
      var lower = ShipTopology.resolve(level, a);
      ctx.assertTrue(
          ShipService.inspect(level, b).vessel() == null,
          "A new upper ship cannot inherit the lower ship identity during inspection");
      var upper = ShipTopology.resolve(level, b);
      ctx.assertTrue(
          !lower.getStringOr("id", "").equals(upper.getStringOr("id", "")),
          "Overlapping vertical allocations cannot steal another pad's ship identity");
      ctx.assertTrue(
          !WorldStore.bounds(lower).contains(b),
          "The lower allocation ends below the upper pad floor");
      ctx.assertTrue(
          !BuildPreviews.shipPoints(level, pad(low)).contains(b),
          "A lower preview cannot include the upper ship");
      ctx.assertTrue(
          WorldStore.get(level.getServer()).at(level, b) == upper
              && PadRegistry.below(level, b)
                  .orElseThrow()
                  .id()
                  .equals(upper.getStringOr("homeId", "")),
          "The higher helm belongs to its nearest registered floor");
      ctx.assertTrue(
          ShipService.inspect(level, b).pad().bounds().equals(pad(high)),
          "Ship inspection must use the upper pad, not registration order");
      boolean obstructed = false;
      try {
        ShipService.inspect(level, a);
      } catch (HullScan.Failure expected) {
        obstructed = true;
      }
      ctx.assertTrue(
          obstructed, "A real pad above another pad still obstructs the lower launch column");
      ctx.succeed();
    } finally {
      level.getServer().getPlayerList().remove(p);
    }
  }

  @GameTest
  public void framedWoodAndIronHatchesSealAndOpenLikeDoors(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(32000, 64, 32000));
    var helm = ShipTestFixture.build(level, base);
    var roof = base.offset(3, 6, 3);
    for (var block : List.of(Blocks.OAK_TRAPDOOR, Blocks.IRON_TRAPDOOR))
      for (var half : Half.values()) {
        level.setBlock(
            roof,
            block
                .defaultBlockState()
                .setValue(TrapDoorBlock.HALF, half)
                .setValue(TrapDoorBlock.OPEN, false),
            18);
        var scan = HullScan.inspect(level, helm, pad(base), true);
        ctx.assertTrue(
            scan.pressure().breathable(base.offset(4, 4, 3)),
            "A closed fully framed hatch must preserve cabin pressure");
        ShipDoors.setOpen(level, roof, true);
        ctx.assertTrue(
            !new HullScan.LivePressure(level, scan.bounds()).breathable(base.offset(4, 4, 3)),
            "An open hatch must expose the cabin to vacuum");
        ShipDoors.setOpen(level, roof, false);
        var frame = roof.east();
        level.setBlock(frame, Blocks.AIR.defaultBlockState(), 18);
        ctx.assertTrue(
            !ShipDoors.sealed(level, roof),
            "A hatch cannot seal with a missing horizontal frame block");
        level.setBlock(frame, Blocks.STONE.defaultBlockState(), 18);
      }
    ctx.succeed();
  }

  @GameTest
  public void ladderToRoofHatchIsARealExit(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(33000, 64, 33000));
    var helm = ShipTestFixture.build(level, base);
    for (int y = 3; y <= 4; y++)
      level.setBlock(base.offset(3, y, 0), Blocks.STONE.defaultBlockState(), 18);
    var roof = base.offset(3, 6, 3);
    level.setBlock(
        roof,
        Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM),
        18);
    for (int y = 3; y <= 5; y++) {
      level.setBlock(base.offset(3, y, 4), Blocks.STONE.defaultBlockState(), 18);
      level.setBlock(
          base.offset(3, y, 3),
          Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH),
          18);
    }
    HullScan.inspect(level, helm, pad(base), true);
    for (int y = 3; y <= 5; y++)
      level.setBlock(base.offset(3, y, 3), Blocks.AIR.defaultBlockState(), 18);
    boolean unreachable = false;
    try {
      HullScan.inspect(level, helm, pad(base), true);
    } catch (HullScan.Failure expected) {
      unreachable = expected.getMessage().contains("path");
    }
    ctx.assertTrue(
        unreachable, "Opening a high hatch is insufficient without a usable route to it");
    ctx.succeed();
  }

  @GameTest
  public void poweredHatchGroupsKeepIdsAndRespectRedstone(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(34000, 64, 34000));
    var helm = ShipTestFixture.build(level, base);
    var hatch = base.offset(3, 6, 3);
    level.setBlock(hatch, Blocks.IRON_TRAPDOOR.defaultBlockState(), 18);
    level.setBlock(base.offset(1, 3, 6), ModBlocks.GENERATOR.defaultBlockState(), 18);
    level.setBlock(base.offset(4, 3, 6), ModBlocks.COMPUTER.defaultBlockState(), 18);
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", level.dimension().identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", helm.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    WorldStore.get(level.getServer()).ships().add(ship);
    SystemsUpgrade.upgrade(level, ship);
    ShipSystems.tick(level, ship);
    var device =
        ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
            .map(t -> (CompoundTag) t)
            .filter(d -> d.getStringOr("type", "").equals("minecraft:iron_trapdoor"))
            .findFirst()
            .orElseThrow();
    var id = device.getStringOr("id", "");
    var p = ctx.makeMockServerPlayerInLevel();
    p.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
    try {
      SystemsConsole.action(
          p, helm, ComputerTerminal.Action.CONFIGURE, "group hatch add " + id.substring(0, 8));
      SystemsConsole.action(p, helm, ComputerTerminal.Action.CONFIGURE, "doors hatch open");
      ShipSystems.tick(level, ship);
      ctx.assertTrue(
          ShipDoors.open(level.getBlockState(hatch)),
          "A named powered group must open an iron hatch");
      SystemsConsole.action(p, helm, ComputerTerminal.Action.CLOSE_DOORS);
      for (int i = 0; i < 3; i++) ShipSystems.tick(level, ship);
      ctx.assertTrue(!ShipDoors.open(level.getBlockState(hatch)), "Close all includes hatches");
      ctx.assertTrue(
          ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
              .map(t -> (CompoundTag) t)
              .anyMatch(d -> d.getStringOr("id", "").equals(id)),
          "Opening and closing cannot recreate device IDs");
      level.setBlockAndUpdate(hatch.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
      ctx.assertTrue(
          ShipDoors.open(level.getBlockState(hatch)), "Native redstone must open the hatch");
      SystemsConsole.action(p, helm, ComputerTerminal.Action.CLOSE_DOORS);
      for (int i = 0; i < 3; i++) ShipSystems.tick(level, ship);
      ctx.assertTrue(
          ShipDoors.open(level.getBlockState(hatch)),
          "Powered close-all cannot override a live redstone signal");
      ctx.succeed();
    } finally {
      level.getServer().getPlayerList().remove(p);
    }
  }

  @GameTest
  public void previewsWorkBeforeRegistrationAndExcludeDisconnectedBlocks(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(35000, 64, 35000));
    var helm = ShipTestFixture.build(level, base);
    force(level, base);
    var bounds = pad(base);
    var expected = HullScan.inspect(level, helm, bounds, true).blocks();
    ctx.assertTrue(
        BuildPreviews.padPoints(bounds).size() == 100,
        "Pad preview includes every floor and ring block");
    ctx.assertTrue(
        new HashSet<>(BuildPreviews.shipPoints(level, bounds)).equals(expected),
        "Ship preview must match connected hull membership");
    var marker = base.offset(7, 5, 7);
    level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
    ctx.assertTrue(
        !BuildPreviews.shipPoints(level, bounds).contains(marker),
        "Disconnected material cannot silently join a ship preview");
    var unsupported = base.offset(4, 3, 4);
    level.setBlock(unsupported, Blocks.STRUCTURE_BLOCK.defaultBlockState(), 18);
    ctx.assertTrue(
        BuildPreviews.shipPoints(level, bounds).contains(unsupported),
        "An invalid machine still appears in repair previews");
    var p = ctx.makeMockServerPlayerInLevel();
    p.teleportTo(base.getX() + .5, base.getY() + 1, base.getZ() + 3.5);
    try {
      var control = base.offset(-1, 1, 3);
      BuildPreviews.toggle(p, control, false);
      BuildPreviews.toggle(p, control, true);
      ctx.assertTrue(
          BuildPreviews.enabled(p, control, false) && BuildPreviews.enabled(p, control, true),
          "A viewer can enable both sensors independently");
      BuildPreviews.toggle(p, control, false);
      ctx.assertTrue(
          !BuildPreviews.enabled(p, control, false) && BuildPreviews.enabled(p, control, true),
          "Toggling one sensor cannot turn off the other");
      var session = ComputerTerminal.issue(p, helm, "", "ship", "");
      boolean rejected = false;
      try {
        ComputerTerminal.authorize(
            p,
            new TerminalPackets.Request(
                session.session(), ComputerTerminal.Action.PREVIEW_PAD, "", ""));
      } catch (IllegalArgumentException expectedError) {
        rejected = expectedError.getMessage().contains("does not belong");
      }
      ctx.assertTrue(rejected, "A helm session cannot forge pad preview actions");
      ctx.succeed();
    } finally {
      level.getServer().getPlayerList().remove(p);
    }
  }

  @GameTest
  public void structureBlocksAndFurnacesPassInspection(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(36000, 64, 36000));
    var helm = ShipTestFixture.build(level, base);
    var at = base.offset(4, 3, 4);
    // Every block entity travels with its saved data, including operator blocks.
    for (var block :
        List.of(Blocks.STRUCTURE_BLOCK, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER)) {
      level.setBlock(at, block.defaultBlockState(), 18);
      HullScan.inspect(level, helm, pad(base), true);
    }
    ctx.succeed();
  }
}
