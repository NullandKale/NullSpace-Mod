package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

/** A sparse block grid in ship coordinates: nose toward -z, stern toward +z, floor at the lowest y. */
public final class Grid
{
    private final Map<BlockPos, Voxel> cells = new HashMap<>();

    public Voxel get(BlockPos at)
    {
        return cells.get(at);
    }

    public Kind kind(BlockPos at)
    {
        var v = cells.get(at);
        return v == null ? null : v.kind();
    }

    public void set(BlockPos at, Kind kind)
    {
        cells.put(at.immutable(), new Voxel(kind));
    }

    public void set(BlockPos at, Kind kind, Direction facing)
    {
        cells.put(at.immutable(), new Voxel(kind, facing));
    }

    public void clear(BlockPos at)
    {
        cells.remove(at);
    }

    public boolean has(BlockPos at)
    {
        return cells.containsKey(at);
    }

    public Map<BlockPos, Voxel> cells()
    {
        return cells;
    }

    /** Smallest box holding every cell, as {min, max}; null when empty. */
    public BlockPos[] bounds()
    {
        BlockPos lo = null, hi = null;
        for (var at : cells.keySet())
        {
            lo = lo == null ? at : BlockPos.min(lo, at);
            hi = hi == null ? at : BlockPos.max(hi, at);
        }
        return lo == null ? null : new BlockPos[]{lo, hi};
    }
}
