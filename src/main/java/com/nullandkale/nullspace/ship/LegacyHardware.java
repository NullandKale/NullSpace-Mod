package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.server.ServerContext;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Keep old registry IDs readable, but retire their placed hardware when saved chunks load. */
public final class LegacyHardware {
  private record ChunkKey(ServerLevel level, long chunk) {}

  private record Work(LevelChunk chunk, ArrayDeque<BlockPos> blocks) {}

  private static final ServerContext.Key<LinkedHashMap<ChunkKey, Work>> KEY =
      new ServerContext.Key<>();

  public static boolean retired(BlockState state) {
    return state.is(ShipBlocks.HELM)
        || state.is(ShipBlocks.SYSTEMS_CONTROLLER)
        || state.is(ShipBlocks.DOOR_CONTROL)
        || state.is(ShipBlocks.LONG_RANGE_SCANNER)
        || state.is(ShipBlocks.DOCKING_PORT)
        || state.is(ShipBlocks.DOOR_CONNECTOR);
  }

  private static LinkedHashMap<ChunkKey, Work> pending(MinecraftServer server) {
    return ServerContext.get(server).service(KEY, LinkedHashMap::new);
  }

  private static void loaded(ServerLevel level, LevelChunk chunk, boolean newlyGenerated) {
    // Snapshot only the blocks present at load, not equipment placed later in the same tick.
    var blocks = new ArrayDeque<BlockPos>();
    var sections = chunk.getSections();
    for (int i = 0; i < sections.length; i++) {
      var section = sections[i];
      if (!section.maybeHas(LegacyHardware::retired)) continue;
      int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
      for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++)
            if (retired(section.getBlockState(x, y, z)))
              blocks.add(
                  new BlockPos(
                      chunk.getPos().getMinBlockX() + x,
                      y0 + y,
                      chunk.getPos().getMinBlockZ() + z));
    }
    if (!blocks.isEmpty())
      pending(level.getServer())
          .put(new ChunkKey(level, chunk.getPos().pack()), new Work(chunk, blocks));
  }

  private static void tick(MinecraftServer server) {
    var queue = ServerContext.get(server).existing(KEY);
    if (queue == null || TravelService.worldMutation()) return;
    int chunks = Math.min(8, queue.size()), budget = 256;
    while (chunks-- > 0 && budget > 0) {
      var entry = queue.pollFirstEntry();
      var key = entry.getKey();
      var work = entry.getValue();
      var level = key.level();
      var chunk = work.chunk();
      if (level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) != chunk)
        continue;
      int count = Math.min(budget, work.blocks().size());
      while (count-- > 0) {
        budget--;
        var at = work.blocks().removeFirst();
        if (!retired(level.getBlockState(at))) continue;
        if (TravelService.locked(level, at)) {
          work.blocks().addLast(at);
          continue;
        }
        // Preserve installed modules as real drops once, rather than deleting or duplicating them.
        var contents = new ArrayList<ItemStack>();
        var container = level.getBlockEntity(at) instanceof Container c ? c : null;
        if (container != null) {
          for (int slot = 0; slot < container.getContainerSize(); slot++)
            contents.add(container.getItem(slot).copy());
          container.clearContent();
        }
        if (level.setBlock(at, Blocks.IRON_BLOCK.defaultBlockState(), 18)) {
          for (var stack : contents)
            Containers.dropItemStack(level, at.getX() + .5, at.getY() + .5, at.getZ() + .5, stack);
        } else {
          if (container != null)
            for (int slot = 0; slot < contents.size(); slot++)
              container.setItem(slot, contents.get(slot));
          work.blocks().addLast(at);
        }
      }
      if (!work.blocks().isEmpty()) queue.put(key, work);
    }
  }

  public static void initialize() {
    ServerChunkEvents.CHUNK_LOAD.register(LegacyHardware::loaded);
    ServerChunkEvents.CHUNK_UNLOAD.register(
        (level, chunk) -> {
          var queue = ServerContext.get(level.getServer()).existing(KEY);
          if (queue != null) queue.remove(new ChunkKey(level, chunk.getPos().pack()));
        });
    ServerContext.tick(ServerContext.Phase.SESSIONS, LegacyHardware::tick);
  }

  private LegacyHardware() {}
}
