package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.station.StationSpace;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Checks the rendered structure of generated stations: sealed rooms, walkable routes through stairs and doors, ports and utilities. */
public final class StationStructureGameTest
{
    static boolean open(Map<BlockPos, BlockState> blocks, BlockPos at)
    {
        var state = blocks.get(at);
        return state == null || state.isAir() || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof BasePressurePlateBlock || state.getBlock() instanceof CarpetBlock || state.is(Blocks.WALL_TORCH) || state.is(Blocks.TORCH);
    }

    static boolean flat(Map<BlockPos, BlockState> blocks, BlockPos at)
    {
        var below = blocks.get(at.below());
        return open(blocks, at) && open(blocks, at.above()) && below != null && !below.isAir() && !(below.getBlock() instanceof DoorBlock);
    }

    static boolean step(Map<BlockPos, BlockState> blocks, BlockPos at)
    {
        var state = blocks.get(at);
        return state != null && state.getBlock() instanceof StairBlock && open(blocks, at.above()) && open(blocks, at.above(2));
    }

    /** Wall cells on a room's pier lines (clear of doorways) that are not log: every pier runs floor to ceiling. */
    private static List<BlockPos> pierGaps(StationPlan plan, Map<BlockPos, BlockState> blocks, Maker maker)
    {
        var style = StationStyle.of(maker, 0);
        var clear = new HashSet<BlockPos>();
        for (var door : plan.doors())
            for (int side = -2; side <= 2; side++)
                clear.add(StationStructure.pierColumn(door.at().relative(door.through().getClockWise(), side)));
        for (var berth : plan.berths())
            for (int side = -2; side <= 2; side++)
                clear.add(StationStructure.pierColumn(berth.port().relative(berth.facing().getClockWise(), side)));
        var gaps = new ArrayList<BlockPos>();
        for (var room : plan.rooms())
        {
            var o = room.outer();
            for (int y = o.y0(); y <= o.y1(); y++)
            {
                for (int a : StationStructure.pierLine(o.x1() - o.x0(), style.pierSpacing))
                    for (int z : new int[]{o.z0(), o.z1()})
                        check(plan, blocks, new BlockPos(o.x0() + a, y, z), clear, gaps);
                for (int a : StationStructure.pierLine(o.z1() - o.z0(), style.pierSpacing))
                    for (int x : new int[]{o.x0(), o.x1()})
                        check(plan, blocks, new BlockPos(x, y, o.z0() + a), clear, gaps);
            }
        }
        return gaps;
    }

    private static void check(StationPlan plan, Map<BlockPos, BlockState> blocks, BlockPos at, Set<BlockPos> clear, List<BlockPos> gaps)
    {
        var state = blocks.get(at);
        if (!clear.contains(StationStructure.pierColumn(at)) && (state == null || !state.getBlock().getDescriptionId().contains("_log")) && !floorOfAnotherRoom(plan, at))
            gaps.add(at);
    }

    /** True where the cell is the walking surface of some room (an upper deck's floor sits on the pier below). */
    private static boolean floorOfAnotherRoom(StationPlan plan, BlockPos at)
    {
        for (var r : plan.rooms())
        {
            var i = r.interior();
            for (int level = 0; level < r.levels(); level++)
                if ((at.getY() == r.y() + level * StationPlan.PITCH || at.getY() == r.y() + level * StationPlan.PITCH + 1) && at.getX() >= i.x0() && at.getX() <= i.x1() && at.getZ() >= i.z0() && at.getZ() <= i.z1())
                    return true;
        }
        return false;
    }

    /** The share of exterior wall cells in the glazing rows that are glass. */
    private static double glazing(StationPlan plan, Map<BlockPos, BlockState> blocks)
    {
        int total = 0, glass = 0;
        for (var room : plan.rooms())
        {
            if (room.use() == Use.DOCK)
                continue;
            var o = room.outer();
            for (int level = 0; level < room.levels(); level++)
                for (int row = 2; row <= 4; row++)
                    for (var side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST))
                    {
                        boolean alongX = side.getAxis() == Direction.Axis.Z;
                        int lo = alongX ? o.x0() : o.z0(), hi = alongX ? o.x1() : o.z1();
                        for (int a = lo + 2; a <= hi - 2; a++)
                        {
                            var at = new BlockPos(alongX ? a : side == Direction.WEST ? o.x0() : o.x1(), room.y() + level * StationPlan.PITCH + 1 + row, alongX ? (side == Direction.NORTH ? o.z0() : o.z1()) : a);
                            var beyond = at.relative(side);
                            boolean exterior = plan.rooms().stream().noneMatch(r -> beyond.getX() >= r.outer().x0() && beyond.getX() <= r.outer().x1() && beyond.getY() >= r.outer().y0() && beyond.getY() <= r.outer().y1() && beyond.getZ() >= r.outer().z0() && beyond.getZ() <= r.outer().z1());
                            var state = blocks.get(at);
                            // Doors and the log piers between the bays are not wall left to glaze.
                            if (!exterior || state == null || state.getBlock() instanceof DoorBlock || state.getBlock().getDescriptionId().contains("_log"))
                                continue;
                            total++;
                            if (state.is(Blocks.GLASS))
                                glass++;
                        }
                    }
        }
        return total == 0 ? 1 : glass / (double) total;
    }

    static boolean passesLight(BlockState state)
    {
        return state == null || state.isAir() || state.getBlock() instanceof BasePressurePlateBlock || state.getBlock() instanceof CarpetBlock || state.is(Blocks.GLASS) || state.getBlock() instanceof StairBlock || state.is(Blocks.WALL_TORCH) || state.is(Blocks.TORCH) || state.is(Blocks.IRON_BARS) || state.is(Blocks.GLASS_PANE);
    }

    /** Standing cells below the minimum light, from an independent flood of the torches' light through the block map. */
    static List<BlockPos> darkCells(StationPlan plan, Map<BlockPos, BlockState> blocks)
    {
        var level = new HashMap<BlockPos, Integer>();
        var queue = new ArrayDeque<BlockPos>();
        for (var e : blocks.entrySet())
            if (e.getValue().is(Blocks.WALL_TORCH) || e.getValue().is(Blocks.TORCH))
            {
                level.put(e.getKey(), 14);
                queue.add(e.getKey());
            }
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            int next = level.get(at) - 1;
            if (next < 1)
                continue;
            for (var d : Direction.values())
            {
                var to = at.relative(d);
                if (passesLight(blocks.get(to)) && level.getOrDefault(to, 0) < next)
                {
                    level.put(to, next);
                    queue.add(to);
                }
            }
        }
        var dark = new ArrayList<BlockPos>();
        for (var room : plan.rooms())
        {
            var i = room.interior();
            for (int l = 0; l < room.levels(); l++)
                for (int x = i.x0(); x <= i.x1(); x++)
                    for (int z = i.z0(); z <= i.z1(); z++)
                    {
                        var at = new BlockPos(x, room.y() + l * StationPlan.PITCH + 2, z);
                        // A cell above a step or a rail is not a floor: players stand in the step's own cell.
                        var under = blocks.get(at.below());
                        boolean onStep = under != null && (under.getBlock() instanceof StairBlock || under.is(Blocks.IRON_BARS) || under.is(Blocks.GLASS_PANE));
                        // A gate inside a room (a cell door) is not a place to stand and light.
                        boolean gate = blocks.get(at) != null && blocks.get(at).getBlock() instanceof DoorBlock;
                        if (!gate && ((flat(blocks, at) && !onStep) || step(blocks, at)) && level.getOrDefault(at, 0) < StationLighting.MINIMUM)
                            dark.add(at);
                    }
        }
        return dark;
    }

    /** Standing cells reachable from {@code start}: flat floors, stair steps one block at a time, doors as passages. */
    static Set<BlockPos> walk(Map<BlockPos, BlockState> blocks, BlockPos start)
    {
        var seen = new HashSet<BlockPos>(Set.of(start));
        var queue = new ArrayDeque<BlockPos>(List.of(start));
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            boolean onStep = step(blocks, at);
            for (var d : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST))
                for (int dy = -1; dy <= 1; dy++)
                {
                    var to = at.relative(d).above(dy);
                    boolean ok;
                    if (dy == 0)
                        ok = flat(blocks, to) || step(blocks, to);
                    else if (dy == 1)
                        ok = (step(blocks, to) && open(blocks, at.above(2))) || (onStep && flat(blocks, to));
                    else
                        ok = step(blocks, to) && (flat(blocks, at) || onStep);
                    if (ok && seen.add(to))
                        queue.add(to);
                }
        }
        return seen;
    }

    /** Air cells connected to {@code from} through open space, never crossing a door (a closed door seals). */
    static Set<BlockPos> airFrom(Map<BlockPos, BlockState> blocks, BlockPos from, int limit)
    {
        var seen = new HashSet<BlockPos>(Set.of(from));
        var queue = new ArrayDeque<BlockPos>(List.of(from));
        while (!queue.isEmpty() && seen.size() <= limit)
        {
            var at = queue.poll();
            for (var d : Direction.values())
            {
                var to = at.relative(d);
                var state = blocks.get(to);
                if ((state == null || state.isAir() || state.getBlock() instanceof BasePressurePlateBlock || state.is(Blocks.WALL_TORCH) || state.is(Blocks.TORCH)) && seen.add(to))
                    queue.add(to);
            }
        }
        return seen;
    }

    @GameTest
    public void everyGeneratedStationIsSealedWalkableAndHasItsPortsAndUtilities(GameTestHelper ctx)
    {
        var problems = new ArrayList<String>();
        int built = 0;
        for (var maker : Maker.values())
            for (var purpose : StationSpec.Purpose.values())
                for (var size : StationSpec.Size.values())
                    for (long seed = 1; seed <= 2; seed++)
                    {
                        var spec = new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, seed * 6151 + purpose.ordinal());
                        var plan = StationPlanner.generate(spec);
                        var out = StationStructure.build(plan);
                        var blocks = out.blocks();
                        built++;
                        try
                        {
                            StationSpace.extent(out, true);
                        }
                        catch (IllegalArgumentException e)
                        {
                            problems.add(spec.description() + ": " + e.getMessage());
                        }
                        if (!blocks.get(out.core()).is(ModBlocks.STATION_CORE))
                            problems.add(spec.description() + ": no Station Core");
                        if (out.docks().size() != plan.berths().size())
                            problems.add(spec.description() + ": docks and berths differ");
                        for (var dock : out.docks())
                            if (!blocks.get(dock).is(ModBlocks.DOCKING_BASE) || !(blocks.get(dock.above()).getBlock() instanceof DoorBlock))
                                problems.add(spec.description() + ": port at " + dock.toShortString() + " lacks a base or door");
                        long air = blocks.values().stream().filter(s -> s.is(ModBlocks.AIR_SUPPLY)).count();
                        if (air < plan.rooms().size())
                            problems.add(spec.description() + ": only " + air + " air supplies for " + plan.rooms().size() + " rooms");
                        if (plan.rooms(Use.ENGINEERING).isEmpty() || blocks.values().stream().noneMatch(s -> s.is(ModBlocks.GENERATOR)) || blocks.values().stream().noneMatch(s -> s.is(ModBlocks.BATTERY)))
                            problems.add(spec.description() + ": no generator and battery");
                        // Style: lots of glass, patterned floors, roofs, rails and torch lighting.
                        long torches = blocks.values().stream().filter(st -> st.is(Blocks.WALL_TORCH) || st.is(Blocks.TORCH)).count();
                        if (torches == 0 || blocks.values().stream().anyMatch(st -> st.is(Blocks.SEA_LANTERN)))
                            problems.add(spec.description() + ": lighting should be torches only, found " + torches);
                        var gaps = pierGaps(plan, blocks, spec.maker());
                        if (!gaps.isEmpty())
                            problems.add(spec.description() + ": " + gaps.size() + " pier cells are not log, first " + gaps.getFirst().toShortString());
                        var glazing = glazing(plan, blocks);
                        if (glazing < .35)
                            problems.add(spec.description() + ": only " + (int) (glazing * 100) + "% of the glazing rows are glass");
                        var floors = new HashSet<net.minecraft.world.level.block.Block>();
                        for (var room : plan.rooms())
                        {
                            var i = room.interior();
                            for (int x = i.x0(); x <= i.x1(); x++)
                                for (int z = i.z0(); z <= i.z1(); z++)
                                    floors.add(blocks.get(new BlockPos(x, room.floorY(), z)).getBlock());
                        }
                        if (blocks.values().stream().noneMatch(st -> st.getBlock().getDescriptionId().contains("planks")) || blocks.values().stream().noneMatch(st -> st.getBlock().getDescriptionId().contains("_log")))
                            problems.add(spec.description() + ": the palette has no wood");
                        if (floors.size() < 2)
                            problems.add(spec.description() + ": floors have no pattern");
                        if (blocks.keySet().stream().noneMatch(at -> at.getY() >= plan.height()))
                            problems.add(spec.description() + ": no roof shapes above the rooms");
                        if (plan.rooms(Use.ARRIVAL).getFirst().levels() >= 2 && blocks.values().stream().noneMatch(st -> st.is(Blocks.IRON_BARS) || st.is(Blocks.GLASS_PANE)))
                            problems.add(spec.description() + ": the gallery has no rail");
                        var dark = darkCells(plan, blocks);
                        if (!dark.isEmpty())
                        {
                            var first = dark.getFirst();
                            var where = plan.rooms().stream().filter(r -> first.getX() >= r.interior().x0() && first.getX() <= r.interior().x1() && first.getZ() >= r.interior().z0() && first.getZ() <= r.interior().z1() && first.getY() >= r.interior().y0() && first.getY() <= r.interior().y1()).findFirst();
                            problems.add(spec.description() + ": " + dark.size() + " standing cells below light " + StationLighting.MINIMUM + ", first " + first.toShortString() + " in " + where.map(r -> r.id() + " " + r.use() + " " + r.x() + "," + r.z() + " " + r.w() + "x" + r.d()).orElse("no room") + ", here " + blocks.get(first) + ", below " + blocks.get(first.below()) + ", above " + blocks.get(first.above()));
                        }
                        // Every room's air stays inside its own interior while the doors are closed.
                        for (var room : plan.rooms())
                        {
                            var i = room.interior();
                            var center = new BlockPos((i.x0() + i.x1()) / 2, i.y0(), (i.z0() + i.z1()) / 2);
                            if (blocks.get(center) != null && !blocks.get(center).isAir())
                                center = new BlockPos(i.x0() + 1, i.y0() + 4, i.z0() + 1);
                            long volume = (long) (i.x1() - i.x0() + 1) * (i.y1() - i.y0() + 1) * (i.z1() - i.z0() + 1);
                            var air2 = airFrom(blocks, center, (int) volume + 1);
                            boolean leaks = air2.size() > volume;
                            for (var cell : air2)
                                leaks |= cell.getX() < i.x0() || cell.getX() > i.x1() || cell.getY() < i.y0() || cell.getY() > i.y1() || cell.getZ() < i.z0() || cell.getZ() > i.z1();
                            if (leaks)
                                problems.add(spec.description() + ": room " + room.id() + " is not sealed");
                        }
                        // Walk from the arrival floor to every other room and every berth.
                        // The walk starts in the arrival hall, just inside its first doorway.
                        var arrivalId = plan.arrival();
                        BlockPos start = null;
                        for (var door : plan.doors())
                            if (door.a().equals(arrivalId))
                            {
                                start = door.at().relative(door.through().getOpposite());
                                break;
                            }
                            else if (door.b().equals(arrivalId))
                            {
                                start = door.at().relative(door.through());
                                break;
                            }
                        if (start == null || !flat(blocks, start))
                            problems.add(spec.description() + ": the arrival hall's doorway has no floor to stand on at " + start);
                        var reach = walk(blocks, start == null ? BlockPos.ZERO : start);
                        for (var room : plan.rooms())
                        {
                            // A room can be walked to when any standing cell on its floor is reachable (a table may fill the middle).
                            var i = room.interior();
                            boolean found = false;
                            for (int x = i.x0(); x <= i.x1() && !found; x++)
                                for (int z = i.z0(); z <= i.z1() && !found; z++)
                                    found = reach.contains(new BlockPos(x, i.y0(), z));
                            if (!found)
                                problems.add(spec.description() + ": cannot walk to " + room.id());
                        }
                        for (var berth : plan.berths())
                            if (!reach.contains(berth.port().relative(berth.facing().getOpposite())))
                                problems.add(spec.description() + ": cannot walk to the port at " + berth.port().toShortString());
                    }
        var kinds = new TreeMap<String, Integer>();
        for (var p : problems)
            kinds.merge(p.substring(p.indexOf(": ") + 2).replaceAll("[-0-9, ]+$", "").replaceAll("(room|port at) [^ ]+.*", "$1"), 1, Integer::sum);
        ctx.assertTrue(problems.isEmpty(), built + " stations built; " + problems.size() + " problems " + kinds + ", first: " + problems.stream().limit(6).toList());
        ctx.succeed();
    }
}
