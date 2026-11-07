package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;

/** One station identity, a bounded build envelope, and independently cached render sections. */
public final class StationSpace {
  public static final int SECTION = 32,
      MAX_WIDTH = 128,
      HEIGHT = 32,
      TILES = MAX_WIDTH / StationLayout.TILE;

  public static List<BlockPos> sections(CompoundTag vessel) {
    var out = new ArrayList<BlockPos>();
    int width = vessel.getIntOr("width", 32), depth = vessel.getIntOr("depth", 32);
    if (width < 1 || depth < 1 || width > MAX_WIDTH || depth > MAX_WIDTH)
      throw new IllegalArgumentException("Invalid station envelope");
    for (int z = 0; z < depth; z += SECTION)
      for (int x = 0; x < width; x += SECTION) out.add(new BlockPos(x, 0, z));
    return List.copyOf(out);
  }

  public static int extent(StationBlueprint.Plan plan, boolean x) {
    int max = 0;
    for (var p : plan.blocks().keySet()) {
      if (p.getX() < 0
          || p.getX() >= MAX_WIDTH
          || p.getY() < 0
          || p.getY() >= HEIGHT
          || p.getZ() < 0
          || p.getZ() >= MAX_WIDTH)
        throw new IllegalArgumentException("Station plan exceeds 128 x 32 x 128");
      max = Math.max(max, x ? p.getX() : p.getZ());
    }
    return (max / SECTION + 1) * SECTION;
  }

  public static void chunks(ServerLevel level, CompoundTag vessel) {
    var bounds = WorldStore.bounds(vessel);
    for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
      for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++) {
        level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(x, z), 2);
        level.getChunk(x, z);
      }
  }

  public static double radius(CompoundTag vessel) {
    double w = vessel.getIntOr("width", 32),
        h = vessel.getIntOr("height", 32),
        d = vessel.getIntOr("depth", 32);
    return Math.sqrt(w * w + h * h + d * d) / 2;
  }

  private StationSpace() {}
}
