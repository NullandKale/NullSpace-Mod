package com.nullandkale.nullspace.worldgen.shipgen;

import net.minecraft.core.Direction;

/** One generated cell: what it is, and which way it faces if that matters. */
public record Voxel(Kind kind, Direction facing)
{
    public Voxel(Kind kind)
    {
        this(kind, Direction.NORTH);
    }

    /** The same cell reflected across the ship's centre line (east and west swap). */
    public Voxel mirrored()
    {
        return switch (facing)
        {
            case EAST -> new Voxel(kind, Direction.WEST);
            case WEST -> new Voxel(kind, Direction.EAST);
            default -> this;
        };
    }
}
