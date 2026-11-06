package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.ship.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** A one-time, terrain-aware arrival pad. Construction only fills previously empty air. */
final class TerraLandingSite {
  static void ensure(ServerLevel level) {
    var server = level.getServer();
    var store = ShipStore.get(server);
    if (store.section("surfaceSites").getBooleanOr(LocalSpaces.HOME, false)
        || PadRegistry.read(server).stream()
            .anyMatch(p -> p.dimension().equals("minecraft:overworld"))) return;
    var spawn = level.getRespawnData().pos();
    for (int attempt = 0; attempt < 64; attempt++) {
      int x = ((spawn.getX() >> 4) + 4 + attempt % 8 * 4) * 16;
      int z = ((spawn.getZ() >> 4) + 4 + attempt / 8 * 4) * 16;
      // Height queries on an unloaded chunk report the minimum build height. Load the
      // complete candidate first, or an ordinary flat world looks like an empty void.
      for (int cx = (x - 1) >> 4; cx <= (x + 32) >> 4; cx++)
        for (int cz = (z - 1) >> 4; cz <= (z + 32) >> 4; cz++) level.getChunk(cx, cz);
      int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE, wet = 0;
      for (int dx = -1; dx <= 32; dx++)
        for (int dz = -1; dz <= 32; dz++) {
          int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz);
          low = Math.min(low, y);
          high = Math.max(high, y);
          if (!level.getFluidState(new BlockPos(x + dx, y - 1, z + dz)).isEmpty()) wet++;
        }
      // Prefer nearby gentle dry land, but ocean/mountain world settings must still be landable.
      // Later candidates may use a raised platform; no terrain is cleared to make room.
      if (attempt < 8 && (high - low > 8 || wet > 32)) continue;
      if (high == level.getMinY()) high = Math.min(64, level.getMaxY() - 34);
      if (high + 34 > level.getMaxY()) continue;
      var min = new BlockPos(x - 1, high, z - 1);
      var max = new BlockPos(x + 32, high + 33, z + 32);
      boolean clear = true;
      for (var p : BlockPos.betweenClosed(min, max))
        if (!level.getBlockState(p).isAir()) {
          clear = false;
          break;
        }
      if (!clear || !ShipEntities.inBounds(level, new ShipScan.Bounds(min, max)).isEmpty())
        continue;
      String name = "Terra Landing";
      var names =
          PadRegistry.read(server).stream()
              .map(p -> p.name().toLowerCase(java.util.Locale.ROOT))
              .toList();
      for (int suffix = 2; names.contains(name.toLowerCase(java.util.Locale.ROOT)); suffix++)
        name = nameFor(suffix);
      for (int dx = -1; dx <= 32; dx++)
        for (int dz = -1; dz <= 32; dz++)
          level.setBlock(
              new BlockPos(x + dx, high, z + dz),
              (dx < 0 || dx > 31 || dz < 0 || dz > 31 ? Blocks.COBBLESTONE : Blocks.DIRT)
                  .defaultBlockState(),
              Block.UPDATE_CLIENTS);
      // A continuous stair across the gentle slope gives players a way off the platform.
      for (int step = 1; step <= Math.min(9, high - low + 1); step++) {
        var p = new BlockPos(x + 16, high - step + 1, z - 1 - step);
        if (level.getBlockState(p).isAir())
          level.setBlock(
              p,
              Blocks.COBBLESTONE_STAIRS
                  .defaultBlockState()
                  .setValue(StairBlock.FACING, Direction.SOUTH),
              Block.UPDATE_CLIENTS);
      }
      var controller = new BlockPos(x - 1, high + 1, z + 16);
      level.setBlock(
          controller,
          PadComputers.BLOCK.defaultBlockState().setValue(PadComputerBlock.FACING, Direction.WEST),
          Block.UPDATE_CLIENTS);
      try {
        PadComputers.register(
            level, controller, new PadScanner.Pad(x, x + 31, high, z, z + 31), name, false);
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Cannot register Terra's landing site", error);
      }
      store.section("surfaceSites").putBoolean(LocalSpaces.HOME, true);
      store.save();
      com.nullandkale.nullspace.NullSpace.LOGGER.info(
          "Created Terra landing pad at {}", controller.toShortString());
      return;
    }
    com.nullandkale.nullspace.NullSpace.LOGGER.warn(
        "No clear terrain site for Terra's starter pad; it will be retried on the next visit");
  }

  private static String nameFor(int suffix) {
    return suffix <= 1 ? "Terra Landing" : "Terra Landing " + suffix;
  }

  private TerraLandingSite() {}
}
