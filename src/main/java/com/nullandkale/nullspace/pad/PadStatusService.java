package com.nullandkale.nullspace.pad;

import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Advisory destination status has a global read budget and never loads terrain. */
public final class PadStatusService {
  public static final int READS_PER_TICK = 2048;
  private static final ServerContext.Key<PadStatusService> KEY = new ServerContext.Key<>();

  private record Cached(PadRegistry.Status status, int tick) {}

  private static final class Job {
    final PadRegistry.Pad pad;
    int cursor;
    boolean floor = true;

    Job(PadRegistry.Pad pad) {
      this.pad = pad;
    }
  }

  private final Map<String, Cached> cached = new HashMap<>();
  private final LinkedHashMap<String, Job> pending = new LinkedHashMap<>();
  private long revision = -1;
  private int lastReads;

  private static PadStatusService get(MinecraftServer s) {
    return ServerContext.get(s).service(KEY, PadStatusService::new);
  }

  public static PadRegistry.Status status(MinecraftServer server, PadRegistry.Pad pad) {
    var s = get(server);
    long version = PadRepository.get(server).revision();
    if (s.revision != version) {
      s.cached.clear();
      s.pending.clear();
      s.revision = version;
    }
    var old = s.cached.get(pad.id());
    if (old != null && server.getTickCount() - old.tick < 40) return old.status;
    s.pending.putIfAbsent(pad.id(), new Job(pad));
    return new PadRegistry.Status(pad, PadRegistry.State.UNKNOWN, "Inspecting destination");
  }

  public static void changed(ServerLevel level, BlockPos p) {
    var s = ServerContext.get(level.getServer()).existing(KEY);
    if (s == null) return;
    var watched = new HashSet<PadRegistry.Pad>();
    for (var value : s.cached.values()) watched.add(value.status.pad());
    for (var value : s.pending.values()) watched.add(value.pad);
    for (var pad : watched) {
      if (!pad.dimension().equals(level.dimension().identifier().toString())) continue;
      var b = pad.bounds();
      if (p.getX() >= b.minX() - 1
              && p.getX() <= b.maxX() + 1
              && p.getZ() >= b.minZ() - 1
              && p.getZ() <= b.maxZ() + 1
              && p.getY() >= b.y()
          || p.equals(pad.computer())) {
        s.cached.remove(pad.id());
        s.pending.remove(pad.id());
      }
    }
  }

  public static int lastReads(MinecraftServer server) {
    return get(server).lastReads;
  }

  private void tick(MinecraftServer server) {
    lastReads = 0;
    var it = pending.entrySet().iterator();
    while (it.hasNext() && lastReads < READS_PER_TICK) {
      var job = it.next().getValue();
      PadRegistry.Status result = null;
      ServerLevel level =
          server.getLevel(
              net.minecraft.resources.ResourceKey.create(
                  net.minecraft.core.registries.Registries.DIMENSION,
                  net.minecraft.resources.Identifier.parse(job.pad.dimension())));
      if (level == null) {
        result =
            new PadRegistry.Status(job.pad, PadRegistry.State.UNAVAILABLE, "Dimension missing");
      }
      var b = job.pad.bounds();
      if (result == null) lastReads++;
      if (result == null
          && (!level.hasChunkAt(job.pad.computer())
              || !level.getBlockState(job.pad.computer()).is(PadComputers.BLOCK))) {
        result =
            new PadRegistry.Status(
                job.pad,
                level.hasChunkAt(job.pad.computer())
                    ? PadRegistry.State.UNAVAILABLE
                    : PadRegistry.State.UNKNOWN,
                "Computer missing or terrain not loaded");
      }
      while (result == null && lastReads < READS_PER_TICK) {
        int width = job.floor ? b.width() + 2 : b.width(),
            depth = job.floor ? b.depth() + 2 : b.depth();
        if (job.cursor >= width * depth) {
          if (job.floor) {
            job.floor = false;
            job.cursor = 0;
            continue;
          }
          result =
              new PadRegistry.Status(
                  job.pad, PadRegistry.State.READY, "Surface, border and sky clear");
          break;
        }
        int x = b.minX() + job.cursor % width - (job.floor ? 1 : 0),
            z = b.minZ() + job.cursor / width - (job.floor ? 1 : 0);
        job.cursor++;
        lastReads++;
        var pos = new BlockPos(x, b.y(), z);
        if (!level.hasChunkAt(pos)) {
          result = new PadRegistry.Status(job.pad, PadRegistry.State.UNKNOWN, "Terrain not loaded");
          break;
        }
        if (job.floor) {
          var state = level.getBlockState(pos);
          boolean ring = b.onRing(x, z);
          if (ring
              ? !state.is(Blocks.COBBLESTONE)
              : !state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)) {
            result =
                new PadRegistry.Status(
                    job.pad, PadRegistry.State.UNAVAILABLE, "Pad floor or border changed");
          }
        } else if (level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) > b.y() + 1)
          result =
              new PadRegistry.Status(
                  job.pad, PadRegistry.State.OCCUPIED, "Launch column is occupied");
      }
      if (result != null) {
        cached.put(job.pad.id(), new Cached(result, server.getTickCount()));
        it.remove();
      }
    }
  }

  public static void initialize() {
    ServerContext.tick(
        ServerContext.Phase.SESSIONS,
        s -> {
          var state = ServerContext.get(s).existing(KEY);
          if (state != null) state.tick(s);
        });
  }

  private PadStatusService() {}
}
