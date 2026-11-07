package com.nullandkale.nullspace.worldgen.sites;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * P8 village space ports: about one Terra village in three gets a registered landing pad with a Pad
 * Computer and a small service hut beside it, so ships can land at villages.
 *
 * <p>Villages are found when the chunk holding their structure start loads. The port is built a few
 * ticks later on the flattest dry ground just outside the village, levelled to one height. Each
 * village is considered once; the decision and the result are saved.
 */
public final class VillagePorts {
  /** Pad interior is 16 x 16 inside a cobblestone ring; the hut sits on the ring's west side. */
  static final int PAD = 16, RING = PAD + 2, HUT = 5, WIDTH = RING + HUT + 1, DEPTH = RING;

  /** Clearance kept above the platform so ships fit (pads hold ships up to 32 tall). */
  static final int CLEAR = 24;

  private static final String[] NAMES = {
    "Larkfield",
    "Ashby",
    "Brookmere",
    "Cindervale",
    "Dunmore",
    "Elmstead",
    "Fernhollow",
    "Glenrock",
    "Haverly",
    "Ivydale",
    "Juniper",
    "Kestrel",
    "Lindow",
    "Marbury",
    "Northwold",
    "Oakhurst",
    "Pennick",
    "Quarrydown",
    "Rowan",
    "Stillwater",
    "Thornby",
    "Underhill",
    "Wexford",
    "Yarrow"
  };

  private static final Deque<long[]> QUEUE = new ArrayDeque<>();

  public static void initialize() {
    ServerChunkEvents.CHUNK_LOAD.register(
        (level, chunk, generated) -> {
          if (level != level.getServer().overworld()) return;
          var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
          for (var entry : chunk.getAllStarts().entrySet()) {
            if (!entry.getValue().isValid()
                || !structures.wrapAsHolder(entry.getKey()).is(StructureTags.VILLAGE)) continue;
            var box = entry.getValue().getBoundingBox();
            QUEUE.add(
                new long[] {
                  chunk.getPos().pack(),
                  box.minX(),
                  box.minY(),
                  box.minZ(),
                  box.maxX(),
                  box.maxY(),
                  box.maxZ()
                });
          }
        });
    ServerContext.tick(ServerContext.Phase.TERRAIN, VillagePorts::tick);
  }

  static void tick(MinecraftServer server) {
    // One village a tick: building loads a few chunks, so spread the work out.
    var next = QUEUE.poll();
    if (next == null) return;
    var box =
        new BoundingBox(
            (int) next[1],
            (int) next[2],
            (int) next[3],
            (int) next[4],
            (int) next[5],
            (int) next[6]);
    consider(server.overworld(), ChunkPos.unpack(next[0]), box);
  }

  /** Whether this village gets a port: one in three, fixed by world seed and village position. */
  public static boolean chosen(long worldSeed, ChunkPos start) {
    long h = worldSeed ^ start.pack() * 0x9E3779B97F4A7C15L;
    h ^= h >>> 31;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return Math.floorMod(h, 3) == 0;
  }

  /** Decides once per village and builds its port if chosen. Returns the port's record. */
  public static CompoundTag consider(ServerLevel level, ChunkPos start, BoundingBox village) {
    var store = WorldStore.get(level.getServer());
    var ports = store.section("villagePorts");
    String key = Long.toString(start.pack());
    if (ports.contains(key)) return ports.getCompoundOrEmpty(key);
    var record = new CompoundTag();
    if (!chosen(level.getSeed(), start)) record.putString("state", "none");
    else {
      var computer = build(level, village, level.getSeed() ^ start.pack());
      if (computer == null) record.putString("state", "no_site");
      else {
        record.putString("state", "built");
        record.putLong("computer", computer.asLong());
      }
    }
    ports.put(key, record);
    store.save();
    return record;
  }

  /**
   * Builds a port beside the village box on the best nearby site and registers its pad. Returns the
   * Pad Computer's position, or null if no dry, gentle site was found.
   */
  public static BlockPos build(ServerLevel level, BoundingBox village, long seed) {
    var site = site(level, village);
    if (site == null) return null;
    int x = site.getX(), y = site.getY(), z = site.getZ();
    var plan = plan();
    for (var e : plan.entrySet()) {
      var at = new BlockPos(x, y, z).offset(e.getKey());
      level.setBlock(at, e.getValue(), Block.UPDATE_CLIENTS);
    }
    // Fill under the platform so it rests on the ground, and clear the sky above it.
    for (int dx = 0; dx < WIDTH; dx++)
      for (int dz = 0; dz < DEPTH; dz++) {
        for (int down = 1; down <= 8; down++) {
          var below = new BlockPos(x + dx, y - down, z + dz);
          var state = level.getBlockState(below);
          if (!state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced()) break;
          level.setBlock(below, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (int up = 1; up <= CLEAR; up++) {
          var above = new BlockPos(x + dx, y + up, z + dz);
          if (plan.containsKey(above.subtract(site))) continue;
          if (!level.getBlockState(above).isAir())
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
      }
    var computer = new BlockPos(x, y, z).offset(COMPUTER);
    var random = new Random(seed);
    String base = NAMES[random.nextInt(NAMES.length)] + " Port", name = base;
    var taken =
        PadRegistry.read(level.getServer()).stream()
            .map(p -> p.name().toLowerCase(Locale.ROOT))
            .toList();
    for (int n = 2; taken.contains(name.toLowerCase(Locale.ROOT)); n++) name = base + " " + n;
    try {
      String result =
          PadComputers.register(
              level,
              computer,
              new PadScanner.Pad(x + HUT + 2, x + HUT + 1 + PAD, y, z + 1, z + PAD),
              name,
              false);
      if (!result.startsWith("Registered")) {
        NullSpace.LOGGER.warn("Village port not registered: {}", result);
        return null;
      }
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot register a village port", error);
    }
    NullSpace.LOGGER.info("Built village port {} at {}", name, computer.toShortString());
    return computer;
  }

  /** Pad Computer: in the hut's east wall, facing the pad. */
  static final BlockPos COMPUTER = new BlockPos(HUT, 1, DEPTH / 2);

  /** The port relative to its north-west floor corner (y 0 is the platform). */
  static Map<BlockPos, BlockState> plan() {
    var out = new LinkedHashMap<BlockPos, BlockState>();
    for (int x = 0; x < WIDTH; x++)
      for (int z = 0; z < DEPTH; z++) {
        boolean pad = x > HUT + 1 && x < WIDTH - 1 && z > 0 && z < DEPTH - 1;
        boolean ring = x >= HUT + 1 && !pad;
        out.put(
            new BlockPos(x, 0, z),
            (pad ? Blocks.SMOOTH_STONE : ring ? Blocks.COBBLESTONE : Blocks.STONE_BRICKS)
                .defaultBlockState());
      }
    // Service hut: a 5 x 7 stone-brick room with a door to the village side and a window on the
    // pad.
    int z0 = DEPTH / 2 - 3, z1 = DEPTH / 2 + 3;
    for (int x = 0; x <= HUT; x++)
      for (int z = z0; z <= z1; z++)
        for (int y = 1; y <= 4; y++) {
          boolean wall = x == 0 || x == HUT || z == z0 || z == z1;
          if (y == 4) out.put(new BlockPos(x, y, z), Blocks.STONE_BRICK_SLAB.defaultBlockState());
          else if (wall)
            out.put(
                new BlockPos(x, y, z),
                (y == 2 && (z == z0 || z == z1) && x > 1 && x < HUT - 1
                        ? Blocks.GLASS_PANE
                        : Blocks.STONE_BRICKS)
                    .defaultBlockState());
        }
    // Door on the west wall, toward the village.
    var lower =
        Blocks.SPRUCE_DOOR
            .defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(
                DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
    out.put(new BlockPos(0, 1, DEPTH / 2), lower);
    out.put(
        new BlockPos(0, 2, DEPTH / 2),
        lower.setValue(
            DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
    out.put(
        COMPUTER,
        PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.EAST));
    // The service point: a place to craft, smelt, store and rest.
    out.put(new BlockPos(1, 1, z0 + 1), Blocks.CRAFTING_TABLE.defaultBlockState());
    out.put(new BlockPos(2, 1, z0 + 1), Blocks.FURNACE.defaultBlockState());
    out.put(new BlockPos(3, 1, z0 + 1), Blocks.BARREL.defaultBlockState());
    out.put(new BlockPos(1, 1, z1 - 1), Blocks.SMITHING_TABLE.defaultBlockState());
    out.put(
        new BlockPos(3, 3, DEPTH / 2),
        Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    // Ring lamps so the pad is visible at night.
    for (var corner :
        List.of(
            new BlockPos(HUT + 1, 1, 0),
            new BlockPos(WIDTH - 1, 1, 0),
            new BlockPos(HUT + 1, 1, DEPTH - 1),
            new BlockPos(WIDTH - 1, 1, DEPTH - 1)))
      out.put(corner, Blocks.LANTERN.defaultBlockState());
    BlueprintOrientation.fix(out);
    return out;
  }

  /**
   * The best site just outside the village: candidates on all four sides, scored by slope; water
   * and steep ground are rejected. Returns the platform's north-west corner at platform height.
   */
  static BlockPos site(ServerLevel level, BoundingBox village) {
    int gap = 6;
    int cx = (village.minX() + village.maxX()) / 2 - WIDTH / 2;
    int cz = (village.minZ() + village.maxZ()) / 2 - DEPTH / 2;
    var candidates =
        List.of(
            new int[] {village.maxX() + gap, cz},
            new int[] {village.minX() - gap - WIDTH, cz},
            new int[] {cx, village.maxZ() + gap},
            new int[] {cx, village.minZ() - gap - DEPTH},
            new int[] {village.maxX() + gap, village.maxZ() + gap},
            new int[] {village.minX() - gap - WIDTH, village.minZ() - gap - DEPTH});
    BlockPos best = null;
    int bestSlope = Integer.MAX_VALUE;
    for (var c : candidates) {
      int x = c[0], z = c[1];
      for (int chunkX = x >> 4; chunkX <= (x + WIDTH) >> 4; chunkX++)
        for (int chunkZ = z >> 4; chunkZ <= (z + DEPTH) >> 4; chunkZ++)
          level.getChunk(chunkX, chunkZ);
      var heights = new ArrayList<Integer>();
      int wet = 0;
      for (int dx = 0; dx < WIDTH; dx += 2)
        for (int dz = 0; dz < DEPTH; dz += 2) {
          int h = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x + dx, z + dz);
          int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz);
          if (!level.getFluidState(new BlockPos(x + dx, top - 1, z + dz)).isEmpty()) wet++;
          heights.add(h);
        }
      Collections.sort(heights);
      int slope = heights.getLast() - heights.getFirst();
      if (wet > heights.size() / 10 || slope > 8) continue;
      int y = heights.get(heights.size() / 2) - 1;
      if (y + CLEAR + 1 >= level.getMaxY() || y <= level.getMinY()) continue;
      if (overlapsPad(level, x, y, z)) continue;
      if (slope < bestSlope) {
        bestSlope = slope;
        best = new BlockPos(x, y, z);
      }
    }
    return best;
  }

  private static boolean overlapsPad(ServerLevel level, int x, int y, int z) {
    var area =
        new net.minecraft.world.phys.AABB(
            x - 4, y - 4, z - 4, x + WIDTH + 4, y + CLEAR, z + DEPTH + 4);
    String dimension = level.dimension().identifier().toString();
    for (var pad : PadRegistry.read(level.getServer())) {
      if (!pad.dimension().equals(dimension)) continue;
      var b = pad.bounds();
      if (area.intersects(
          new net.minecraft.world.phys.AABB(
              b.minX(), b.y(), b.minZ(), b.maxX() + 1, b.y() + 33, b.maxZ() + 1))) return true;
    }
    return false;
  }

  private VillagePorts() {}
}
