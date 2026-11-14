package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.StationPlan.*;
import com.nullandkale.nullspace.worldgen.stations.StationProfiles.Item;

import java.util.*;
import java.util.random.RandomGenerator;

import net.minecraft.core.*;

/** Shared placement toolkit for the manufacturer layout methods: rooms, doors, stairs, slots and berths. */
final class StationBuilder
{
    static final int DOCK_WIDTH = 7, DOCK_DEPTH = 8, BERTH = 34, CORRIDOR = 5;
    static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /** Somewhere a room can be attached: a wall of an existing room. */
    record Slot(Room anchor, Direction side)
    {}

    final StationSpec spec;
    final RandomGenerator rng;
    final List<Room> rooms = new ArrayList<>();
    final List<Door> doors = new ArrayList<>();
    final List<Berth> berths = new ArrayList<>();
    final int budget, maxLevels;
    /** Where non-dock rooms may stand, inclusive. Docks use the margin outside it. */
    int wx0, wx1, wz0, wz1;
    String arrival;
    /** The last unmet requirement a layout method hit, for failure messages. */
    String note = "";
    private final Map<Use, Integer> counters = new EnumMap<>(Use.class);

    /** The fewest and most levels a station of this size may stand: small 1-2, medium 2-3, large 3-4. */
    static int[] levelRange(StationSpec.Size size)
    {
        return switch (size)
        {
            case COMPACT -> new int[]{1, 2};
            case STANDARD -> new int[]{2, 3};
            case LARGE -> new int[]{3, 4};
        };
    }

    /** {@code levels} is how many decks this attempt builds; the arrival hall is that tall. */
    StationBuilder(StationSpec spec, RandomGenerator rng, int levels)
    {
        this.spec = spec;
        this.rng = rng;
        this.budget = switch (spec.size())
        {
            case COMPACT -> 64;
            case STANDARD -> 96;
            case LARGE -> 128;
        };
        this.maxLevels = levels;
        int margin = StationBuilder.DOCK_DEPTH;
        boolean xDocks = spec.size() != StationSpec.Size.COMPACT || rng.nextBoolean();
        boolean zDocks = spec.size() != StationSpec.Size.COMPACT || !xDocks;
        // A medium station with a third deck stands on a smaller footprint: the extra height replaces length.
        int shrink = spec.size() == StationSpec.Size.STANDARD && levels >= 3 ? 8 : 0;
        wx0 = (xDocks ? margin : 0) + shrink;
        wx1 = budget - 1 - (xDocks ? margin : 0) - shrink;
        wz0 = (zDocks ? margin : 0) + shrink;
        wz1 = budget - 1 - (zDocks ? margin : 0) - shrink;
    }

    /** A ship's builder: a fixed window of {@code width} by {@code depth} blocks and {@code levels} decks, with no dock margins. */
    StationBuilder(StationSpec spec, RandomGenerator rng, int levels, int width, int depth)
    {
        this.spec = spec;
        this.rng = rng;
        this.budget = Math.max(width, depth);
        this.maxLevels = levels;
        wx0 = 0;
        wx1 = width - 1;
        wz0 = 0;
        wz1 = depth - 1;
    }

    /** True when the arrival hall carries stair flights: it is more than one deck tall. */
    boolean tall()
    {
        return maxLevels >= 2;
    }

    /** The hall walls that carry stair flights: flights alternate between the climb's wall and the one opposite. */
    static Set<Direction> flightWalls(Room hall)
    {
        if (hall.levels() < 2 || hall.climb() == null)
            return Set.of();
        var wall = hall.climb().getClockWise();
        return hall.levels() >= 3 ? Set.of(wall, wall.getOpposite()) : Set.of(wall);
    }

    /** True when the window is at least as long in x as in z: where a compact station runs its chain. */
    boolean longAxisX()
    {
        return wx1 - wx0 >= wz1 - wz0;
    }

    int nextInt(int lo, int hi)
    {
        return lo >= hi ? lo : lo + rng.nextInt(hi - lo + 1);
    }

    String name(Use use)
    {
        return use.name().toLowerCase(Locale.ROOT) + "_" + counters.merge(use, 1, Integer::sum);
    }

    private int[] extents(Room extra)
    {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        var all = new ArrayList<>(rooms);
        if (extra != null)
            all.add(extra);
        for (var r : all)
        {
            x0 = Math.min(x0, r.x());
            z0 = Math.min(z0, r.z());
            x1 = Math.max(x1, r.x() + r.w() - 1);
            z1 = Math.max(z1, r.z() + r.d() - 1);
        }
        return new int[]{x0, z0, x1, z1};
    }

    boolean fits(Room room, boolean dock)
    {
        var o = room.outer();
        if (o.y0() < 0 || room.level() + room.levels() > maxLevels)
            return false;
        if (dock)
        {
            var e = extents(room);
            if (e[2] - e[0] + 1 > budget || e[3] - e[1] + 1 > budget)
                return false;
        }
        else if (o.x0() < wx0 || o.x1() > wx1 || o.z0() < wz0 || o.z1() > wz1)
            return false;
        for (var r : rooms)
            if (r.outer().conflicts(o))
                return false;
        return true;
    }

    /** The doorway joining two rooms that share a wall layer, or null when the shared wall is too short or too low. */
    static Door doorBetween(Room a, Room b)
    {
        var A = a.outer();
        var B = b.outer();
        int ys = Math.max(a.y(), b.y());
        var ia = a.interior();
        var ib = b.interior();
        if (ys + 3 > Math.min(ia.y1(), ib.y1()) || ys + 2 < Math.max(ia.y0(), ib.y0()))
            return null;
        if ((A.x1() == B.x0() || B.x1() == A.x0()) && Math.min(A.z1(), B.z1()) - Math.max(A.z0(), B.z0()) >= 3)
        {
            boolean east = A.x1() == B.x0();
            int lo = Math.max(ia.z0(), ib.z0()), hi = Math.min(ia.z1(), ib.z1());
            if (hi - lo + 1 < 2)
                return null;
            return new Door(a.id(), b.id(), new BlockPos(east ? A.x1() : A.x0(), ys + 2, (lo + hi) / 2), east ? Direction.EAST : Direction.WEST);
        }
        if ((A.z1() == B.z0() || B.z1() == A.z0()) && Math.min(A.x1(), B.x1()) - Math.max(A.x0(), B.x0()) >= 3)
        {
            boolean south = A.z1() == B.z0();
            int lo = Math.max(ia.x0(), ib.x0()), hi = Math.min(ia.x1(), ib.x1());
            if (hi - lo + 1 < 2)
                return null;
            return new Door(a.id(), b.id(), new BlockPos((lo + hi) / 2, ys + 2, south ? A.z1() : A.z0()), south ? Direction.SOUTH : Direction.NORTH);
        }
        return null;
    }

    /** Places a room with no neighbour requirement, for the first room of a layout. */
    Room place(Use use, int x, int y, int z, int w, int d, int levels)
    {
        return place(use, x, y, z, w, d, levels, null);
    }

    Room place(Use use, int x, int y, int z, int w, int d, int levels, Direction climb)
    {
        var room = new Room(name(use), use, x, y, z, w, d, levels, climb);
        if (!fits(room, false))
        {
            counters.merge(use, -1, Integer::sum);
            return null;
        }
        rooms.add(room);
        return room;
    }

    /** The position along {@code anchor}'s wall that centres a room {@code along} blocks wide on it. */
    static int centered(Room anchor, Direction side, int along)
    {
        boolean ns = side.getAxis() == Direction.Axis.Z;
        int lateral = ns ? anchor.w() : anchor.d();
        return (ns ? anchor.x() : anchor.z()) + (lateral - along) / 2;
    }

    /**
     * Attaches a room to a wall of {@code anchor}, sharing that wall layer, at exactly {@code pos}
     * along the wall. {@code along} is its size along the wall, {@code depth} away from it. Returns
     * null when it does not fit or no doorway can join the two.
     */
    Room attachAt(Room anchor, Direction side, Use use, int along, int depth, int levels, int y, Direction climb, int pos)
    {
        boolean ns = side.getAxis() == Direction.Axis.Z;
        int x, z, w, d;
        if (ns)
        {
            w = along;
            d = depth;
            x = pos;
            z = side == Direction.NORTH ? anchor.z() - depth + 1 : anchor.z() + anchor.d() - 1;
        }
        else
        {
            w = depth;
            d = along;
            z = pos;
            x = side == Direction.WEST ? anchor.x() - depth + 1 : anchor.x() + anchor.w() - 1;
        }
        var probe = new Room("", use, x, y, z, w, d, levels, climb);
        if (!fits(probe, use == Use.DOCK))
            return null;
        var room = new Room(name(use), use, x, y, z, w, d, levels, climb);
        var door = doorBetween(anchor, room);
        if (door == null)
        {
            counters.merge(use, -1, Integer::sum);
            return null;
        }
        rooms.add(room);
        doors.add(door);
        return room;
    }

    /** As {@link #attachAt}, centred on the wall. */
    Room attachCentered(Room anchor, Direction side, Use use, int along, int depth, int levels, int y, Direction climb)
    {
        return attachAt(anchor, side, use, along, depth, levels, y, climb, centered(anchor, side, along));
    }

    /** Removes the most recently attached room and its door, after a later step failed. */
    void undo(Room room)
    {
        rooms.remove(room);
        doors.removeIf(d -> d.a().equals(room.id()) || d.b().equals(room.id()));
    }

    /**
     * Adds centred dock rooms, with their ports and approach boxes, at the ends of corridors and on
     * the sides of the arrival room, until {@code target} berths exist or nothing more fits.
     */
    void berths(int target)
    {
        var candidates = new ArrayList<Slot>();
        for (var room : List.copyOf(rooms))
        {
            if (room.use() == Use.ARRIVAL)
            {
                // The sides the atrium flights run along carry no door.
                var stairSides = flightWalls(room);
                for (var side : SIDES)
                    if (!stairSides.contains(side))
                        candidates.add(new Slot(room, side));
            }
            else if (room.use() == Use.CORRIDOR && room.w() == room.d())
                for (var side : SIDES)
                    candidates.add(new Slot(room, side));
            else if (room.use() == Use.CORRIDOR)
            {
                boolean alongX = room.w() >= room.d();
                candidates.add(new Slot(room, alongX ? Direction.EAST : Direction.SOUTH));
                candidates.add(new Slot(room, alongX ? Direction.WEST : Direction.NORTH));
            }
        }
        for (int i = candidates.size() - 1; i > 0; i--)
            Collections.swap(candidates, i, rng.nextInt(i + 1));
        for (var slot : candidates)
        {
            if (berths.size() >= target)
                return;
            var dock = attachCentered(slot.anchor(), slot.side(), Use.DOCK, DOCK_WIDTH, DOCK_DEPTH, 1, slot.anchor().y(), null);
            if (dock == null)
                continue;
            var berth = berth(dock, slot.side());
            boolean clear = true;
            for (var r : rooms)
                clear &= !berth.approach().intersects(r.outer());
            for (var b : berths)
                clear &= !berth.approach().intersects(b.approach());
            if (clear)
                berths.add(berth);
            else
                undo(dock);
        }
    }

    static Berth berth(Room dock, Direction facing)
    {
        var o = dock.outer();
        int py = dock.y() + 2;
        int cx = dock.x() + dock.w() / 2, cz = dock.z() + dock.d() / 2;
        var port = switch (facing)
        {
            case EAST -> new BlockPos(o.x1(), py, cz);
            case WEST -> new BlockPos(o.x0(), py, cz);
            case SOUTH -> new BlockPos(cx, py, o.z1());
            default -> new BlockPos(cx, py, o.z0());
        };
        int y0 = py - BERTH / 2, y1 = y0 + BERTH - 1;
        var box = switch (facing)
        {
            case EAST -> new Box(port.getX() + 1, y0, cz - BERTH / 2, port.getX() + BERTH, y1, cz - BERTH / 2 + BERTH - 1);
            case WEST -> new Box(port.getX() - BERTH, y0, cz - BERTH / 2, port.getX() - 1, y1, cz - BERTH / 2 + BERTH - 1);
            case SOUTH -> new Box(cx - BERTH / 2, y0, port.getZ() + 1, cx - BERTH / 2 + BERTH - 1, y1, port.getZ() + BERTH);
            default -> new Box(cx - BERTH / 2, y0, port.getZ() - BERTH, cx - BERTH / 2 + BERTH - 1, y1, port.getZ() - 1);
        };
        return new Berth(dock.id(), port, facing, box);
    }

    /** The finished plan, shifted so the station starts at the origin; null when it is unsound. */
    StationPlan finish()
    {
        if (rooms.isEmpty() || arrival == null)
            return null;
        // A corridor that leads nowhere (an unused pier or an empty hall) is dead space: take it out.
        boolean pruned = true;
        while (pruned)
        {
            pruned = false;
            for (var room : List.copyOf(rooms))
            {
                if (room.use() != Use.CORRIDOR)
                    continue;
                long links = doors.stream().filter(d -> d.a().equals(room.id()) || d.b().equals(room.id())).count();
                if (links <= 1)
                {
                    undo(room);
                    pruned = true;
                }
            }
        }
        var e = extents(null);
        int dx = -e[0], dz = -e[1];
        var shifted = rooms.stream().map(r -> r.shift(dx, dz)).toList();
        int height = shifted.stream().mapToInt(r -> r.outer().y1()).max().orElse(0) + 1;
        var plan = new StationPlan(spec, e[2] - e[0] + 1, e[3] - e[1] + 1, height, shifted, doors.stream().map(d -> d.shift(dx, dz)).toList(), berths.stream().map(b -> b.shift(dx, dz)).toList(), arrival);
        return plan.problems().isEmpty() ? plan : null;
    }
}
