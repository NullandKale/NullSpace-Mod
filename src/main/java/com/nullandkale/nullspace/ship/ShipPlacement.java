package com.nullandkale.nullspace.ship;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.*;

/** Physical replay primitives shared by transfer and recovery. The journal owns their data. */
final class ShipPlacement {
  private static final ThreadLocal<Boolean> WORLD_MUTATION = ThreadLocal.withInitial(() -> false);

  static boolean worldMutation() {
    return WORLD_MUTATION.get();
  }

  static void place(ServerLevel level, StructureTemplate template, BlockPos min) {
    boolean previous = WORLD_MUTATION.get();
    WORLD_MUTATION.set(true);
    try {
      var snapshot = template.save(new CompoundTag()).copy();
      var size = template.getSize();
      clear(level, min, min.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
      if (!template.placeInWorld(
          level,
          min,
          min,
          new StructurePlaceSettings().setIgnoreEntities(true).setKnownShape(true),
          level.getRandom(),
          ShipBlockSupport.TRANSFER_FLAGS))
        throw new IllegalStateException("Could not place ship snapshot");
      // Vanilla structure placement randomizes loot-table seeds. Reload the exact saved
      // data after placement so unopened loot, inventories, books, and machine state agree
      // with the journal. Relocated hive memories are already in this snapshot.
      for (var entry : snapshot.getListOrEmpty("blocks")) {
        var block = (CompoundTag) entry;
        var data = block.getCompound("nbt");
        if (data.isEmpty()) continue;
        var coords = block.getListOrEmpty("pos");
        var at = min.offset(coords.getIntOr(0, 0), coords.getIntOr(1, 0), coords.getIntOr(2, 0));
        var be = level.getBlockEntity(at);
        if (be == null)
          throw new IllegalStateException("Missing transferred block entity at " + at);
        be.loadWithComponents(
            TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data.get()));
        be.setChanged();
      }
    } finally {
      WORLD_MUTATION.set(previous);
    }
  }

  static void clear(ServerLevel level, BlockPos min, BlockPos max) {
    boolean previous = WORLD_MUTATION.get();
    WORLD_MUTATION.set(true);
    try {
      var area = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(min, max);
      level.getBlockTicks().clearArea(area);
      level.getFluidTicks().clearArea(area);
      level.clearBlockEvents(area);
      for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
        // Do not clear inventories: some containers notify neighbors and pop attached
        // decorations when emptied. Remove the entity and suppress removal side effects.
        // All source menus were closed before mutation; the journal owns the saved items.
        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), ShipBlockSupport.TRANSFER_FLAGS);
      }
    } finally {
      WORLD_MUTATION.set(previous);
    }
  }

  static void clear(ServerLevel level, java.util.Set<BlockPos> captured) {
    boolean previous = WORLD_MUTATION.get();
    WORLD_MUTATION.set(true);
    try {
      for (BlockPos pos : captured) {
        var area = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(pos, pos);
        level.getBlockTicks().clearArea(area);
        level.getFluidTicks().clearArea(area);
        level.clearBlockEvents(area);
        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), ShipBlockSupport.TRANSFER_FLAGS);
      }
    } finally {
      WORLD_MUTATION.set(previous);
    }
  }

  private ShipPlacement() {}
}
