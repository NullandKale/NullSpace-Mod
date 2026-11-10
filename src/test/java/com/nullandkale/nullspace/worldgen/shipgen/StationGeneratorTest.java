package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The generated station tiles keep the ship rules: reachable, open, bright, one task, and a shell that mirrors. */
class StationGeneratorTest
{
    private static final Set<Direction> SIDES = EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    private static String name(StationGenerator.Kit kit, int windows)
    {
        return kit + " (" + (windows == 1 ? "few" : "many") + " windows)";
    }

    private static Walk.Cells view(ShipGenerator.Ship ship)
    {
        return new Walk.Cells()
        {
            @Override
            public Kind kind(BlockPos at)
            {
                return ship.kind(at);
            }

            @Override
            public boolean interior(BlockPos at)
            {
                return ship.interior().contains(at);
            }
        };
    }

    @Test
    void everyTileIsASolidSixteenCubeOfTwoStories()
    {
        for (int windows : new int[]{1, 2})
            for (var kit : StationGenerator.Kit.values())
            {
                var ship = StationGenerator.tile(kit, SIDES, windows, Spec.Maker.HALCYON);
                var name = name(kit, windows);
                assertEquals(StationGenerator.TILE, ship.width(), name);
                assertEquals(StationGenerator.TILE, ship.depth(), name);
                assertEquals(StationGenerator.HEIGHT, ship.height(), name);
                assertEquals(2, ship.decks(), name);
                // No basement void: the layer under the lower floor is solid.
                for (int x = 0; x < StationGenerator.TILE; x++)
                    for (int z = 0; z < StationGenerator.TILE; z++)
                        assertTrue(ship.kind(new BlockPos(x, 0, z)) != null && ship.kind(new BlockPos(x, 0, z)).solid(), name + " has a hole in its base at " + x + "," + z);
            }
    }

    @Test
    void everyFixtureCanBeReachedOnBothStoriesWithoutNarrowPassages()
    {
        var problems = new TreeSet<String>();
        for (int windows : new int[]{1, 2})
            for (var kit : StationGenerator.Kit.values())
            {
                var ship = StationGenerator.tile(kit, SIDES, windows, Spec.Maker.HALCYON);
                var name = name(kit, windows);
                var cells = view(ship);
                var walk = Walk.reachable(cells, new BlockPos(4, StationGenerator.BASE + 1, StationGenerator.TILE - 2));
                int upperRow = StationGenerator.BASE + StationGenerator.DECK + 1;
                if (walk.stream().noneMatch(p -> p.getY() == upperRow))
                    problems.add(name + ": the stairs do not reach the upper story");
                // Every doorway slot has its approach clear on both stories.
                for (int story = 0; story < 2; story++)
                    for (var side : SIDES)
                        for (int slot = 0; slot < 2; slot++)
                        {
                            var wall = StationGenerator.doorway(side, slot, StationGenerator.DOOR_ROWS[story]);
                            var inside = wall.relative(side.getOpposite());
                            if (!walk.contains(inside))
                                problems.add(name + ": the " + side + " doorway " + slot + " on story " + story + " cannot be reached from the stairs");
                            var kind = ship.kind(wall);
                            if (kind == null || !kind.solid() || kind == Kind.GLASS)
                                problems.add(name + ": the " + side + " doorway slot " + slot + " on story " + story + " is " + kind + ", not wall");
                        }
                for (var e : ship.voxels().entrySet())
                {
                    var k = e.getValue().kind();
                    if (k.fixture() && k != Kind.FARMLAND && k != Kind.CAMERA && k != Kind.BED && k != Kind.BED_FOOT && !Walk.usable(walk, e.getKey()))
                        problems.add(name + ": unreachable " + k + " at " + e.getKey());
                }
                for (var p : walk)
                {
                    // The stair rows and a chair are not corridors.
                    if (p.getZ() == 1 || p.getZ() == StationGenerator.TILE - 2 || ship.kind(p) == Kind.SEAT || ship.kind(p.below()) == Kind.STAIR)
                        continue;
                    var open = new ArrayList<Direction>();
                    for (var d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST})
                        if (walk.contains(p.relative(d)))
                            open.add(d);
                    if (open.size() == 2 && open.get(0).getOpposite() == open.get(1))
                        problems.add(name + ": a passage one block wide at " + p);
                }
            }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyHallIsBrightCropsAreLitAndWaterIsSealed()
    {
        var problems = new TreeSet<String>();
        for (int windows : new int[]{1, 2})
            for (var kit : StationGenerator.Kit.values())
            {
                var ship = StationGenerator.tile(kit, SIDES, windows, Spec.Maker.HALCYON);
                var name = name(kit, windows);
                var lit = RoomKitTest.light(ship);
                for (var at : ship.interior())
                {
                    var k = ship.kind(at);
                    if (k == Kind.CROP || k == Kind.CROP2 || k == Kind.CROP3)
                    {
                        if (lit.getOrDefault(at, 0) < 9)
                            problems.add(name + " has a crop at " + at + " with light " + lit.getOrDefault(at, 0));
                    }
                    else if ((k == null || !k.solid()) && ship.kind(at.below()) != null && ship.kind(at.below()).solid() && lit.getOrDefault(at, 0) < 8)
                        problems.add(name + " is dark at " + at + " (" + lit.getOrDefault(at, 0) + ")");
                }
                for (var e : ship.voxels().entrySet())
                    if (e.getValue().kind() == Kind.WATER)
                        for (var d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN})
                        {
                            var k = ship.kind(e.getKey().relative(d));
                            if (k == null || !k.solid() && k != Kind.WATER)
                                problems.add(name + " has water that could spill " + d + " at " + e.getKey());
                        }
            }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theShellMirrorsAndEveryHallHasVentsAndTheMachinesAreOneEach()
    {
        var problems = new TreeSet<String>();
        var shell = EnumSet.of(Kind.WALL, Kind.GLASS, Kind.VENT, Kind.LIGHT, Kind.HULL);
        for (int windows : new int[]{1, 2})
            for (var kit : StationGenerator.Kit.values())
            {
                var ship = StationGenerator.tile(kit, SIDES, windows, Spec.Maker.HALCYON);
                var name = name(kit, windows);
                for (var e : ship.voxels().entrySet())
                {
                    var at = e.getKey();
                    var other = ship.voxels().get(new BlockPos(ship.width() - 1 - at.getX(), at.getY(), at.getZ()));
                    // The slab layer is cut by the one-sided stair shafts, and the solid under each step is inside the halls, not shell.
                    if (at.getY() != StationGenerator.BASE + StationGenerator.DECK && !ship.interior().contains(at) && shell.contains(e.getValue().kind()) && (other == null || other.kind() != e.getValue().kind()))
                        problems.add(name + " does not mirror at " + at + " " + e.getValue().kind());
                }
                for (int story = 0; story < 2; story++)
                {
                    int lo = StationGenerator.BASE + 1 + story * StationGenerator.DECK, hi = lo + StationGenerator.STORY;
                    var count = new EnumMap<Kind, Integer>(Kind.class);
                    for (var e : ship.voxels().entrySet())
                        if (e.getKey().getY() >= lo - 1 && e.getKey().getY() <= hi)
                            count.merge(e.getValue().kind(), 1, Integer::sum);
                    if (count.getOrDefault(Kind.VENT, 0) < 2)
                        problems.add(name + " story " + story + " has no air vents");
                    for (var one : new Kind[]{Kind.COMPUTER, Kind.SCANNER, Kind.TERMINAL})
                        if (count.getOrDefault(one, 0) > 1)
                            problems.add(name + " story " + story + " has " + count.get(one) + " of " + one);
                }
            }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void manyWindowsHaveMoreGlassThanFew()
    {
        for (var kit : StationGenerator.Kit.values())
        {
            int few = glass(StationGenerator.tile(kit, SIDES, 1, Spec.Maker.HALCYON)), many = glass(StationGenerator.tile(kit, SIDES, 2, Spec.Maker.HALCYON));
            assertTrue(many > few, kit + " has more windows when asked: " + few + " vs " + many);
            assertTrue(few > 0, kit + " has windows");
        }
    }

    private static int glass(ShipGenerator.Ship ship)
    {
        return (int) ship.voxels().values().stream().filter(v -> v.kind() == Kind.GLASS).count();
    }
}
