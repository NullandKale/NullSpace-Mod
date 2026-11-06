package com.nullandkale.nullspace.shipyard;

import com.nullandkale.nullspace.pad.PadScanner;
import com.nullandkale.nullspace.ship.ShipBlocks;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Versioned, deterministic hulls shared by the beacon and the physical showroom. */
public final class ShipBlueprints {
  public record Preset(
      String id,
      String name,
      int width,
      int height,
      int depth,
      int decks,
      String description,
      String color,
      boolean armed,
      boolean cargo) {
    public String size() {
      return width + " x " + height + " x " + depth;
    }

    public BlockPos helm() {
      if (ManufacturerShips.contains(id)) return ManufacturerShips.controller(id);
      return new BlockPos(width - 3, (decks - 1) * 5 + 1, width == 9 ? 6 : 3);
    }

    public String manufacturer() {
      return ManufacturerShips.manufacturer(id);
    }

    public String role() {
      if (ManufacturerRoleShips.contains(id))
        return ManufacturerRoleShips.explorer(id) ? "Explorer" : "Freighter";
      return id.equals("mule")
          ? "Freighter"
          : id.equals("atlas") || id.equals("wayfarer")
              ? "Explorer"
              : id.equals("aegis") ? "Patrol" : "Scout";
    }
  }

  public static final List<Preset> PRESETS =
      List.of(
          new Preset(
              "sparrow",
              "Sparrow",
              9,
              5,
              11,
              1,
              "Compact starter with a sealed cabin and workshop.",
              "white",
              false,
              false),
          new Preset(
              "skylark",
              "Skylark",
              11,
              5,
              15,
              1,
              "Windowed scout with twin engines and survey equipment.",
              "cyan",
              false,
              false),
          new Preset(
              "mule",
              "Mule",
              13,
              5,
              17,
              1,
              "Freighter with a separate hold and empty cargo barrels.",
              "orange",
              false,
              true),
          new Preset(
              "wayfarer",
              "Wayfarer",
              15,
              10,
              21,
              2,
              "Two-deck explorer with a bridge, rooms and internal stairs.",
              "blue",
              false,
              false),
          new Preset(
              "aegis",
              "Aegis",
              17,
              10,
              23,
              2,
              "Two-deck patrol ship with twin engines and a cannon.",
              "red",
              true,
              false),
          new Preset(
              "atlas",
              "Atlas",
              23,
              15,
              29,
              3,
              "Three-deck expedition ship with workshops and cargo rooms.",
              "green",
              false,
              true),
          new Preset(
              "rivet_bolt",
              "Rivet Bolt",
              17,
              7,
              25,
              1,
              "Rivet Works scout: offset bridge, narrow spine and broad service pods.",
              "orange",
              false,
              true),
          new Preset(
              "halcyon_swift",
              "Halcyon Swift",
              19,
              7,
              27,
              1,
              "Halcyon Aerospace scout: tapered bow, swept shoulders and observation bridge.",
              "cyan",
              false,
              false),
          new Preset(
              "bastion_ward",
              "Bastion Ward",
              19,
              7,
              25,
              1,
              "Bastion Shipbuilding scout: angular shoulders, recessed bridge and bulkhead.",
              "red",
              false,
              false),
          new Preset(
              "rivet_hauler",
              "Rivet Hauler",
              25,
              8,
              31,
              1,
              "Rivet Works freighter: cargo racks, crew mess and a dedicated repair shop.",
              "orange",
              false,
              true),
          new Preset(
              "rivet_ranger",
              "Rivet Ranger",
              25,
              12,
              31,
              2,
              "Rivet Works explorer: reclamation bay, workshop, galley and crew berth.",
              "orange",
              false,
              false),
          new Preset(
              "halcyon_courier",
              "Halcyon Courier",
              25,
              8,
              31,
              1,
              "Halcyon Aerospace freighter: glazed cargo hold, galley and courier cabin.",
              "cyan",
              false,
              true),
          new Preset(
              "halcyon_horizon",
              "Halcyon Horizon",
              25,
              12,
              31,
              2,
              "Halcyon Aerospace explorer: observation bridge, survey lab, galley and crew cabin.",
              "cyan",
              false,
              false),
          new Preset(
              "bastion_tender",
              "Bastion Tender",
              25,
              8,
              31,
              1,
              "Bastion Shipbuilding freighter: protected hold, provisions and maintenance bay.",
              "red",
              false,
              true),
          new Preset(
              "bastion_pioneer",
              "Bastion Pioneer",
              25,
              12,
              31,
              2,
              "Bastion Shipbuilding explorer: raised command, workshop, sickbay and crew quarters.",
              "red",
              false,
              false));

  public static int airDemand(Preset preset) {
    return Math.max(
        4,
        Math.min(
            12,
            (int) blocks(preset).values().stream().filter(s -> s.is(ShipBlocks.AIR_SUPPLY)).count()
                * 2));
  }

  public static Preset get(String id) {
    return PRESETS.stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Choose a ship from the beacon catalog."));
  }

  /** Sizes are width/height/depth; never rotate a hull or silently use a different pad layer. */
  public static BlockPos origin(Preset ship, PadScanner.Pad pad, int height) {
    if (ship.width() > pad.width() || ship.depth() > pad.depth() || ship.height() > height)
      throw new IllegalArgumentException(
          ship.name()
              + " needs "
              + ship.width()
              + " x "
              + ship.depth()
              + " floor space and "
              + ship.height()
              + " blocks of height. This pad has "
              + pad.width()
              + " x "
              + pad.depth()
              + " and "
              + height
              + " blocks of height.");
    return new BlockPos(
        pad.minX() + (pad.width() - ship.width()) / 2,
        pad.y() + 1,
        pad.minZ() + (pad.depth() - ship.depth()) / 2);
  }

  public static Map<BlockPos, BlockState> blocks(Preset p) {
    if (ManufacturerShips.contains(p.id())) return ManufacturerShips.build(p);
    var out = new LinkedHashMap<BlockPos, BlockState>();
    Block hull =
        switch (p.color()) {
          case "cyan" -> Blocks.CONCRETE.cyan();
          case "orange" -> Blocks.CONCRETE.orange();
          case "blue" -> Blocks.CONCRETE.blue();
          case "red" -> Blocks.CONCRETE.red();
          case "green" -> Blocks.CONCRETE.green();
          default -> Blocks.CONCRETE.white();
        };
    int w = p.width(), h = p.height(), d = p.depth(), middle = w / 2;
    for (int x = 1; x < w - 1; x++)
      for (int z = 2; z < d - 1; z++)
        for (int y = 0; y < h; y++) {
          boolean wall = x == 1 || x == w - 2 || z == 2 || z == d - 2;
          boolean deck = y % 5 == 0 || y == h - 1;
          if (!wall && !deck) continue;
          Block material = deck ? Blocks.OAK_PLANKS : hull;
          if (wall && (y % 5 == 2 || y % 5 == 3) && z < d - 3) material = Blocks.GLASS;
          put(out, x, y, z, material);
        }
    for (int deck = 0; deck < p.decks(); deck++) {
      int floor = deck * 5;
      if (d >= 17) {
        for (int x = 2; x < w - 2; x++)
          for (int y = 1; y <= 4 && floor + y < h - 1; y++) put(out, x, floor + y, d / 2, hull);
        door(out, middle, floor + 1, d / 2);
      }
      put(out, 2, floor + 1, 3, Blocks.CRAFTING_TABLE);
      put(out, w - 3, floor + 1, d - 4, Blocks.BARREL);
      put(out, middle, Math.min(h - 1, floor + 4), 4, Blocks.SEA_LANTERN);
      if (p.cargo())
        for (int z = d / 2 + 2; z < d - 3; z += 2) {
          put(out, 2, floor + 1, z, Blocks.BARREL);
          put(out, w - 3, floor + 1, z, Blocks.BARREL);
        }
    }
    for (int deck = 0; deck < p.decks() - 1; deck++)
      for (int step = 1; step <= 5; step++) {
        int y = deck * 5 + step, z = d - 11 + step;
        for (int clear = 1; clear <= 3; clear++) out.remove(new BlockPos(2, y + clear, z));
        out.put(
            new BlockPos(2, y, z),
            Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
      }
    // Full-block frames at the main door are required for launch and pressure sealing.
    for (int y = 1; y <= 3; y++) {
      put(out, middle - 1, y, 2, hull);
      put(out, middle + 1, y, 2, hull);
    }
    put(out, middle, 3, 2, hull);
    door(out, middle, 1, 2);
    out.put(
        new BlockPos(middle, 0, 1),
        Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
    machine(out, p.helm(), ShipBlocks.COMPUTER, Direction.WEST);
    machine(out, new BlockPos(1, 1, d - 4), ShipBlocks.GENERATOR, Direction.EAST);
    machine(out, new BlockPos(w - 2, 1, d - 3), ShipBlocks.AIR_SUPPLY, Direction.WEST);
    machine(out, new BlockPos(1, 1, d - 5), ShipBlocks.BATTERY, Direction.EAST);
    machine(out, new BlockPos(w - 2, 1, 5), ShipBlocks.TERMINAL, Direction.WEST);
    machine(out, new BlockPos(1, 2, d - 4), ShipBlocks.LOCAL_SCANNER, Direction.EAST);
    machine(out, new BlockPos(2, h - 2, d - 4), ShipBlocks.CAMERA, Direction.EAST);
    machine(
        out, new BlockPos(w - 3, (p.decks() - 1) * 5 + 2, 3), ShipBlocks.MONITOR, Direction.WEST);
    if (p.id().equals("sparrow"))
      machine(out, new BlockPos(middle, 1, d - 2), ShipBlocks.ENGINE, Direction.SOUTH);
    else {
      machine(out, new BlockPos(2, 1, d - 2), ShipBlocks.ENGINE, Direction.SOUTH);
      machine(out, new BlockPos(w - 3, 1, d - 2), ShipBlocks.ENGINE, Direction.SOUTH);
    }
    if (p.armed()) machine(out, new BlockPos(middle - 2, 1, 1), ShipBlocks.WEAPON, Direction.NORTH);
    com.nullandkale.nullspace.station.StationBlueprint.compact(
        out, new BlockPos(middle, 1, 1), Direction.NORTH);
    machine(out, new BlockPos(w - 2, 1, d - 5), ShipBlocks.GENERATOR, Direction.WEST);
    out.entrySet().removeIf(entry -> entry.getValue().isAir());
    for (var at : out.keySet())
      if (at.getX() < 1
          || at.getY() < 0
          || at.getZ() < 1
          || at.getX() >= w - 1
          || at.getY() >= h
          || at.getZ() >= d - 1)
        throw new IllegalStateException("Blueprint exceeds its declared bounds: " + p.id());
    return Collections.unmodifiableMap(out);
  }

  private static void put(Map<BlockPos, BlockState> out, int x, int y, int z, Block block) {
    out.put(new BlockPos(x, y, z), block.defaultBlockState());
  }

  private static void machine(
      Map<BlockPos, BlockState> out, BlockPos at, Block block, Direction facing) {
    var state = block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
    if (block == ShipBlocks.CAMERA || block == ShipBlocks.MONITOR)
      state = state.setValue(ShipBlocks.InventoryMachine.LEGACY_HULL, false);
    out.put(at, state);
  }

  private static void door(Map<BlockPos, BlockState> out, int x, int y, int z) {
    var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
    out.put(new BlockPos(x, y, z), state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
    out.put(new BlockPos(x, y + 1, z), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
  }

  private ShipBlueprints() {}
}
