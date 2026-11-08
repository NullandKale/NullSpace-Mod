package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.gameplay.pads.BuildPreviews;
import com.nullandkale.nullspace.gameplay.pads.PadRefresh;
import com.nullandkale.nullspace.gameplay.pads.PadStatus;
import com.nullandkale.nullspace.gameplay.scene.SceneSnapshots;
import com.nullandkale.nullspace.gameplay.scene.TerrainService;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Physical world edits publish invalidations here; journal replay retains its guarded semantics.
 */
public final class HullEdits
{
    public static void blockChanged(ServerLevel level, BlockPos pos, boolean replaced)
    {
        ShipSystems.changed(level, pos, replaced);
        SceneSnapshots.changed(level, pos);
        TerrainService.changed(level, pos);
        PadStatus.changed(level, pos);
        PadRefresh.changed(level, pos);
        BuildPreviews.changed(level, pos);
    }

    private HullEdits()
    {
    }
}
