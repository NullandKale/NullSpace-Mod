package com.nullandkale.nullspace.model.air;

import java.util.*;

/**
 * Bounded cell quantities. Interior history keeps a breached room finite instead of mixing with
 * infinite vacuum.
 */
public final class Atmosphere
{
    public static final int SIZE = 32, CELLS = 32768, FULL = 1000, BREATHABLE = 250;

    public record Grid(int width, int height, int depth)
    {
        public Grid
        {
            if (width < 1 || width > 128 || height < 1 || height > 32 || depth < 1 || depth > 128)
                throw new IllegalArgumentException("Atmosphere exceeds station envelope");
        }

        public int cells()
        {
            return width * height * depth;
        }

        public int index(int x, int y, int z)
        {
            return x + z * width + y * width * depth;
        }

        public int x(int cell)
        {
            return cell % width;
        }

        public int y(int cell)
        {
            return cell / (width * depth);
        }

        public int z(int cell)
        {
            return cell / width % depth;
        }

        public int[] offsets()
        {
            return new int[]{-1, 1, -width * depth, width * depth, -width, width};
        }

        public boolean contains(int x, int y, int z)
        {
            return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth;
        }
    }

    public static final Grid SHIP = new Grid(32, 32, 32);

    public record Room(int[] cells, int leaks)
    {}

    /** roomAt uses -1 for exterior/solid cells. Arrays belong to this bounded server topology. */
    public record Topology(List<Room> rooms, int[] roomAt, long displaced, long vented, Grid grid)
    {
        public Topology(List<Room> rooms, int[] roomAt, long displaced, long vented)
        {
            this(rooms, roomAt, displaced, vented, SHIP);
        }
    }

    public static int index(int x, int y, int z)
    {
        return x + z * 32 + y * 1024;
    }

    public static List<Room> rooms(boolean[] seals, int[] previous, boolean initialize)
    {
        return rebuild(seals, previous, initialize).rooms();
    }

    public static Topology rebuild(boolean[] seals, int[] previous, boolean initialize)
    {
        return rebuild(seals, previous, initialize, SHIP);
    }

    public static Topology rebuild(boolean[] seals, int[] previous, boolean initialize, Grid grid)
    {
        int cells = grid.cells();
        var neighbors = neighborMasks(grid);
        var offsets = grid.offsets();
        if (seals.length != cells || previous.length != cells)
            throw new IllegalArgumentException("Invalid atmosphere grid");
        var outside = new BitSet(cells);
        int[] queue = new int[cells];
        int head = 0, tail = 0;
        for (int i = 0; i < cells; i++)
            if (!seals[i] && neighbors[i] != 63)
            {
                outside.set(i);
                queue[tail++] = i;
            }
        while (head < tail)
        {
            int i = queue[head++], mask = neighbors[i];
            for (int d = 0; d < 6; d++)
                if ((mask & (1 << d)) != 0)
                {
                    int n = i + offsets[d];
                    if (!seals[n] && !outside.get(n))
                    {
                        outside.set(n);
                        queue[tail++] = n;
                    }
                }
        }
        var interior = new BitSet(cells);
        for (int i = 0; i < cells; i++)
            if (!seals[i] && (!outside.get(i) || previous[i] >= 0))
            {
                interior.set(i);
                if (previous[i] < 0)
                    previous[i] = initialize ? FULL : 0;
            }
        // Follow whole clusters of newly filled old air cells. Thick walls must not
        // silently delete the gas in their middle. Old solid cells remain barriers.
        long displaced = 0, vented = 0;
        var filled = new BitSet(cells);
        int[] destinations = null, marked = null;
        int stamp = 0;
        for (int start = 0; start < cells; start++)
            if (seals[start] && previous[start] >= 0 && !filled.get(start))
            {
                if (destinations == null)
                {
                    destinations = new int[cells];
                    marked = new int[cells];
                }
                int count = 0;
                long quantity = 0;
                head = 0;
                tail = 0;
                stamp++;
                filled.set(start);
                queue[tail++] = start;
                while (head < tail)
                {
                    int i = queue[head++], mask = neighbors[i];
                    quantity += previous[i];
                    for (int d = 0; d < 6; d++)
                        if ((mask & (1 << d)) != 0)
                        {
                            int n = i + offsets[d];
                            if (seals[n] && previous[n] >= 0 && !filled.get(n))
                            {
                                filled.set(n);
                                queue[tail++] = n;
                            }
                            else if (interior.get(n) && marked[n] != stamp)
                            {
                                marked[n] = stamp;
                                destinations[count++] = n;
                            }
                        }
                }
                if (count == 0)
                    vented += quantity;
                else
                {
                    displaced += quantity;
                    long share = quantity / count, extra = quantity % count;
                    for (int n = 0; n < count; n++)
                        previous[destinations[n]] = Math.toIntExact(previous[destinations[n]] + share + (n < extra ? 1 : 0));
                }
            }
        for (int i = 0; i < cells; i++)
            if (!interior.get(i))
                previous[i] = -1;
        var result = new ArrayList<Room>();
        int[] roomAt = new int[cells];
        Arrays.fill(roomAt, -1);
        for (int start = interior.nextSetBit(0); start >= 0; start = interior.nextSetBit(start + 1))
        {
            if (roomAt[start] >= 0)
                continue;
            head = 0;
            tail = 0;
            int room = result.size(), leaks = 0;
            roomAt[start] = room;
            queue[tail++] = start;
            while (head < tail)
            {
                int i = queue[head++], mask = neighbors[i];
                leaks += 6 - Integer.bitCount(mask);
                for (int d = 0; d < 6; d++)
                    if ((mask & (1 << d)) != 0)
                    {
                        int n = i + offsets[d];
                        if (interior.get(n))
                        {
                            if (roomAt[n] < 0)
                            {
                                roomAt[n] = room;
                                queue[tail++] = n;
                            }
                        }
                        else if (!seals[n])
                            leaks++;
                    }
            }
            result.add(new Room(Arrays.copyOf(queue, tail), leaks));
        }
        return new Topology(List.copyOf(result), roomAt, displaced, vented, grid);
    }

    private static byte[] neighborMasks(Grid grid)
    {
        byte[] result = new byte[grid.cells()];
        for (int i = 0; i < result.length; i++)
        {
            int x = grid.x(i), y = grid.y(i), z = grid.z(i);
            result[i] = (byte) ((x > 0 ? 1 : 0) | (x < grid.width() - 1 ? 2 : 0) | (y > 0 ? 4 : 0) | (y < grid.height() - 1 ? 8 : 0) | (z > 0 ? 16 : 0) | (z < grid.depth() - 1 ? 32 : 0));
        }
        return result;
    }

    private Atmosphere()
    {
    }
}
