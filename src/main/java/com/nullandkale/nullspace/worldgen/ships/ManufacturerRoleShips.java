package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.blueprint.ShipRooms;
import com.nullandkale.nullspace.worldgen.blueprint.VesselTemplate;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Larger reference roles retain each manufacturer's hull language and have real usable rooms. */
public final class ManufacturerRoleShips {
  private static final Set<String> IDS =
      Set.of(
          "rivet_hauler",
          "rivet_ranger",
          "halcyon_courier",
          "halcyon_horizon",
          "bastion_tender",
          "bastion_pioneer");

  public static boolean contains(String id) {
    return IDS.contains(id);
  }

  public static boolean explorer(String id) {
    return Set.of("rivet_ranger", "halcyon_horizon", "bastion_pioneer").contains(id);
  }

  public static String manufacturer(String id) {
    return id.startsWith("rivet_")
        ? "Rivet Works"
        : id.startsWith("halcyon_") ? "Halcyon Aerospace" : "Bastion Shipbuilding";
  }

  public static BlockPos controller(String id) {
    if (explorer(id)) return new BlockPos(id.startsWith("rivet_") ? 8 : 9, 6, 8);
    return id.startsWith("rivet_")
        ? new BlockPos(7, 1, 5)
        : id.startsWith("halcyon_") ? new BlockPos(10, 1, 7) : new BlockPos(9, 1, 6);
  }

  public static Map<String, BlockPos> rooms(String id) {
    var result = new LinkedHashMap<String, BlockPos>();
    for (var room : ShipRooms.rooms(id)) result.put(room.name(), room.walk());
    return Collections.unmodifiableMap(result);
  }

  public static Map<BlockPos, BlockState> build(ShipBlueprints.Preset p) {
    String id = p.id();
    boolean explorer = explorer(id),
        rivet = id.startsWith("rivet_"),
        halcyon = id.startsWith("halcyon_");
    Block hull =
        rivet ? Blocks.STONE_BRICKS : halcyon ? Blocks.QUARTZ_BLOCK : Blocks.POLISHED_DEEPSLATE;
    Block deck = rivet ? Blocks.POLISHED_ANDESITE : Blocks.SMOOTH_STONE;
    var t = new VesselTemplate();
    var footprint = new LinkedHashSet<BlockPos>();
    for (int z = 2; z <= 28; z++) {
      int left, right;
      if (rivet) {
        left =
            z <= 8
                ? (explorer ? 4 : 5)
                : z <= (explorer ? 14 : 11)
                    ? (explorer ? 7 : 9)
                    : z <= (explorer ? 26 : 23) ? 2 : explorer ? 8 : 3;
        right =
            z <= 8
                ? (explorer ? 18 : 17)
                : z <= (explorer ? 14 : 11)
                    ? (explorer ? 17 : 16)
                    : z <= (explorer ? 26 : 23) ? 22 : explorer ? 16 : 21;
      } else {
        int half =
            halcyon
                ? z <= 3 ? 2 : z <= 6 ? z : z <= (explorer ? 20 : 23) ? (explorer ? 10 : 11) : 6
                : z <= 3
                    ? 4
                    : z <= 7 ? 6 : z <= (explorer ? 18 : 24) ? 10 : explorer && z <= 26 ? 8 : 7;
        left = 12 - half;
        right = 12 + half;
      }
      VesselTemplate.rectangle(footprint, left, z, right, z);
    }
    var upper = new LinkedHashSet<BlockPos>();
    if (explorer)
      for (int z = 5; z <= 25; z++) {
        int left = rivet && z <= 12 ? 7 : halcyon && z <= 7 ? 9 : 8;
        int right = rivet && z <= 12 ? 14 : halcyon && z <= 7 ? 15 : 16;
        VesselTemplate.rectangle(upper, left, z, right, z);
      }
    java.util.function.ToIntFunction<BlockPos> roof =
        at -> {
          int x = at.getX(), z = at.getZ(), side = Math.abs(x - 12);
          if (explorer && upper.contains(at)) return 5;
          if (rivet) return z <= 8 ? 5 : z <= 11 ? 4 : x <= 5 || x >= 19 ? 4 : explorer ? 4 : 6;
          if (halcyon)
            return Math.min(
                z <= 4 || z >= 25 ? 3 : z <= 10 ? 5 : explorer ? 4 : 6,
                side >= 9 ? 2 : side >= 7 ? 3 : 7);
          return Math.min(
              z <= 3 || z >= 26 ? 3 : z <= 10 ? 5 : explorer ? 4 : 6, side >= 9 ? 3 : 7);
        };
    t.shell(footprint, 0, roof, hull, deck);
    if (explorer)
      t.shell(
          upper,
          5,
          at -> {
            int z = at.getZ();
            return z <= 13 ? (halcyon ? 5 : 6) : 4;
          },
          hull,
          deck);
    // Rivet uses short service windows; Bastion leaves solid armor around command and cargo.
    for (var e : t.finish(p.width(), p.height(), p.depth()).entrySet())
      if (e.getValue().is(Blocks.GLASS) && !halcyon) {
        var at = e.getKey();
        boolean window =
            (at.getY() == 2 || at.getY() == 7)
                && (rivet
                    ? at.getZ() < 9 || at.getZ() >= 16 && at.getZ() <= 20
                    : at.getZ() <= 10 || at.getZ() >= 19 && at.getZ() <= 22);
        if (!window) t.state(at, hull.defaultBlockState());
      }
    if (explorer) {
      partition(t, footprint, roof, 15, 0, hull);
      partition(t, upper, at -> at.getZ() <= 13 ? (halcyon ? 5 : 6) : 4, 14, 5, hull);
    } else {
      partition(t, footprint, roof, 10, 0, hull);
      partition(t, footprint, roof, 24, 0, hull);
    }
    t.dock(new BlockPos(12, 1, 2), Direction.NORTH, hull);
    t.state(
        new BlockPos(12, 0, 1),
        Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
    t.machine(new BlockPos(12, 4, 1), ModBlocks.CAMERA, Direction.NORTH);
    t.machine(controller(id), ModBlocks.COMPUTER, Direction.EAST);
    if (explorer) {
      t.machine(controller(id).above(), ModBlocks.MONITOR, Direction.EAST);
      machine(t, 10, 6, 10, ModBlocks.TERMINAL);
      machine(t, 14, 6, 9, ModBlocks.LOCAL_SCANNER);
      air(t, 13, 6, 7);
      air(t, 14, 6, 21);
      air(t, 14, 1, 9);
      air(t, 14, 1, 25);
      machine(t, 10, 1, 25, ModBlocks.GENERATOR);
      machine(t, 11, 1, 25, ModBlocks.GENERATOR);
      machine(t, 10, 1, 26, ModBlocks.BATTERY);
      machine(t, 11, 1, 26, ModBlocks.BATTERY);
      t.put(10, 1, 11, Blocks.CRAFTING_TABLE);
      t.put(10, 1, 12, Blocks.FURNACE);
      t.put(9, 1, 13, Blocks.BARREL);
      t.put(16, 1, 12, Blocks.BARREL);
      t.put(10, 6, 17, Blocks.CRAFTING_TABLE);
      t.put(9, 6, 21, Blocks.BARREL);
      t.state(
          new BlockPos(10, 6, 19),
          (halcyon ? Blocks.QUARTZ_STAIRS : Blocks.OAK_STAIRS)
              .defaultBlockState()
              .setValue(StairBlock.FACING, Direction.SOUTH));
      // Supported, open stairwell. Keep the lower pressure floor and clear real headroom.
      for (int step = 1; step <= 5; step++) {
        int z = 17 + step;
        for (int y = 1; y < step; y++) t.put(15, y, z, hull);
        t.state(
            new BlockPos(15, step, z),
            Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        for (int y = 1; y <= 3; y++) t.clear(new BlockPos(15, step + y, z));
      }
      t.light(12, 11 - (halcyon ? 1 : 0), 9);
      t.light(12, 9, 21);
      t.light(12, 5, 11);
      t.light(12, 5, 24);
    } else {
      int monitorX = rivet ? 6 : 7, monitorZ = rivet ? 5 : halcyon ? 7 : 6;
      machine(t, monitorX, 2, monitorZ, ModBlocks.MONITOR);
      machine(t, 10, 1, 8, ModBlocks.TERMINAL);
      machine(t, 14, 1, 7, ModBlocks.LOCAL_SCANNER);
      air(t, 14, 1, 5);
      air(t, 8, 1, 14);
      air(t, 15, 1, 26);
      machine(t, 10, 1, 26, ModBlocks.GENERATOR);
      machine(t, 11, 1, 26, ModBlocks.GENERATOR);
      machine(t, 10, 1, 27, ModBlocks.BATTERY);
      for (int x : new int[] {6, 18})
        for (int z = 13; z <= 21; z += 2)
          for (int y = 1; y <= 2; y++) t.put(x, y, z, Blocks.BARREL);
      t.put(9, 1, 12, Blocks.CRAFTING_TABLE);
      t.put(9, 1, 13, Blocks.FURNACE);
      t.light(12, roof.applyAsInt(new BlockPos(12, 0, 6)), 6);
      t.light(12, roof.applyAsInt(new BlockPos(12, 0, 17)), 17);
      t.light(12, roof.applyAsInt(new BlockPos(12, 0, 26)), 26);
    }
    for (int x : new int[] {8, 16})
      t.machine(new BlockPos(x, 1, 28), ModBlocks.ENGINE, Direction.SOUTH);
    if (!rivet && !halcyon)
      for (int z = 11; z <= 17; z++) {
        t.put(1, 0, z, hull);
        t.put(23, 0, z, hull);
      }
    ShipRooms.furnishRole(t, id, roof, hull);
    return t.finish(p.width(), p.height(), p.depth());
  }

  private static void partition(
      VesselTemplate t,
      Set<BlockPos> footprint,
      java.util.function.ToIntFunction<BlockPos> roof,
      int z,
      int floor,
      Block hull) {
    for (var at : footprint)
      if (at.getZ() == z)
        for (int y = 1; y < roof.applyAsInt(at); y++) t.put(at.getX(), floor + y, z, hull);
    t.door(new BlockPos(12, floor + 1, z), Direction.NORTH, hull);
  }

  private static void air(VesselTemplate t, int x, int y, int z) {
    t.machine(new BlockPos(x, y, z), ModBlocks.AIR_SUPPLY, Direction.WEST);
  }

  private static void machine(VesselTemplate t, int x, int y, int z, Block block) {
    t.machine(new BlockPos(x, y, z), block, Direction.EAST);
  }

  private ManufacturerRoleShips() {}
}
