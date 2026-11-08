package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.core.mixin.EntitySectionsAccessor;
import com.nullandkale.nullspace.core.mixin.ServerLevelEntitiesAccessor;

import java.util.*;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Include loaded inactive sections: the ordinary world entity getter only sees tracked sections.
 */
public final class HullEntities
{
    public static List<Entity> inBounds(ServerLevel level, HullScan.Bounds bounds)
    {
        var manager = ((ServerLevelEntitiesAccessor) level).nullspace$entities();
        var sections = ((EntitySectionsAccessor) manager).nullspace$sections();
        var result = new ArrayList<Entity>();
        var box = bounds.box();
        for (int x = (bounds.min().getX() - 2) >> 4; x <= (bounds.max().getX() + 2) >> 4; x++)
            for (int z = (bounds.min().getZ() - 2) >> 4; z <= (bounds.max().getZ() + 2) >> 4; z++)
                sections.getExistingSectionsInChunk(new ChunkPos(x, z).pack()).forEach(section -> section.getEntities().filter(entity -> !entity.isRemoved() && box.intersects(entity.getBoundingBox())).forEach(result::add));
        return result;
    }

    public static void requireLoaded(ServerLevel level, HullScan.Bounds bounds)
    {
        if (!loaded(level, bounds))
            throw new IllegalArgumentException("Ship passengers are still loading; try again shortly");
    }

    public static boolean loaded(ServerLevel level, HullScan.Bounds bounds)
    {
        var manager = ((ServerLevelEntitiesAccessor) level).nullspace$entities();
        for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
            for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                if (!manager.areEntitiesLoaded(new ChunkPos(x, z).pack()))
                    return false;
        return true;
    }

    private HullEntities()
    {
    }
}
