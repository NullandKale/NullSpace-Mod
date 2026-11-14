package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.shipgen.ShipBlocks;
import com.nullandkale.nullspace.worldgen.stations.StationLighting.Torch;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.*;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Renders a {@link StationPlan} as real blocks, in passes: shells in the maker's palette with
 * courses and piers, patterned floors, windows, framed doors, docking ports, the atrium stair and
 * gallery, utilities, rooftops, and finally the torch lighting rounds. Rooms are left empty for the
 * room generators; each room records one {@code room} point at its floor centre.
 */
public final class StationStructure
{
    private static final int STEPS = StationPlan.PITCH, ROUNDS = 12;

    public static StationBlueprint.Plan build(StationPlan plan)
    {
        return build(plan, false);
    }

    /**
     * {@code ship} builds a vessel: no Station Core, the bridge's helm is the controller, and engineering gets its engines in the stern wall.
     */
    static StationBlueprint.Plan build(StationPlan plan, boolean ship)
    {
        var palette = ShipBlocks.palette(plan.spec().maker());
        var style = StationStyle.of(plan.spec().maker(), plan.spec().seed());
        var out = new LinkedHashMap<BlockPos, BlockState>();
        var occupied = shell(plan, style, out);
        var docks = new ArrayList<BlockPos>();
        for (var berth : plan.berths())
        {
            StationBlueprint.compact(out, berth.port(), berth.facing());
            docks.add(berth.port().below());
            for (int depth = 1; depth <= 3; depth++)
                occupied.add(berth.port().relative(berth.facing().getOpposite(), depth));
        }
        var points = new ArrayList<Piece.Point>();
        for (var room : plan.rooms())
        {
            if (room.use() == Use.ARRIVAL && room.levels() >= 2 && room.climb() != null)
                occupied.addAll(atrium(out, room, style, palette));
            var interior = room.interior();
            var center = new BlockPos((interior.x0() + interior.x1()) / 2, interior.y0(), (interior.z0() + interior.z1()) / 2);
            points.add(new Piece.Point(center, "room", room.id() + ":" + room.use().name().toLowerCase(Locale.ROOT)));
        }
        // Every room with one door and one job is furnished by its design.
        var furnished = new HashSet<String>();
        for (var room : plan.rooms())
        {
            if (!StationRooms.furnishes(room.use()))
                continue;
            var doors = plan.doors().stream().filter(d -> d.a().equals(room.id()) || d.b().equals(room.id())).toList();
            if (doors.size() != 1)
                continue;
            // Every room has an Air Supply asking for power, so a ship's power plant grows with its rooms: a generator per 16 power wanted.
            int generators = (4 * plan.rooms().size() + 12 + 15) / 16;
            int variant = ship && room.use() == Use.ENGINEERING ? Math.min(3, Math.max(1, (generators + 1) / 2 - 1)) : StationRooms.variantFor(plan.spec(), room);
            occupied.addAll(StationRooms.furnish(style, palette, out, points, room, doors.getFirst(), variant, plan.spec().seed()));
            furnished.add(room.id());
        }
        BlockPos core = null;
        for (var room : plan.rooms())
        {
            var cells = corners(room, occupied);
            if (ship)
            {
                // A vessel has no core; its helm is the controller.
            }
            else if (room.id().equals(plan.arrival()) && room.levels() >= 2 && room.climb() != null)
            {
                // A tall hall has its core on the centre line, under the open well, facing the first doorway.
                var i = room.interior();
                core = new BlockPos((i.x0() + i.x1()) / 2, room.standY(), (i.z0() + i.z1()) / 2);
                var facing = Direction.NORTH;
                for (var door : plan.doors())
                    if (door.a().equals(room.id()) || door.b().equals(room.id()))
                    {
                        facing = door.a().equals(room.id()) ? door.through() : door.through().getOpposite();
                        break;
                    }
                machine(out, core, ModBlocks.STATION_CORE, facing);
                occupied.add(core);
            }
            else if (room.id().equals(plan.arrival()) && !cells.isEmpty())
            {
                core = cells.removeFirst();
                machine(out, core, ModBlocks.STATION_CORE, toward(room, core));
                occupied.add(core);
            }
            // A furnished room's design supplies its own air and, in engineering, its own machines.
            boolean designed = furnished.contains(room.id());
            if (room.use() == Use.ENGINEERING && cells.size() >= 3 && !(designed && has(out, room, ModBlocks.GENERATOR) && has(out, room, ModBlocks.BATTERY)))
            {
                for (int k = 0; k < 2; k++)
                {
                    var at = cells.removeFirst();
                    machine(out, at, ModBlocks.GENERATOR, toward(room, at));
                    machine(out, at.above(), ModBlocks.BATTERY, toward(room, at));
                    occupied.add(at);
                }
            }
            if (!cells.isEmpty() && !(designed && has(out, room, ModBlocks.AIR_SUPPLY)))
            {
                // The Air Supply breathes out into the cell it faces, so that cell must be open.
                BlockPos at = null;
                for (var cell : cells)
                {
                    var outlet = out.get(cell.relative(toward(room, cell)));
                    if (!occupied.contains(cell.relative(toward(room, cell))) && (outlet == null || outlet.isAir()))
                    {
                        at = cell;
                        break;
                    }
                }
                // A room with no cell that has an open outlet still gets its supply, in its first free cell.
                if (at == null)
                    at = cells.getFirst();
                machine(out, at, ModBlocks.AIR_SUPPLY, toward(room, at));
                occupied.add(at);
            }
        }
        if (ship)
            for (var e : out.entrySet())
                if (e.getValue().is(ModBlocks.COMPUTER))
                    core = e.getKey();
        if (core == null)
            throw new IllegalStateException(ship ? "The bridge has no helm" : "No free cell for the Station Core in " + plan.arrival());
        lootChests(plan, out, points);
        roofs(out, plan, style);
        // An Air Supply breathes into the cell it faces, so no torch may stand there.
        for (var e : new ArrayList<>(out.entrySet()))
            if (e.getValue().is(ModBlocks.AIR_SUPPLY))
                occupied.add(e.getKey().relative(e.getValue().getValue(HorizontalDirectionalBlock.FACING)));
        light(out, plan, occupied);
        return new StationBlueprint.Plan(out, core, docks, points);
    }

    /**
     * Marks one chest in each room as its loot chest, for a found vessel to stock: the room's first single chest, else
     * its first double chest, else its first barrel. A working station never stocks them.
     */
    private static void lootChests(StationPlan plan, Map<BlockPos, BlockState> out, List<Piece.Point> points)
    {
        var best = new HashMap<String, BlockPos>();
        var score = new HashMap<String, Long>();
        for (var e : out.entrySet())
        {
            var state = e.getValue();
            boolean chest = state.is(Blocks.CHEST), barrel = state.is(Blocks.BARREL);
            if (!chest && !barrel)
                continue;
            var at = e.getKey();
            for (var room : plan.rooms())
            {
                if (room.use() == Use.CORRIDOR || room.use() == Use.ARRIVAL || room.use() == Use.DOCK)
                    continue;
                var i = room.interior();
                if (at.getX() < i.x0() || at.getX() > i.x1() || at.getY() < i.y0() || at.getY() > i.y1() || at.getZ() < i.z0() || at.getZ() > i.z1())
                    continue;
                long rank = (chest ? state.getValue(ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE ? 0 : 1 : 2) * (1L << 60) + at.asLong() + (1L << 59);
                if (rank < score.getOrDefault(room.id(), Long.MAX_VALUE))
                {
                    score.put(room.id(), rank);
                    best.put(room.id(), at);
                }
                break;
            }
        }
        for (var room : plan.rooms())
            if (best.containsKey(room.id()))
                points.add(new Piece.Point(best.get(room.id()), "roomloot", room.id() + " " + room.use().name().toLowerCase(Locale.ROOT) + " " + StationRooms.variantFor(plan.spec(), room)));
    }

    /** True when a block of this kind stands anywhere in the room's interior. */
    private static boolean has(Map<BlockPos, BlockState> out, Room room, Block block)
    {
        var i = room.interior();
        for (int x = i.x0(); x <= i.x1(); x++)
            for (int z = i.z0(); z <= i.z1(); z++)
                for (int y = i.y0(); y <= i.y1(); y++)
                {
                    var state = out.get(new BlockPos(x, y, z));
                    if (state != null && state.is(block))
                        return true;
                }
        return false;
    }

    /**
     * The passes every station and every review room share: ceilings, walls, piers, floors, windows,
     * framed doors with a stone pressure plate on each side, and the cells kept clear around them.
     * Returns the cells later passes must leave alone.
     */
    private static Set<BlockPos> shell(StationPlan plan, StationStyle style, Map<BlockPos, BlockState> out)
    {
        // Ceilings, then walls and posts, then floors: where levels share a slab it is the upper room's floor.
        for (var room : plan.rooms())
            ceiling(out, room, style);
        for (var room : plan.rooms())
            walls(out, room, style);
        // Piers go on after every hull so a neighbour's wall never erases them; none within two cells of a doorway.
        var clear = new HashSet<BlockPos>();
        for (var door : plan.doors())
            for (int side = -2; side <= 2; side++)
                clear.add(pierColumn(door.at().relative(door.through().getClockWise(), side)));
        for (var berth : plan.berths())
            for (int side = -2; side <= 2; side++)
                clear.add(pierColumn(berth.port().relative(berth.facing().getClockWise(), side)));
        var reserved = new HashSet<BlockPos>();
        for (var room : plan.rooms())
            piers(out, room, style, clear, reserved);
        for (var room : plan.rooms())
            floor(out, room, style);
        for (var door : plan.doors())
            for (int dy = 0; dy <= 2; dy++)
            {
                reserved.add(door.at().above(dy));
                reserved.add(door.at().relative(door.through().getClockWise()).above(dy));
                reserved.add(door.at().relative(door.through().getCounterClockWise()).above(dy));
            }
        for (var berth : plan.berths())
            for (int dy = -1; dy <= 3; dy++)
                for (int side = -1; side <= 1; side++)
                    reserved.add(berth.port().relative(berth.facing().getClockWise(), side).above(dy));
        windows(out, plan, style, reserved);
        for (var door : plan.doors())
        {
            door(out, door.at(), door.through());
            plates(out, door);
        }
        var occupied = new HashSet<BlockPos>();
        for (var door : plan.doors())
        {
            // Keep the doorway and the cells straight through it clear of utilities.
            occupied.add(door.at());
            occupied.add(door.at().relative(door.through()));
            occupied.add(door.at().relative(door.through().getOpposite()));
        }
        return occupied;
    }

    /** A stone pressure plate in the cell in front of a door on each side: stepping up opens it, stepping away lets it close. */
    private static void plates(Map<BlockPos, BlockState> out, Door door)
    {
        var plate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
        out.put(door.at().relative(door.through()), plate);
        out.put(door.at().relative(door.through().getOpposite()), plate);
    }

    private static boolean plate(BlockState state)
    {
        return state != null && state.getBlock() instanceof BasePressurePlateBlock;
    }

    /**
     * One room on its own, for review: a small lobby with an open doorway, the plated door between,
     * and the room furnished by its design. {@code variant} picks between a room's variants.
     */
    public static StationBlueprint.Plan room(StationPlan plan, int variant)
    {
        var palette = ShipBlocks.palette(plan.spec().maker());
        var style = StationStyle.of(plan.spec().maker(), plan.spec().seed());
        var out = new LinkedHashMap<BlockPos, BlockState>();
        var occupied = shell(plan, style, out);
        var points = new ArrayList<Piece.Point>();
        for (var room : plan.rooms())
        {
            if (!StationRooms.furnishes(room.use()))
                continue;
            for (var door : plan.doors())
                if (door.a().equals(room.id()) || door.b().equals(room.id()))
                {
                    occupied.addAll(StationRooms.furnish(style, palette, out, points, room, door, variant, plan.spec().seed()));
                    break;
                }
            var interior = room.interior();
            points.add(new Piece.Point(new BlockPos((interior.x0() + interior.x1()) / 2, interior.y0(), (interior.z0() + interior.z1()) / 2), "room", room.id() + ":" + room.use().name().toLowerCase(Locale.ROOT)));
        }
        // The lobby's outer wall is open, so a visitor walks straight in.
        for (var lobby : plan.rooms(Use.CORRIDOR))
        {
            int x = (lobby.x() + lobby.x() + lobby.w() - 1) / 2;
            for (int dx = -1; dx <= 1; dx++)
                for (int y = lobby.standY(); y <= lobby.standY() + 2; y++)
                    out.put(new BlockPos(x + dx, y, lobby.z()), Blocks.AIR.defaultBlockState());
        }
        light(out, plan, occupied);
        return new StationBlueprint.Plan(out, BlockPos.ZERO, List.of(), points);
    }

    // ---- Shells ----

    private static void ceiling(Map<BlockPos, BlockState> out, Room room, StationStyle style)
    {
        var o = room.outer();
        var block = style.ceiling.defaultBlockState();
        boolean beamsAcrossZ = o.x1() - o.x0() >= o.z1() - o.z0();
        // Ceiling beams lie flat along the line they span.
        var beam = style.beam.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, beamsAcrossZ ? net.minecraft.core.Direction.Axis.Z : net.minecraft.core.Direction.Axis.X);
        var lines = pierLine(beamsAcrossZ ? o.x1() - o.x0() : o.z1() - o.z0(), style.pierSpacing);
        for (int x = o.x0(); x <= o.x1(); x++)
            for (int z = o.z0(); z <= o.z1(); z++)
            {
                // Log beams span the room's short way at the same spacing as the wall piers.
                boolean line = lines.contains(beamsAcrossZ ? x - o.x0() : z - o.z0());
                out.put(new BlockPos(x, o.y1(), z), line ? beam : block);
            }
    }

    /** Wall courses: a skirting at the foot, a cornice at the head, piers at a regular spacing, weighted hull blocks between. */
    private static void walls(Map<BlockPos, BlockState> out, Room room, StationStyle style)
    {
        var o = room.outer();
        var frame = style.frame.defaultBlockState();
        for (int y = o.y0(); y <= o.y1(); y++)
            for (int x = o.x0(); x <= o.x1(); x++)
                for (int z = o.z0(); z <= o.z1(); z++)
                {
                    boolean edgeX = x == o.x0() || x == o.x1(), edgeZ = z == o.z0() || z == o.z1();
                    if (!edgeX && !edgeZ)
                        continue;
                    if (edgeX && edgeZ)
                    {
                        out.put(new BlockPos(x, y, z), frame);
                        continue;
                    }
                    BlockState state;
                    if (y == o.y0() + 2)
                        state = style.skirting.defaultBlockState();
                    else if (y == o.y1() - 1)
                        state = style.cornice.defaultBlockState();
                    else
                        state = style.hull(x, y, z);
                    out.put(new BlockPos(x, y, z), state);
                }
    }

    /**
     * Where log piers stand along a wall (or beams across a ceiling) of the given length: the room is divided into
     * equal bays and the positions are mirrored, so every wall of a room shows the same even rhythm.
     */
    static List<Integer> pierLine(int length, int spacing)
    {
        var out = new ArrayList<Integer>();
        if (spacing <= 0 || length < 5)
            return out;
        if (length < 2 * spacing - 1)
        {
            // A short wall: one centred pier, or two mirrored at the thirds.
            if (length % 2 == 0)
                out.add(length / 2);
            else
            {
                out.add(Math.round(length / 3f));
                out.add(length - Math.round(length / 3f));
            }
            return out;
        }
        int bays = Math.max(2, Math.round(length / (float) spacing));
        if (bays % 2 == 0 && length % 2 == 1)
            bays++; // an odd-length wall has no centre block for a middle pier
        for (int k = 1; k < bays; k++)
            out.add(k <= bays / 2 ? Math.round(k * length / (float) bays) : length - Math.round((bays - k) * length / (float) bays));
        return out;
    }

    /** A wall column, whatever its height: doorway clearance applies to the whole pier, not just the door's rows. */
    static BlockPos pierColumn(BlockPos at)
    {
        return new BlockPos(at.getX(), 0, at.getZ());
    }

    /** Full-height log piers on every wall, laid out per room and kept clear of doorways. */
    private static void piers(Map<BlockPos, BlockState> out, Room room, StationStyle style, Set<BlockPos> clear, Set<BlockPos> placed)
    {
        var o = room.outer();
        var pier = style.beam.defaultBlockState();
        for (int a : pierLine(o.x1() - o.x0(), style.pierSpacing))
            for (int z : new int[]{o.z0(), o.z1()})
                for (int y = o.y0(); y <= o.y1(); y++)
                {
                    var at = new BlockPos(o.x0() + a, y, z);
                    if (!clear.contains(pierColumn(at)))
                    {
                        out.put(at, pier);
                        placed.add(at);
                    }
                }
        for (int a : pierLine(o.z1() - o.z0(), style.pierSpacing))
            for (int x : new int[]{o.x0(), o.x1()})
                for (int y = o.y0(); y <= o.y1(); y++)
                {
                    var at = new BlockPos(x, y, o.z0() + a);
                    if (!clear.contains(pierColumn(at)))
                    {
                        out.put(at, pier);
                        placed.add(at);
                    }
                }
    }

    private static void floor(Map<BlockPos, BlockState> out, Room room, StationStyle style)
    {
        var i = room.interior();
        int w = i.x1() - i.x0() + 1, d = i.z1() - i.z0() + 1;
        for (int x = i.x0(); x <= i.x1(); x++)
            for (int z = i.z0(); z <= i.z1(); z++)
            {
                // The slab's lower block is structure (a room under this one has already put its ceiling there);
                // the upper block is the floor surface, which a room's design may replace.
                out.putIfAbsent(new BlockPos(x, room.y(), z), style.ceiling.defaultBlockState());
                out.put(new BlockPos(x, room.floorY(), z), style.floor(x - i.x0(), z - i.z0(), w, d, room.use() == Use.CORRIDOR));
            }
    }

    // ---- Windows ----

    /** True when the cell lies inside any room's outer box. */
    private static boolean covered(StationPlan plan, int x, int y, int z)
    {
        for (var r : plan.rooms())
        {
            var o = r.outer();
            if (x >= o.x0() && x <= o.x1() && y >= o.y0() && y <= o.y1() && z >= o.z0() && z <= o.z1())
                return true;
        }
        return false;
    }

    /**
     * Glazing on every exterior wall in the maker's style. Windows are whole glass blocks, so a shell
     * stays sealed; they stay clear of doorways and ports.
     */
    private static void windows(Map<BlockPos, BlockState> out, StationPlan plan, StationStyle style, Set<BlockPos> reserved)
    {
        var glass = style.glass.defaultBlockState();
        for (var room : plan.rooms())
        {
            if (room.use() == Use.DOCK)
                continue;
            var o = room.outer();
            for (int level = 0; level < room.levels(); level++)
                for (int row = 2; row <= 4; row++)
                {
                    int y = room.y() + level * StationPlan.PITCH + 1 + row;
                    for (var side : StationBuilder.SIDES)
                    {
                        boolean alongX = side.getAxis() == Direction.Axis.Z;
                        int lo = alongX ? o.x0() : o.z0(), hi = alongX ? o.x1() : o.z1();
                        // Bays run between the room's piers; each bay is glazed the same mirrored way.
                        var bounds = new ArrayList<Integer>();
                        bounds.add(0);
                        bounds.addAll(pierLine(hi - lo, style.pierSpacing));
                        bounds.add(hi - lo);
                        for (int a = lo + 2; a <= hi - 2; a++)
                        {
                            int rel = a - lo, start = 0, end = hi - lo;
                            for (int b = 1; b < bounds.size(); b++)
                                if (rel > bounds.get(b - 1) && rel < bounds.get(b))
                                {
                                    start = bounds.get(b - 1);
                                    end = bounds.get(b);
                                }
                            // Distance from the nearer pier of this bay: 0 is the cell beside it.
                            int edge = Math.min(rel - start - 1, end - rel - 1);
                            boolean pane = switch (style.windows)
                            {
                                case BANDS -> true;
                                case GRID -> edge >= 1 || end - start <= 3;
                                case SLITS -> edge % 2 == 1 || end - start <= 3;
                            };
                            if (!pane)
                                continue;
                            int x = alongX ? a : side == Direction.WEST ? o.x0() : o.x1();
                            int z = alongX ? (side == Direction.NORTH ? o.z0() : o.z1()) : a;
                            var at = new BlockPos(x, y, z);
                            var beyond = at.relative(side);
                            if (reserved.contains(at) || covered(plan, beyond.getX(), beyond.getY(), beyond.getZ()))
                                continue;
                            out.put(at, glass);
                        }
                    }
                }
        }
    }

    private static void door(Map<BlockPos, BlockState> out, BlockPos at, Direction through)
    {
        var s = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, through);
        out.put(at, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        out.put(at.above(), s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    // ---- The atrium stair and gallery ----

    private static BlockPos laneCell(Room room, Direction dir, int lane, int index)
    {
        var i = room.interior();
        var band = dir.getClockWise();
        if (dir.getAxis() == Direction.Axis.X)
            return new BlockPos(dir == Direction.EAST ? i.x0() + index : i.x1() - index, 0, band == Direction.SOUTH ? i.z1() - lane : i.z0() + lane);
        return new BlockPos(band == Direction.EAST ? i.x1() - lane : i.x0() + lane, 0, dir == Direction.SOUTH ? i.z0() + index : i.z1() - index);
    }

    /**
     * A tall arrival hall: gallery rings around an open well, one per upper level, joined by straight
     * flights. Flight k climbs from ring k to ring k+1 along a wall on the climber's right; flights
     * alternate between the climb's wall and the one opposite it, so each lands on its own ring and the
     * next starts across the hall. A flight has a flat landing at its foot, a stringer under the wall
     * side only (the rest is open beneath) and a rail along its open side; each ring is open only where
     * the arriving flight rises through it, has a rail on its whole inner edge, and has a rail across
     * the hole's low end. The ring widens with the hall so the well stays three cells across.
     * Returns the ground cells the first flight and its landing occupy.
     */
    private static Set<BlockPos> atrium(Map<BlockPos, BlockState> out, Room room, StationStyle style, ShipBlocks.Palette palette)
    {
        var i = room.interior();
        int span = Math.min(i.x1() - i.x0(), i.z1() - i.z0()) + 1, maxDepth = (span - 1) / 2;
        // Two flights on opposite walls would cut a narrow ring in two, stranding the rooms between the
        // cuts. A hall of three or more decks therefore keeps its well only when the ring is wide enough to
        // walk round the flights (15 or more across); narrower halls have full floors with just the stair holes.
        boolean well = room.levels() < 3 || span >= 15;
        int ringWidth = well ? Math.max(3, (span - 3) / 2) : maxDepth + 1;
        var stringer = style.skirting.defaultBlockState();
        var rail = style.rail.defaultBlockState();
        var reserved = new HashSet<BlockPos>();
        // The cells each flight rises through, which the ring it arrives at leaves open.
        var holes = new ArrayList<Set<BlockPos>>();
        var lowEnds = new ArrayList<List<BlockPos>>();
        for (int flight = 0; flight < room.levels() - 1; flight++)
        {
            var dir = flight % 2 == 0 ? room.climb() : room.climb().getOpposite();
            // Rows are counted from the floor surface: step k stands in row base + 1 + k.
            int base = room.floorY() + flight * STEPS;
            var step = palette.stairs().defaultBlockState().setValue(StairBlock.FACING, dir);
            var open = new HashSet<BlockPos>();
            var low = new ArrayList<BlockPos>();
            for (int lane = 0; lane < 3; lane++)
            {
                if (flight == 0)
                    reserved.add(laneCell(room, dir, lane, 0).atY(base + 1));
                for (int k = 0; k < STEPS; k++)
                {
                    var c = laneCell(room, dir, lane, k + 1);
                    for (int y = 1; y <= k && lane == 0; y++)
                        out.put(c.atY(base + y), stringer);
                    out.put(c.atY(base + 1 + k), step);
                    open.add(c);
                    if (flight == 0)
                        reserved.add(c.atY(base + 1));
                }
            }
            // The rail on the flight's open side climbs with the steps; its cells are also left open in the ring.
            // Two bars tall, so each column shares a level with the next and the rail is one connected line;
            // the lower bar is level with the step beside it, so the rail starts at the tread.
            for (int k = 0; k < STEPS; k++)
            {
                var c = laneCell(room, dir, 3, k + 1);
                open.add(c);
                out.put(c.atY(base + 1 + k), rail);
                out.put(c.atY(base + 2 + k), rail);
            }
            for (int lane = 0; lane <= 3 && lane < ringWidth; lane++)
                low.add(laneCell(room, dir, lane, 0));
            holes.add(open);
            lowEnds.add(low);
        }
        var ringFloor = style.floorA.defaultBlockState();
        var ringEdge = style.floorB.defaultBlockState();
        for (int level = 1; level < room.levels(); level++)
        {
            int top = room.floorY() + level * STEPS;
            var open = holes.get(level - 1);
            for (int x = i.x0(); x <= i.x1(); x++)
                for (int z = i.z0(); z <= i.z1(); z++)
                {
                    int depth = Math.min(Math.min(x - i.x0(), i.x1() - x), Math.min(z - i.z0(), i.z1() - z));
                    var cell = new BlockPos(x, 0, z);
                    if (depth >= ringWidth || open.contains(cell))
                        continue;
                    boolean edge = well && depth == ringWidth - 1;
                    out.put(new BlockPos(x, top - 1, z), style.ceiling.defaultBlockState());
                    out.put(new BlockPos(x, top, z), edge ? ringEdge : ringFloor);
                    // The ring's inner edge carries a rail so nobody walks off into the well.
                    if (edge)
                        out.put(new BlockPos(x, top + 1, z), rail);
                }
            // Nobody walks off the ring into the foot of the hole either.
            for (var c : lowEnds.get(level - 1))
                out.put(c.atY(top + 1), rail);
        }
        return reserved;
    }

    // ---- Roofs ----

    /** Rooftops in the maker's shape, over every cell whose sky is free; edges are cells that drop off to nothing. */
    private static void roofs(Map<BlockPos, BlockState> out, StationPlan plan, StationStyle style)
    {
        for (var room : plan.rooms())
        {
            var o = room.outer();
            int y = o.y1();
            switch (style.roofs)
            {
                case PARAPET -> parapet(out, plan, style, o, y);
                case TERRACED -> terraces(out, plan, style, room, o, y);
                case BATTLEMENT -> battlements(out, plan, style, o, y);
            }
        }
    }

    private static boolean dropOff(StationPlan plan, int x, int y, int z)
    {
        return !covered(plan, x, y, z);
    }

    private static boolean free(StationPlan plan, int x, int y, int z)
    {
        return !covered(plan, x, y + 1, z);
    }

    private static void parapet(Map<BlockPos, BlockState> out, StationPlan plan, StationStyle style, Box o, int y)
    {
        var rim = style.skirting.defaultBlockState();
        var vent = Blocks.COPPER_GRATE.waxed().unaffected().defaultBlockState();
        for (int x = o.x0(); x <= o.x1(); x++)
            for (int z = o.z0(); z <= o.z1(); z++)
            {
                if (!free(plan, x, y, z))
                    continue;
                boolean edge = x == o.x0() || x == o.x1() || z == o.z0() || z == o.z1();
                if (edge)
                {
                    boolean exposed = false;
                    for (var d : StationBuilder.SIDES)
                        exposed |= dropOff(plan, x + d.getStepX(), y, z + d.getStepZ());
                    if (exposed)
                        out.put(new BlockPos(x, y + 1, z), rim);
                }
                else if (o.x1() - o.x0() >= 9 && o.z1() - o.z0() >= 9 && (x - o.x0()) % 6 == 3 && (z - o.z0()) % 6 == 3)
                    out.put(new BlockPos(x, y + 1, z), vent);
            }
    }

    private static void terraces(Map<BlockPos, BlockState> out, StationPlan plan, StationStyle style, Room room, Box o, int y)
    {
        int steps = Math.min(3, (Math.min(o.x1() - o.x0(), o.z1() - o.z0()) - 2) / 2);
        if (steps < 1)
            return;
        boolean skylight = room.use() != Use.ENGINEERING && room.use() != Use.DOCK && steps >= 2;
        for (int x = o.x0(); x <= o.x1(); x++)
            for (int z = o.z0(); z <= o.z1(); z++)
            {
                int depth = Math.min(Math.min(x - o.x0(), o.x1() - x), Math.min(z - o.z0(), o.z1() - z));
                int height = Math.min(steps, depth);
                boolean plateau = depth >= steps && skylight;
                if (height < 1 || !free(plan, x, y, z))
                    continue;
                boolean clear = true;
                for (int h = 1; h <= height; h++)
                    clear &= !covered(plan, x, y + h, z);
                if (!clear)
                    continue;
                for (int h = 1; h <= height; h++)
                    out.put(new BlockPos(x, y + h, z), plateau ? style.glass.defaultBlockState() : style.hull(x, y + h, z));
                // The ceiling under the skylight is glass too, so the light comes through.
                if (plateau)
                    out.put(new BlockPos(x, y, z), style.glass.defaultBlockState());
            }
    }

    private static void battlements(Map<BlockPos, BlockState> out, StationPlan plan, StationStyle style, Box o, int y)
    {
        for (int x = o.x0(); x <= o.x1(); x++)
            for (int z = o.z0(); z <= o.z1(); z++)
            {
                boolean edgeX = x == o.x0() || x == o.x1(), edgeZ = z == o.z0() || z == o.z1();
                if ((!edgeX && !edgeZ) || !free(plan, x, y, z))
                    continue;
                boolean exposed = false;
                for (var d : StationBuilder.SIDES)
                    exposed |= dropOff(plan, x + d.getStepX(), y, z + d.getStepZ());
                if (!exposed)
                    continue;
                if (edgeX && edgeZ)
                {
                    // Corner turrets rise two blocks.
                    out.put(new BlockPos(x, y + 1, z), style.frame.defaultBlockState());
                    out.put(new BlockPos(x, y + 2, z), style.frame.defaultBlockState());
                }
                else if ((x + z) % 2 == 0)
                    out.put(new BlockPos(x, y + 1, z), style.hull(x, y + 1, z));
            }
    }

    // ---- Lighting ----

    private static boolean transparent(BlockState state)
    {
        return state == null || state.isAir() || plate(state) || state.getBlock() instanceof CarpetBlock || state.is(Blocks.GLASS) || state.getBlock() instanceof StairBlock || state.is(Blocks.WALL_TORCH) || state.is(Blocks.TORCH) || state.is(Blocks.IRON_BARS) || state.is(Blocks.GLASS_PANE);
    }

    /** True for a cell a player can stand in: open above, with a solid block under it, or a step itself. */
    private static boolean standing(Map<BlockPos, BlockState> out, BlockPos at)
    {
        var here = out.get(at);
        var above = out.get(at.above());
        var below = out.get(at.below());
        boolean headroom = transparent(above) && !(above != null && above.getBlock() instanceof StairBlock);
        if (here != null && here.getBlock() instanceof StairBlock)
            return headroom;
        boolean open = here == null || here.isAir() || plate(here) || here.getBlock() instanceof CarpetBlock || here.is(Blocks.WALL_TORCH) || here.is(Blocks.TORCH);
        return open && headroom && below != null && !below.isAir() && !transparent(below);
    }

    /**
     * Torch lighting in rounds: a regular mirrored row of wall torches first, then fixing rounds that
     * add a torch wherever a standing cell is still too dark, preferring wall sites to floor torches.
     */
    private static void light(Map<BlockPos, BlockState> out, StationPlan plan, Set<BlockPos> occupied)
    {
        var doorColumns = new HashSet<BlockPos>();
        for (var door : plan.doors())
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    doorColumns.add(new BlockPos(door.at().getX() + dx, 0, door.at().getZ() + dz));
        var standing = new ArrayList<BlockPos>();
        var baseline = new ArrayList<Torch>();
        var candidates = new ArrayList<Torch>();
        for (var room : plan.rooms())
        {
            var i = room.interior();
            for (int level = 0; level < room.levels(); level++)
            {
                int floorY = room.y() + level * StationPlan.PITCH + 2;
                for (int x = i.x0(); x <= i.x1(); x++)
                    for (int z = i.z0(); z <= i.z1(); z++)
                    {
                        var cell = new BlockPos(x, floorY, z);
                        if (standing(out, cell))
                            standing.add(cell);
                        for (var side : StationBuilder.SIDES)
                        {
                            // A wall site: this interior cell touches the wall on {@code side}.
                            boolean touches = side == Direction.WEST ? x == i.x0() : side == Direction.EAST ? x == i.x1() : side == Direction.NORTH ? z == i.z0() : z == i.z1();
                            if (!touches || doorColumns.contains(new BlockPos(x, 0, z)))
                                continue;
                            for (int h : new int[]{3, 5, 4})
                            {
                                var at = cell.above(h - 1);
                                var support = out.get(at.relative(side));
                                var inside = out.get(at);
                                boolean solid = support != null && !transparent(support);
                                if (solid && (inside == null || inside.isAir()) && !occupied.contains(at))
                                {
                                    var torch = new Torch(at, side);
                                    candidates.add(torch);
                                    // The regular row: centred on each wall and repeating every eight cells.
                                    int along = side.getAxis() == Direction.Axis.Z ? x - i.x0() : z - i.z0();
                                    int length = side.getAxis() == Direction.Axis.Z ? i.x1() - i.x0() : i.z1() - i.z0();
                                    if (h == 3 && Math.abs(along - length / 2) % 8 == 0 && along >= 1 && along <= length - 1)
                                        baseline.add(torch);
                                    break;
                                }
                            }
                        }
                    }
            }
        }
        // Standing torches on the floor are the last resort for the middle of large rooms.
        var all = new ArrayList<>(candidates);
        for (var cell : standing)
            if ((out.get(cell) == null || out.get(cell).isAir()) && !occupied.contains(cell))
                all.add(new Torch(cell, null));
        var result = StationLighting.light(at -> transparent(out.get(at)), standing, List.of(), baseline, all, ROUNDS);
        for (var torch : result.torches())
            out.put(torch.at(), torch.wall() == null ? Blocks.TORCH.defaultBlockState() : Blocks.WALL_TORCH.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, torch.wall().getOpposite()));
    }

    // ---- Utilities ----

    /** Interior floor cells along the walls, nearest a corner first, that no doorway or port uses. */
    private static ArrayList<BlockPos> corners(Room room, Set<BlockPos> occupied)
    {
        var i = room.interior();
        int y = room.standY();
        var cells = new ArrayList<BlockPos>();
        for (int x = i.x0(); x <= i.x1(); x++)
            for (int z = i.z0(); z <= i.z1(); z++)
                if (x == i.x0() || x == i.x1() || z == i.z0() || z == i.z1())
                    cells.add(new BlockPos(x, y, z));
        cells.removeIf(occupied::contains);
        cells.sort(Comparator.comparingInt((BlockPos at) -> Math.min(at.getX() - i.x0(), i.x1() - at.getX()) + Math.min(at.getZ() - i.z0(), i.z1() - at.getZ())).thenComparingLong(BlockPos::asLong));
        return cells;
    }

    /** The direction from a wall-side cell back into the room's centre. */
    private static Direction toward(Room room, BlockPos at)
    {
        var i = room.interior();
        double dx = (i.x0() + i.x1()) / 2.0 - at.getX(), dz = (i.z0() + i.z1()) / 2.0 - at.getZ();
        return Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static void machine(Map<BlockPos, BlockState> out, BlockPos at, Block block, Direction facing)
    {
        out.put(at, block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing));
    }

    private StationStructure()
    {
    }
}
