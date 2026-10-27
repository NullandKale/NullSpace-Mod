package com.nullandkale.nullspace.scene;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** The same bounded, update-tag-only visual contract for ship volumes and terrain sections. */
public final class VisualSnapshot {
  public static final int MAX_ENTITIES = 64, MAX_ENTITY_BYTES = 8192, MAX_BYTES = 16384;

  public static final class Collector {
    private final ListTag entities = new ListTag();
    private int bytes;

    public void add(ServerLevel level, BlockPos position, int cell) {
      if (entities.size() >= MAX_ENTITIES) return;
      var be = level.getBlockEntity(position);
      if (be == null) return;
      var tag = be.getUpdateTag(level.registryAccess()).copy();
      tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
      if (tag.sizeInBytes() > MAX_ENTITY_BYTES) return;
      var visual = new CompoundTag();
      visual.putInt("cell", cell);
      visual.put("data", tag);
      if (entities.sizeInBytes() + visual.sizeInBytes() > MAX_BYTES) return;
      entities.add(visual);
      bytes = entities.sizeInBytes();
    }

    public ListTag entities() {
      return entities;
    }

    public int bytes() {
      return bytes;
    }
  }

  public static ListTag capture(ServerLevel level, BlockPos base, int size, int[] states) {
    var out = new Collector();
    for (int i = 0; i < states.length; i++)
      if (net.minecraft.world.level.block.Block.stateById(states[i]).hasBlockEntity()) {
        var pos = base.offset(i % size, i / (size * size), i / size % size);
        if (level.hasChunkAt(pos)) out.add(level, pos, i);
      }
    return out.entities();
  }

  public static void validate(ListTag entities, int cells) {
    if (entities.size() > MAX_ENTITIES || entities.sizeInBytes() > MAX_BYTES)
      throw new IllegalArgumentException("Visual snapshot exceeds budget");
    for (Tag tag : entities) {
      if (!(tag instanceof CompoundTag visual)
          || visual.getIntOr("cell", -1) < 0
          || visual.getIntOr("cell", -1) >= cells
          || visual.getCompoundOrEmpty("data").sizeInBytes() > MAX_ENTITY_BYTES)
        throw new IllegalArgumentException("Invalid visual block entity");
    }
  }

  private VisualSnapshot() {}
}
