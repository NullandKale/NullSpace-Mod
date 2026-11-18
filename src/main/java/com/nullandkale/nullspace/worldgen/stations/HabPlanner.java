package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.StationPlan.Room;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Plans a hab: a station turned into the ground (see {@code docs/design/habs.md}). It is planned by the station layout methods, so
 * it has the same straight carriers, mirrored bays and tall stair hall, with these differences:
 * <ul>
 * <li>The <b>entry hall</b> is the arrival hall, as tall as the whole hab, and its top story is the only part that stands at the
 * surface: floor 1. The arms and every other room are on the levels below it, so a hab shows one small building and the rest is
 * underground. Level 0, the lowest, is the biggest floor.</li>
 * <li>Everything is built <b>larger</b>: there is more space underground, so halls, bays and corridors are four blocks wider and deeper
 * and the corridors seven wide, and the arms fill a bigger square.</li>
 * <li>There are <b>no berths</b>: nothing docks at a hab.</li>
 * <li>The deepest floor ends in the <b>grove</b>: a big hall two levels tall, at the end of a corridor, with the tree at its centre.</li>
 * </ul>
 */
public final class HabPlanner
{
    public static final int ATTEMPTS = 64;

    /** Why the last hab whose chosen layout form kept failing gave it up for the arms (for tests and performance work). */
    static volatile String formFailure = "";

    /** The widest a hab plan may be: 512 by 512, the biggest air section supported. */
    public static final int MAX_WIDTH = 512;

    /**
     * The four wings of a hab. Each arm of the entry hall is one wing, and holds the rooms of that kind of life: the living wing (cabins,
     * bunks, the commons, kitchen and infirmary), the works (workshop, foundry, stores, maintenance, engineering), the gardens (growing
     * bays and gardens, with the grove at the end of the deepest floor) and the command wing (labs, archive, observation, operations, armory).
     */
    public enum Wing
    {
        LIVING("Living wing", Use.FAMILY_QUARTERS, Use.CABIN, Use.BUNK_ROOM, Use.COMMONS, Use.KITCHEN, Use.INFIRMARY, Use.CHAPEL),
        WORKS("Works", Use.MACHINE_HALL, Use.WORKSHOP, Use.FOUNDRY, Use.MAINTENANCE, Use.STORES, Use.CARGO_HOLD, Use.RECEIVING_BAY, Use.ENGINEERING, Use.EVA_PREP, Use.MINE_HEAD),
        GARDENS("Gardens", Use.GROWING_BAY, Use.GARDEN, Use.APIARY),
        COMMAND("Command wing", Use.CONTROLLER_ROOM, Use.LAB, Use.ARCHIVE, Use.OBSERVATION, Use.OPERATIONS, Use.ARMORY, Use.BRIG, Use.LOOKOUT);

        public final String label;
        private final List<Use> uses;

        Wing(String label, Use... uses)
        {
            this.label = label;
            this.uses = List.of(uses);
        }

        public List<Use> uses()
        {
            return uses;
        }

        /** The wing a kind of room belongs to; the plain halls and anything else go with the works. */
        public static Wing of(Use use)
        {
            for (var wing : values())
                if (wing.uses.contains(use))
                    return wing;
            return WORKS;
        }
    }

    /**
     * How a hab's rooms are laid out round the entry hall (see {@code docs/design/hab-rooms.md}). {@link #ARMS} is a corridor out from the hall
     * with rooms either side; {@link #BEADS} is a short passage to a plaza with rooms round it, then another; {@link #SUITES} is rooms that
     * open into each other in a straight line, so the rooms are the route; {@link #STACKED} is arms that take turns on the levels so they need no
     * bare lead; {@link #RING} is a loop of halls round the grove, closed by a door that power holds shut.
     */
    public enum Form
    {
        ARMS, BEADS, SUITES, STACKED, RING
    }

    /** The layout form a hab's seed gives it: each maker leans one way, and a small hab has no room for plazas. */
    public static Form formFor(StationSpec spec)
    {
        int[] weights = switch (spec.maker())
        {
            case RIVET -> new int[]{3, 2, 1, 2, 1};
            case HALCYON -> new int[]{2, 2, 2, 1, 2};
            case BASTION -> new int[]{1, 2, 3, 1, 2};
        };
        if (spec.size() == StationSpec.Size.COMPACT)
        {
            weights[Form.BEADS.ordinal()] = 0;
            weights[Form.RING.ordinal()] = 0;
        }
        int total = 0;
        for (int w : weights)
            total += w;
        int roll = stream(spec.seed(), 5).nextInt(total);
        for (var form : Form.values())
        {
            roll -= weights[form.ordinal()];
            if (roll < 0)
                return form;
        }
        return Form.ARMS;
    }

    /** The wing a purpose is mostly about, which gets the most rooms. */
    static Wing primary(StationSpec.Purpose purpose)
    {
        return switch (purpose)
        {
            case AGRICULTURE -> Wing.GARDENS;
            case INDUSTRY, CARGO, HARBOR -> Wing.WORKS;
            case RESEARCH, SECURITY -> Wing.COMMAND;
            default -> Wing.LIVING;
        };
    }

    /** The wings in the order the arms leave the entry hall: the purpose's own first, the rest in an order the seed chooses. */
    static List<Wing> wingsFor(StationSpec spec)
    {
        var rest = new ArrayList<>(List.of(Wing.values()));
        rest.remove(primary(spec.purpose()));
        var random = stream(spec.seed(), 2);
        for (int i = rest.size() - 1; i > 0; i--)
            java.util.Collections.swap(rest, i, random.nextInt(i + 1));
        var order = new ArrayList<Wing>();
        order.add(primary(spec.purpose()));
        order.addAll(rest);
        return order;
    }

    /**
     * How long each wing's arm is, as a share of the most it could be. A third of habs have even arms; the others have the purpose's wing
     * full length and each other wing between 60 and 100 percent (a small hab 70 to 100), so a hab may be lopsided on purpose.
     */
    public static java.util.Map<Wing, Double> scalesFor(StationSpec spec)
    {
        var random = stream(spec.seed(), 3);
        var scales = new java.util.EnumMap<Wing, Double>(Wing.class);
        boolean even = random.nextInt(3) == 0;
        for (var wing : Wing.values())
            // A small hab's arms are short to begin with, and the first 26 blocks are bare: a wing under 70 percent would hold no rooms at all.
            scales.put(wing, even || wing == primary(spec.purpose()) ? 1.0 : spec.size() == StationSpec.Size.COMPACT ? (7 + random.nextInt(4)) / 10.0 : (6 + random.nextInt(5)) / 10.0);
        return scales;
    }

    /** A kind of room and how likely a wing is to hold one more of it. */
    private record Pool(Use use, double weight)
    {}

    private static final java.util.Map<Wing, List<Pool>> POOLS = java.util.Map.of(
            Wing.LIVING, List.of(new Pool(Use.FAMILY_QUARTERS, 5), new Pool(Use.BUNK_ROOM, 2), new Pool(Use.COMMONS, 2), new Pool(Use.KITCHEN, 2), new Pool(Use.INFIRMARY, 1), new Pool(Use.CHAPEL, 1)),
            Wing.WORKS, List.of(new Pool(Use.WORKSHOP, 3), new Pool(Use.MAINTENANCE, 3), new Pool(Use.STORES, 4), new Pool(Use.FOUNDRY, 1), new Pool(Use.CARGO_HOLD, 1), new Pool(Use.EVA_PREP, 1), new Pool(Use.MINE_HEAD, 1)),
            Wing.GARDENS, List.of(new Pool(Use.GROWING_BAY, 3), new Pool(Use.GARDEN, 2), new Pool(Use.APIARY, 1)),
            Wing.COMMAND, List.of(new Pool(Use.LAB, 3), new Pool(Use.ARCHIVE, 2), new Pool(Use.OBSERVATION, 2), new Pool(Use.ARMORY, 2), new Pool(Use.OPERATIONS, 1), new Pool(Use.BRIG, 1)));

    private static StationProfiles.Class sizeOf(Use use)
    {
        return switch (use)
        {
            case COMMONS, FOUNDRY, GROWING_BAY, CARGO_HOLD, WORKSHOP -> StationProfiles.Class.LARGE;
            default -> StationProfiles.Class.SMALL;
        };
    }

    /** The rooms a hab never has more than one of: one shrine and one shaft to the seam. */
    private static boolean single(Use use)
    {
        return use == Use.CHAPEL || use == Use.MINE_HEAD;
    }

    /**
     * What a hab makes of a station program: homes are family quarters, the engineering room is the machine hall, a lookout (it needs a window
     * onto the outside) is an observation room and a receiving bay (it serves ships) is stores.
     */
    private static StationProfiles.Item habRoom(StationProfiles.Item item)
    {
        var use = switch (item.use())
        {
            case CABIN -> Use.FAMILY_QUARTERS;
            case ENGINEERING -> Use.MACHINE_HALL;
            case LOOKOUT -> Use.OBSERVATION;
            case RECEIVING_BAY -> Use.STORES;
            default -> item.use();
        };
        return use == item.use() ? item : new StationProfiles.Item(use, item.size(), item.required());
    }

    /**
     * The room program of a hab: the rooms its purpose requires, then rooms drawn from each wing's own pool, the purpose's wing getting
     * the most, so every wing is a place with a character of its own.
     */
    static List<StationProfiles.Item> program(StationSpec spec, SplittableRandom random, java.util.Map<Wing, Double> scales)
    {
        var asked = new StationSpec(spec.maker(), spec.purpose(), spec.size() == StationSpec.Size.LARGE ? StationSpec.Size.STANDARD : spec.size(), spec.condition(), spec.seed());
        var items = new ArrayList<StationProfiles.Item>();
        for (var item : StationProfiles.program(asked, random, 0))
            items.add(habRoom(item));
        // The room the hab is run from: one, and it must be there.
        items.add(new StationProfiles.Item(Use.CONTROLLER_ROOM, StationProfiles.Class.SMALL, true));
        var counts = new java.util.EnumMap<Use, Integer>(Use.class);
        for (var item : items)
            counts.merge(item.use(), 1, Integer::sum);
        int each = optionalFor(spec.size()) / Wing.values().length;
        for (var wing : Wing.values())
        {
            // A shorter arm has room for fewer rooms.
            int count = (int) ((wing == primary(spec.purpose()) ? each * 8 / 5 : each * 4 / 5) * scales.get(wing));
            for (int i = 0; i < count; i++)
            {
                var pool = POOLS.get(wing);
                double total = 0;
                var weights = new double[pool.size()];
                for (int k = 0; k < weights.length; k++)
                {
                    weights[k] = single(pool.get(k).use()) && counts.getOrDefault(pool.get(k).use(), 0) > 0 ? 0 : pool.get(k).weight() / (1 + 2.5 * counts.getOrDefault(pool.get(k).use(), 0));
                    total += weights[k];
                }
                double roll = random.nextDouble() * total;
                int pick = 0;
                for (; pick < weights.length - 1 && roll >= weights[pick]; pick++)
                    roll -= weights[pick];
                var use = pool.get(pick).use();
                counts.merge(use, 1, Integer::sum);
                items.add(new StationProfiles.Item(use, sizeOf(use), false));
            }
        }
        return items;
    }

    /**
     * A planned hab: the plan, the room of the tree, how many floors it has, the row of the surface in plan coordinates, and the doors that
     * only power opens (the controller room's, the command wing's from the entry hall, and a ring's closing door).
     */
    public record Hab(StationPlan plan, String grove, int floors, int groundY, List<StationPlan.Door> powered, java.util.Set<StationPlan.Door> open, Form form)
    {
        /** The entry hall, whose top story is floor 1. */
        public Room entry()
        {
            return plan.room(plan.arrival());
        }

        public Room groveRoom()
        {
            return plan.room(grove);
        }
    }

    /** Floors, counting the entry hall's story as floor 1: small 3, medium and large 4 (a taller hab would pass the 32 block limit). */
    public static int floorsFor(StationSpec.Size size)
    {
        return size == StationSpec.Size.COMPACT ? 3 : 4;
    }

    /** The side of the square the arms may reach into: 192, 320 and 512 blocks, the largest a hab may be (a hab is not built in a station's 128 block box), so that an arm that ends early leaves room for the grove past its end. */
    public static int windowFor(StationSpec.Size size)
    {
        return switch (size)
        {
            case COMPACT -> 192;
            case STANDARD -> 320;
            case LARGE -> 512;
        };
    }

    /** The outer sizes a grove may have, biggest first: odd inside, so the tree stands on the middle line. */
    public static int[] grovesFor(StationSpec.Size size)
    {
        return switch (size)
        {
            case COMPACT -> new int[]{27, 23};
            case STANDARD -> new int[]{35, 31, 27, 23};
            case LARGE -> new int[]{43, 39, 35, 31, 27, 23};
        };
    }

    /** How many optional rooms a hab asks for: more than there is space for, so it fills with as many as fit (a room that does not fit is dropped). */
    static int optionalFor(StationSpec.Size size)
    {
        return switch (size)
        {
            case COMPACT -> 110;
            case STANDARD -> 270;
            case LARGE -> 560;
        };
    }

    /** The fewest rooms people go into (not corridors, the entry hall or the grove) a hab of this size has. */
    public static int minimumRooms(StationSpec.Size size)
    {
        return switch (size)
        {
            case COMPACT -> 30;
            case STANDARD -> 60;
            case LARGE -> 110;
        };
    }

    /** What a hab holds: the purposes that need no docks. */
    public static StationSpec.Purpose purposeFor(StationSpec.Purpose purpose)
    {
        return purpose == StationSpec.Purpose.HARBOR || purpose == StationSpec.Purpose.CARGO ? StationSpec.Purpose.HABITATION : purpose;
    }

    private static SplittableRandom stream(long seed, long salt)
    {
        return new SplittableRandom(seed * 0x9E3779B97F4A7C15L ^ (salt * 0xBF58476D1CE4E5B9L + 0x94D049BB133111EBL));
    }

    public static Hab generate(StationSpec brief)
    {
        var spec = new StationSpec(brief.maker(), purposeFor(brief.purpose()), brief.size(), brief.condition(), brief.seed());
        var scales = scalesFor(spec);
        var program = program(spec, stream(spec.seed(), 1), scales);
        var wings = wingsFor(spec);
        int floors = floorsFor(spec.size()), window = windowFor(spec.size());
        String failure = "no attempt succeeded";
        for (int attempt = 0; attempt < ATTEMPTS; attempt++)
        {
            var builder = new StationBuilder(spec, stream(spec.seed(), 100 + attempt), floors, window, window);
            // If the chosen form keeps failing for this brief, the arms, which always fit, are the last resort.
            if (attempt == ATTEMPTS * 3 / 4)
                formFailure = failure;
            builder.form = attempt < ATTEMPTS * 3 / 4 ? formFor(spec) : Form.ARMS;
            builder.armLevels = floors - 1;
            builder.big = true;
            builder.wings = wings;
            builder.armScale = wings.stream().map(scales::get).toList();
            builder.maxWidth = MAX_WIDTH;
            // The arms are laid out leaving a margin the size of the smallest grove on the far sides, so there is always room past an arm's end for one.
            int margin = grovesFor(spec.size())[grovesFor(spec.size()).length - 1];
            builder.wx0 = 0;
            builder.wz0 = 0;
            builder.wx1 = window - 1 - margin;
            builder.wz1 = window - 1 - margin;
            if (!StationMethods.layout(spec.maker(), builder, program))
            {
                failure = "required rooms did not fit" + (builder.note.isEmpty() ? "" : " (" + builder.note + ")");
                continue;
            }
            var grove = placeGrove(builder, spec.size());
            if (grove == null)
            {
                failure = "no corridor on the deepest floor had room for the grove";
                continue;
            }
            var plan = builder.finish(false);
            if (plan == null)
            {
                failure = "the plan failed validation";
                continue;
            }
            plan = withOpenings(plan);
            var hab = new Hab(plan, grove, floors, (floors - 1) * StationPlan.PITCH + 1, poweredDoors(plan, builder), openDoors(plan), builder.form);
            if (!problems(hab).isEmpty())
            {
                failure = problems(hab).getFirst();
                continue;
            }
            return hab;
        }
        throw new IllegalStateException("Could not generate a hab, " + spec.description() + ": " + failure);
    }

    /**
     * Puts the grove at the free end of a corridor on the deepest floor, two levels tall, as big as will fit: the biggest size
     * on the longest corridors first. Returns its id, or null when no corridor end has room.
     */
    static String placeGrove(StationBuilder b, StationSpec.Size size)
    {
        // A ring has its grove in the courtyard already.
        for (var room : b.rooms)
            if (room.use() == Use.GARDEN && room.levels() == 2)
                return room.id();
        var corridors = new ArrayList<Room>();
        for (var room : b.rooms)
            if (room.y() == 0 && room.use() != Use.ARRIVAL && (room.use() == Use.CORRIDOR || b.form == Form.SUITES && b.doors.stream().filter(d -> d.a().equals(room.id()) || d.b().equals(room.id())).count() == 1))
                corridors.add(room);
        // The grove belongs to the gardens: the corridors of that wing come first, then the longest.
        var byId = new java.util.HashMap<String, Room>();
        for (var room : b.rooms)
            byId.put(room.id(), room);
        java.util.function.ToIntFunction<Room> gardens = corridor ->
        {
            int n = 0;
            for (var door : b.doors)
            {
                var other = door.a().equals(corridor.id()) ? byId.get(door.b()) : door.b().equals(corridor.id()) ? byId.get(door.a()) : null;
                if (other != null && Wing.of(other.use()) == Wing.GARDENS)
                    n++;
            }
            return n;
        };
        corridors.sort(Comparator.comparingInt((Room r) -> -gardens.applyAsInt(r)).thenComparingInt((Room r) -> -Math.max(r.w(), r.d())).thenComparing(Room::id));
        // The margin is outside the window arms are laid out in, so the grove may stand in it.
        b.wx0 = 0;
        b.wz0 = 0;
        b.wx1 = windowFor(size) - 1;
        b.wz1 = windowFor(size) - 1;
        for (int outer : grovesFor(size))
            for (var corridor : corridors)
            {
                boolean alongX = corridor.w() >= corridor.d();
                var ends = alongX ? List.of(Direction.EAST, Direction.WEST) : List.of(Direction.SOUTH, Direction.NORTH);
                // A chain of rooms may end on either axis; a corridor on its long one.
                for (var end : b.form == Form.SUITES ? List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH) : ends)
                {
                    var room = b.attachCentered(corridor, end, Use.GARDEN, outer, outer, 2, 0, null);
                    if (room != null)
                        return room.id();
                }
            }
        return null;
    }

    /**
     * The doors only power opens: the controller room's, the entry hall's to the command wing, and the closing door of each level of a ring.
     * The machine hall is never behind one (see {@link #problems(Hab)}), so a player can always get to the generators and feed them.
     */
    static List<StationPlan.Door> poweredDoors(StationPlan plan, StationBuilder builder)
    {
        var out = new ArrayList<StationPlan.Door>();
        for (var door : plan.doors())
        {
            var a = plan.room(door.a());
            var c = plan.room(door.b());
            boolean controller = a.use() == Use.CONTROLLER_ROOM || c.use() == Use.CONTROLLER_ROOM;
            boolean toCommand = a.id().equals(plan.arrival()) && wingOf(plan, c) == Wing.COMMAND || c.id().equals(plan.arrival()) && wingOf(plan, a) == Wing.COMMAND;
            boolean closing = builder.locked.stream().anyMatch(pair -> pair[0].equals(door.a()) && pair[1].equals(door.b()) || pair[0].equals(door.b()) && pair[1].equals(door.a()));
            if (controller || toCommand || closing)
                out.add(door);
        }
        return out;
    }

    /** The rooms that run into each other without a door: a hab's growing bays, gardens (not the grove) and apiaries, whose farmland and pens a door would cut. */
    static boolean open(Room room)
    {
        return room.use() == Use.GROWING_BAY || room.use() == Use.APIARY || room.use() == Use.GARDEN && room.levels() == 1;
    }

    /** Every pair of open rooms that stand side by side on a floor, with a long wall between them, gets a connection (and so, below, an opening). */
    static StationPlan withOpenings(StationPlan plan)
    {
        var doors = new ArrayList<>(plan.doors());
        var rooms = plan.rooms();
        for (int i = 0; i < rooms.size(); i++)
            for (int j = i + 1; j < rooms.size(); j++)
            {
                var a = rooms.get(i);
                var b = rooms.get(j);
                if (!open(a) || !open(b) || a.y() != b.y())
                    continue;
                var shared = a.outer().overlap(b.outer());
                if (Math.max(shared[0], shared[2]) < 7 || Math.min(shared[0], shared[2]) != 1)
                    continue;
                boolean joined = doors.stream().anyMatch(d -> d.a().equals(a.id()) && d.b().equals(b.id()) || d.a().equals(b.id()) && d.b().equals(a.id()));
                var door = joined ? null : StationBuilder.doorBetween(a, b);
                if (door != null)
                    doors.add(door);
            }
        return doors.size() == plan.doors().size() ? plan : new StationPlan(plan.spec(), plan.width(), plan.depth(), plan.height(), plan.rooms(), List.copyOf(doors), plan.berths(), plan.arrival());
    }

    /** The doors between two open rooms: they are openings, and the rooms' floors are one. */
    static java.util.Set<StationPlan.Door> openDoors(StationPlan plan)
    {
        var out = new java.util.LinkedHashSet<StationPlan.Door>();
        for (var door : plan.doors())
            if (open(plan.room(door.a())) && open(plan.room(door.b())))
                out.add(door);
        return out;
    }

    /** The rooms reachable from the entry hall through doors that need no power. */
    public static java.util.Set<String> reachableUnpowered(Hab hab)
    {
        var seen = new java.util.LinkedHashSet<String>();
        var queue = new java.util.ArrayDeque<String>(List.of(hab.plan().arrival()));
        seen.add(hab.plan().arrival());
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (var door : hab.plan().doors())
            {
                if (hab.powered().contains(door))
                    continue;
                var other = door.a().equals(at) ? door.b() : door.b().equals(at) ? door.a() : null;
                if (other != null && seen.add(other))
                    queue.add(other);
            }
        }
        return seen;
    }

    /** The wing a corridor leads to: the commonest wing of the rooms that open off it (the grove and other corridors apart), or null when none do. */
    public static Wing wingOf(StationPlan plan, Room corridor)
    {
        // A room that is not a passage is simply of its own wing.
        if (corridor.use() == Use.ARRIVAL)
            return null;
        if (corridor.use() != Use.CORRIDOR)
            return Wing.of(corridor.use());
        var counts = new java.util.EnumMap<Wing, Integer>(Wing.class);
        for (var door : plan.doors())
        {
            String other = door.a().equals(corridor.id()) ? door.b() : door.b().equals(corridor.id()) ? door.a() : null;
            if (other == null)
                continue;
            var room = plan.room(other);
            if (room.use() != Use.CORRIDOR && room.use() != Use.ARRIVAL && room.use() != Use.GARDEN || room.use() == Use.GARDEN && room.levels() < 2)
                counts.merge(Wing.of(room.use()), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
    }

    /** Every unmet rule of a hab: the station plan's, and that the surface holds only the entry hall, the arms are below it, the grove is a big tall garden on the deepest floor. */
    public static List<String> problems(Hab hab)
    {
        var out = new ArrayList<>(hab.plan().problems(false, MAX_WIDTH));
        var entry = hab.entry();
        int top = entry.y() + (hab.floors() - 1) * StationPlan.PITCH;
        if (entry.use() != Use.ARRIVAL || entry.levels() != hab.floors() || entry.climb() == null)
            out.add("The entry hall is not a tall stair hall of " + hab.floors() + " floors");
        for (var room : hab.plan().rooms())
            if (room != entry && room.outer().y1() > top)
                out.add("Room " + room.id() + " reaches the surface floor");
        var grove = hab.groveRoom();
        if (grove.level() != 0 || grove.use() != Use.GARDEN || grove.levels() != 2 || grove.w() < 19 || grove.d() < 19)
            out.add("The grove is not a big, two level garden on the deepest floor");
        if (!hab.plan().berths().isEmpty())
            out.add("A hab has no berths");
        var open = reachableUnpowered(hab);
        for (var room : hab.plan().rooms())
            if (room.use() == Use.MACHINE_HALL && !open.contains(room.id()))
                out.add("The machine hall " + room.id() + " is behind a door that needs power");
        return out;
    }

    private HabPlanner()
    {
    }
}
