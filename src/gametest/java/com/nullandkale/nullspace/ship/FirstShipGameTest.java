package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** The exact starter layouts described in docs/first-ship-walkthrough.md. */
public final class FirstShipGameTest {
  @GameTest(environment = "nullspace:first_ship_tiny", maxTicks = 300)
  public void tinyCabinWorksWithoutBatterySuitScannerOrDockingBase(GameTestHelper ctx)
      throws Exception {
    verify(ctx, 3, 2100000);
  }

  @GameTest(environment = "nullspace:first_ship_room", maxTicks = 300)
  public void smallWalkableCabinWorksWithoutBatterySuitScannerOrDockingBase(GameTestHelper ctx)
      throws Exception {
    verify(ctx, 5, 2110000);
  }

  private static void verify(GameTestHelper ctx, int width, int coordinate) throws Exception {
    var level = ctx.getLevel();
    var server = level.getServer();
    var space = server.getLevel(TravelService.SPACE);
    var floor = new BlockPos(coordinate, 81, coordinate);
    int middle = width / 2;
    for (int x = -1; x <= width; x++)
      for (int z = -1; z <= width; z++)
        level.setBlock(
            floor.offset(x, -1, z),
            (x == -1 || z == -1 || x == width || z == width ? Blocks.COBBLESTONE : Blocks.DIRT)
                .defaultBlockState(),
            18);
    var padComputer = floor.offset(-1, 0, -1);
    level.setBlock(padComputer, PadComputers.BLOCK.defaultBlockState(), 18);
    var scan = PadComputers.inspect(level, padComputer);
    ctx.assertTrue(scan.valid(), "Player-built dirt pad and cobble ring are valid");
    String name = "First ship " + width;
    PadComputers.register(level, padComputer, scan.pad(), name);
    for (int y = 0; y < 4; y++)
      for (int x = 0; x < width; x++)
        for (int z = 0; z < width; z++)
          if (y == 0 || y == 3 || x == 0 || z == 0 || x == width - 1 || z == width - 1)
            level.setBlock(floor.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 18);
    var door = floor.offset(middle, 1, 0);
    var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
    level.setBlock(door, state, 18);
    level.setBlock(door.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
    var helm = floor.offset(middle, 1, width - 1);
    level.setBlock(
        helm,
        ShipBlocks.COMPUTER
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
        18);
    var air = floor.offset(width == 3 ? 2 : 0, 1, middle);
    level.setBlock(
        air,
        ShipBlocks.AIR_SUPPLY
            .defaultBlockState()
            .setValue(
                HorizontalDirectionalBlock.FACING, width == 3 ? Direction.WEST : Direction.EAST),
        18);
    var engine = floor.offset(width == 3 ? 0 : 1, 1, width == 3 ? 1 : width - 1);
    level.setBlock(
        engine,
        ShipBlocks.ENGINE
            .defaultBlockState()
            .setValue(
                HorizontalDirectionalBlock.FACING, width == 3 ? Direction.WEST : Direction.SOUTH),
        18);
    var generator = width == 3 ? floor.offset(1, 2, 2) : floor.offset(4, 1, 2);
    level.setBlock(
        generator,
        ShipBlocks.GENERATOR
            .defaultBlockState()
            .setValue(
                HorizontalDirectionalBlock.FACING, width == 3 ? Direction.NORTH : Direction.WEST),
        18);
    var window = width == 3 ? floor.offset(0, 2, 1) : floor.offset(2, 2, 4);
    level.setBlock(window, Blocks.GLASS.defaultBlockState(), 18);
    ((MachineEntity) level.getBlockEntity(generator)).setItem(3, new ItemStack(Items.COAL, 8));
    var player = ctx.makeMockServerPlayerInLevel();
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    player.teleportTo(floor.getX() + middle + .5, floor.getY() + 1, floor.getZ() + width - 1.5);
    var view = ShipControl.open(player, helm, "First ship check");
    ctx.assertTrue(view != null, "Normal controller interaction opens the unified controls");
    var ship = ShipStore.get(server).at(level, helm);
    ctx.assertTrue(
        ShipSystems.enabled(ship), "Normal controller interaction initializes finite air/power");
    ShipSystems.tick(level, ship);
    var inspected = ShipService.inspect(level, helm);
    ctx.assertTrue(
        inspected.scan().blocks().size() == (width == 3 ? 34 : 82),
        "Exact documented occupied block count");
    ctx.assertTrue(
        ShipSystems.breathable(level, ship, player.blockPosition().above()),
        "Standing room has real supplied air");
    ctx.assertTrue(
        ShipSystems.power(level, ship, PowerBus.System.ENGINES) > 0,
        "One stock generator powers propulsion without a battery");
    ctx.assertTrue(
        ShipControl.reason(player, ship, null, ShipControl.Action.LAUNCH).isEmpty(),
        "Launch button is ready with the default settings");
    int slot = 0;
    while (!TravelService.spaceSlotFree(server, slot)) slot++;
    var orbit =
        new ShipScan.Bounds(
            new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0),
            new BlockPos(slot * TravelService.SPACE_SPACING + 31, 127, 31));
    var ground = ShipStore.bounds(ship);
    for (int x = (orbit.min().getX() - 2) >> 4; x <= (orbit.max().getX() + 2) >> 4; x++)
      for (int z = -1; z <= 2; z++) {
        space.setChunkForced(x, z, true);
        space.getChunk(x, z);
      }
    for (int x = (ground.min().getX() - 2) >> 4; x <= (ground.max().getX() + 2) >> 4; x++)
      for (int z = (ground.min().getZ() - 2) >> 4; z <= (ground.max().getZ() + 2) >> 4; z++) {
        level.setChunkForced(x, z, true);
        level.getChunk(x, z);
      }
    int[] ticks = {0};
    boolean[] started = {false};
    ctx.onEachTick(
        () -> {
          if (started[0]
              || ++ticks[0] < 20
              || !ShipEntities.loaded(level, ground)
              || !ShipEntities.loaded(space, orbit)) return;
          started[0] = true;
          var result = TravelService.transfer(player, helm, "", ShipService.preflight(level, helm));
          ctx.assertTrue(
              result.outcome() == TravelService.Outcome.COMPLETED,
              "Minimal ship launches: " + result.message());
          var inSpace = ShipStore.get(server).repository().find(ship.getStringOr("id", ""));
          var orbitHelm = ShipStore.pos(inSpace, "controller");
          var returnPad =
              PadRegistry.read(server).stream()
                  .filter(p -> p.name().equals(name))
                  .findFirst()
                  .orElseThrow();
          var returned =
              TravelService.transfer(
                  player,
                  orbitHelm,
                  "id:" + returnPad.id(),
                  ShipService.preflight(space, orbitHelm));
          ctx.assertTrue(
              returned.outcome() == TravelService.Outcome.COMPLETED,
              "Same ship lands on its original pad: " + returned.message());
          ctx.assertTrue(
              player.level() == level && ShipStore.get(server).at(level, helm) != null,
              "Player and identity return together");
          ctx.assertTrue(
              level.getBlockState(air).is(ShipBlocks.AIR_SUPPLY), "Equipment survives round trip");
          server.getPlayerList().remove(player);
          ShipStore.get(server)
              .ships()
              .removeIf(
                  t ->
                      ((net.minecraft.nbt.CompoundTag) t)
                          .getStringOr("id", "")
                          .equals(ship.getStringOr("id", "")));
          for (int x = (orbit.min().getX() - 2) >> 4; x <= (orbit.max().getX() + 2) >> 4; x++)
            for (int z = -1; z <= 2; z++) space.setChunkForced(x, z, false);
          for (int x = (ground.min().getX() - 2) >> 4; x <= (ground.max().getX() + 2) >> 4; x++)
            for (int z = (ground.min().getZ() - 2) >> 4; z <= (ground.max().getZ() + 2) >> 4; z++)
              level.setChunkForced(x, z, false);
          ctx.succeed();
        });
  }
}
