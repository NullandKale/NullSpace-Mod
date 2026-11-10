package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

/**
 * One room, walkway or hall: a set of interior cells, plus the frame furniture is laid out in. The
 * frame is {@code u} across (to the right when walking in), {@code v} deeper from the entry, and a
 * row above the floor. A furnished room is symmetric about its entry axis, so a room on one side
 * of the ship is the mirror of its partner on the other.
 */
public final class Space
{
    public final String id;
    public final RoomUse use;
    public final int level, floorY, height;
    public final Direction forward;
    /** Canonical width (across the entry axis) and depth (away from the entry). */
    public final int w, d;
    /** 0 none, 1 a few windows, 2 many. */
    public int windows;
    private final BlockPos origin;
    private final Set<BlockPos> cells = new HashSet<>();

    private Space(String id, RoomUse use, int level, int floorY, int height, Direction forward, int w, int d, BlockPos origin, int windows)
    {
        this.id = id;
        this.use = use;
        this.level = level;
        this.floorY = floorY;
        this.height = height;
        this.forward = forward;
        this.w = w;
        this.d = d;
        this.origin = origin;
        this.windows = windows;
    }

    /** A box room over {@code minX..maxX} by {@code minZ..maxZ}, entered through the wall behind it relative to {@code forward}. */
    public static Space box(String id, RoomUse use, int level, int floorY, int height, int minX, int maxX, int minZ, int maxZ, Direction forward, int windows)
    {
        boolean alongZ = forward.getAxis() == Direction.Axis.Z;
        int w = alongZ ? maxX - minX + 1 : maxZ - minZ + 1, d = alongZ ? maxZ - minZ + 1 : maxX - minX + 1;
        var right = forward.getClockWise();
        int x = right.getStepX() > 0 || forward.getStepX() > 0 ? minX : maxX, z = right.getStepZ() > 0 || forward.getStepZ() > 0 ? minZ : maxZ;
        var s = new Space(id, use, level, floorY, height, forward, w, d, new BlockPos(x, floorY + 1, z), windows);
        for (int xx = minX; xx <= maxX; xx++)
            for (int zz = minZ; zz <= maxZ; zz++)
                for (int y = 0; y < height; y++)
                    s.cells.add(new BlockPos(xx, floorY + 1 + y, zz));
        return s;
    }

    /** A space with its own cells (the round hub); {@code w} and {@code d} are its bounding size. */
    public static Space custom(String id, RoomUse use, int level, int floorY, int height, Direction forward, int w, int d, BlockPos origin, Collection<BlockPos> cells, int windows)
    {
        var s = new Space(id, use, level, floorY, height, forward, w, d, origin, windows);
        s.cells.addAll(cells);
        return s;
    }

    public Set<BlockPos> cells()
    {
        return cells;
    }

    /** The block at canonical {@code u, v} and {@code row} (0 is the first row above the floor). */
    public BlockPos at(int u, int v, int row)
    {
        var right = forward.getClockWise();
        return origin.offset(right.getStepX() * u + forward.getStepX() * v, row, right.getStepZ() * u + forward.getStepZ() * v);
    }

    public int centre()
    {
        return w / 2;
    }

    public boolean contains(int u, int v, int row)
    {
        return cells.contains(at(u, v, row));
    }

    /** A canonical direction in ship terms: 0 deeper into the room, 1 toward the entry, 2 right, 3 left. */
    public Direction dir(int local)
    {
        return switch (local)
        {
            case 0 -> forward;
            case 1 -> forward.getOpposite();
            case 2 -> forward.getClockWise();
            default -> forward.getCounterClockWise();
        };
    }

    /** A spot in the aisle by the entry, where a person can stand. */
    public BlockPos walk()
    {
        return at(centre(), Math.min(1, d - 1), 0);
    }
}
