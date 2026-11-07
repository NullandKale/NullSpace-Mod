package com.nullandkale.nullspace.worldgen.blueprint;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.ships.ManufacturerRoleShips;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Local room landmarks shared by interiors, encounter supplies and walking checks. */
public final class ShipRooms {
  public enum Use {
    BRIDGE,
    ENGINEERING,
    CARGO,
    WORKSHOP,
    GALLEY,
    CREW,
    LAB,
    INFIRMARY
  }

  public record Room(String name, Use use, BlockPos walk, BlockPos supplies) {}

  private static Room room(String name, Use use, int x, int y, int z, BlockPos supplies) {
    return new Room(name, use, new BlockPos(x, y, z), supplies);
  }

  public static List<Room> rooms(String id) {
    if (ManufacturerRoleShips.contains(id)) {
      boolean rivet = id.startsWith("rivet_"), halcyon = id.startsWith("halcyon_");
      if (ManufacturerRoleShips.explorer(id))
        return List.of(
            room("bridge", Use.BRIDGE, 12, 6, 9, null),
            room("workshop", Use.WORKSHOP, 12, 1, 11, new BlockPos(9, 1, 13)),
            room("engineering", Use.ENGINEERING, 12, 1, 25, new BlockPos(9, 1, 26)),
            room(
                rivet ? "crew bunks" : halcyon ? "crew cabin" : "crew quarters",
                Use.CREW,
                10,
                6,
                22,
                new BlockPos(9, 6, 23)),
            room("galley", Use.GALLEY, 10, 6, 17, new BlockPos(9, 6, 18)),
            room(
                rivet ? "reclamation bay" : halcyon ? "survey lab" : "sickbay",
                rivet ? Use.CARGO : halcyon ? Use.LAB : Use.INFIRMARY,
                9,
                1,
                20,
                new BlockPos(8, 1, 22)));
      return List.of(
          room("bridge", Use.BRIDGE, 12, 1, 6, null),
          room("cargo hold", Use.CARGO, 12, 1, 17, new BlockPos(10, 1, 17)),
          room("engineering", Use.ENGINEERING, 12, 1, 26, new BlockPos(13, 1, 27)),
          room(
              rivet ? "crew mess" : halcyon ? "galley" : "provisions",
              Use.GALLEY,
              7,
              1,
              14,
              new BlockPos(5, 1, 16)),
          room(
              rivet ? "repair shop" : halcyon ? "courier cabin" : "maintenance bay",
              halcyon ? Use.CREW : Use.WORKSHOP,
              17,
              1,
              20,
              new BlockPos(19, 1, 22)));
    }
    return switch (id) {
      case "rivet_bolt" ->
          List.of(
              room("bridge", Use.BRIDGE, 7, 1, 5, null),
              room("engineering", Use.ENGINEERING, 8, 1, 17, new BlockPos(4, 1, 19)),
              room("repair bench", Use.WORKSHOP, 4, 1, 15, new BlockPos(3, 1, 16)),
              room("mess nook", Use.GALLEY, 7, 1, 14, new BlockPos(6, 1, 15)));
      case "halcyon_swift" ->
          List.of(
              room("bridge", Use.BRIDGE, 9, 1, 8, null),
              room("engineering", Use.ENGINEERING, 9, 1, 20, new BlockPos(8, 1, 20)),
              room("survey bench", Use.LAB, 7, 1, 15, new BlockPos(6, 1, 16)),
              room("galley nook", Use.GALLEY, 11, 1, 15, new BlockPos(12, 1, 15)));
      case "bastion_ward" ->
          List.of(
              room("bridge", Use.BRIDGE, 9, 1, 7, null),
              room("engineering", Use.ENGINEERING, 9, 1, 19, new BlockPos(6, 1, 19)),
              room("maintenance bench", Use.WORKSHOP, 12, 1, 14, new BlockPos(13, 1, 14)),
              room("provisions nook", Use.GALLEY, 11, 1, 16, new BlockPos(12, 1, 16)));
      default -> List.of();
    };
  }

  public static void furnishRole(
      VesselTemplate t, String id, java.util.function.ToIntFunction<BlockPos> roof, Block hull) {
    boolean rivet = id.startsWith("rivet_"), halcyon = id.startsWith("halcyon_");
    if (ManufacturerRoleShips.explorer(id)) {
      t.clear(new BlockPos(10, 6, 17));
      t.clear(new BlockPos(9, 6, 21));
      // The starboard stair and its landing stay open. New cabins open onto the same aisle.
      enclosure(t, 7, 16, 11, 23, 0, roof, hull, new BlockPos(11, 1, 20));
      enclosure(t, 8, 15, 12, 19, 5, p -> 4, hull, new BlockPos(12, 6, 17));
      enclosure(t, 8, 19, 12, 24, 5, p -> 4, hull, new BlockPos(12, 6, 21));
      // The side aisle joins the bridge directly instead of running into the galley wall.
      for (int y = 6; y <= 8; y++) t.put(12, y, 14, hull);
      t.door(new BlockPos(14, 6, 14), Direction.NORTH, hull);
      t.clear(new BlockPos(14, 6, 21)); // This aisle shares air with engineering via the stairwell.
      vent(t, 10, 1, 18);
      vent(t, 11, 6, 16);
      vent(t, 11, 6, 23);
      t.put(9, 6, 16, Blocks.CRAFTING_TABLE);
      t.put(9, 6, 17, Blocks.FURNACE);
      t.put(11, 6, 18, halcyon ? Blocks.QUARTZ_STAIRS : Blocks.OAK_STAIRS);
      bed(
          t,
          new BlockPos(9, 6, 20),
          Direction.SOUTH,
          halcyon ? Blocks.BED.white() : Blocks.BED.red());
      if (rivet) {
        t.put(8, 1, 17, Blocks.SMITHING_TABLE);
        t.put(8, 1, 18, Blocks.BLAST_FURNACE);
        t.put(8, 1, 21, Blocks.BARREL);
      } else if (halcyon) {
        t.put(8, 1, 17, Blocks.CARTOGRAPHY_TABLE);
        t.put(8, 1, 18, Blocks.BOOKSHELF);
        t.put(8, 1, 21, Blocks.LECTERN);
      } else {
        bed(t, new BlockPos(8, 1, 17), Direction.SOUTH, Blocks.BED.white());
        t.put(8, 1, 21, Blocks.CAULDRON);
      }
      t.light(10, 9, 17);
      t.light(10, 9, 22);
      t.light(14, 9, 18);
      t.light(14, 9, 23);
      t.light(9, 5, 20);
    } else {
      for (int x : new int[] {6, 18})
        for (int z = 13; z <= 21; z += 2)
          for (int y = 1; y <= 2; y++) t.clear(new BlockPos(x, y, z));
      // Real side rooms leave a three-block central cargo route and twenty reachable barrels.
      enclosure(t, 4, 12, 9, 17, 0, roof, hull, new BlockPos(9, 1, 14));
      enclosure(t, 15, 17, 20, 23, 0, roof, hull, new BlockPos(15, 1, 20));
      t.clear(new BlockPos(8, 1, 14));
      vent(t, 8, 1, 13);
      vent(t, 14, 1, 12);
      vent(t, 19, 1, 21);
      t.put(5, 1, 13, Blocks.FURNACE);
      t.put(5, 1, 14, Blocks.CRAFTING_TABLE);
      t.put(8, 1, 16, halcyon ? Blocks.QUARTZ_STAIRS : Blocks.OAK_STAIRS);
      if (halcyon) {
        bed(t, new BlockPos(18, 1, 18), Direction.SOUTH, Blocks.BED.white());
        t.put(16, 1, 22, Blocks.BOOKSHELF);
      } else {
        t.put(16, 1, 18, Blocks.SMITHING_TABLE);
        t.put(16, 1, 19, rivet ? Blocks.BLAST_FURNACE : Blocks.FURNACE);
        t.put(16, 1, 22, Blocks.CRAFTING_TABLE);
      }
      for (int x : new int[] {10, 14})
        for (int z = 13; z <= 21; z += 2)
          for (int y = 1; y <= 2; y++) t.put(x, y, z, Blocks.BARREL);
      t.light(7, roof.applyAsInt(new BlockPos(7, 0, 14)), 14);
      t.light(17, roof.applyAsInt(new BlockPos(17, 0, 20)), 20);
    }
    for (var room : rooms(id))
      if (room.supplies != null) t.state(room.supplies, Blocks.BARREL.defaultBlockState());
  }

  public static void furnishScout(VesselTemplate t, String id) {
    if (id.equals("rivet_bolt")) {
      t.put(3, 1, 15, Blocks.FURNACE);
      bed(t, new BlockPos(6, 1, 17), Direction.SOUTH, Blocks.BED.red());
    } else if (id.equals("halcyon_swift")) {
      t.put(6, 1, 14, Blocks.CARTOGRAPHY_TABLE);
      t.put(12, 1, 16, Blocks.FURNACE);
      bed(t, new BlockPos(8, 1, 17), Direction.SOUTH, Blocks.BED.white());
    } else {
      t.put(13, 1, 15, Blocks.FURNACE);
      bed(t, new BlockPos(8, 1, 17), Direction.NORTH, Blocks.BED.red());
    }
    for (var room : rooms(id))
      if (room.supplies != null) t.state(room.supplies, Blocks.BARREL.defaultBlockState());
  }

  private static void enclosure(
      VesselTemplate t,
      int x0,
      int z0,
      int x1,
      int z1,
      int floor,
      java.util.function.ToIntFunction<BlockPos> roof,
      Block hull,
      BlockPos door) {
    for (int x = x0; x <= x1; x++)
      for (int z = z0; z <= z1; z++)
        for (int y = 1; y < roof.applyAsInt(new BlockPos(x, 0, z)); y++)
          if (x == x0 || x == x1 || z == z0 || z == z1) t.put(x, floor + y, z, hull);
    t.door(door, Direction.EAST, hull);
  }

  private static void vent(VesselTemplate t, int x, int y, int z) {
    t.machine(new BlockPos(x, y, z), ModBlocks.AIR_SUPPLY, Direction.WEST);
  }

  private static void bed(VesselTemplate t, BlockPos foot, Direction facing, Block block) {
    var state = block.defaultBlockState().setValue(BedBlock.FACING, facing);
    t.state(foot, state.setValue(BedBlock.PART, BedPart.FOOT));
    t.state(foot.relative(facing), state.setValue(BedBlock.PART, BedPart.HEAD));
  }

  private ShipRooms() {}
}
