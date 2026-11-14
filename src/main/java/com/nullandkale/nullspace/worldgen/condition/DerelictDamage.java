package com.nullandkale.nullspace.worldgen.condition;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationPlan;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Box;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Room;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The wreck: a seeded pass over a finished vessel that tells the story of its {@link DerelictHistory}. It never changes
 * the plan, only the blocks and entity points of the built result, so changing the damage never rearranges the rooms.
 *
 * <p>Danger sets how many rooms are damaged (a third, a half, two thirds and four fifths of the rooms). A damaged room is
 * properly wrecked and the rooms left alone stay whole, so a visitor hops from one intact bubble to the next across the
 * ruin. The history picks the tools:
 * <ul>
 * <li><b>Evacuated</b>: doors propped open or jammed, fixtures stripped, empty frames and suit stands, dead lights, cobwebs.</li>
 * <li><b>Breach</b>: a wing that ruptured. The wound rooms have the hull torn open and scorched, the rooms around them
 * collapse, and decks are broken through; doors between wrecked rooms are knocked out.</li>
 * <li><b>Power failure</b>: every light is out, the generators and batteries are gone, and the damaged rooms lost their air
 * supplies.</li>
 * <li><b>Interrupted work</b>: workrooms are left whole, with wreckage in the other rooms.</li>
 * </ul>
 *
 * <p>The dock room, its port and the two cells inside it, the doorways from the dock room, the controller and the marked
 * loot chests are never touched. Removed blocks become air entries, so the vessel keeps its extent.
 */
public final class DerelictDamage
{
    /** What the pass did, for tests and the survey. */
    public record Report(DerelictHistory history, List<String> damaged, List<String> wounded, int removed, int doorsChanged, int breaches, int holes)
    {}

    public record Result(StationBlueprint.Plan plan, Report report)
    {}

    /** The share of rooms that are damaged at each danger. */
    private static final double[] SHARE = {1 / 3.0, 1 / 2.0, 2 / 3.0, 4 / 5.0};
    private static final double[] FIXTURES = {0.2, 0.3, 0.45, 0.6};
    private static final double[] DARK = {0.5, 0.6, 0.8, 1.0};
    private static final int[] WEBS = {2, 3, 5, 7};
    private static final int[] BREACH_RADIUS = {1, 1, 2, 3};
    private static final Set<String> WORK = Set.of("workshop", "foundry", "kitchen", "lab", "growing_bay", "garden", "armory");

    /** {@code dz} is how far the plan's rooms are shifted along z in the blocks (a ship's ramp), zero for a station. */
    public static Result apply(StationPlan layout, int dz, StationBlueprint.Plan built, long seed, int danger)
    {
        return apply(layout, dz, built, seed, danger, null);
    }

    /** As above, telling the story of {@code history} instead of the one the seed and danger would draw; null draws it. */
    public static Result apply(StationPlan layout, int dz, StationBlueprint.Plan built, long seed, int danger, DerelictHistory history)
    {
        int level = Math.max(0, Math.min(3, danger));
        var wreck = new Wreck(layout, dz, built, seed, level, history != null ? history : DerelictHistory.of(seed, level));
        return wreck.run();
    }

    private static final class Wreck
    {
        final StationPlan layout;
        final int dz, danger;
        final long seed;
        final DerelictHistory history;
        final Map<BlockPos, BlockState> blocks;
        final List<Piece.Point> points;
        final BlockPos core;
        final List<BlockPos> docks;
        final Set<BlockPos> keep = new HashSet<>();
        final Set<BlockPos> gone = new HashSet<>();
        final Set<String> dockRooms = new HashSet<>();
        final Set<String> damaged = new LinkedHashSet<>();
        final Set<String> wounded = new LinkedHashSet<>();
        int doorsChanged, breaches, holes;

        Wreck(StationPlan layout, int dz, StationBlueprint.Plan built, long seed, int danger, DerelictHistory history)
        {
            this.layout = layout;
            this.dz = dz;
            this.seed = seed;
            this.danger = danger;
            this.history = history;
            this.blocks = new LinkedHashMap<>(built.blocks());
            this.points = new ArrayList<>(built.points());
            this.core = built.core();
            this.docks = built.docks();
        }

        Result run()
        {
            protect();
            chooseRooms();
            for (var id : damaged)
                wreckRoom(layout.room(id));
            if (history == DerelictHistory.POWER_FAILURE)
                powerFailure();
            doors();
            settle();
            var kept = filterPoints();
            int removed = gone.size();
            var plan = new StationBlueprint.Plan(blocks, core, docks, kept);
            return new Result(plan, new Report(history, List.copyOf(damaged), List.copyOf(wounded), removed, doorsChanged, breaches, holes));
        }

        // ---- Geometry helpers ----

        BlockPos at(int x, int y, int z)
        {
            return new BlockPos(x, y, z + dz);
        }

        Box shifted(Box box)
        {
            return box.shift(0, dz);
        }

        boolean contains(Box box, BlockPos p)
        {
            return p.getX() >= box.x0() && p.getX() <= box.x1() && p.getY() >= box.y0() && p.getY() <= box.y1() && p.getZ() >= box.z0() && p.getZ() <= box.z1();
        }

        /** True when some room's outer box holds the cell (the cell is inside the vessel, not open space). */
        boolean covered(BlockPos p)
        {
            for (var room : layout.rooms())
                if (contains(shifted(room.outer()), p))
                    return true;
            return false;
        }

        Room roomAt(BlockPos p)
        {
            for (var room : layout.rooms())
                if (contains(shifted(room.interior()), p))
                    return room;
            for (var room : layout.rooms())
                if (contains(shifted(room.outer()), p))
                    return room;
            return null;
        }

        Random rng(long salt, String room)
        {
            return new Random(DerelictHistory.scatter(seed ^ salt ^ ((long) room.hashCode() << 20)));
        }

        static boolean machine(BlockState state)
        {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("nullspace");
        }

        static String name(BlockState state)
        {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        }

        BlockState state(BlockPos p)
        {
            var s = blocks.get(p);
            return s == null ? Blocks.AIR.defaultBlockState() : s;
        }

        // ---- What is never touched ----

        void protect()
        {
            keep.add(core);
            keep.add(core.above());
            for (var berth : layout.berths())
            {
                dockRooms.add(berth.dock());
                var port = berth.port().offset(0, 0, dz);
                var side = berth.facing().getClockWise();
                for (int s = -1; s <= 1; s++)
                    for (int y = -1; y <= 3; y++)
                        keep.add(port.relative(side, s).above(y));
                for (int depth = 1; depth <= 3; depth++)
                    for (int s = -1; s <= 1; s++)
                        for (int y = 0; y <= 2; y++)
                            keep.add(port.relative(berth.facing().getOpposite(), depth).relative(side, s).above(y));
            }
            for (var dock : docks)
                keep.add(dock);
            for (var point : points)
                if (point.name().equals("roomloot"))
                {
                    keep.add(point.pos());
                    keep.add(point.pos().above());
                    var s = state(point.pos());
                    var other = partner(point.pos(), s);
                    if (other != null)
                        keep.add(other);
                }
            for (var door : layout.doors())
                if (dockRooms.contains(door.a()) || dockRooms.contains(door.b()))
                    keepDoor(door);
        }

        void keepDoor(StationPlan.Door door)
        {
            var at = at(door.at().getX(), door.at().getY(), door.at().getZ());
            for (int dy = 0; dy <= 2; dy++)
            {
                keep.add(at.above(dy));
                keep.add(at.relative(door.through()).above(dy));
                keep.add(at.relative(door.through().getOpposite()).above(dy));
            }
        }

        // ---- Which rooms ----

        void chooseRooms()
        {
            var eligible = new ArrayList<Room>();
            for (var room : layout.rooms())
                if (room.use() != Use.DOCK && room.use() != Use.ARRIVAL && !dockRooms.contains(room.id()))
                    eligible.add(room);
            if (eligible.isEmpty())
                return;
            int count = Math.min(eligible.size(), Math.max(1, (int) Math.round(SHARE[danger] * layout.rooms().size())));
            var ranked = new ArrayList<>(eligible);
            // Scatter first; the history then pulls its own rooms to the front.
            ranked.sort(Comparator.comparingLong((Room r) -> DerelictHistory.scatter(seed * 31 + r.id().hashCode())));
            var picked = new LinkedHashSet<Room>();
            switch (history)
            {
                case BREACH ->
                {
                    var wound = ranked.stream().filter(r -> r.use() != Use.CORRIDOR && DerelictHistory.wound(seed, r.id())).toList();
                    var start = wound.isEmpty() ? ranked.stream().filter(r -> r.use() != Use.CORRIDOR).findFirst().orElse(ranked.getFirst()) : wound.getFirst();
                    // A wing ruptured: the wound rooms first, then the rooms around them.
                    picked.add(start);
                    for (var r : wound)
                        if (picked.size() < Math.max(1, count / 2))
                            picked.add(r);
                    wounded.addAll(picked.stream().map(Room::id).toList());
                    grow(picked, ranked, count);
                }
                case POWER_FAILURE ->
                {
                    var engineering = ranked.stream().filter(r -> r.use() == Use.ENGINEERING).findFirst();
                    picked.add(engineering.orElse(ranked.getFirst()));
                    wounded.add(picked.iterator().next().id());
                    grow(picked, ranked, count);
                }
                case INTERRUPTED_WORK ->
                {
                    // The crew vanished mid-task: the workrooms stay as they were.
                    var order = new ArrayList<>(ranked);
                    order.sort(Comparator.comparingInt((Room r) -> WORK.contains(r.use().name().toLowerCase(Locale.ROOT)) ? 1 : 0));
                    for (var r : order)
                        if (picked.size() < count)
                            picked.add(r);
                }
                default ->
                {
                    for (var r : ranked)
                        if (picked.size() < count)
                            picked.add(r);
                }
            }
            for (var r : picked)
                damaged.add(r.id());
        }

        /** Adds rooms joined by a door to the picked ones until there are {@code count}, then any others. */
        void grow(Set<Room> picked, List<Room> ranked, int count)
        {
            boolean added = true;
            while (picked.size() < count && added)
            {
                added = false;
                for (var r : ranked)
                {
                    if (picked.size() >= count || picked.contains(r))
                        continue;
                    boolean next = false;
                    for (var d : layout.doors())
                        for (var p : picked)
                            next |= d.a().equals(p.id()) && d.b().equals(r.id()) || d.b().equals(p.id()) && d.a().equals(r.id());
                    if (next)
                    {
                        picked.add(r);
                        added = true;
                    }
                }
            }
            for (var r : ranked)
                if (picked.size() < count)
                    picked.add(r);
        }

        // ---- One wrecked room ----

        void wreckRoom(Room room)
        {
            var random = rng(0x57A7E, room.id());
            boolean wound = wounded.contains(room.id());
            switch (history)
            {
                case EVACUATED ->
                {
                    fixtures(room, FIXTURES[danger] * 0.8, random, false);
                    darken(room, DARK[danger], random);
                    cobwebs(room, WEBS[danger] + 3, random);
                    if (danger >= 1)
                        collapse(room, 1, random);
                }
                case INTERRUPTED_WORK ->
                {
                    fixtures(room, FIXTURES[danger] * 0.4, random, true);
                    darken(room, DARK[danger] * 0.6, random);
                    cobwebs(room, WEBS[danger], random);
                    collapse(room, 1 + danger / 2, random);
                }
                case BREACH ->
                {
                    fixtures(room, FIXTURES[danger], random, false);
                    darken(room, 1.0, random);
                    cobwebs(room, Math.max(1, WEBS[danger] / 2), random);
                    if (room.use() != Use.CORRIDOR)
                    {
                        if (wound)
                            for (int i = 0; i < (danger >= 3 ? 2 : 1) + (danger >= 2 && room.w() * room.d() > 100 ? 1 : 0); i++)
                                breach(room, BREACH_RADIUS[danger], random);
                        collapse(room, wound ? 2 : 1, random);
                        if (danger >= 2 && (wound || random.nextInt(5) < 2))
                            brokenDeck(room, random);
                    }
                    else
                        collapse(room, 1, random);
                }
                case POWER_FAILURE ->
                {
                    fixtures(room, FIXTURES[danger] * 0.7, random, false);
                    darken(room, 1.0, random);
                    cobwebs(room, WEBS[danger], random);
                    removeAir(room);
                    if (danger >= 2 && room.use() != Use.CORRIDOR)
                        collapse(room, 1, random);
                }
            }
        }

        // ---- Tools ----

        /** The other half of a two-block thing, or null. */
        BlockPos partner(BlockPos p, BlockState s)
        {
            if (s.getBlock() instanceof BedBlock)
            {
                var facing = s.getValue(BedBlock.FACING);
                return s.getValue(BedBlock.PART) == BedPart.FOOT ? p.relative(facing) : p.relative(facing.getOpposite());
            }
            if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
                return p.relative(ChestBlock.getConnectedDirection(s));
            if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
                return s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? p.above() : p.below();
            return null;
        }

        /** Removes a block and its other half. Returns false when the block is protected, a machine or already gone. */
        boolean remove(BlockPos p, boolean machinesToo)
        {
            var s = blocks.get(p);
            if (s == null || s.isAir() || keep.contains(p) || !machinesToo && machine(s))
                return false;
            var other = partner(p, s);
            if (other != null && keep.contains(other))
                return false;
            blocks.put(p, Blocks.AIR.defaultBlockState());
            gone.add(p);
            if (other != null && blocks.containsKey(other) && !blocks.get(other).isAir())
            {
                blocks.put(other, Blocks.AIR.defaultBlockState());
                gone.add(other);
            }
            return true;
        }

        /** Strips a share of the room's fixtures: its furniture, partitions and plants, not its machines, doors or stairs. */
        void fixtures(Room room, double share, Random random, boolean keepWork)
        {
            var i = shifted(room.interior());
            for (int x = i.x0(); x <= i.x1(); x++)
                for (int z = i.z0(); z <= i.z1(); z++)
                    for (int y = i.y0(); y <= i.y1(); y++)
                    {
                        var p = new BlockPos(x, y, z);
                        var s = state(p);
                        if (s.isAir() || keep.contains(p) || !loose(s, keepWork))
                            continue;
                        var other = partner(p, s);
                        // A two-block thing is rolled once, from its lower or first block.
                        if (other != null && other.asLong() < p.asLong() && !gone.contains(other) && blocks.containsKey(other))
                            continue;
                        if (random.nextDouble() < share)
                            remove(p, false);
                    }
        }

        /** True for a fixture a wreck may lose. */
        boolean loose(BlockState s, boolean keepWork)
        {
            if (machine(s) || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BasePressurePlateBlock || s.getBlock() instanceof StairBlock || s.getBlock() instanceof LiquidBlock || s.getBlock() instanceof TrapDoorBlock)
                return false;
            String n = name(s);
            if (n.equals("water") || n.equals("lava") || n.endsWith("torch") || n.equals("lantern") || n.equals("sea_lantern"))
                return false;
            if (keepWork && (n.contains("furnace") || n.equals("smoker") || n.equals("crafting_table") || n.equals("anvil") || n.endsWith("_table") || n.equals("brewing_stand") || n.equals("grindstone") || n.equals("stonecutter") || n.equals("loom") || n.equals("lectern") || n.equals("composter") || n.equals("farmland") || n.equals("wheat") || n.equals("barrel")))
                return false;
            return true;
        }

        /** Puts out lights: torches and lanterns go, sea lanterns turn to scorched stone. */
        void darken(Room room, double share, Random random)
        {
            var o = shifted(room.outer());
            for (int x = o.x0(); x <= o.x1(); x++)
                for (int z = o.z0(); z <= o.z1(); z++)
                    for (int y = o.y0(); y <= o.y1(); y++)
                    {
                        var p = new BlockPos(x, y, z);
                        var s = state(p);
                        if (s.isAir() || keep.contains(p))
                            continue;
                        String n = name(s);
                        if (!(n.endsWith("torch") || n.equals("lantern") || n.equals("sea_lantern")) || random.nextDouble() >= share)
                            continue;
                        if (n.equals("sea_lantern"))
                        {
                            blocks.put(p, Blocks.BLACKSTONE.defaultBlockState());
                            gone.add(p);
                        }
                        else
                            remove(p, false);
                    }
        }

        /** Cobwebs in the open cells under the ceiling, where dust gathers. */
        void cobwebs(Room room, int count, Random random)
        {
            var i = shifted(room.interior());
            int top = i.y1();
            for (int placed = 0, tries = 0; placed < count && tries < count * 20; tries++)
            {
                int x = i.x0() + random.nextInt(i.x1() - i.x0() + 1), z = i.z0() + random.nextInt(i.z1() - i.z0() + 1);
                // Webs gather in the corners and along the walls first.
                if (random.nextInt(3) > 0 && x != i.x0() && x != i.x1() && z != i.z0() && z != i.z1())
                    continue;
                for (int y = top; y >= top - 1; y--)
                {
                    var p = new BlockPos(x, y, z);
                    if (state(p).isAir() && !keep.contains(p) && !state(p.above()).isAir())
                    {
                        blocks.put(p, Blocks.COBWEB.defaultBlockState());
                        placed++;
                        break;
                    }
                }
            }
        }

        /** Tears the hull open: a ball of wall, window and roof removed at an exterior spot, with scorched edges. */
        void breach(Room room, int radius, Random random)
        {
            var o = shifted(room.outer());
            var spots = new ArrayList<BlockPos>();
            // The wall and roof cells whose outward neighbour is open space, not a neighbouring room.
            for (int x = o.x0(); x <= o.x1(); x++)
                for (int z = o.z0(); z <= o.z1(); z++)
                    for (int y = o.y0() + 3; y <= o.y1(); y++)
                    {
                        boolean edge = x == o.x0() || x == o.x1() || z == o.z0() || z == o.z1() || y == o.y1();
                        var p = new BlockPos(x, y, z);
                        if (edge && exposed(p, o) && (y <= o.y0() + 6 || y == o.y1()) && !keep.contains(p))
                            spots.add(p);
                    }
            spots.removeIf(keep::contains);
            if (spots.isEmpty())
                return;
            spots.sort(Comparator.comparingLong(BlockPos::asLong));
            var center = spots.get(random.nextInt(spots.size()));
            int cut = 0;
            for (int dx = -radius; dx <= radius; dx++)
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dz = -radius; dz <= radius; dz++)
                    {
                        if (dx * dx + dy * dy + dz * dz > radius * radius + 1)
                            continue;
                        var p = center.offset(dx, dy, dz);
                        // The torn hull is this room's own; a neighbour keeps its walls.
                        if (contains(o, p) && !sharedWithAnother(room, p) && cut(p))
                            cut++;
                    }
            if (cut == 0)
                return;
            breaches++;
            // Scorch and cracks where the hull was torn.
            for (int dx = -radius - 1; dx <= radius + 1; dx++)
                for (int dy = -radius - 1; dy <= radius + 1; dy++)
                    for (int dz = -radius - 1; dz <= radius + 1; dz++)
                    {
                        var p = center.offset(dx, dy, dz);
                        var s = state(p);
                        if (s.isAir() || keep.contains(p) || machine(s) || !s.canOcclude() || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock)
                            continue;
                        boolean next = false;
                        for (var d : Direction.values())
                            next |= gone.contains(p.relative(d));
                        if (!next)
                            continue;
                        int roll = random.nextInt(100);
                        if (roll < 40)
                            blocks.put(p, Blocks.BLACKSTONE.defaultBlockState());
                        else if (roll < 65)
                            blocks.put(p, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
                    }
        }

        /** True when the cell lies on a face of the box with open space (no other room) just outside it. */
        boolean exposed(BlockPos p, Box o)
        {
            for (var d : Direction.values())
            {
                var out = p.relative(d);
                if (!contains(o, out) && !covered(out))
                    return true;
            }
            return false;
        }

        /** Removes one block of the hull or its fittings. */
        boolean cut(BlockPos p)
        {
            return remove(p, false);
        }

        /** The ceiling falls in: a patch of it comes down and lands as rubble on the floor below. */
        void collapse(Room room, int size, Random random)
        {
            var i = shifted(room.interior());
            var o = shifted(room.outer());
            int radius = Math.max(1, Math.min(size, Math.min(i.x1() - i.x0(), i.z1() - i.z0()) / 3));
            int cx = i.x0() + 1 + random.nextInt(Math.max(1, i.x1() - i.x0() - 1)), cz = i.z0() + 1 + random.nextInt(Math.max(1, i.z1() - i.z0() - 1));
            int top = o.y1();
            for (int x = cx - radius; x <= cx + radius; x++)
                for (int z = cz - radius; z <= cz + radius; z++)
                {
                    if (x < i.x0() || x > i.x1() || z < i.z0() || z > i.z1() || (x - cx) * (x - cx) + (z - cz) * (z - cz) > radius * radius + 1)
                        continue;
                    // The roof comes down where nothing is above it, and whatever sits on it; a deck shared with the room above stays.
                    var ceiling = new BlockPos(x, top, z);
                    if (!covered(ceiling.above()) && !sharedWithAnother(room, ceiling))
                    {
                        remove(ceiling, false);
                        for (int up = 1; up <= 3; up++)
                            remove(new BlockPos(x, top + up, z), false);
                    }
                    rubble(new BlockPos(x, i.y0(), z), random);
                }
            // Hanging fittings (lanterns, wall torches) near the fall lose their hold with the settle pass.
        }

        /** True when the cell is part of some other room's box too: a wall or slab two rooms share. */
        boolean sharedWithAnother(Room room, BlockPos p)
        {
            for (var other : layout.rooms())
                if (other != room && contains(shifted(other.outer()), p))
                    return true;
            return false;
        }

        /** A block of rubble on the floor cell, if it is open and not in anyone's way. */
        void rubble(BlockPos p, Random random)
        {
            if (keep.contains(p) || !state(p).isAir() || random.nextInt(3) == 0)
                return;
            var below = state(p.below());
            if (below.isAir() || below.getBlock() instanceof StairBlock || below.getBlock() instanceof BasePressurePlateBlock || below.getBlock() instanceof DoorBlock)
                return;
            blocks.put(p, random.nextInt(4) == 0 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
        }

        /** A hole through the deck between this room and the one above it: the slab removed over a patch, and what stood over it. */
        void brokenDeck(Room room, Random random)
        {
            var o = shifted(room.outer());
            int slab = o.y1();
            var i = shifted(room.interior());
            for (var upper : layout.rooms())
            {
                var u = shifted(upper.outer());
                if (upper == room || u.y0() != o.y1() || upper.use() == Use.ARRIVAL || upper.use() == Use.DOCK || dockRooms.contains(upper.id()))
                    continue;
                var ui = shifted(upper.interior());
                int x0 = Math.max(i.x0(), ui.x0()), x1 = Math.min(i.x1(), ui.x1()), z0 = Math.max(i.z0(), ui.z0()), z1 = Math.min(i.z1(), ui.z1());
                if (x1 - x0 < 2 || z1 - z0 < 2)
                    continue;
                int cx = x0 + 1 + random.nextInt(x1 - x0 - 1), cz = z0 + 1 + random.nextInt(z1 - z0 - 1);
                int radius = danger >= 3 ? 2 : 1;
                int cut = 0;
                for (int x = cx - radius; x <= cx + radius; x++)
                    for (int z = cz - radius; z <= cz + radius; z++)
                    {
                        if (x < x0 || x > x1 || z < z0 || z > z1 || (x - cx) * (x - cx) + (z - cz) * (z - cz) > radius * radius + 1)
                            continue;
                        // Two layers of slab, and the fittings standing over the hole in the room above.
                        boolean did = remove(new BlockPos(x, slab, z), false);
                        did |= remove(new BlockPos(x, slab + 1, z), false);
                        for (int up = 2; up <= 4; up++)
                            remove(new BlockPos(x, slab + up, z), false);
                        if (did)
                            cut++;
                    }
                if (cut > 0)
                {
                    holes++;
                    return;
                }
            }
        }

        /** Strips the room's Air Supplies, so its air is stale. */
        void removeAir(Room room)
        {
            var i = shifted(room.interior());
            for (int x = i.x0(); x <= i.x1(); x++)
                for (int z = i.z0(); z <= i.z1(); z++)
                    for (int y = i.y0(); y <= i.y1(); y++)
                    {
                        var p = new BlockPos(x, y, z);
                        if (state(p).is(ModBlocks.AIR_SUPPLY))
                            remove(p, true);
                    }
        }

        /** Engineering died: no generator or battery is left anywhere, and every light but the dock's is out. */
        void powerFailure()
        {
            for (var e : new ArrayList<>(blocks.entrySet()))
            {
                var s = e.getValue();
                if (s.is(ModBlocks.GENERATOR) || s.is(ModBlocks.BATTERY))
                    remove(e.getKey(), true);
            }
            for (var room : layout.rooms())
                if (!dockRooms.contains(room.id()))
                    darken(room, 1.0, rng(0x11C4, room.id()));
        }

        // ---- Doors ----

        void doors()
        {
            for (var door : layout.doors())
            {
                boolean dock = dockRooms.contains(door.a()) || dockRooms.contains(door.b());
                boolean a = damaged.contains(door.a()), b = damaged.contains(door.b());
                if (dock || !a && !b)
                    continue;
                var random = rng(0xD00A, door.a() + ">" + door.b());
                int roll = random.nextInt(100);
                var at = at(door.at().getX(), door.at().getY(), door.at().getZ());
                var state = state(at);
                if (!(state.getBlock() instanceof DoorBlock))
                    continue;
                // Pressure and venting: only a door between two wrecked rooms is opened up; a door to a whole room stays shut, at most jammed.
                boolean both = a && b;
                int knock, open, jam;
                switch (history)
                {
                    case EVACUATED ->
                    {
                        knock = 0;
                        open = 70;
                        jam = 85;
                    }
                    case BREACH ->
                    {
                        knock = 40;
                        open = 40;
                        jam = 75;
                    }
                    case POWER_FAILURE ->
                    {
                        knock = 10;
                        open = 30;
                        jam = 70;
                    }
                    default ->
                    {
                        knock = 0;
                        open = 20;
                        jam = 35;
                    }
                }
                if (both && roll < knock)
                {
                    remove(at, false);
                    removePlates(door, at);
                    doorsChanged++;
                }
                else if (both && roll < open)
                {
                    setOpen(at, true);
                    doorsChanged++;
                }
                else if (roll < jam && (history != DerelictHistory.EVACUATED || both))
                {
                    removePlates(door, at);
                    doorsChanged++;
                }
            }
        }

        void setOpen(BlockPos at, boolean open)
        {
            for (var p : List.of(at, at.above()))
            {
                var s = state(p);
                if (s.getBlock() instanceof DoorBlock)
                    blocks.put(p, s.setValue(DoorBlock.OPEN, open));
            }
        }

        void removePlates(StationPlan.Door door, BlockPos at)
        {
            for (var p : List.of(at.relative(door.through()), at.relative(door.through().getOpposite())))
                if (state(p).getBlock() instanceof BasePressurePlateBlock)
                {
                    blocks.put(p, Blocks.AIR.defaultBlockState());
                    gone.add(p);
                }
        }

        // ---- Afterwards ----

        /** Removes whatever hung on or stood on a block that is gone, until nothing is left without support. */
        void settle()
        {
            for (int pass = 0; pass < 6; pass++)
            {
                boolean changed = false;
                for (var e : new ArrayList<>(blocks.entrySet()))
                {
                    var p = e.getKey();
                    var s = e.getValue();
                    if (s.isAir() || keep.contains(p))
                        continue;
                    String n = name(s);
                    BlockPos support = null;
                    boolean needs = false;
                    if (n.equals("wall_torch") || n.equals("soul_wall_torch") || n.equals("redstone_wall_torch"))
                    {
                        support = p.relative(s.getValue(HorizontalDirectionalBlock.FACING).getOpposite());
                        needs = true;
                    }
                    else if (n.equals("torch") || n.equals("soul_torch") || s.getBlock() instanceof CarpetBlock || s.getBlock() instanceof BasePressurePlateBlock || s.getBlock() instanceof VegetationBlock || s.getBlock() instanceof FlowerPotBlock || s.getBlock() instanceof CandleBlock || n.equals("redstone_wire"))
                    {
                        support = p.below();
                        needs = true;
                    }
                    else if (s.getBlock() instanceof LanternBlock)
                    {
                        support = s.getValue(BlockStateProperties.HANGING) ? p.above() : p.below();
                        needs = true;
                    }
                    else if (s.getBlock() instanceof LadderBlock)
                    {
                        support = p.relative(s.getValue(HorizontalDirectionalBlock.FACING).getOpposite());
                        needs = true;
                    }
                    if (needs && !(s.getBlock() instanceof DoorBlock) && state(support).isAir() && gone.contains(support))
                    {
                        if (remove(p, false))
                            changed = true;
                    }
                }
                if (!changed)
                    break;
            }
        }

        /** Points of entities the wreck lost or stripped: frames empty or gone, stands bare, animals dead, paintings off a torn wall. */
        List<Piece.Point> filterPoints()
        {
            var out = new ArrayList<Piece.Point>();
            for (var point : points)
            {
                var room = roomAt(point.pos());
                boolean hit = room != null && damaged.contains(room.id());
                var random = new Random(DerelictHistory.scatter(seed ^ point.pos().asLong()));
                switch (point.name())
                {
                    case "frame" ->
                    {
                        var parts = point.argument().split(" ");
                        var face = parts.length > 1 ? Direction.byName(parts[1]) : null;
                        if (face != null && (gone.contains(point.pos().relative(face.getOpposite())) || state(point.pos().relative(face.getOpposite())).isAir()))
                            continue;
                        int empty = switch (history)
                        {
                            case EVACUATED -> 100;
                            case BREACH -> 70;
                            case POWER_FAILURE -> 40;
                            default -> 20;
                        };
                        if (hit && parts.length > 1 && random.nextInt(100) < empty)
                            out.add(new Piece.Point(point.pos(), "frame", "minecraft:air " + parts[1]));
                        else
                            out.add(point);
                    }
                    case "painting" ->
                    {
                        var parts = point.argument().split(" ");
                        var face = Direction.byName(parts[0]);
                        boolean torn = false;
                        if (face != null)
                        {
                            var wall = point.pos().relative(face.getOpposite());
                            for (var g : gone)
                                torn |= Math.abs(g.getX() - wall.getX()) <= 2 && Math.abs(g.getY() - wall.getY()) <= 2 && Math.abs(g.getZ() - wall.getZ()) <= 2;
                        }
                        if (!torn)
                            out.add(point);
                    }
                    case "stand" ->
                    {
                        var parts = point.argument().split(" ");
                        if (gone.contains(point.pos().below()) || state(point.pos().below()).isAir())
                            continue;
                        if (hit && parts.length >= 1 && random.nextInt(100) < (history == DerelictHistory.INTERRUPTED_WORK ? 20 : 80))
                            out.add(new Piece.Point(point.pos(), "stand", parts[0] + " empty"));
                        else
                            out.add(point);
                    }
                    case "animal" ->
                    {
                        // Animals in a wrecked room have no air, or no keeper.
                        if (gone.contains(point.pos().below()) || state(point.pos().below()).isAir() || hit && history != DerelictHistory.INTERRUPTED_WORK)
                            continue;
                        out.add(point);
                    }
                    default -> out.add(point);
                }
            }
            return out;
        }
    }

    private DerelictDamage()
    {
    }
}
