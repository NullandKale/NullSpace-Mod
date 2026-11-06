package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.ship.ShipBlocks;
import com.nullandkale.nullspace.templates.VesselTemplate;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;

/** Compact, three-wing template showroom. The entire station stays inside one 32-block section. */
public final class ManufacturerStation {
  public static final BlockPos CORE = new BlockPos(14, 3, 14);
  public static final Map<String, BlockPos> ROOMS =
      Map.of(
          "Rivet workshop", new BlockPos(6, 3, 15),
          "Halcyon observation", new BlockPos(26, 3, 15),
          "Bastion operations", new BlockPos(15, 3, 26));

  public static StationBlueprint.Plan build() {
    var t = new VesselTemplate();
    var footprint = new LinkedHashSet<BlockPos>();
    VesselTemplate.rectangle(footprint, 11, 11, 20, 20);
    VesselTemplate.rectangle(footprint, 12, 1, 19, 10);
    VesselTemplate.rectangle(footprint, 1, 11, 10, 20);
    for (int x = 21; x <= 30; x++) {
      int trim = x >= 29 ? 2 : x >= 27 ? 1 : 0;
      VesselTemplate.rectangle(footprint, x, 11 + trim, x, 20 - trim);
    }
    VesselTemplate.rectangle(footprint, 11, 21, 20, 30);
    t.shell(footprint, 2, 7, Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_STONE);
    // Consistent detailing within each manufacturer's room, with different exterior masses.
    for (var e : t.finish(32, 32, 32).entrySet()) {
      var at = e.getKey();
      var material =
          at.getX() <= 10
              ? Blocks.STONE_BRICKS
              : at.getZ() >= 21 ? Blocks.POLISHED_DEEPSLATE : Blocks.QUARTZ_BLOCK;
      if (e.getValue().is(Blocks.QUARTZ_BLOCK)) t.state(at, material.defaultBlockState());
      if (e.getValue().is(Blocks.GLASS) && (at.getX() <= 10 || at.getZ() >= 21) && at.getY() != 4)
        t.state(at, material.defaultBlockState());
      if (at.getY() == 2)
        t.state(at, (at.getX() <= 10 ? Blocks.IRON_BLOCK : material).defaultBlockState());
    }
    for (int z = 11; z <= 20; z++)
      for (int y = 3; y <= 7; y++) {
        t.put(10, y, z, Blocks.STONE_BRICKS);
        t.put(21, y, z, Blocks.QUARTZ_BLOCK);
      }
    t.door(new BlockPos(10, 3, 15), Direction.WEST, Blocks.STONE_BRICKS);
    t.door(new BlockPos(21, 3, 15), Direction.EAST, Blocks.QUARTZ_BLOCK);
    t.bulkhead(21, 11, 20, 2, 7, 15, Blocks.POLISHED_DEEPSLATE);
    t.dock(new BlockPos(15, 3, 1), Direction.NORTH, Blocks.QUARTZ_BLOCK);
    t.dock(new BlockPos(15, 3, 30), Direction.SOUTH, Blocks.POLISHED_DEEPSLATE);
    t.machine(CORE, ShipBlocks.STATION_CORE, Direction.SOUTH);
    t.machine(new BlockPos(13, 3, 14), ShipBlocks.TERMINAL, Direction.SOUTH);
    t.machine(new BlockPos(17, 3, 5), ShipBlocks.AIR_SUPPLY, Direction.WEST);
    t.machine(new BlockPos(18, 3, 18), ShipBlocks.AIR_SUPPLY, Direction.WEST);
    // Rivet: receiving, real workshop/storage and accessible machinery along the back wall.
    t.put(3, 3, 13, Blocks.CRAFTING_TABLE);
    t.put(4, 3, 13, Blocks.FURNACE);
    for (int z : new int[] {13, 15, 17}) t.put(2, 3, z, Blocks.BARREL);
    t.machine(new BlockPos(3, 3, 18), ShipBlocks.GENERATOR, Direction.NORTH);
    t.machine(new BlockPos(4, 3, 18), ShipBlocks.GENERATOR, Direction.NORTH);
    t.machine(new BlockPos(5, 3, 18), ShipBlocks.BATTERY, Direction.NORTH);
    t.machine(new BlockPos(8, 3, 18), ShipBlocks.AIR_SUPPLY, Direction.NORTH);
    // Halcyon: swept glazed lounge, shared sensor station and a mounted camera.
    t.machine(new BlockPos(23, 3, 18), ShipBlocks.AIR_SUPPLY, Direction.NORTH);
    t.machine(new BlockPos(25, 3, 18), ShipBlocks.LOCAL_SCANNER, Direction.NORTH);
    t.machine(new BlockPos(27, 5, 18), ShipBlocks.CAMERA, Direction.NORTH);
    for (int x = 24; x <= 26; x++)
      t.state(
          new BlockPos(x, 3, 13),
          Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
    t.put(27, 3, 16, Blocks.BARREL);
    // Bastion: compartmentalized operations, stores and a raised armored roof profile.
    t.machine(new BlockPos(18, 3, 27), ShipBlocks.AIR_SUPPLY, Direction.WEST);
    t.machine(new BlockPos(12, 4, 24), ShipBlocks.MONITOR, Direction.EAST);
    t.machine(new BlockPos(12, 3, 24), ShipBlocks.TERMINAL, Direction.EAST);
    t.put(18, 3, 23, Blocks.BARREL);
    t.put(18, 3, 25, Blocks.BARREL);
    for (int x = 12; x <= 19; x++)
      for (int z = 23; z <= 28; z++) t.put(x, 9, z, Blocks.POLISHED_DEEPSLATE);
    for (var at :
        List.of(
            new BlockPos(15, 8, 6),
            new BlockPos(15, 8, 16),
            new BlockPos(5, 8, 15),
            new BlockPos(25, 8, 15),
            new BlockPos(15, 8, 25))) t.light(at.getX(), at.getY(), at.getZ());
    return new StationBlueprint.Plan(
        t.finish(32, 32, 32), CORE, List.of(new BlockPos(15, 2, 1), new BlockPos(15, 2, 30)));
  }

  public static void label(net.minecraft.server.level.ServerLevel level, BlockPos base) {
    com.nullandkale.nullspace.dev.ShipGallery.label(
        level,
        base.offset(6, 3, 12),
        net.minecraft.world.item.DyeColor.ORANGE,
        "RIVET WORKS",
        "Service module",
        "Workshop + stores",
        "Bolt scout");
    com.nullandkale.nullspace.dev.ShipGallery.label(
        level,
        base.offset(24, 3, 18),
        net.minecraft.world.item.DyeColor.CYAN,
        "HALCYON AEROSPACE",
        "Observation module",
        "Sensors + lounge",
        "Swift scout");
    com.nullandkale.nullspace.dev.ShipGallery.label(
        level,
        base.offset(16, 3, 23),
        net.minecraft.world.item.DyeColor.RED,
        "BASTION",
        "Operations module",
        "Bulkheads + stores",
        "Ward scout");
  }

  private ManufacturerStation() {}
}
