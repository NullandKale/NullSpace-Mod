package com.nullandkale.nullspace.worldgen.stations;

import java.util.*;

import net.minecraft.core.*;

/**
 * A generated station before any block exists: room volumes, the doors that join them, and the
 * berths with their reserved approach space. Pure integers, so layouts can be checked without a
 * running game. Coordinates are station-local; every room's outer box includes its own walls, and
 * two rooms may share one wall layer.
 */
public record StationPlan(StationSpec spec, int width, int depth, int height, List<Room> rooms, List<Door> doors, List<Berth> berths, String arrival)
{

    /**
     * A level is a floor slab two blocks thick, then five clear blocks. The slab's lower block is
     * structure (and the ceiling of the level below); its upper block is the floor surface, which a room
     * can replace with water or soil without opening the room under it.
     */
    public static final int PITCH = 7;
    public static final int MAX_WIDTH = 128, MAX_HEIGHT = 32;

    /** What a room is for. The planner places volumes by use; batch 5 fills them. */
    public enum Use
    {
        ARRIVAL, ENGINEERING, DOCK, CORRIDOR, STORES, MAINTENANCE, COMMONS, KITCHEN, OBSERVATION, ARCHIVE, WORKSHOP, FOUNDRY, CARGO_HOLD, RECEIVING_BAY, GROWING_BAY, LAB, CABIN, BUNK_ROOM, GARDEN, OPERATIONS, ARMORY, LOOKOUT, EVA_PREP, INFIRMARY, BRIG,
        /** A ship's helm room: the docking door is in its nose wall. */
        BRIDGE
    }

    /** An inclusive block box. */
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1)
    {
        /** The number of layers the two boxes share on each axis; zero when apart. */
        public int[] overlap(Box o)
        {
            return new int[]{Math.max(0, Math.min(x1, o.x1) - Math.max(x0, o.x0) + 1), Math.max(0, Math.min(y1, o.y1) - Math.max(y0, o.y0) + 1), Math.max(0, Math.min(z1, o.z1) - Math.max(z0, o.z0) + 1)};
        }

        /** True when the boxes share more than a single wall layer. */
        public boolean conflicts(Box o)
        {
            var s = overlap(o);
            return s[0] >= 2 && s[1] >= 2 && s[2] >= 2;
        }

        public boolean intersects(Box o)
        {
            var s = overlap(o);
            return s[0] > 0 && s[1] > 0 && s[2] > 0;
        }

        public Box shift(int dx, int dz)
        {
            return new Box(x0 + dx, y0, z0 + dz, x1 + dx, y1, z1 + dz);
        }
    }

    /**
     * One room: outer footprint {@code w} by {@code d} at {@code x, z}, floor slab from {@code y},
     * {@code levels} stacked levels tall. A stair room climbs toward {@code climb}.
     */
    public record Room(String id, Use use, int x, int y, int z, int w, int d, int levels, Direction climb)
    {
        public Box outer()
        {
            return new Box(x, y, z, x + w - 1, y + levels * PITCH, z + d - 1);
        }

        public Box interior()
        {
            return new Box(x + 1, y + 2, z + 1, x + w - 2, y + levels * PITCH - 1, z + d - 2);
        }

        /** The row of the floor surface: the block people walk on, one above the slab's structural layer. */
        public int floorY()
        {
            return y + 1;
        }

        /** The row people stand in at this room's floor: one above the floor surface. */
        public int standY()
        {
            return y + 2;
        }

        public int level()
        {
            return y / PITCH;
        }

        public Room shift(int dx, int dz)
        {
            return new Room(id, use, x + dx, y, z + dz, w, d, levels, climb);
        }
    }

    /** A doorway between two rooms: the wall cell at standing height, and the way through from {@code a} to {@code b}. */
    public record Door(String a, String b, BlockPos at, Direction through)
    {
        public Door shift(int dx, int dz)
        {
            return new Door(a, b, at.offset(dx, 0, dz), through);
        }
    }

    /** A docking port in the outer wall of a dock room, and the clear box a docked ship may occupy. */
    public record Berth(String dock, BlockPos port, Direction facing, Box approach)
    {
        public Berth shift(int dx, int dz)
        {
            return new Berth(dock, port.offset(dx, 0, dz), facing, approach.shift(dx, dz));
        }
    }

    public Room room(String id)
    {
        for (var r : rooms)
            if (r.id().equals(id))
                return r;
        throw new IllegalArgumentException("No room " + id);
    }

    public List<Room> rooms(Use use)
    {
        return rooms.stream().filter(r -> r.use() == use).toList();
    }

    /** The number of levels with floors a visitor can stand on. */
    public int levels()
    {
        return rooms.stream().mapToInt(r -> r.level() + 1).max().orElse(0);
    }

    /** Rooms reachable through doors from {@code start}. Doors open both ways. */
    public Set<String> reachable(String start)
    {
        var seen = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>(List.of(start));
        seen.add(start);
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (var d : doors)
            {
                var other = d.a().equals(at) ? d.b() : d.b().equals(at) ? d.a() : null;
                if (other != null && seen.add(other))
                    queue.add(other);
            }
        }
        return seen;
    }

    /** Every unmet structural requirement; empty when the plan is sound. */
    public List<String> problems()
    {
        var out = new ArrayList<String>();
        if (width < 1 || depth < 1 || width > MAX_WIDTH || depth > MAX_WIDTH || height > MAX_HEIGHT)
            out.add("Station extent " + width + " x " + height + " x " + depth + " exceeds 128 x 32 x 128");
        var ids = new HashSet<String>();
        for (var r : rooms)
        {
            if (!ids.add(r.id()))
                out.add("Duplicate room " + r.id());
            var o = r.outer();
            if (o.x0() < 0 || o.z0() < 0 || o.y0() < 0 || o.x1() >= width || o.z1() >= depth || o.y1() >= height)
                out.add("Room " + r.id() + " is outside the station");
        }
        for (int i = 0; i < rooms.size(); i++)
            for (int j = i + 1; j < rooms.size(); j++)
                if (rooms.get(i).outer().conflicts(rooms.get(j).outer()))
                    out.add("Rooms " + rooms.get(i).id() + " and " + rooms.get(j).id() + " overlap");
        if (rooms.isEmpty() || arrival == null || !ids.contains(arrival))
            return out;
        var reach = reachable(arrival);
        for (var r : rooms)
            if (!reach.contains(r.id()))
                out.add("Room " + r.id() + " cannot be reached from the arrival room");
        for (var d : doors)
            if (!ids.contains(d.a()) || !ids.contains(d.b()))
                out.add("Door joins an unknown room");
        if (berths.isEmpty())
            out.add("Station has no berth");
        for (int i = 0; i < berths.size(); i++)
        {
            var b = berths.get(i);
            if (!ids.contains(b.dock()) || room(b.dock()).use() != Use.DOCK && room(b.dock()).use() != Use.BRIDGE)
                out.add("Berth " + i + " is not in a dock room");
            for (var r : rooms)
                if (b.approach().intersects(r.outer()))
                    out.add("Berth " + i + " approach is blocked by room " + r.id());
            for (int j = i + 1; j < berths.size(); j++)
                if (b.approach().intersects(berths.get(j).approach()))
                    out.add("Berth " + i + " and berth " + j + " approaches overlap");
        }
        return out;
    }
}
