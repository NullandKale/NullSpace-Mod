package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.CabinPaths;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;

public final class SphereGameTest {
  @GameTest(maxTicks = 240)
  public void sphereDecksRoomsPressureAndFullSizeTravel(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var server = level.getServer();
    var base = ctx.absolutePos(new BlockPos(4096, 64, 4096));
    String name = "Sphere " + java.util.UUID.randomUUID().toString().substring(0, 8),
        destination = name + " XL";
    SphereLab.buildPad(level, base, name);
    SphereLab.buildShip(level, base);
    SphereLab.buildPad(level, base.offset(48, 0, 0), destination);
    var controller = SphereLab.controller(base);
    var scan = ShipService.inspect(level, controller).scan();
    ctx.assertTrue(
        scan.bounds().min().equals(base.above())
            && scan.bounds().max().equals(base.offset(31, 32, 31)),
        "Sphere must occupy exactly 32 x 32 x 32 above the pad");
    ctx.assertTrue(
        scan.blocks().stream().filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE)).count()
            == 4,
        "Four outward-facing engines required");
    for (int floor : SphereLab.DECKS)
      for (int x : new int[] {7, 24})
        ctx.assertTrue(
            scan.pressure().breathable(base.offset(x, floor + 2, 15)),
            "Both side rooms must be sealed on every deck");
    var outer = base.offset(15, 2, 1);
    var inner = base.offset(15, 6, 9);
    door(level, outer, true);
    var pressure = HullScan.pressure(level, scan.bounds(), scan.blocks());
    ctx.assertTrue(
        !pressure.breathable(base.offset(15, 3, 2)) && pressure.breathable(controller.north()),
        "Open exterior airlock must leave the main sphere supplied");
    door(level, outer, false);
    door(level, inner, true);
    pressure = HullScan.pressure(level, scan.bounds(), scan.blocks());
    for (int floor : SphereLab.DECKS)
      ctx.assertTrue(
          CabinPaths.reaches(
              level, base.offset(15, 2, 2), base.offset(16, floor + 1, 20), pressure),
          "Walking route from entry must reach deck floor " + floor);
    ctx.assertTrue(
        CabinPaths.reaches(level, base.offset(15, 2, 2), controller.north(), pressure),
        "Stairs must reach the actual bridge without flight");
    for (int floor : SphereLab.DECKS)
      for (int side : new int[] {0, 1}) {
        var roomDoor = base.offset(side == 0 ? 9 : 22, floor + 1, 15);
        door(level, roomDoor, true);
        ctx.assertTrue(
            CabinPaths.reaches(
                level,
                base.offset(15, 2, 2),
                base.offset(side == 0 ? 7 : 24, floor + 1, 15),
                HullScan.pressure(level, scan.bounds(), scan.blocks())),
            "All ten rooms must be walkable through their doors");
        door(level, roomDoor, false);
      }
    door(level, inner, false);
    var breach = base.offset(15, 16, 31);
    var hull = level.getBlockState(breach);
    var inside = base.offset(15, 16, 30);
    var second = level.getBlockState(inside);
    level.setBlock(breach, Blocks.AIR.defaultBlockState(), 18);
    level.setBlock(inside, Blocks.AIR.defaultBlockState(), 18);
    pressure = HullScan.pressure(level, scan.bounds(), scan.blocks());
    ctx.assertTrue(
        !pressure.breathable(controller.north()), "A main-hall breach must vent the bridge");
    for (int floor : SphereLab.DECKS)
      for (int x : new int[] {7, 24})
        ctx.assertTrue(
            pressure.breathable(base.offset(x, floor + 2, 15)),
            "Closed room doors must preserve all ten rooms after a hall breach");
    var navigationDoor = base.offset(9, 16, 15);
    door(level, navigationDoor, true);
    pressure = HullScan.pressure(level, scan.bounds(), scan.blocks());
    ctx.assertTrue(
        !pressure.breathable(base.offset(7, 17, 15))
            && pressure.breathable(base.offset(24, 17, 15)),
        "Opening navigation to the breached hall must vent it while the opposite room stays"
            + " sealed");
    door(level, navigationDoor, false);
    level.setBlock(breach, hull, 18);
    level.setBlock(inside, second, 18);
    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(base.getX() + 16.5, base.getY() + 16, base.getZ() + 23.5);
    player.getInventory().add(new ItemStack(Items.EMERALD, 11));
    for (int x = base.getX() >> 4; x <= (base.getX() + 79) >> 4; x++)
      for (int z = base.getZ() >> 4; z <= (base.getZ() + 31) >> 4; z++)
        level.setChunkForced(x, z, true);
    var space = server.getLevel(TravelService.SPACE);
    int free = 0;
    for (int slot = 0; free < 3; slot++) {
      final int x = slot * TravelService.SPACE_SPACING;
      if (TravelService.spaceSlotFree(server, slot)) {
        for (int dx = 0; dx <= 1; dx++)
          for (int dz = 0; dz <= 1; dz++) space.setChunkForced((x >> 4) + dx, dz, true);
        free++;
      }
    }
    ctx.runAfterDelay(
        20,
        () -> {
          TravelService.transfer(player, controller, "", ShipService.preflight(level, controller));
          ctx.assertTrue(player.level() == space, "Full 32-cube sphere must launch");
          var vessel = WorldStore.get(server).at(space, player.blockPosition());
          var computer = WorldStore.pos(vessel, "controller");
          TravelService.transfer(
              player, computer, destination, ShipService.preflight(space, computer));
          var landed = base.offset(48, 0, 0);
          ctx.assertTrue(
              player.level() == level
                  && level.getBlockState(SphereLab.controller(landed)).is(ModBlocks.COMPUTER)
                  && level.getBlockState(controller).isAir(),
              "Sphere must land on an equally large empty pad with one authoritative copy");
          for (int deck = 0; deck < SphereLab.DECKS.size(); deck++)
            for (int x : new int[] {6, 25}) {
              var cargo =
                  (net.minecraft.world.Container)
                      level.getBlockEntity(landed.offset(x, SphereLab.DECKS.get(deck) + 1, 15));
              ctx.assertTrue(
                  cargo.getItem(0).getCount() == deck + 1,
                  "Room cargo must survive maximum-size travel");
            }
          ctx.assertTrue(
              player.getInventory().countItem(Items.EMERALD) == 11, "Crew inventory must survive");
          server.getPlayerList().remove(player);
          ctx.succeed();
        });
  }

  private static void door(
      net.minecraft.server.level.ServerLevel level, BlockPos pos, boolean open) {
    ((DoorBlock) level.getBlockState(pos).getBlock())
        .setOpen(null, level, level.getBlockState(pos), pos, open);
  }
}
