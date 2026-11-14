package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.*;
import com.nullandkale.nullspace.worldgen.stations.StationProfiles.Item;

import java.util.*;

import net.minecraft.core.Direction;

/**
 * The three manufacturer layout methods. Every walking path is a straight carrier (a corridor, or a
 * chain of halls) with rooms flush along it in columns, mirrored across it, each with a door at the
 * middle of its wall. Sizes come from short lists so facades step cleanly, and the room graph is a
 * tree. Levels are stacked decks: the arrival hall is a tall atrium with one stair flight to a gallery
 * ring, and each upper arm sits directly over a lower one. A seed chooses the arrangement, the sizes
 * and which rooms go where; it never produces a bend or an off-centre door.
 */
final class StationMethods
{
    private static final int LANE = StationBuilder.CORRIDOR, PITCH = StationPlan.PITCH;

    /** One column of bays along a carrier: up to two rooms facing each other across it, the same size. */
    private record Column(Item left, Item right, int width, int depth, int minimum)
    {
        Column narrowed(int to)
        {
            return new Column(left, right, to, depth, minimum);
        }
    }

    static boolean layout(Maker maker, StationBuilder b, List<Item> items)
    {
        return switch (maker)
        {
            case RIVET -> rivet(b, items);
            case HALCYON -> halcyon(b, items);
            case BASTION -> bastion(b, items);
        };
    }

    private static int pick(StationBuilder b, int... values)
    {
        return values[b.rng.nextInt(values.length)];
    }

    private static int levels(StationBuilder b)
    {
        return b.maxLevels;
    }

    private static boolean optional(Column c)
    {
        return (c.left() == null || !c.left().required()) && (c.right() == null || !c.right().required());
    }

    /**
     * Pairs the program into columns. Large rooms share one depth and small rooms another, so a
     * carrier's facade has at most two steps; widths come from a short list.
     */
    private static List<Column> columns(StationBuilder b, List<Item> items)
    {
        boolean compact = b.spec.size() == StationSpec.Size.COMPACT;
        int deepDepth = compact ? 8 : pick(b, 14, 16, 18), shallowDepth = compact ? 8 : pick(b, 10, 12);
        var deep = new ArrayList<Item>();
        var shallow = new ArrayList<Item>();
        for (var item : items)
            (item.size() == StationProfiles.Class.LARGE && !compact ? deep : shallow).add(item);
        var out = new ArrayList<Column>();
        // One width per class gives each facade a regular rhythm.
        int deepWidth = pick(b, 12, 14, 16, 18), shallowWidth = pick(b, 8, 10, 12);
        boolean deepFirst = b.rng.nextBoolean();
        for (int pass = 0; pass < 2; pass++)
        {
            boolean isDeep = (pass == 0) == deepFirst;
            var group = isDeep ? deep : shallow;
            for (int i = 0; i < group.size(); i += 2)
            {
                int width = isDeep ? deepWidth : shallowWidth;
                out.add(new Column(group.get(i), i + 1 < group.size() ? group.get(i + 1) : null, width, isDeep ? deepDepth : shallowDepth, isDeep ? 12 : 8));
            }
        }
        return out;
    }

    private static int span(List<Column> columns)
    {
        int total = 1;
        for (var c : columns)
            total += c.width() - 1;
        return total;
    }

    /**
     * Deals the columns one at a time across the carriers, so every carrier is used and mirrored arms
     * stay balanced. A column that does not fit narrows toward its minimum width before the next
     * carrier is tried. Returns the columns no carrier could take.
     */
    private static List<Column> deal(List<Column> pending, List<List<Column>> slots, int[] caps, boolean narrow)
    {
        var left = new ArrayList<Column>();
        int turn = 0;
        for (var c : pending)
        {
            boolean placed = false;
            for (int k = 0; k < slots.size() && !placed; k++)
            {
                int i = (turn + k) % slots.size();
                int free = caps[i] - span(slots.get(i)) + 1;
                int width = narrow ? Math.min(c.width(), free) : free >= c.width() ? c.width() : 0;
                if (width >= c.minimum() && width > 0)
                {
                    slots.get(i).add(c.narrowed(width));
                    turn = i + 1;
                    placed = true;
                }
            }
            if (!placed)
                left.add(c);
        }
        return left;
    }

    /** Places each column's rooms flush along {@code carrier}, from its start; false when a required room did not fit. */
    private static boolean bays(StationBuilder b, Room carrier, List<Column> columns, int start, boolean fromEnd)
    {
        boolean alongX = carrier.w() >= carrier.d();
        var first = alongX ? Direction.NORTH : Direction.WEST;
        var second = alongX ? Direction.SOUTH : Direction.EAST;
        // Arms leaving the hub toward west or north have their hub end at the highest coordinate: run the chain from there.
        int cursor = fromEnd ? (alongX ? carrier.x() + carrier.w() - 1 : carrier.z() + carrier.d() - 1) - start : (alongX ? carrier.x() : carrier.z()) + start;
        boolean ok = true;
        for (var c : columns)
        {
            int p = fromEnd ? cursor - (c.width() - 1) : cursor;
            var sides = new Direction[]{first, second};
            var items = new Item[]{c.left(), c.right()};
            for (int k = 0; k < 2; k++)
            {
                if (items[k] == null)
                    continue;
                var room = b.attachAt(carrier, sides[k], items[k].use(), c.width(), c.depth(), 1, carrier.y(), null, p);
                if (room == null && items[k].required())
                {
                    ok = false;
                    b.note = items[k].use() + " " + c.width() + "x" + c.depth() + " did not fit beside " + carrier.id();
                }
            }
            cursor = fromEnd ? p : cursor + c.width() - 1;
        }
        return ok;
    }

    /** A free band side for the atrium stair: one of the two sides across {@code axis}. */
    private static Direction stairBand(StationBuilder b, Direction axis)
    {
        return axis.getAxis() == Direction.Axis.X ? (b.rng.nextBoolean() ? Direction.NORTH : Direction.SOUTH) : (b.rng.nextBoolean() ? Direction.WEST : Direction.EAST);
    }

    /** The flight's climb direction for a stair along the band on {@code band}: the band is on the climber's right. */
    private static Direction climbFor(Direction band)
    {
        return band.getCounterClockWise();
    }

    // ---- Rivet Works: a service spine from the arrival hall ----

    private static boolean rivet(StationBuilder b, List<Item> items)
    {
        boolean compact = b.spec.size() == StationSpec.Size.COMPACT;
        boolean alongX = compact ? b.longAxisX() : b.rng.nextBoolean();
        var heading = alongX ? Direction.EAST : Direction.SOUTH;
        int levels = levels(b);
        // A hall with stairs is at least 11 wide so the flight lands.
        int hubSize = compact ? (b.tall() ? 11 : 9) : pick(b, 11, 13);
        // The arrival either sits mid-spine, with an arm each way, or at one end with a single arm.
        boolean centred = compact || b.rng.nextBoolean();
        int x0 = b.wx0, x1 = b.wx1, z0 = b.wz0, z1 = b.wz1;
        int hx = alongX ? (centred ? (x0 + x1) / 2 - hubSize / 2 : x0) : (x0 + x1) / 2 - hubSize / 2;
        int hz = alongX ? (z0 + z1) / 2 - hubSize / 2 : (centred ? (z0 + z1) / 2 - hubSize / 2 : z0);
        var band = stairBand(b, heading);
        var hub = b.place(Use.ARRIVAL, hx, 0, hz, hubSize, hubSize, levels, b.tall() ? climbFor(band) : null);
        if (hub == null)
            return false;
        b.arrival = hub.id();
        var arms = centred ? List.of(heading, heading.getOpposite()) : List.of(heading);
        boolean piers = b.spec.size() != StationSpec.Size.COMPACT && b.rng.nextInt(4) > 0;
        return decks(b, hub, arms, items, levels, 0, piers);
    }

    /** A run of bays along one arm at one level: its start along the arm and the length it may use. */
    private record Segment(int arm, int level, int start, int length)
    {}

    /**
     * Fills stacked arms of a hub with the program's columns. {@code lead} keeps the first stretch of
     * each arm bare so crossed arms clear each other's bays. With {@code piers}, straight piers leave
     * the ground-level arms every 34 blocks, each ending in a berth, and bays fill the stretches between.
     */
    private static boolean decks(StationBuilder b, Room hub, List<Direction> arms, List<Item> items, int levels, int lead, boolean piers)
    {
        var pending = columns(b, items);
        var segments = new ArrayList<Segment>();
        var pierOffsets = new ArrayList<List<Integer>>();
        int pierLength = 0;
        for (int arm = 0; arm < arms.size(); arm++)
        {
            int reach = reach(b, hub, arms.get(arm));
            var offsets = new ArrayList<Integer>();
            if (piers)
            {
                int across = arms.get(arm).getAxis() == Direction.Axis.X ? (b.wz1 - b.wz0 + 1) : (b.wx1 - b.wx0 + 1);
                pierLength = Math.max(pierLength, (across - LANE) / 2 + 1);
                for (int o = 12; o + 8 <= reach; o += 34)
                    offsets.add(o);
            }
            pierOffsets.add(offsets);
            for (int level = 0; level < levels; level++)
            {
                int cursor = lead;
                for (int o : offsets)
                {
                    if (o - 2 - cursor + 1 >= 6)
                        segments.add(new Segment(arm, level, cursor, o - 2 - cursor + 1));
                    cursor = o + 2;
                }
                if (reach - cursor >= 6)
                    segments.add(new Segment(arm, level, cursor, reach - cursor));
            }
        }
        var slots = new ArrayList<List<Column>>();
        var caps = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++)
        {
            slots.add(new ArrayList<>());
            caps[i] = segments.get(i).length();
        }
        var left = deal(pending, slots, caps, false);
        if (!left.stream().allMatch(StationMethods::optional))
        {
            // Some required room found no run at its full width: let columns narrow.
            slots.forEach(List::clear);
            left = deal(pending, slots, caps, true);
        }
        boolean ok = left.stream().allMatch(StationMethods::optional);
        if (!ok)
            b.note = left.size() + " columns had no room (" + segments.size() + " runs)";
        for (int arm = 0; arm < arms.size(); arm++)
            for (int level = 0; level < levels; level++)
            {
                int length = 0;
                for (int i = 0; i < segments.size(); i++)
                    if (segments.get(i).arm() == arm && segments.get(i).level() == level && !slots.get(i).isEmpty())
                        length = Math.max(length, segments.get(i).start() + span(slots.get(i)));
                if (level == 0)
                    for (int o : pierOffsets.get(arm))
                        if (o + 8 <= reach(b, hub, arms.get(arm)))
                            length = Math.max(length, o + 8);
                if (length == 0)
                    continue;
                var carrier = b.attachCentered(hub, arms.get(arm), Use.CORRIDOR, LANE, length, 1, level * PITCH, null);
                if (carrier == null)
                {
                    for (int i = 0; i < segments.size(); i++)
                        if (segments.get(i).arm() == arm && segments.get(i).level() == level)
                            ok &= slots.get(i).stream().allMatch(StationMethods::optional);
                    continue;
                }
                for (int i = 0; i < segments.size(); i++)
                    if (segments.get(i).arm() == arm && segments.get(i).level() == level && !slots.get(i).isEmpty())
                        ok &= bays(b, carrier, slots.get(i), segments.get(i).start(), fromHubEnd(arms.get(arm)));
                if (level == 0 && piers)
                    pier(b, carrier, pierOffsets.get(arm), pierLength, fromHubEnd(arms.get(arm)));
            }
        return ok;
    }

    /** Straight piers across the spine at each offset, both ways, each ending where a berth can dock. */
    private static void pier(StationBuilder b, Room carrier, List<Integer> offsets, int length, boolean fromEnd)
    {
        boolean alongX = carrier.w() >= carrier.d();
        int low = alongX ? carrier.x() : carrier.z();
        int high = low + (alongX ? carrier.w() : carrier.d()) - 1;
        for (int o : offsets)
        {
            if (o + 8 > (alongX ? carrier.w() : carrier.d()))
                continue;
            int pos = fromEnd ? high - o - 2 : low + o - 2;
            for (var side : alongX ? List.of(Direction.NORTH, Direction.SOUTH) : List.of(Direction.WEST, Direction.EAST))
                b.attachAt(carrier, side, Use.CORRIDOR, LANE, length, 1, 0, null, pos);
        }
    }

    /** Arms leaving toward west or north are placed with their hub end at the highest coordinate. */
    private static boolean fromHubEnd(Direction dir)
    {
        return dir == Direction.WEST || dir == Direction.NORTH;
    }

    /** How far a carrier leaving {@code hub} toward {@code dir} may run inside the window. */
    private static int reach(StationBuilder b, Room hub, Direction dir)
    {
        return switch (dir)
        {
            case EAST -> b.wx1 - (hub.x() + hub.w() - 1) + 1;
            case WEST -> hub.x() - b.wx0 + 1;
            case SOUTH -> b.wz1 - (hub.z() + hub.d() - 1) + 1;
            default -> hub.z() - b.wz0 + 1;
        };
    }

    // ---- Halcyon Aerospace: a central hub with spokes ----

    private static boolean halcyon(StationBuilder b, List<Item> items)
    {
        boolean compact = b.spec.size() == StationSpec.Size.COMPACT;
        boolean cross = b.spec.size() == StationSpec.Size.LARGE || (b.spec.size() == StationSpec.Size.STANDARD && b.rng.nextBoolean());
        // A crossed hub needs room for the eight-cell stair along a wall whose door is at its middle, with a cell to spare.
        int hubSize = compact ? (b.tall() ? 11 : 9) : cross ? 19 : pick(b, 11, 13);
        boolean alongX = compact ? b.longAxisX() : b.rng.nextBoolean();
        var axis = alongX ? Direction.EAST : Direction.SOUTH;
        int levels = levels(b);
        int cx = (b.wx0 + b.wx1) / 2, cz = (b.wz0 + b.wz1) / 2;
        var band = cross ? Direction.values()[2 + b.rng.nextInt(4)] : stairBand(b, axis);
        var hub = b.place(Use.ARRIVAL, cx - hubSize / 2, 0, cz - hubSize / 2, hubSize, hubSize, levels, b.tall() ? climbFor(band) : null);
        if (hub == null)
            return false;
        b.arrival = hub.id();
        var arms = new ArrayList<>(List.of(axis, axis.getOpposite()));
        if (cross)
        {
            arms.add(axis.getClockWise());
            arms.add(axis.getCounterClockWise());
        }
        return decks(b, hub, arms, items, levels, cross ? 12 : 0, false);
    }

    // ---- Bastion Shipbuilding: a chain of halls, each with its own rooms ----

    private static boolean bastion(StationBuilder b, List<Item> items)
    {
        boolean compact = b.spec.size() == StationSpec.Size.COMPACT;
        boolean alongX = compact ? b.longAxisX() : b.rng.nextBoolean();
        var heading = alongX ? Direction.EAST : Direction.SOUTH;
        int hall = compact ? 9 : pick(b, 9, 11);
        int passage = compact ? 4 : pick(b, 5, 7);
        int levels = levels(b);
        int depthSmall = compact ? 8 : pick(b, 10, 12), depthLarge = compact ? 8 : pick(b, 14, 16);
        int window = alongX ? b.wx1 - b.wx0 + 1 : b.wz1 - b.wz0 + 1;
        int cross = alongX ? b.wz1 - b.wz0 + 1 : b.wx1 - b.wx0 + 1;
        int halls = Math.max(1, (window - hall) / (hall + passage - 2) + 1);
        int pierLength = (cross - hall) / 2;
        int line = (alongX ? b.wz0 : b.wx0) + (cross - hall) / 2;
        int start = alongX ? b.wx0 : b.wz0;
        var band = stairBand(b, heading);
        // A two-level arrival hall holds the atrium flight along a wall: foot landing, six steps, top landing.
        int arrivalSize = b.tall() ? Math.max(hall, 11) : hall;
        int arrivalLine = (alongX ? b.wz0 : b.wx0) + (cross - arrivalSize) / 2;
        var arrival = b.place(Use.ARRIVAL, alongX ? start : arrivalLine, 0, alongX ? arrivalLine : start, arrivalSize, arrivalSize, levels, b.tall() ? climbFor(band) : null);
        if (arrival == null)
            return false;
        b.arrival = arrival.id();
        var pending = new ArrayList<>(items);
        // Dock halls keep their sides free for berths. Berth approaches are 34 blocks wide, so dock halls
        // repeat on a stride; every other hall takes a mirrored pair of rooms.
        int pitch = hall + passage - 2;
        int stride = (StationBuilder.BERTH + pitch - 1) / pitch;
        int phase = b.rng.nextInt(stride);
        boolean ok = true;
        for (int level = 0; level < levels; level++)
        {
            Room previous = arrival;
            for (int i = 0; i < halls; i++)
            {
                Room current = arrival;
                if (i > 0 || level > 0)
                {
                    var link = b.attachCentered(previous, heading, Use.CORRIDOR, LANE, passage, 1, level * PITCH, null);
                    current = link == null ? null : b.attachCentered(link, heading, Use.CORRIDOR, hall, hall, 1, level * PITCH, null);
                    if (current == null)
                    {
                        if (link != null)
                            b.undo(link);
                        break;
                    }
                }
                previous = current;
                // Compact stations dock at the last hall; larger ones send piers out from dock halls.
                boolean dockHall = current == arrival ? false : compact ? i == halls - 1 : (i + phase) % stride == 0;
                if (dockHall && !compact && level == 0)
                    for (var side : alongX ? List.of(Direction.NORTH, Direction.SOUTH) : List.of(Direction.WEST, Direction.EAST))
                        b.attachCentered(current, side, Use.CORRIDOR, LANE, pierLength, 1, 0, null);
                if (dockHall || pending.isEmpty())
                    continue;
                var sides = alongX ? List.of(Direction.NORTH, Direction.SOUTH) : List.of(Direction.WEST, Direction.EAST);
                boolean large = pending.getFirst().size() == StationProfiles.Class.LARGE && !compact;
                int depth = large ? depthLarge : depthSmall;
                for (var side : sides)
                {
                    // The arrival hall keeps the walls of the atrium flights clear.
                    if (current == arrival && StationBuilder.flightWalls(arrival).contains(side) || pending.isEmpty())
                        continue;
                    var item = pending.removeFirst();
                    var placed = b.attachCentered(current, side, item.use(), hall, depth, 1, current.y(), null);
                    if (placed == null)
                        ok &= !item.required();
                }
            }
        }
        for (var left : pending)
            ok &= !left.required();
        return ok;
    }

    private StationMethods()
    {
    }
}
