package com.nullandkale.nullspace.gameplay.ship;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.SphereLab;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Playtest 3: doorway air, Engage closing doors, and fuel drawn evenly from generators. */
public final class PlaytestThreeGameTest {
  @GameTest(maxTicks = 200)
  public void doorwaysBreatheTheRoomEngageClosesDoorsAndFuelDrawsEvenly(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(7168, 64, 7168));
    String name = "Playtest " + UUID.randomUUID().toString().substring(0, 8);
    SphereLab.buildPad(level, base, name);
    SphereLab.buildShip(level, base);
    var helm = SphereLab.controller(base);
    level.setBlock(
        helm,
        ModBlocks.COMPUTER
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
        18);
    Block[] row = {
      ModBlocks.GENERATOR,
      ModBlocks.GENERATOR,
      ModBlocks.AIR_SUPPLY,
      ModBlocks.BATTERY,
      ModBlocks.COMPUTER
    };
    for (int x = 14; x < 14 + row.length; x++)
      level.setBlock(
          base.offset(x, 6, 21),
          row[x - 14]
              .defaultBlockState()
              .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
          18);
    var first = base.offset(14, 6, 21);
    var second = base.offset(15, 6, 21);
    FuelSystem.provision(level, first, 48);
    FuelSystem.provision(level, second, 16);
    var ship = ShipTopology.resolve(level, base.offset(18, 6, 21));
    SystemsUpgrade.upgrade(level, ship);
    for (int i = 0; i < 20; i++) ShipSystems.tick(level, ship);

    // Fuel: a draw comes out of every generator in proportion to what each holds.
    var generators = FuelSystem.generators(level, ship);
    var before = generators.stream().mapToLong(MachineBlockEntity::available).toArray();
    long total = Arrays.stream(before).sum();
    int draw = FuelSystem.UNITS_PER_FUEL * 8;
    ctx.assertTrue(FuelSystem.spend(level, ship, draw), "The ship can pay the draw");
    long taken = 0;
    for (int i = 0; i < generators.size(); i++) {
      long used = before[i] - generators.get(i).available();
      taken += used;
      long share = (long) draw * before[i] / total;
      ctx.assertTrue(
          Math.abs(used - share) <= generators.size(),
          "Generator " + i + " pays its share: used " + used + ", share " + share);
    }
    ctx.assertTrue(taken == draw, "The whole draw is paid: " + taken);
    ctx.assertTrue(
        ((MachineBlockEntity) level.getBlockEntity(second)).available()
            < before[generators.indexOf((MachineBlockEntity) level.getBlockEntity(second))],
        "The smaller generator is drawn from too, not left until the first is empty");

    // Doorway: an open door cell takes the air of the room beside it, not vacuum.
    var doors =
        ShipSystems.connected(level, ship).stream()
            .filter(
                p ->
                    level.getBlockState(p).getBlock() instanceof DoorBlock
                        && level.getBlockState(p).getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .toList();
    ctx.assertTrue(!doors.isEmpty(), "The lab ship has doors");
    BlockPos tested = null;
    for (var door : doors) {
      // An interior door: aired rooms on both sides, so opening it vents nothing.
      var facing = level.getBlockState(door).getValue(DoorBlock.FACING);
      if (ShipAir.oxygen(level, ship, door.relative(facing)) == 0
          || ShipAir.oxygen(level, ship, door.relative(facing.getOpposite())) == 0) continue;
      // A closed door is a thin panel: you can stand in its block, which belongs to no room.
      ShipDoors.setOpen(level, door, false);
      ShipSystems.tick(level, ship);
      ctx.assertTrue(ShipAir.roomAt(level, ship, door) == null, "A closed door cell is in no room");
      ctx.assertTrue(
          ShipAir.oxygen(level, ship, door) > 0 && ShipAir.oxygen(level, ship, door.above()) > 0,
          "Standing in a closed doorway breathes the room beside it: door "
              + ShipAir.oxygen(level, ship, door)
              + "/"
              + ShipAir.oxygen(level, ship, door.above())
              + " sides "
              + ShipAir.oxygen(level, ship, door.relative(facing))
              + "/"
              + ShipAir.oxygen(level, ship, door.relative(facing.getOpposite()))
              + " room "
              + ShipAir.roomAt(level, ship, door)
              + " open "
              + level.getBlockState(door).getValue(DoorBlock.OPEN));
      tested = door;
      break;
    }
    ctx.assertTrue(tested != null, "A door beside an aired room was tested");

    // Engage closes every door; a door held open by redstone is reported, not ignored.
    for (var door : doors) ShipDoors.setOpen(level, door, true);
    ctx.assertTrue(
        SystemsConsole.closeDoorsNow(level, ship).isEmpty()
            && doors.stream().noneMatch(p -> level.getBlockState(p).getValue(DoorBlock.OPEN)),
        "Every door closes");
    var held = doors.getFirst();
    BlockPos power = null;
    for (var d : Direction.Plane.HORIZONTAL) {
      var at = held.relative(d);
      if (level.getBlockState(at).isAir()) {
        power = at;
        break;
      }
    }
    ctx.assertTrue(power != null, "Room beside the door for a redstone block");
    level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
    ShipDoors.setOpen(level, held, true);
    var stuck = SystemsConsole.closeDoorsNow(level, ship);
    ctx.assertTrue(
        stuck.size() == 1 && stuck.getFirst().equals(held),
        "A door held open by redstone is reported: " + stuck);
    level.setBlock(power, Blocks.AIR.defaultBlockState(), 3);
    WorldStore.get(level.getServer()).ships().remove(ship);
    ctx.succeed();
  }

  @GameTest(maxTicks = 100)
  public void snowOnTheHullAndPadDoesNotBlockLaunchAndIsLeftBehind(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(8192, 64, 8192));
    SphereLab.buildPad(level, base, "Snow " + UUID.randomUUID().toString().substring(0, 8));
    SphereLab.buildShip(level, base);
    var helm = SphereLab.controller(base);
    level.setBlock(
        helm,
        ModBlocks.COMPUTER
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
        18);
    var blocks = ShipService.inspect(level, helm).scan().blocks();
    // Snow settles on the top of every hull column and on the open pad around the ship.
    var tops = new HashMap<Long, BlockPos>();
    for (var p : blocks)
      tops.merge(BlockPos.asLong(p.getX(), 0, p.getZ()), p, (a, b) -> a.getY() > b.getY() ? a : b);
    var snowed = new ArrayList<BlockPos>();
    for (var top : tops.values())
      if (level.getBlockState(top.above()).isAir()) {
        level.setBlock(top.above(), Blocks.SNOW.defaultBlockState(), 18);
        snowed.add(top.above());
      }
    int padSnow = 0;
    for (int x = 0; x < 32; x++)
      for (int z = 0; z < 32; z++) {
        var at = base.offset(x, 1, z);
        if (level.getBlockState(at).isAir() && !blocks.contains(at.below())) {
          level.setBlock(at, Blocks.SNOW.defaultBlockState(), 18);
          padSnow++;
        }
      }
    ctx.assertTrue(!snowed.isEmpty() && padSnow > 0, "Snow was placed on the hull and the pad");
    try {
      ShipService.inspect(level, helm);
    } catch (RuntimeException blocked) {
      throw new AssertionError("Snow must not block a launch: " + blocked.getMessage());
    }
    HullScan.clearCover(level, blocks);
    ctx.assertTrue(
        snowed.stream().allMatch(p -> level.getBlockState(p).isAir()),
        "Snow on the hull is cleared at departure, not left floating");
    ctx.succeed();
  }
}
