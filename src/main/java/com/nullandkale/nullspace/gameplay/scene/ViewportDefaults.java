package com.nullandkale.nullspace.gameplay.scene;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Which camera a monitor shows when nobody has chosen one: the nearest, so a viewport in a room shows the camera on that room's outer
 * wall and a monitor deep in a hab shows the closest camera, not whichever happens to be first in the list.
 */
public final class ViewportDefaults
{
    /** The index of the camera nearest the monitor (the first of equals), or 0 when there is none. */
    public static int nearest(List<BlockPos> cameras, BlockPos monitor)
    {
        int best = 0;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < cameras.size(); i++)
        {
            double d = cameras.get(i).distSqr(monitor);
            if (d < distance)
            {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    private ViewportDefaults()
    {
    }
}
