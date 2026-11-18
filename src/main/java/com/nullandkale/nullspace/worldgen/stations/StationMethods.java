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
        if (b.wings != null && b.form != HabPlanner.Form.ARMS)
            return hubForm(b, items);
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

    /** How much wider and deeper everything is built in a hab. */
    private static int grow(StationBuilder b)
    {
        return b.big ? 4 : 0;
    }

    /** The width of a carrier: a hab's corridors are wider. */
    private static int lane(StationBuilder b)
    {
        return b.big ? LANE + 2 : LANE;
    }

    /** How many levels carry arms: all of them, unless the builder keeps the top level for the arrival hall alone (a hab). */
    private static int armLevels(StationBuilder b)
    {
        return b.armLevels > 0 ? b.armLevels : b.maxLevels;
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
        int deepDepth = (compact ? 8 : pick(b, 14, 16, 18)) + grow(b), shallowDepth = (compact ? 8 : pick(b, 10, 12)) + grow(b);
        var deep = new ArrayList<Item>();
        var shallow = new ArrayList<Item>();
        for (var item : items)
            (item.size() == StationProfiles.Class.LARGE && !compact ? deep : shallow).add(item);
        var out = new ArrayList<Column>();
        // One width per class gives each facade a regular rhythm.
        int deepWidth = pick(b, 12, 14, 16, 18) + grow(b), shallowWidth = pick(b, 8, 10, 12) + grow(b);
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
        int hubSize = (compact ? (b.tall() ? 11 : 9) : pick(b, 11, 13)) + grow(b);
        // The arrival either sits mid-spine, with an arm each way, or at one end with a single arm.
        boolean centred = compact || b.big || b.rng.nextBoolean();
        int x0 = b.wx0, x1 = b.wx1, z0 = b.wz0, z1 = b.wz1;
        int hx = alongX ? (centred ? (x0 + x1) / 2 - hubSize / 2 : x0) : (x0 + x1) / 2 - hubSize / 2;
        int hz = alongX ? (z0 + z1) / 2 - hubSize / 2 : (centred ? (z0 + z1) / 2 - hubSize / 2 : z0);
        var band = stairBand(b, heading);
        var hub = b.place(Use.ARRIVAL, hx, 0, hz, hubSize, hubSize, levels, b.tall() ? climbFor(band) : null);
        if (hub == null)
            return false;
        b.arrival = hub.id();
        // A hab's arms leave the hall in all four directions, so it fills a square and not a strip.
        var arms = b.big ? List.of(heading, heading.getOpposite(), heading.getClockWise(), heading.getCounterClockWise()) : centred ? List.of(heading, heading.getOpposite()) : List.of(heading);
        boolean piers = !b.big && b.spec.size() != StationSpec.Size.COMPACT && b.rng.nextInt(4) > 0;
        // A hab's arms cross, and its bays are deep: the first stretch of each arm stays bare so crossed arms clear each other's rooms.
        return decks(b, hub, arms, items, armLevels(b), b.big ? 26 : 0, piers);
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
        var pending = b.wings == null ? columns(b, items) : List.<Column>of();
        var segments = new ArrayList<Segment>();
        var pierOffsets = new ArrayList<List<Integer>>();
        int pierLength = 0;
        for (int arm = 0; arm < arms.size(); arm++)
        {
            // A hab's wings may be shorter or longer arms.
            int reach = (int) (reach(b, hub, arms.get(arm)) * b.scale(arm));
            var offsets = new ArrayList<Integer>();
            if (piers)
            {
                int across = arms.get(arm).getAxis() == Direction.Axis.X ? (b.wz1 - b.wz0 + 1) : (b.wx1 - b.wx0 + 1);
                pierLength = Math.max(pierLength, (across - lane(b)) / 2 + 1);
                for (int o = 12; o + 8 <= reach; o += 34)
                    offsets.add(o);
            }
            pierOffsets.add(offsets);
            for (int level = 0; level < levels; level++)
            {
                if (b.stagger && level % 2 != arm / 2)
                    continue;
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
        var left = dealAll(b, pending, items, arms.size(), segments, slots, caps, false);
        if (!left.stream().allMatch(StationMethods::optional))
        {
            // Some required room found no run at its full width: let columns narrow.
            slots.forEach(List::clear);
            left = dealAll(b, pending, items, arms.size(), segments, slots, caps, true);
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
                var carrier = b.attachCentered(hub, arms.get(arm), Use.CORRIDOR, lane(b), length, 1, level * PITCH, null);
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

    /**
     * Deals the columns to the runs. A hab's arms are wings: each arm is dealt only the rooms of its own wing (so a wing is made of
     * one kind of life), and what its wing has no room for goes wherever there is room left.
     */
    private static List<Column> dealAll(StationBuilder b, List<Column> pending, List<Item> items, int arms, List<Segment> segments, List<List<Column>> slots, int[] caps, boolean narrow)
    {
        if (b.wings == null)
            return deal(pending, slots, caps, narrow);
        var left = new ArrayList<Column>();
        for (int arm = 0; arm < arms; arm++)
        {
            var wing = b.wings.get(arm % b.wings.size());
            var mine = items.stream().filter(i -> HabPlanner.Wing.of(i.use()) == wing).toList();
            var armSlots = new ArrayList<List<Column>>();
            var armCaps = new ArrayList<Integer>();
            for (int i = 0; i < segments.size(); i++)
                if (segments.get(i).arm() == arm)
                {
                    armSlots.add(slots.get(i));
                    armCaps.add(caps[i]);
                }
            left.addAll(deal(columns(b, mine), armSlots, armCaps.stream().mapToInt(Integer::intValue).toArray(), narrow));
        }
        // Only a wing's required rooms go to another wing's arm when their own is full; the optional ones that do not fit are dropped, so a wing stays one kind of life.
        var spill = left.stream().filter(c -> !optional(c)).toList();
        var dropped = new ArrayList<>(left.stream().filter(StationMethods::optional).toList());
        dropped.addAll(deal(spill, slots, caps, narrow));
        return dropped;
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
                b.attachAt(carrier, side, Use.CORRIDOR, lane(b), length, 1, 0, null, pos);
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
        boolean cross = b.big || b.spec.size() == StationSpec.Size.LARGE || (b.spec.size() == StationSpec.Size.STANDARD && b.rng.nextBoolean());
        // A crossed hub needs room for the eight-cell stair along a wall whose door is at its middle, with a cell to spare.
        int hubSize = (compact ? (b.tall() ? 11 : 9) : cross ? 19 : pick(b, 11, 13)) + grow(b);
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
        return decks(b, hub, arms, items, armLevels(b), b.big ? 26 : cross ? 12 : 0, false);
    }

    // ---- A hab's other forms: plazas and suites round a hub ----

    /** The rooms of the wing whose arm this is, the required ones first, as a queue to place. */
    private static ArrayList<Item> wingItems(StationBuilder b, List<Item> items, int arm)
    {
        var wing = b.wings.get(arm % b.wings.size());
        var mine = new ArrayList<>(items.stream().filter(i -> HabPlanner.Wing.of(i.use()) == wing).toList());
        mine.sort(Comparator.comparing((Item i) -> !i.required()));
        return mine;
    }

    /**
     * A hab laid out as {@link HabPlanner.Form#BEADS} or {@link HabPlanner.Form#SUITES}: the same tall entry hall and four wings as the arms, with
     * rooms in a different order. Every arm of the hall is one wing's, and its rooms are placed on every level below the surface.
     */
    private static boolean hubForm(StationBuilder b, List<Item> items)
    {
        if (b.form == HabPlanner.Form.RING)
            return ring(b, items);
        b.stagger = b.form == HabPlanner.Form.STACKED || b.form == HabPlanner.Form.BEADS;
        int hubSize = 19 + grow(b);
        var axis = b.rng.nextBoolean() ? Direction.EAST : Direction.SOUTH;
        int cx = (b.wx0 + b.wx1) / 2, cz = (b.wz0 + b.wz1) / 2;
        var band = Direction.values()[2 + b.rng.nextInt(4)];
        var hub = b.place(Use.ARRIVAL, cx - hubSize / 2, 0, cz - hubSize / 2, hubSize, hubSize, levels(b), climbFor(band));
        if (hub == null)
            return false;
        b.arrival = hub.id();
        var arms = List.of(axis, axis.getOpposite(), axis.getClockWise(), axis.getCounterClockWise());
        if (b.form == HabPlanner.Form.STACKED)
            return decks(b, hub, arms, items, armLevels(b), 6, false);
        boolean ok = true;
        for (int arm = 0; arm < arms.size(); arm++)
        {
            var mine = wingItems(b, items, arm);
            int reach = (int) (reach(b, hub, arms.get(arm)) * b.scale(arm));
            ok &= b.form == HabPlanner.Form.BEADS ? beads(b, hub, arms.get(arm), mine, reach, arm) : suites(b, hub, arms.get(arm), mine, reach);
        }
        return ok;
    }

    /**
     * A hab laid out as a ring: a square of seven-wide halls round a courtyard with the grove in it, rooms along both sides of every hall, one
     * wing to a side, and the entry hall outside the south hall. The loop is closed by a door that power holds shut, so without power the
     * halls are a chain and with it a ring. Every level has its own ring, joined to the tall hall.
     */
    private static boolean ring(StationBuilder b, List<Item> items)
    {
        int hubSize = 19 + grow(b), lane = lane(b);
        int avail = Math.min(b.wx1 - b.wx0 + 1, b.wz1 - b.wz0 + 1);
        int side = avail - 48;
        if (side < 70)
            return false;
        int rx = b.wx0 + 23, rz = b.wz0 + 23, levels = armLevels(b), inner = side - 2 * lane + 2;
        Room hub = null;
        var halls = new ArrayList<Room[]>();
        for (int level = 0; level < levels; level++)
        {
            int y = level * PITCH;
            var north = b.place(Use.CORRIDOR, rx, y, rz, side, lane, 1);
            var west = north == null ? null : b.attachAt(north, Direction.SOUTH, Use.CORRIDOR, lane, inner, 1, y, null, rx);
            var east = north == null ? null : b.attachAt(north, Direction.SOUTH, Use.CORRIDOR, lane, inner, 1, y, null, rx + side - lane);
            var south = west == null ? null : b.attachAt(west, Direction.SOUTH, Use.CORRIDOR, side, lane, 1, y, null, rx);
            if (south == null || east == null)
                return false;
            var closing = StationBuilder.doorBetween(south, east);
            if (closing == null)
                return false;
            b.doors.add(closing);
            b.locked.add(new String[]{south.id(), east.id()});
            if (level == 0)
            {
                var band = Direction.values()[2 + b.rng.nextInt(4)];
                hub = b.attachCentered(south, Direction.SOUTH, Use.ARRIVAL, hubSize, hubSize, levels(b), 0, climbFor(band));
                if (hub == null)
                    return false;
                b.arrival = hub.id();
            }
            else
            {
                var door = StationBuilder.doorBetween(south, hub);
                if (door == null)
                    return false;
                b.doors.add(door);
            }
            halls.add(new Room[]{north, east, south, west});
        }
        // The grove stands in the courtyard, on the inside of the north hall's lowest level.
        int g = 0;
        for (int size : HabPlanner.grovesFor(b.spec.size()))
            if (g == 0 && b.attachCentered(halls.getFirst()[0], Direction.SOUTH, Use.GARDEN, size, size, 2, 0, null) != null)
                g = size;
        if (g == 0)
            return false;
        // One wing to a side, in the order the wings leave the hall: north, east, south, west.
        var queues = new ArrayList<ArrayList<Item>>();
        for (int wing = 0; wing < 4; wing++)
            queues.add(wingItems(b, items, wing));
        for (int level = 0; level < levels; level++)
            for (int i = 0; i < 4; i++)
            {
                var hall = halls.get(level)[i];
                // The outside of the ring and then the courtyard side: north's outside faces north, east's east, south's south, west's west.
                var outside = switch (i)
                {
                    case 0 -> Direction.NORTH;
                    case 1 -> Direction.EAST;
                    case 2 -> Direction.SOUTH;
                    default -> Direction.WEST;
                };
                row(b, hall, outside, queues.get(i));
                row(b, hall, outside.getOpposite(), queues.get(i));
            }
        return queues.stream().allMatch(q -> q.stream().noneMatch(Item::required));
    }

    /** Rooms flush along one side of a hall, from its start: a room that meets something is tried a little further along. */
    private static void row(StationBuilder b, Room hall, Direction side, List<Item> queue)
    {
        boolean across = side.getAxis() == Direction.Axis.Z;
        int pos = across ? hall.x() : hall.z(), end = pos + (across ? hall.w() : hall.d());
        while (!queue.isEmpty())
        {
            var item = queue.getFirst();
            boolean large = item.size() == StationProfiles.Class.LARGE && b.spec.size() != StationSpec.Size.COMPACT;
            int width = (large ? pick(b, 16, 18, 20) : pick(b, 12, 14, 16)) + grow(b), depth = (large ? pick(b, 14, 16, 18) : pick(b, 12, 14)) + grow(b);
            if (pos + width > end)
                break;
            var room = b.attachAt(hall, side, item.use(), width, depth, 1, hall.y(), null, pos);
            if (room == null)
            {
                pos += 2;
                continue;
            }
            queue.removeFirst();
            pos += width - 1;
        }
    }

    /**
     * Rooms that open into each other along one arm, a straight line from the hall: each takes the next room of the wing and is attached to the
     * far wall of the last, door to door. From the third room on, a room may have one more beside it on each side. The rooms are the route.
     */
    private static boolean suites(StationBuilder b, Room hub, Direction dir, List<Item> mine, int reach)
    {
        var sides = dir.getAxis() == Direction.Axis.X ? List.of(Direction.NORTH, Direction.SOUTH) : List.of(Direction.WEST, Direction.EAST);
        var trace = new StringBuilder();
        for (int level = 0; level < armLevels(b) && !mine.isEmpty(); level++)
        {
            Room previous = hub;
            int used = 0, index = 0, rotations = 0;
            var chain = new ArrayList<Room>();
            trace.append(" L").append(level).append(':');
            while (!mine.isEmpty())
            {
                var item = mine.getFirst();
                // The room the hab is run from is a dead end off the chain, never a way through: it goes beside the third room or a later one, or waits;
                // when it is all that is left it ends the chain, which is a dead end too.
                if (item.use() == Use.CONTROLLER_ROOM && mine.size() > 1 && rotations <= mine.size())
                {
                    Room side = null;
                    for (int at = Math.min(1, chain.size()); at < chain.size() && side == null; at++)
                        for (var turn : sides)
                            if (side == null)
                            {
                                var beside = chain.get(at);
                                int along = dir.getAxis() == Direction.Axis.X ? beside.w() : beside.d();
                                side = b.attachCentered(beside, turn, item.use(), along, pick(b, 12, 14) + grow(b), 1, level * PITCH, null);
                            }
                    if (side != null)
                    {
                        mine.removeFirst();
                        continue;
                    }
                    trace.append(" R");
                    // It waits at the back of the queue; having waited its turn round, it ends the chain where it is.
                    rotations++;
                    var waiting = mine.removeFirst();
                    int required = (int) mine.stream().filter(Item::required).count();
                    mine.add(required, waiting);
                    continue;
                }
                boolean large = item.size() == StationProfiles.Class.LARGE && b.spec.size() != StationSpec.Size.COMPACT;
                int across = (large ? pick(b, 16, 18, 20) : pick(b, 12, 14, 16)) + grow(b), depth = (large ? pick(b, 14, 16, 18) : pick(b, 12, 14)) + grow(b);
                if (used + depth > reach)
                {
                    trace.append(" reach").append(used).append('+').append(depth);
                    break;
                }
                var room = b.attachCentered(previous, dir, item.use(), across, depth, 1, level * PITCH, null);
                if (room == null)
                {
                    b.note += " | suites attach null: " + item.use() + " across " + across + " depth " + depth + " level " + level + " index " + index + " used " + used + " prev " + previous.id() + " door " + (previous == hub ? "hub" : "room");
                    break;
                }
                mine.removeFirst();
                trace.append(" P").append(item.use().name().charAt(0));
                previous = room;
                chain.add(room);
                used += depth - 1;
                index++;
                if (index >= 3)
                    for (var side : sides)
                    {
                        // A side room is as long along the chain as the room it opens off and no more than one in a row, so a side room never meets the next one.
                        if (mine.isEmpty() || mine.getFirst().required() && index < 6)
                            break;
                        var extra = mine.getFirst();
                        var placed = b.attachCentered(room, side, extra.use(), depth, pick(b, 10, 12) + grow(b), 1, level * PITCH, null);
                        if (placed != null)
                            mine.removeFirst();
                    }
            }
        }
        boolean fits = mine.stream().allMatch(i -> !i.required());
        if (!fits)
            b.note += " | suites " + trace + " arm " + dir + " left " + mine.stream().filter(Item::required).map(i -> i.use().name()).toList() + " of " + mine.size() + " reach " + reach + " levels " + armLevels(b) + " hub " + hub.x() + "," + hub.z() + " window " + b.wx0 + ".." + b.wx1 + "," + b.wz0 + ".." + b.wz1;
        return fits;
    }

    /**
     * A short passage to a plaza and rooms round it, then the next: a plaza is a hall 31 long and 23 across with two rooms across from each other
     * at each of two places, and the chain goes on through its far wall.
     */
    private static boolean beads(StationBuilder b, Room hub, Direction dir, List<Item> mine, int reach, int arm)
    {
        var columns = columns(b, mine);
        boolean ok = true;
        int across = 23;
        for (int level = 0; level < armLevels(b) && !columns.isEmpty(); level++)
        {
            // Arms that cross never share a level with short passages: the east and west arms have theirs short on the even levels and the north and
            // south arms on the odd, and on the other levels the first passage is long enough to clear the crossing arm's plaza.
            boolean short_ = level % 2 == arm / 2;
            Room previous = hub;
            int used = 0;
            boolean first = true;
            while (!columns.isEmpty())
            {
                // A plaza is as long as the two columns of rooms it has along it need, and at least as long as it is wide.
                var taken = new ArrayList<Column>();
                for (int i = 0; i < Math.min(2, columns.size()); i++)
                    taken.add(columns.get(i));
                int plaza = Math.max(across, span(taken) + 2);
                int passage = first && !short_ ? 24 : 6;
                if (used + passage + plaza > reach)
                    break;
                var link = b.attachCentered(previous, dir, Use.CORRIDOR, lane(b), passage, 1, level * PITCH, null);
                var hall = link == null ? null : b.attachCentered(link, dir, Use.CORRIDOR, across, plaza, 1, level * PITCH, null);
                if (hall == null)
                {
                    if (link != null)
                        b.undo(link);
                    break;
                }
                first = false;
                previous = hall;
                used += passage + plaza - 2;
                for (int i = 0; i < taken.size(); i++)
                    columns.removeFirst();
                ok &= bays(b, hall, taken, 1, fromHubEnd(dir));
            }
        }
        boolean fits = ok && columns.stream().allMatch(StationMethods::optional);
        if (!fits)
            b.note = "beads arm " + dir + " ok " + ok + " columns left " + columns.stream().filter(c -> !optional(c)).count() + " reach " + reach + " note " + b.note;
        return fits;
    }

    // ---- Bastion Shipbuilding: a chain of halls, each with its own rooms ----

    private static boolean bastion(StationBuilder b, List<Item> items)
    {
        boolean compact = b.spec.size() == StationSpec.Size.COMPACT;
        boolean alongX = compact ? b.longAxisX() : b.rng.nextBoolean();
        var heading = alongX ? Direction.EAST : Direction.SOUTH;
        int hall = (compact ? 9 : pick(b, 9, 11)) + grow(b);
        int passage = (compact ? 4 : pick(b, 5, 7)) + (b.big ? 2 : 0);
        int levels = levels(b);
        int depthSmall = (compact ? 8 : pick(b, 10, 12)) + grow(b), depthLarge = (compact ? 8 : pick(b, 14, 16)) + grow(b);
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
        // A hab's wings run along the chain: the rooms of one wing come one after another.
        if (b.wings != null)
            pending.sort(java.util.Comparator.comparing((Item i) -> !i.required()).thenComparingInt(i -> b.wings.indexOf(HabPlanner.Wing.of(i.use()))));
        // Dock halls keep their sides free for berths. Berth approaches are 34 blocks wide, so dock halls
        // repeat on a stride; every other hall takes a mirrored pair of rooms.
        int pitch = hall + passage - 2;
        int stride = (StationBuilder.BERTH + pitch - 1) / pitch;
        int phase = b.rng.nextInt(stride);
        boolean ok = true;
        for (int level = 0; level < armLevels(b); level++)
        {
            Room previous = arrival;
            for (int i = 0; i < halls; i++)
            {
                Room current = arrival;
                if (i > 0 || level > 0)
                {
                    var link = b.attachCentered(previous, heading, Use.CORRIDOR, lane(b), passage, 1, level * PITCH, null);
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
                // A hab has no docks, so every hall holds rooms.
                boolean dockHall = current == arrival || b.big ? false : compact ? i == halls - 1 : (i + phase) % stride == 0;
                if (dockHall && !compact && level == 0)
                    for (var side : alongX ? List.of(Direction.NORTH, Direction.SOUTH) : List.of(Direction.WEST, Direction.EAST))
                        b.attachCentered(current, side, Use.CORRIDOR, lane(b), pierLength, 1, 0, null);
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
