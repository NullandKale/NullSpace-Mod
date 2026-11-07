package com.nullandkale.nullspace.shipyard;

import com.nullandkale.nullspace.ship.ShipBlocks;
import com.nullandkale.nullspace.templates.VesselTemplate;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** First review set: three scouts with distinct footprints, rooms and equipment arrangements. */
public final class ManufacturerShips {
  public static boolean contains(String id) {
    return Set.of("rivet_bolt", "halcyon_swift", "bastion_ward").contains(id)
        || ManufacturerRoleShips.contains(id);
  }

  public static String manufacturer(String id) {
    if (ManufacturerRoleShips.contains(id)) return ManufacturerRoleShips.manufacturer(id);
    return switch (id) {
      case "rivet_bolt" -> "Rivet Works";
      case "halcyon_swift" -> "Halcyon Aerospace";
      case "bastion_ward" -> "Bastion Shipbuilding";
      default -> "Original designs";
    };
  }

  public static BlockPos controller(String id) {
    if (ManufacturerRoleShips.contains(id)) return ManufacturerRoleShips.controller(id);
    return switch (id) {
      case "rivet_bolt" -> new BlockPos(4, 1, 5);
      case "halcyon_swift" -> new BlockPos(6, 1, 7);
      case "bastion_ward" -> new BlockPos(7, 1, 6);
      default -> throw new IllegalArgumentException("Unknown manufacturer scout");
    };
  }

  public static Map<BlockPos, BlockState> build(ShipBlueprints.Preset preset) {
    if (ManufacturerRoleShips.contains(preset.id())) return ManufacturerRoleShips.build(preset);
    var t = new VesselTemplate();
    var footprint = new LinkedHashSet<BlockPos>();
    String id = preset.id();
    int doorX, divider, left, right, engineZ;
    Block hull, deck;
    if (id.equals("rivet_bolt")) {
      VesselTemplate.rectangle(footprint, 3, 2, 11, 7);
      VesselTemplate.rectangle(footprint, 5, 8, 11, 12);
      VesselTemplate.rectangle(footprint, 2, 13, 14, 20);
      VesselTemplate.rectangle(footprint, 5, 21, 11, 21);
      doorX = 7;
      divider = 10;
      left = 5;
      right = 11;
      engineZ = 20;
      hull = Blocks.STONE_BRICKS;
      deck = Blocks.POLISHED_ANDESITE;
    } else if (id.equals("halcyon_swift")) {
      for (int z = 2; z <= 24; z++) {
        int half =
            z == 2 ? 1 : z <= 4 ? 2 : z <= 7 ? 4 : z <= 16 ? 6 : z <= 20 ? 4 : z <= 23 ? 3 : 2;
        VesselTemplate.rectangle(footprint, 9 - half, z, 9 + half, z);
      }
      doorX = 9;
      divider = 13;
      left = 3;
      right = 15;
      engineZ = 24;
      hull = Blocks.QUARTZ_BLOCK;
      deck = Blocks.SMOOTH_STONE;
    } else {
      for (int z = 2; z <= 22; z++) {
        int half = z <= 4 ? 3 : z <= 8 ? 5 : z <= 15 ? 7 : z <= 20 ? 5 : 4;
        VesselTemplate.rectangle(footprint, 9 - half, z, 9 + half, z);
      }
      doorX = 9;
      divider = 12;
      left = 2;
      right = 16;
      engineZ = 22;
      hull = Blocks.POLISHED_DEEPSLATE;
      deck = Blocks.SMOOTH_STONE;
    }
    java.util.function.ToIntFunction<BlockPos> ceiling =
        at -> {
          int x = at.getX(), z = at.getZ(), side = Math.abs(x - 9);
          if (id.equals("rivet_bolt")) {
            if (z >= 3 && z <= 6 && x >= 4 && x <= 9) return 6;
            if (z >= 8 && z <= 12) return 3;
            if (z >= 13 && (x <= 4 || x >= 12)) return 3;
            return 4;
          }
          if (id.equals("halcyon_swift")) {
            int roof = z <= 5 || z >= 21 ? 3 : z <= 12 ? 5 : 4;
            return Math.min(roof, side >= 5 ? 2 : side == 4 ? 3 : 6);
          }
          if (x >= 6 && x <= 12 && z >= 6 && z <= 10) return 6;
          int roof = z <= 4 || z >= 21 ? 3 : 4;
          return Math.min(roof, side >= 6 ? 2 : side == 5 ? 3 : 6);
        };
    t.shell(footprint, 0, ceiling, hull, deck);
    for (var e : t.finish(preset.width(), preset.height(), preset.depth()).entrySet()) {
      if (!e.getValue().is(Blocks.GLASS) || id.equals("halcyon_swift")) continue;
      var at = e.getKey();
      boolean window =
          at.getY() == 2
              && (id.equals("rivet_bolt")
                  ? at.getZ() <= 7 || at.getZ() >= 14 && at.getZ() <= 17
                  : at.getZ() <= 10 || at.getZ() >= 17 && at.getZ() <= 19);
      if (!window) t.state(at, hull.defaultBlockState());
    }
    for (int x = left; x <= right; x++)
      for (int y = 1; y < ceiling.applyAsInt(new BlockPos(x, 0, divider)); y++)
        t.put(x, y, divider, hull);
    t.door(new BlockPos(doorX, 1, divider), Direction.NORTH, hull);
    t.dock(new BlockPos(doorX, 1, 2), Direction.NORTH, hull);
    t.state(
        new BlockPos(doorX, 0, 1),
        Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
    t.machine(controller(id), ShipBlocks.COMPUTER, Direction.EAST);
    t.machine(new BlockPos(doorX, 4, 1), ShipBlocks.CAMERA, Direction.NORTH);
    if (id.equals("rivet_bolt")) {
      machine(t, 4, 1, 4, ShipBlocks.TERMINAL);
      machine(t, 10, 1, 4, ShipBlocks.LOCAL_SCANNER);
      machine(t, 4, 2, 5, ShipBlocks.MONITOR);
      machine(t, 10, 1, 6, ShipBlocks.AIR_SUPPLY);
      machine(t, 10, 1, 16, ShipBlocks.AIR_SUPPLY);
      machine(t, 3, 1, 17, ShipBlocks.GENERATOR);
      machine(t, 4, 1, 17, ShipBlocks.GENERATOR);
      machine(t, 4, 1, 18, ShipBlocks.BATTERY);
      t.put(3, 1, 14, Blocks.CRAFTING_TABLE);
      for (int z : new int[] {14, 16, 18}) t.put(13, 1, z, Blocks.BARREL);
      t.machine(new BlockPos(3, 1, engineZ), ShipBlocks.ENGINE, Direction.SOUTH);
      t.machine(new BlockPos(13, 1, engineZ), ShipBlocks.ENGINE, Direction.SOUTH);
      t.light(7, ceiling.applyAsInt(new BlockPos(7, 0, 5)), 5);
      t.light(8, ceiling.applyAsInt(new BlockPos(8, 0, 16)), 16);
    } else if (id.equals("halcyon_swift")) {
      machine(t, 6, 1, 9, ShipBlocks.TERMINAL);
      machine(t, 12, 1, 9, ShipBlocks.LOCAL_SCANNER);
      machine(t, 6, 2, 7, ShipBlocks.MONITOR);
      machine(t, 12, 1, 11, ShipBlocks.AIR_SUPPLY);
      machine(t, 11, 1, 19, ShipBlocks.AIR_SUPPLY);
      machine(t, 6, 1, 20, ShipBlocks.GENERATOR);
      machine(t, 7, 1, 20, ShipBlocks.GENERATOR);
      machine(t, 7, 1, 21, ShipBlocks.BATTERY);
      t.put(6, 1, 15, Blocks.CRAFTING_TABLE);
      t.put(12, 1, 15, Blocks.BARREL);
      t.state(
          new BlockPos(7, 1, 6),
          Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
      t.machine(new BlockPos(8, 1, engineZ), ShipBlocks.ENGINE, Direction.SOUTH);
      t.machine(new BlockPos(10, 1, engineZ), ShipBlocks.ENGINE, Direction.SOUTH);
      t.light(9, ceiling.applyAsInt(new BlockPos(9, 0, 8)), 8);
      t.light(9, ceiling.applyAsInt(new BlockPos(9, 0, 19)), 19);
    } else {
      machine(t, 5, 1, 7, ShipBlocks.TERMINAL);
      machine(t, 13, 1, 7, ShipBlocks.LOCAL_SCANNER);
      machine(t, 5, 2, 6, ShipBlocks.MONITOR);
      machine(t, 12, 1, 9, ShipBlocks.AIR_SUPPLY);
      machine(t, 12, 1, 17, ShipBlocks.AIR_SUPPLY);
      machine(t, 5, 1, 18, ShipBlocks.GENERATOR);
      machine(t, 6, 1, 18, ShipBlocks.GENERATOR);
      machine(t, 5, 1, 19, ShipBlocks.BATTERY);
      t.put(5, 1, 14, Blocks.CRAFTING_TABLE);
      t.put(13, 1, 14, Blocks.BARREL);
      t.machine(new BlockPos(6, 1, engineZ), ShipBlocks.ENGINE, Direction.SOUTH);
      t.machine(new BlockPos(12, 1, engineZ), ShipBlocks.ENGINE, Direction.SOUTH);
      for (int z = 10; z <= 14; z++) {
        t.put(1, 0, z, hull);
        t.put(17, 0, z, hull);
      }
      t.light(9, ceiling.applyAsInt(new BlockPos(9, 0, 7)), 7);
      t.light(9, ceiling.applyAsInt(new BlockPos(9, 0, 17)), 17);
    }
    ShipRooms.furnishScout(t, id);
    return t.finish(preset.width(), preset.height(), preset.depth());
  }

  private static void machine(VesselTemplate t, int x, int y, int z, Block block) {
    // Air outlets face the central aisle; screens mount on the wall behind their east-facing face.
    t.machine(
        new BlockPos(x, y, z),
        block,
        block == ShipBlocks.AIR_SUPPLY ? Direction.WEST : Direction.EAST);
  }

  private ManufacturerShips() {}
}
