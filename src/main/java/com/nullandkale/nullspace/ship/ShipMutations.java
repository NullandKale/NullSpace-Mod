package com.nullandkale.nullspace.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Physical world edits publish invalidations here; journal replay retains its guarded semantics.
 */
public final class ShipMutations {
  public static void blockChanged(ServerLevel level, BlockPos pos, boolean replaced) {
    com.nullandkale.nullspace.systems.ShipSystems.changed(level, pos, replaced);
    com.nullandkale.nullspace.scene.SceneSnapshots.changed(level, pos);
    com.nullandkale.nullspace.scene.TerrainService.changed(level, pos);
    com.nullandkale.nullspace.pad.PadStatusService.changed(level, pos);
    com.nullandkale.nullspace.pad.PadRefreshService.changed(level, pos);
    com.nullandkale.nullspace.pad.PreviewService.changed(level, pos);
  }

  private ShipMutations() {}
}
