package com.nullandkale.nullspace.gameplay.pads;

import java.util.LinkedHashSet;

/** Bounded discovery on the supporting ring's exact layer. Console facing is cosmetic. */
public final class PadScanner
{
    public enum Cell
    {
        GRASS, DIRT, BORDER, OTHER, UNLOADED
    }

    @FunctionalInterface
    public interface Grid
    {
        Cell at(int x, int y, int z);
    }

    public record Pad(int minX, int maxX, int y, int minZ, int maxZ)
    {
        public int width()
        {
            return maxX - minX + 1;
        }

        public int depth()
        {
            return maxZ - minZ + 1;
        }

        public boolean overlaps(Pad b)
        {
            return y == b.y && minX - 1 <= b.maxX + 1 && maxX + 1 >= b.minX - 1 && minZ - 1 <= b.maxZ + 1 && maxZ + 1 >= b.minZ - 1;
        }

        public boolean onRing(int x, int z)
        {
            return x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1 && (x == minX - 1 || x == maxX + 1 || z == minZ - 1 || z == maxZ + 1);
        }
    }

    public record Point(int x, int y, int z)
    {}

    public record Result(Pad pad, String message, Point problem)
    {
        public Result(Pad pad, String message)
        {
            this(pad, message, null);
        }

        public boolean valid()
        {
            return pad != null;
        }
    }

    private static Result fail(String message, int x, int y, int z)
    {
        return new Result(null, message + " at " + x + ", " + y + ", " + z, new Point(x, y, z));
    }

    private static boolean surface(Cell cell)
    {
        return cell == Cell.GRASS || cell == Cell.DIRT;
    }

    public static Result scan(Grid grid, int cx, int cy, int cz, int dx, int dz)
    {
        if (Math.abs(dx) + Math.abs(dz) != 1)
            throw new IllegalArgumentException("Horizontal facing required");
        int y = cy - 1;
        var support = grid.at(cx, y, cz);
        if (support == Cell.UNLOADED)
            return fail("The pad area is still loading. Wait a moment and inspect again", cx, y, cz);
        if (support != Cell.BORDER)
            return new Result(null, "Place the pad computer on top of the cobblestone ring, beside its grass or dirt floor.");
        var found = new LinkedHashSet<Pad>();
        Result error = null;
        // Diagonal seeds also allow a controller on a corner of the ring.
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (var offset : offsets)
        {
            int x = cx + offset[0], z = cz + offset[1];
            var cell = grid.at(x, y, z);
            if (surface(cell))
            {
                var result = fromSurface(grid, x, y, z);
                if (result.valid() && result.pad.onRing(cx, cz))
                    found.add(result.pad);
                else if (!result.valid() && error == null)
                    error = result;
            }
            else if (cell == Cell.UNLOADED && error == null)
                error = fail("The pad area is still loading. Wait a moment and inspect again", x, y, z);
        }
        if (found.size() > 1)
            return new Result(null, "This computer touches more than one pad. Move it to an outer edge belonging to just one" + " cobblestone ring.");
        if (found.size() == 1)
        {
            var pad = found.iterator().next();
            return new Result(pad, "Valid pad: " + pad.width() + " x " + pad.depth() + ", ship height limit 32");
        }
        return error != null ? error : new Result(null, "No grass or dirt pad was found beside this computer. Place it on the cobblestone ring" + " at the pad's floor height; facing does not matter.");
    }

    private static Result fromSurface(Grid grid, int sx, int y, int sz)
    {
        int[] ends = new int[4];
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int d = 0; d < 4; d++)
        {
            boolean border = false;
            for (int n = 1; n <= 32; n++)
            {
                int x = sx + dirs[d][0] * n, z = sz + dirs[d][1] * n;
                Cell cell = grid.at(x, y, z);
                if (cell == Cell.BORDER)
                {
                    ends[d] = n;
                    border = true;
                    break;
                }
                if (cell == Cell.UNLOADED)
                    return fail("The pad area is still loading. Wait a moment and inspect again", x, y, z);
                if (!surface(cell))
                    return fail("Fill this hole with grass or dirt, or complete the cobblestone ring", x, y, z);
            }
            if (!border)
                return new Result(null, "The cobblestone ring is missing or too far away. Keep the pad interior within 32 x 32" + " blocks.");
        }
        var pad = new Pad(sx - ends[0] + 1, sx + ends[1] - 1, y, sz - ends[2] + 1, sz + ends[3] - 1);
        if (pad.width() > 32 || pad.depth() > 32)
            return new Result(null, "The pad interior is too large. Reduce it to at most 32 x 32 blocks.");
        for (int x = pad.minX - 1; x <= pad.maxX + 1; x++)
            for (int z = pad.minZ - 1; z <= pad.maxZ + 1; z++)
            {
                boolean edge = pad.onRing(x, z);
                var cell = grid.at(x, y, z);
                if (edge ? cell != Cell.BORDER : !surface(cell))
                    return fail(cell == Cell.UNLOADED ? "The pad area is still loading" : edge ? "Repair the cobblestone ring here" : "Replace this pad-floor block with grass or dirt", x, y, z);
            }
        return new Result(pad, "");
    }

    private PadScanner()
    {
    }
}
