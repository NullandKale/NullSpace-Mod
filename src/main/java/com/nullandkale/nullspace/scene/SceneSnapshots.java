package com.nullandkale.nullspace.scene;

import com.nullandkale.nullspace.ship.ShipScan;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

/** Server-thread source cache; lighting threads only increment registered chunk counters. */
public final class SceneSnapshots {
  public static final int MAX_SOURCES = 64, IDLE_TICKS = 300;
  public static final TicketType CAMERA_TICKET =
      Registry.register(
          BuiltInRegistries.TICKET_TYPE,
          Identifier.parse("nullspace:camera"),
          new TicketType(IDLE_TICKS, TicketType.FLAG_LOADING));

  public static void initialize() {}

  public record Snapshot(String id, long revision, CompoundTag geometry, boolean ready) {}

  public record Stats(
      long requests,
      long blockCaptures,
      long lightCaptures,
      long reused,
      long blockReads,
      long lightReads,
      long evictions,
      int sources) {}

  private record Key(long base, long min, long max) {}

  private static final class Watch {
    final AtomicLong epoch = new AtomicLong();
    int references;
  }

  private static final class Entry {
    final String id = UUID.randomUUID().toString();
    final BlockPos base;
    final ShipScan.Bounds bounds;
    final long[] chunks;
    boolean blockDirty = true, lightDirty = true;
    long mask = -1, lightEpoch = -1, revision;
    int sky = Integer.MIN_VALUE, lastUse;
    net.minecraft.nbt.ListTag visuals;
    int visualTick;
    int[] blocks;
    byte[] light;
    CompoundTag blockData, geometry;

    Entry(BlockPos base, ShipScan.Bounds bounds) {
      this.base = base.immutable();
      this.bounds = bounds;
      int minX = base.getX() >> 4,
          maxX = (base.getX() + 31) >> 4,
          minZ = base.getZ() >> 4,
          maxZ = (base.getZ() + 31) >> 4;
      chunks = new long[(maxX - minX + 1) * (maxZ - minZ + 1)];
      int n = 0;
      for (int z = minZ; z <= maxZ; z++)
        for (int x = minX; x <= maxX; x++) chunks[n++] = new ChunkPos(x, z).pack();
    }
  }

  private static final class Cache {
    final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    final ConcurrentHashMap<Long, Watch> watches = new ConcurrentHashMap<>();
    long requests, blockCaptures, lightCaptures, reused, blockReads, lightReads, evictions;
  }

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<ServerLevel, Cache>>
      KEY = new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<ServerLevel, Cache> caches(MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server)
        .service(KEY, IdentityHashMap::new);
  }

  private static final ConcurrentHashMap<ServerLevel, ConcurrentHashMap<Long, Watch>>
      LIGHT_WATCHES = new ConcurrentHashMap<>();

  public static Snapshot capture(ServerLevel level, BlockPos base, ShipScan.Bounds bounds) {
    var cache =
        caches(level.getServer())
            .computeIfAbsent(
                level,
                l -> {
                  var value = new Cache();
                  LIGHT_WATCHES.put(level, value.watches);
                  return value;
                });
    cache.requests++;
    int tick = level.getServer().getTickCount();
    prune(level, cache, tick);
    var key = new Key(base.asLong(), bounds.min().asLong(), bounds.max().asLong());
    var entry = cache.entries.get(key);
    if (entry == null) {
      if (cache.entries.size() >= MAX_SOURCES) {
        var oldest = cache.entries.entrySet().iterator();
        release(level, cache, oldest.next().getValue());
        oldest.remove();
        cache.evictions++;
      }
      entry = new Entry(base, bounds);
      cache.entries.put(key, entry);
      for (long chunk : entry.chunks)
        cache.watches.computeIfAbsent(chunk, k -> new Watch()).references++;
    }
    entry.lastUse = tick;
    long mask = 0, lightEpoch = 0;
    for (int i = 0; i < entry.chunks.length; i++) {
      var chunk = ChunkPos.unpack(entry.chunks[i]);
      level.getChunkSource().addTicketWithRadius(CAMERA_TICKET, chunk, 0);
      if (level.hasChunk(chunk.x(), chunk.z())) mask |= 1L << i;
      lightEpoch += cache.watches.get(entry.chunks[i]).epoch.get();
    }
    boolean loadingChanged = mask != entry.mask, changed = false;
    if (entry.blockDirty || loadingChanged || entry.blocks == null) {
      var blocks = readBlocks(level, entry, mask, cache);
      cache.blockCaptures++;
      if (!Arrays.equals(entry.blocks, blocks)) {
        entry.blocks = blocks;
        entry.blockData = SceneVolumes.blocks(blocks);
        changed = true;
      }
    }
    int sky = level.getSkyDarken();
    if (entry.lightDirty
        || loadingChanged
        || entry.light == null
        || lightEpoch != entry.lightEpoch
        || sky != entry.sky) {
      var light = readLight(level, entry, mask, cache);
      cache.lightCaptures++;
      if (!Arrays.equals(entry.light, light)) {
        entry.light = light;
        changed = true;
      }
    }
    if (entry.blockDirty
        || loadingChanged
        || entry.visuals == null
        || tick - entry.visualTick >= 40) {
      var visuals = VisualSnapshot.capture(level, entry.base, 32, entry.blocks);
      if (!visuals.equals(entry.visuals)) {
        entry.visuals = visuals;
        changed = true;
      }
      entry.visualTick = tick;
    }
    if (changed || entry.geometry == null) {
      entry.geometry = SceneVolumes.geometry(entry.blockData, entry.light);
      entry.geometry.put("blockEntities", entry.visuals);
      entry.revision++;
    } else cache.reused++;
    entry.blockDirty = false;
    entry.lightDirty = false;
    entry.mask = mask;
    entry.lightEpoch = lightEpoch;
    entry.sky = sky;
    return new Snapshot(
        entry.id, entry.revision, entry.geometry, mask == (1L << entry.chunks.length) - 1);
  }

  private static boolean loaded(Entry entry, int x, int z, long mask) {
    int width = ((entry.base.getX() + 31) >> 4) - (entry.base.getX() >> 4) + 1;
    int index =
        ((z >> 4) - (entry.base.getZ() >> 4)) * width + ((x >> 4) - (entry.base.getX() >> 4));
    return (mask & (1L << index)) != 0;
  }

  private static int[] readBlocks(ServerLevel level, Entry entry, long mask, Cache cache) {
    int[] result = new int[VoxelVolume.MAX_CELLS];
    var pos = new BlockPos.MutableBlockPos();
    for (int y = 0; y < 32; y++)
      for (int z = 0; z < 32; z++)
        for (int x = 0; x < 32; x++) {
          pos.set(entry.base.getX() + x, entry.base.getY() + y, entry.base.getZ() + z);
          if (entry.bounds.contains(pos) && loaded(entry, pos.getX(), pos.getZ(), mask)) {
            result[x + z * 32 + y * 1024] = Block.getId(level.getBlockState(pos));
            cache.blockReads++;
          }
        }
    return result;
  }

  private static byte[] readLight(ServerLevel level, Entry entry, long mask, Cache cache) {
    byte[] result = new byte[VoxelVolume.MAX_CELLS];
    var pos = new BlockPos.MutableBlockPos();
    for (int y = 0; y < 32; y++)
      for (int z = 0; z < 32; z++)
        for (int x = 0; x < 32; x++) {
          pos.set(entry.base.getX() + x, entry.base.getY() + y, entry.base.getZ() + z);
          if (loaded(entry, pos.getX(), pos.getZ(), mask)) {
            result[x + z * 32 + y * 1024] = (byte) level.getMaxLocalRawBrightness(pos);
            cache.lightReads++;
          }
        }
    return result;
  }

  public static void changed(ServerLevel level, BlockPos position) {
    var cache = caches(level.getServer()).get(level);
    if (cache == null) return;
    for (var entry : cache.entries.values())
      if (entry.bounds.contains(position)
          && position.getX() >= entry.base.getX()
          && position.getX() < entry.base.getX() + 32
          && position.getY() >= entry.base.getY()
          && position.getY() < entry.base.getY() + 32
          && position.getZ() >= entry.base.getZ()
          && position.getZ() < entry.base.getZ() + 32) {
        entry.blockDirty = true;
        entry.lightDirty = true;
      }
  }

  /** May be invoked by a native lighting worker. It never reads worlds or server-owned maps. */
  public static void lightChanged(ServerLevel level, long chunk) {
    var watches = LIGHT_WATCHES.get(level);
    if (watches != null) {
      var watch = watches.get(chunk);
      if (watch != null) watch.epoch.incrementAndGet();
    }
  }

  public static Stats stats(ServerLevel level) {
    var c = caches(level.getServer()).get(level);
    return c == null
        ? new Stats(0, 0, 0, 0, 0, 0, 0, 0)
        : new Stats(
            c.requests,
            c.blockCaptures,
            c.lightCaptures,
            c.reused,
            c.blockReads,
            c.lightReads,
            c.evictions,
            c.entries.size());
  }

  public static void expire(MinecraftServer server) {
    for (var entry : caches(server).entrySet())
      if (entry.getKey().getServer() == server)
        prune(entry.getKey(), entry.getValue(), server.getTickCount());
  }

  public static void clear(MinecraftServer server) {
    var it = caches(server).entrySet().iterator();
    while (it.hasNext()) {
      var entry = it.next();
      if (entry.getKey().getServer() != server) continue;
      for (var source : entry.getValue().entries.values())
        release(entry.getKey(), entry.getValue(), source);
      it.remove();
    }
    LIGHT_WATCHES.keySet().removeIf(level -> level.getServer() == server);
  }

  private static void prune(ServerLevel level, Cache cache, int tick) {
    var iterator = cache.entries.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next().getValue();
      if (tick - entry.lastUse > IDLE_TICKS) {
        release(level, cache, entry);
        iterator.remove();
      }
    }
  }

  private static void release(ServerLevel level, Cache cache, Entry entry) {
    for (long chunk : entry.chunks) {
      var watch = cache.watches.get(chunk);
      if (watch != null && --watch.references == 0) {
        cache.watches.remove(chunk, watch);
        level.getChunkSource().removeTicketWithRadius(CAMERA_TICKET, ChunkPos.unpack(chunk), 0);
      }
    }
  }

  private SceneSnapshots() {}
}
