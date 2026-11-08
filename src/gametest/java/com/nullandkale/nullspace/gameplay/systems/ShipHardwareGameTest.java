package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenu;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.travel.TransferRecovery;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;

public final class ShipHardwareGameTest {
  @GameTest(environment = "nullspace:ship_hardware")
  public void equipmentReadingsSeparateUnmetDemandAndRoomAverageAndAuthorizePowerButtons(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var original = StationGameTest.fixture(level, new BlockPos(780000, 96, 780000), true);
    var ship = original.copy();
    var air = ship.getCompoundOrEmpty("systems").getIntArray("air").orElseThrow();
    for (int i = 0; i < air.length; i++) if (air[i] >= 0) air[i] = 500;
    var vent =
        ShipSystems.connected(level, original).stream()
            .filter(p -> level.getBlockState(p).is(ModBlocks.AIR_SUPPLY))
            .findFirst()
            .orElseThrow();
    var outlet =
        vent.relative(level.getBlockState(vent).getValue(HorizontalDirectionalBlock.FACING));
    var local = outlet.subtract(WorldStore.pos(ship, "base"));
    air[Atmosphere.index(local.getX(), local.getY(), local.getZ())] = 2500;
    ship.getCompoundOrEmpty("systems").putIntArray("air", air);
    WorldStore.get(server).ships().remove(original);
    WorldStore.get(server).ships().add(ship);
    var player = ctx.makeMockServerPlayerInLevel();
    try {
      player.teleportTo(
          level, vent.getX() + .5, vent.getY(), vent.getZ() + 1.5, Set.of(), 0, 0, true);
      int fuel = FuelSystem.available(level, ship);
      var airMenu =
          new MachineMenu(
              1, player.getInventory(), (MachineBlockEntity) level.getBlockEntity(vent));
      airMenu.broadcastChanges();
      ctx.assertTrue(
          airMenu.reading.getIntOr("oxygen", 0)
                  == ShipAir.roomAt(level, ship, outlet).oxygenPercent()
              && airMenu.reading.getIntOr("oxygen", 0) < 100,
          "Room air shows the whole room, not the overfilled vent cell");
      ctx.assertTrue(
          FuelSystem.available(level, ship) == fuel, "Reading equipment does not burn fuel");
      var generators =
          ShipSystems.connected(level, ship).stream()
              .filter(p -> level.getBlockState(p).is(ModBlocks.GENERATOR))
              .toList();
      for (var generator : generators) ShipSystems.setEnabled(level, ship, generator, false);
      var power = ShipSystems.powerDisplay(level, ship);
      ctx.assertTrue(
          power[0] == 0 && power[4] > 0, "Unmet demand stays visible when generation is disabled");
      for (var generator : generators) ShipSystems.setEnabled(level, ship, generator, true);
      var engine =
          ShipSystems.connected(level, ship).stream()
              .filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE))
              .findFirst()
              .orElseThrow();
      player.teleportTo(engine.getX() + .5, engine.getY(), engine.getZ() + 1.5);
      int before = EquipmentPanel.requestedAllocation(level, engine);
      var menu =
          new MachineMenu(
              2, player.getInventory(), (MachineBlockEntity) level.getBlockEntity(engine));
      menu.edit(player, 15, "-1");
      ctx.assertTrue(
          EquipmentPanel.requestedAllocation(level, engine) == before - 1,
          "Ordinary power buttons update the real allocation");
      var forged =
          new MachineMenu(
              3, player.getInventory(), (MachineBlockEntity) level.getBlockEntity(engine));
      forged.edit(player, 15, "999");
      ctx.assertTrue(
          EquipmentPanel.requestedAllocation(level, engine) == before - 1,
          "Forged power changes cannot exceed the allowed step");
      ship.putString("kind", "station");
      ship.putString("owner", UUID.randomUUID().toString());
      var visitor =
          new MachineMenu(
              4, player.getInventory(), (MachineBlockEntity) level.getBlockEntity(engine));
      visitor.edit(player, 15, "1");
      ctx.assertTrue(
          EquipmentPanel.requestedAllocation(level, engine) == before - 1,
          "Visitors cannot change station power allocations");
      ctx.succeed();
    } finally {
      server.getPlayerList().remove(player);
      WorldStore.get(server).ships().remove(ship);
    }
  }

  @GameTest(environment = "nullspace:ship_hardware")
  public void modulesImproveRealOutputAndThrustWithoutFreeChargeOrInspectionFuelUse(
      GameTestHelper ctx) throws java.io.IOException {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(220, 6, 220));
    var helm = ShipTestFixture.build(level, base);
    PadComputers.register(
        level,
        base.offset(-1, 1, 3),
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7),
        "Module performance");
    var scanner = base.offset(2, 3, 1);
    var battery = base.offset(4, 3, 1);
    level.setBlock(scanner, ModBlocks.LOCAL_SCANNER.defaultBlockState(), 18);
    level.setBlock(battery, ModBlocks.BATTERY.defaultBlockState(), 18);
    var ship = ShipTopology.resolve(level, helm);
    SystemsUpgrade.upgrade(level, ship);
    ShipSystems.tick(level, ship);
    int initialOutput = ShipSystems.powerStats(level, ship)[0];
    int initialThrust = NavigationService.propulsion(level, ship);
    int charge = ShipSystems.deviceEnergy(ship, battery);
    for (var at : ShipSystems.connected(level, ship))
      if (level.getBlockEntity(at) instanceof MachineBlockEntity machine && machine.upgrades()) {
        // Tier III needs three modules including an exotic one.
        machine.setItem(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
        machine.setItem(1, new ItemStack(ModBlocks.ADVANCED_UPGRADE));
        machine.setItem(2, new ItemStack(ModBlocks.EXOTIC_UPGRADE));
      }
    ctx.assertTrue(
        ShipSystems.deviceEnergy(ship, battery) == charge,
        "Installing capacity never creates charge");
    int fuel = FuelSystem.available(level, ship);
    ctx.assertTrue(
        NavigationService.propulsion(level, ship) > initialThrust,
        "Modules increase delivered propulsion");
    ctx.assertTrue(
        ShipSensors.installed(level, ship, true) && ShipSensors.range(level, ship) == 8192,
        "An upgraded powered Scanner unlocks distant targets and real contact range");
    ShipSystems.powerStats(level, ship);
    ctx.assertTrue(
        FuelSystem.available(level, ship) == fuel,
        "Inspecting upgraded power, propulsion and sensors consumes no fuel");
    ShipSystems.tick(level, ship);
    int actualOutput = ShipSystems.powerStats(level, ship)[0];
    ctx.assertTrue(
        actualOutput > initialOutput && ShipSystems.deviceEnergy(ship, battery) > charge,
        "Real output and battery charging increase");
    ctx.assertTrue(
        fuel - FuelSystem.available(level, ship) < actualOutput,
        "Generator modules reduce fuel per electricity produced");
    ctx.assertTrue(
        FuelSystem.travelCost(level, ship, 1000) < 1000,
        "Engine modules reduce the actual travel fuel quote");
    WorldStore.get(level.getServer()).ships().remove(ship);
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_hardware")
  public void batteryModulesChangeCapacityWithoutCreatingChargeOrDiscardingIt(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(120, 6, 120));
    var helm = ShipTestFixture.build(level, base);
    PadComputers.register(
        level,
        base.offset(-1, 1, 3),
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7),
        "Battery modules");
    var at = base.offset(2, 3, 6);
    level.setBlock(at, ModBlocks.BATTERY.defaultBlockState(), 18);
    var battery = (MachineBlockEntity) level.getBlockEntity(at);
    battery.setItem(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
    battery.setItem(1, new ItemStack(ModBlocks.ADVANCED_UPGRADE));
    var ship = ShipTopology.resolve(level, helm);
    SystemsUpgrade.upgrade(level, ship);
    var saved =
        ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
            .map(t -> (CompoundTag) t)
            .filter(d -> d.getStringOr("type", "").equals("nullspace:battery"))
            .findFirst()
            .orElseThrow();
    saved.putInt("energy", 2500);
    ctx.assertTrue(
        FuelSystem.capacity(level, at) == 3600 && !battery.canRemoveUpgrade(),
        "Removing a module cannot discard charge above the reduced capacity");
    saved.putInt("energy", 2399);
    ctx.assertTrue(
        battery.canRemoveUpgrade(), "Discharging to the reduced capacity permits module removal");
    battery.removeItem(0, 1);
    ctx.assertTrue(
        FuelSystem.capacity(level, at) == 2400 && ShipSystems.deviceEnergy(ship, at) == 2399,
        "Removing a module preserves actual charge");
    battery.setItem(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
    battery.setItem(2, new ItemStack(ModBlocks.EXOTIC_UPGRADE));
    ctx.assertTrue(
        FuelSystem.capacity(level, at) == 4800 && ShipSystems.deviceEnergy(ship, at) == 2399,
        "Three modules with an exotic one reach Tier III without refilling the battery");
    WorldStore.get(level.getServer()).ships().remove(ship);
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_hardware")
  public void rectangularMonitorsShareStableCameraIdentityAndOldTilesKeepHullSeals(
      GameTestHelper ctx) throws java.io.IOException {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(160, 6, 160));
    var helm = ShipTestFixture.build(level, base);
    PadComputers.register(
        level,
        base.offset(-1, 1, 3),
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7),
        "Monitor wall");
    var camera = base.offset(5, 3, 5);
    level.setBlock(camera, ModBlocks.CAMERA.defaultBlockState(), 18);
    var mounted =
        ModBlocks.MONITOR
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
    for (int x = 2; x <= 4; x++)
      for (int y = 3; y <= 4; y++) level.setBlock(base.offset(x, y, 5), mounted, 18);
    var ship = ShipTopology.resolve(level, helm);
    SystemsUpgrade.upgrade(level, ship);
    var first = base.offset(2, 3, 5);
    var tiles = MonitorWall.tiles(level, first);
    ctx.assertTrue(tiles.size() == 6, "Six adjacent tiles form a 3 x 2 rectangle");
    MonitorWall.select(level, ship, first, Long.toString(camera.asLong()));
    String expected = "id:" + ShipSystems.deviceId(ship, camera);
    ctx.assertTrue(
        tiles.stream()
            .allMatch(p -> ((MachineBlockEntity) level.getBlockEntity(p)).camera.equals(expected)),
        "Feed selection applies to every tile using the camera's persistent device identity");
    ctx.assertTrue(
        !level.getBlockState(first).isCollisionShapeFullBlock(level, first),
        "New wall monitor is physically thin");
    WorldStore.get(level.getServer()).ships().remove(ship);
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_hardware")
  public void furnaceFuelReservesItsContainerAndQueriesSpendNothing(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var at = ctx.absolutePos(new BlockPos(3, 4, 3));
    level.setBlock(at, ModBlocks.GENERATOR.defaultBlockState(), 18);
    var generator = (MachineBlockEntity) level.getBlockEntity(at);
    generator.setItem(3, new ItemStack(Items.LAVA_BUCKET));
    generator.setItem(9, new ItemStack(Items.BUCKET, 64));
    var before = generator.saveWithoutMetadata(level.registryAccess());
    ctx.assertTrue(
        generator.available() == 0 && generator.draw(1) == 0,
        "Blocked bucket return prevents fuel consumption");
    ctx.assertTrue(
        generator.saveWithoutMetadata(level.registryAccess()).equals(before),
        "Readiness queries and rejected burn preserve every slot");
    generator.setItem(9, ItemStack.EMPTY);
    int energy = generator.duration(generator.getItem(3)) * 8;
    ctx.assertTrue(
        energy > 0 && generator.draw(energy - 1) == energy - 1,
        "Uses the server furnace fuel value");
    ctx.assertTrue(
        generator.burn() == 1
            && generator.getItem(3).isEmpty()
            && generator.getItem(9).is(Items.BUCKET),
        "Consumes exactly one lava bucket and preserves its container");
    ctx.assertTrue(
        generator.draw(1) == 1 && generator.draw(1) == 0,
        "Partial burn is conserved to the final unit");
    for (var side : Direction.values()) {
      ctx.assertTrue(
          !generator.canPlaceItemThroughFace(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE), side),
          "Hoppers never insert upgrades");
      ctx.assertTrue(
          !generator.canTakeItemThroughFace(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE), side),
          "Hoppers never remove upgrades");
    }
    ctx.assertTrue(
        !generator.canPlaceItem(3, new ItemStack(Items.DIAMOND)),
        "Invalid furnace fuel is rejected");
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_hardware", maxTicks = 100)
  public void standardHoppersInsertFuelAndExtractReturns(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var at = ctx.absolutePos(new BlockPos(12, 5, 12));
    level.setBlock(at, ModBlocks.GENERATOR.defaultBlockState(), 18);
    level.setBlock(
        at.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN),
        18);
    level.setBlock(
        at.below(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST),
        18);
    var input = (HopperBlockEntity) level.getBlockEntity(at.above());
    input.setItem(0, new ItemStack(Items.COAL, 2));
    input.setItem(1, new ItemStack(Items.DIAMOND, 3));
    var generator = (MachineBlockEntity) level.getBlockEntity(at);
    generator.setItem(9, new ItemStack(Items.BUCKET));
    ctx.runAfterDelay(
        32,
        () -> {
          int coal = 0;
          for (int i = 3; i < 9; i++)
            if (generator.getItem(i).is(Items.COAL)) coal += generator.getItem(i).getCount();
          var output = (HopperBlockEntity) level.getBlockEntity(at.below());
          int buckets = 0;
          for (int i = 0; i < output.getContainerSize(); i++)
            if (output.getItem(i).is(Items.BUCKET)) buckets += output.getItem(i).getCount();
          ctx.assertTrue(
              coal == 2 && input.getItem(1).getCount() == 3,
              "Hoppers insert real fuel and retain invalid items");
          ctx.assertTrue(
              generator.getItem(9).isEmpty() && buckets == 1,
              "Bottom hopper extracts exactly one return");
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:ship_hardware")
  public void machineInventoryBurnAndUpgradeSlotsSurviveStructureTransfer(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var at = ctx.absolutePos(new BlockPos(24, 5, 24));
    level.setBlock(at, ModBlocks.GENERATOR.defaultBlockState(), 18);
    var original = (MachineBlockEntity) level.getBlockEntity(at);
    original.setItem(3, new ItemStack(Items.COAL, 8));
    original.setItem(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
    original.draw(137);
    original.label = "Power plant";
    var template =
        new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
    template.fillFromWorld(level, at, new Vec3i(1, 1, 1), false, List.of());
    var target = at.offset(4, 0, 0);
    TransferRecovery.place(level, template, target);
    var copy = (MachineBlockEntity) level.getBlockEntity(target);
    ctx.assertTrue(
        copy.burn() == original.burn()
            && copy.getItem(3).getCount() == 7
            && copy.tier() == 1
            && copy.label.equals("Power plant"),
        "Transfer preserves partial burn, fuel, actual module items and names");
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_hardware")
  public void compactWoodenAndIronPortsUseOneBaseAndKeepMatedCabinsSealed(GameTestHelper ctx)
      throws java.io.IOException {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(80, 6, 80));
    var helm = ShipTestFixture.build(level, base);
    var bounds =
        new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
    PadComputers.register(level, base.offset(-1, 1, 3), bounds, "Compact hardware");
    var ship = ShipTopology.resolve(level, helm);
    SystemsUpgrade.upgrade(level, ship);
    var door = base.offset(3, 3, 0);
    var port = door.below();
    level.setBlock(port, ModBlocks.DOCKING_BASE.defaultBlockState(), 18);
    for (var block : List.of(Blocks.OAK_DOOR, Blocks.IRON_DOOR)) {
      level.setBlock(
          door, block.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH), 18);
      level.setBlock(
          door.above(),
          block
              .defaultBlockState()
              .setValue(DoorBlock.FACING, Direction.NORTH)
              .setValue(
                  DoorBlock.HALF,
                  net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),
          18);
      var found = DockingPorts.readPort(level, ship, port, true);
      ctx.assertTrue(
          found.outer().equals(found.inner()) && !found.anchors().isEmpty(),
          "A standard door and one base need no separate chamber hardware");
      var d = new CompoundTag();
      d.putString("source", ship.getStringOr("id", ""));
      d.putString("state", "CLAMPED");
      ship.put("dock", d);
      ShipDoors.setOpen(level, door, true);
      ctx.assertTrue(
          DockingPorts.sealedMate(level, door) && HullScan.seals(level, door),
          "Captured open doorway remains a pressure seal");
      ship.remove("dock");
      ctx.assertTrue(!HullScan.seals(level, door), "An open undocked doorway exposes real vacuum");
      ShipDoors.setOpen(level, door, false);
    }
    WorldStore.get(level.getServer()).ships().remove(ship);
    ctx.succeed();
  }
}
