package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.ship.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/** Separate additive annex: a maximum-size ship and an equally large empty landing destination. */
public final class SphereLab {
  public static final List<Integer> DECKS = List.of(5, 10, 15, 20, 25);
  public static final int WIDTH = 80, DEPTH = 46;
  private static final String[][] ROOMS = {
    {"Cargo hold", "Workshop"},
    {"Crew quarters", "Medical bay"},
    {"Navigation", "Chart room"},
    {"Laboratory", "Life support bay"},
    {"Observation", "Reserve stores"}
  };

  public record Annex(BlockPos origin, String sphereName, String blankName) {
    public BlockPos base() {
      return origin.offset(4, 0, 10);
    }

    public BlockPos blank() {
      return origin.offset(44, 0, 10);
    }

    public BlockPos entry() {
      return base().offset(15, 1, -2);
    }

    public BlockPos controller() {
      return SphereLab.controller(base());
    }
  }

  public static BlockPos controller(BlockPos base) {
    return base.offset(16, 16, 24);
  }

  public static PadScanner.Pad footprint(BlockPos b) {
    return new PadScanner.Pad(b.getX(), b.getX() + 31, b.getY(), b.getZ(), b.getZ() + 31);
  }

  public static Annex ensure(MinecraftServer server, ShipGallery.Gallery gallery)
      throws java.io.IOException {
    Path marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-sphere-lab-v1.nbt");
    if (Files.exists(marker)) {
      var data = ShipStore.read(marker);
      if (data.getIntOr("version", 0) != 1 || !data.getBooleanOr("complete", false))
        throw new IllegalStateException(
            "Sphere lab creation was interrupted or has an unknown version; existing blocks are"
                + " preserved");
      return new Annex(
          ShipStore.pos(data, "origin"),
          data.getStringOr("sphereName", "Meridian"),
          data.getStringOr("blankName", "XL Blank"));
    }
    var level = server.overworld();
    var pads = PadRegistry.read(server);
    Annex annex = null;
    for (int attempt = 0; attempt < 16 && annex == null; attempt++) {
      int x = gallery.origin().getX() + ShipGallery.WIDTH + 8 + (attempt % 4) * 96,
          z = gallery.origin().getZ() + (attempt / 4) * 64;
      var area = new PadScanner.Pad(x, x + WIDTH - 1, 0, z, z + DEPTH - 1);
      if (pads.stream()
          .anyMatch(
              p ->
                  p.dimension().equals(level.dimension().identifier().toString())
                      && area.overlaps(p.bounds()))) continue;
      for (int cx = x >> 4; cx <= (x + WIDTH - 1) >> 4; cx++)
        for (int cz = z >> 4; cz <= (z + DEPTH - 1) >> 4; cz++) level.getChunk(cx, cz);
      int y = gallery.origin().getY();
      for (int dx = 0; dx < WIDTH; dx++)
        for (int dz = 0; dz < DEPTH; dz++)
          y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz) + 4);
      if (y + 36 > level.getMaxY()) continue;
      annex =
          new Annex(
              new BlockPos(x, y, z),
              unique(pads, gallery.prefix() + " 21 Meridian"),
              unique(pads, gallery.prefix() + " 22 XL Blank"));
    }
    if (annex == null)
      throw new IllegalStateException("No clear sphere annex site in the bounded search area");
    var data = new CompoundTag();
    data.putInt("version", 1);
    data.putLong("origin", annex.origin.asLong());
    data.putString("sphereName", annex.sphereName);
    data.putString("blankName", annex.blankName);
    data.putBoolean("complete", false);
    ShipStore.write(marker, data);
    for (int x = 0; x < WIDTH; x++)
      for (int z = 0; z < DEPTH; z++) {
        var at = annex.origin.offset(x, 0, z);
        level.setBlock(
            at,
            ((x % 5 == 0 && z % 5 == 0) ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray())
                .defaultBlockState(),
            18);
        if (x == 0 || x == WIDTH - 1 || z == 0 || z == DEPTH - 1)
          level.setBlock(at.above(), Blocks.GLASS.defaultBlockState(), 18);
      }
    buildPad(level, annex.base(), annex.sphereName);
    buildPad(level, annex.blank(), annex.blankName);
    buildShip(level, annex.base());
    ShipGallery.label(
        level,
        annex.base().offset(8, 1, -3),
        DyeColor.BLUE,
        "21 MERIDIAN",
        "32 x 32 x 32",
        "5 decks / 10 rooms",
        "Bridge: deck 3");
    ShipGallery.label(
        level,
        annex.blank().offset(8, 1, -3),
        DyeColor.BLUE,
        "22 XL BLANK",
        "32 x 32 pad",
        "For large ships",
        "Build height: 32");
    ShipService.inspect(level, annex.controller());
    connect(level, gallery, annex);
    server.saveEverything(true, true, true);
    data.putBoolean("complete", true);
    ShipStore.write(marker, data);
    return annex;
  }

  private static String unique(List<PadRegistry.Pad> pads, String name) {
    String result = name;
    int suffix = 2;
    while (true) {
      String candidate = result;
      if (pads.stream().noneMatch(p -> p.name().equalsIgnoreCase(candidate))) return result;
      result = name + " " + suffix++;
    }
  }

  /** Test fixtures share the production geometry and real registry validation. */
  public static void buildPad(ServerLevel level, BlockPos b, String name)
      throws java.io.IOException {
    for (int x = -1; x <= 32; x++)
      for (int z = -1; z <= 32; z++)
        level.setBlock(
            b.offset(x, 0, z),
            (x == -1 || x == 32 || z == -1 || z == 32 ? Blocks.COBBLESTONE : Blocks.GRASS_BLOCK)
                .defaultBlockState(),
            18);
    level.setBlock(
        b.offset(-1, 1, 15),
        PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.EAST),
        18);
    String result = PadComputers.register(level, b.offset(-1, 1, 15), footprint(b), name, false);
    if (!result.startsWith("Registered ")) throw new IllegalStateException(result);
  }

  private static boolean volume(int x, int y, int z) {
    if (x < 0 || x > 31 || y < 1 || y > 32 || z < 0 || z > 31) return false;
    double dx = x - 15.5, dy = y - 16.5, dz = z - 15.5;
    return dx * dx + dy * dy + dz * dz <= 256 || (x >= 13 && x <= 18 && y <= 9 && z >= 1 && z <= 9);
  }

  private static boolean shell(int x, int y, int z) {
    if (!volume(x, y, z)) return false;
    double dx = x - 15.5, dy = y - 16.5, dz = z - 15.5;
    // A radial two-block shell stays face-connected at voxel steps and polar caps.
    boolean interior =
        dx * dx + dy * dy + dz * dz < 196
            || (x >= 14 && x <= 17 && y >= 2 && y <= 8 && z >= 2 && z <= 8);
    return !interior;
  }

  public static void buildShip(ServerLevel level, BlockPos b) {
    for (int x = 0; x < 32; x++)
      for (int y = 1; y <= 32; y++)
        for (int z = 0; z < 32; z++)
          if (shell(x, y, z)) {
            boolean window = y % 5 == 3 && x != 15 && x != 16 && z != 15 && z != 16;
            level.setBlock(
                b.offset(x, y, z),
                (window ? Blocks.GLASS : Blocks.QUARTZ_BLOCK).defaultBlockState(),
                18);
          }
    for (int floor : DECKS)
      for (int x = 0; x < 32; x++)
        for (int z = 0; z < 32; z++)
          if (volume(x, floor, z)) {
            boolean light = x % 5 == 2 && z % 5 == 2 && !shell(x, floor, z);
            level.setBlock(
                b.offset(x, floor, z),
                (light ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightBlue()).defaultBlockState(),
                18);
          }
    // Explicit airtight stair airlock, including where its walls meet the round hull.
    for (int x = 13; x <= 18; x++)
      for (int y = 1; y <= 9; y++)
        for (int z = 1; z <= 9; z++)
          if (x == 13 || x == 18 || y == 1 || y == 9 || z == 1 || z == 9)
            level.setBlock(b.offset(x, y, z), Blocks.QUARTZ_BLOCK.defaultBlockState(), 18);
    // Open the first deck around the ramp, keeping the chamber's shell intact.
    for (int x = 15; x <= 16; x++)
      for (int z = 2; z <= 7; z++)
        level.setBlock(b.offset(x, 5, z), Blocks.AIR.defaultBlockState(), 18);
    for (int x = 15; x <= 16; x++) {
      level.setBlock(
          b.offset(x, 1, 0),
          Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH),
          18);
      for (int step = 0; step < 4; step++) stair(level, b, x, 2 + step, 4 + step);
    }
    door(level, b.offset(15, 2, 1), Direction.NORTH);
    door(level, b.offset(15, 6, 9), Direction.NORTH);
    for (int flight = 0; flight < 4; flight++) {
      int floor = DECKS.get(flight), x = flight % 2 == 0 ? 12 : 18;
      for (int dx = 0; dx < 2; dx++)
        for (int z = 11; z <= 16; z++)
          level.setBlock(b.offset(x + dx, floor + 5, z), Blocks.AIR.defaultBlockState(), 18);
      for (int dx = 0; dx < 2; dx++)
        for (int step = 0; step < 5; step++) {
          for (int support = floor + 1; support < floor + 1 + step; support++)
            level.setBlock(
                b.offset(x + dx, support, 12 + step), Blocks.QUARTZ_BLOCK.defaultBlockState(), 18);
          level.setBlock(
              b.offset(x + dx, floor + 1 + step, 12 + step),
              Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH),
              18);
        }
      var pane =
          Blocks.GLASS_PANE
              .defaultBlockState()
              .setValue(IronBarsBlock.NORTH, true)
              .setValue(IronBarsBlock.SOUTH, true);
      for (int side : new int[] {x - 1, x + 2})
        for (int z = 12; z <= 16; z++) level.setBlock(b.offset(side, floor + 6, z), pane, 18);
    }
    for (int deck = 0; deck < DECKS.size(); deck++) {
      int floor = DECKS.get(deck), top = deck == 4 ? 32 : floor + 4;
      for (int x : new int[] {9, 22})
        for (int y = floor + 1; y <= top; y++)
          for (int z = 0; z < 32; z++)
            if (volume(x, y, z))
              level.setBlock(b.offset(x, y, z), Blocks.CONCRETE.blue().defaultBlockState(), 18);
      door(level, b.offset(9, floor + 1, 15), Direction.WEST);
      door(level, b.offset(22, floor + 1, 15), Direction.EAST);
      sign(
          level,
          b.offset(10, floor + 3, 15),
          Direction.EAST,
          "DECK " + (deck + 1),
          ROOMS[deck][0],
          "Close after use",
          "Pressure boundary");
      sign(
          level,
          b.offset(21, floor + 3, 15),
          Direction.WEST,
          "DECK " + (deck + 1),
          ROOMS[deck][1],
          "Close after use",
          "Pressure boundary");
      for (int x : new int[] {6, 25}) {
        var at = b.offset(x, floor + 1, 15);
        level.setBlock(at, Blocks.BARREL.defaultBlockState(), 18);
        ((net.minecraft.world.Container) level.getBlockEntity(at))
            .setItem(0, new ItemStack(x == 6 ? Items.IRON_INGOT : Items.DIAMOND, deck + 1));
      }
      ShipGallery.label(
          level,
          b.offset(15, floor + 1, 20),
          DyeColor.BLUE,
          "DECK " + (deck + 1),
          deck == 2 ? "BRIDGE" : deck == 4 ? "OBSERVATION" : "STAIR HALL",
          "Stairs alternate",
          "Close all doors");
    }
    for (int x : new int[] {0, 31})
      for (int z : new int[] {14, 17})
        level.setBlock(
            b.offset(x, 16, z),
            ShipBlocks.ENGINE
                .defaultBlockState()
                .setValue(
                    HorizontalDirectionalBlock.FACING, x == 0 ? Direction.WEST : Direction.EAST),
            18);
    level.setBlock(
        controller(b),
        ShipBlocks.COMPUTER
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
        18);
    level.setBlock(b.offset(10, 6, 20), ShipBlocks.GENERATOR.defaultBlockState(), 18);
    com.nullandkale.nullspace.systems.FuelSystem.provision(level, b.offset(10, 6, 20), 64);
    level.setBlock(
        b.offset(10, 6, 21),
        ShipBlocks.AIR_SUPPLY
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH),
        18);
  }

  private static void stair(ServerLevel level, BlockPos b, int x, int y, int z) {
    for (int below = 2; below < y; below++)
      level.setBlock(b.offset(x, below, z), Blocks.QUARTZ_BLOCK.defaultBlockState(), 18);
    level.setBlock(
        b.offset(x, y, z),
        Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH),
        18);
  }

  private static void door(ServerLevel level, BlockPos at, Direction facing) {
    var state =
        Blocks.OAK_DOOR
            .defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.OPEN, false);
    level.setBlock(at, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 18);
    level.setBlock(at.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
  }

  private static void sign(ServerLevel level, BlockPos at, Direction facing, String... lines) {
    level.setBlock(
        at, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), 18);
    var text = SignText.EMPTY.asMutable().setColor(DyeColor.WHITE).setTextGlowing(true);
    for (int i = 0; i < 4; i++) text.setLine(i, Component.literal(lines[i]));
    var sign = (SignBlockEntity) level.getBlockEntity(at);
    sign.setText(text.asImmutable(), SignTextSlot.FRONT);
    sign.setWaxed(true);
  }

  private static void connect(ServerLevel level, ShipGallery.Gallery g, Annex a) {
    if (a.origin.getY() != g.origin().getY()
        || a.origin.getZ() != g.origin().getZ()
        || a.origin.getX() != g.origin().getX() + ShipGallery.WIDTH + 8) return;
    // Open only the original railing; preserve edited exits or any occupied bridge volume.
    for (int z = 3; z <= 6; z++)
      if (!level.getBlockState(g.origin().offset(ShipGallery.WIDTH - 1, 1, z)).is(Blocks.GLASS))
        return;
    for (int x = ShipGallery.WIDTH; x < ShipGallery.WIDTH + 8; x++)
      for (int z = 3; z <= 6; z++)
        for (int y = 0; y <= 2; y++)
          if (!level.getBlockState(g.origin().offset(x, y, z)).isAir()) return;
    for (int x = ShipGallery.WIDTH; x < ShipGallery.WIDTH + 8; x++)
      for (int z = 3; z <= 6; z++) {
        level.setBlock(
            g.origin().offset(x, 0, z), Blocks.CONCRETE.lightGray().defaultBlockState(), 18);
        level.setBlock(
            g.origin().offset(x, 1, z),
            (z == 3 || z == 6 ? Blocks.GLASS : Blocks.AIR).defaultBlockState(),
            18);
      }
    for (int x : new int[] {ShipGallery.WIDTH - 1, ShipGallery.WIDTH + 8})
      for (int z = 4; z <= 5; z++)
        level.setBlock(g.origin().offset(x, 1, z), Blocks.AIR.defaultBlockState(), 18);
  }

  private SphereLab() {}
}
