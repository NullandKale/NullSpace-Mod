package com.nullandkale.nullspace.worldgen.shipgen;

import com.nullandkale.nullspace.worldgen.shipgen.ShipGenerator.Ship;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.core.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The rules a generated ship must follow, checked on every maker, shape, tier and role. */
class ShipGeneratorTest
{
    static List<Spec> specs()
    {
        var out = new ArrayList<Spec>();
        for (var shape : Spec.Shape.values())
            for (var tier : Spec.Tier.values())
                for (var role : Spec.Role.values())
                    for (long seed = 1; seed <= 2; seed++)
                        out.add(new Spec(Spec.Maker.values()[(int) ((seed + tier.ordinal() + role.ordinal()) % 3)], shape, tier, role, seed));
        return out;
    }

    private static Walk.Cells view(Ship ship)
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

    private static Set<BlockPos> reachable(Ship ship)
    {
        var start = ship.dock().relative(Direction.SOUTH);
        assertTrue(Walk.standable(view(ship), start), "A person can stand just inside the docking door: " + start);
        return Walk.reachable(view(ship), start);
    }

    static String label(Spec spec)
    {
        return spec.maker() + " " + spec.shape() + " " + spec.tier() + " " + spec.role() + " #" + spec.seed();
    }

    @Test
    void shipsAreDeterministicAndFitTheBuildVolume()
    {
        for (var spec : specs())
        {
            var a = ShipGenerator.build(spec);
            var b = ShipGenerator.build(spec);
            assertEquals(a.voxels(), b.voxels(), label(spec));
            assertTrue(a.width() <= 32 && a.height() <= 32 && a.depth() <= 32, label(spec) + " is " + a.width() + " x " + a.height() + " x " + a.depth());
        }
    }

    @Test
    void shipsMirrorAcrossTheirCentreLine()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            assertEquals(1, ship.width() % 2, label(spec) + " has an odd width, so it has a centre line");
            int bad = 0;
            String first = null;
            for (var e : ship.voxels().entrySet())
            {
                var at = e.getKey();
                var mirror = new BlockPos(ship.width() - 1 - at.getX(), at.getY(), at.getZ());
                var other = ship.voxels().get(mirror);
                // The room's design mirrors (shell, windows, doors, stairs, lights) and so do the ship's engines, generators and batteries; furniture does not have to.
                if (!RoomKitTest.mirrored(e.getValue().kind()))
                    continue;
                if (other == null || other.kind() != e.getValue().kind() || other.facing() != e.getValue().mirrored().facing())
                {
                    bad++;
                    if (first == null)
                        first = at + " " + e.getValue() + " vs " + other;
                }
            }
            assertEquals(0, bad, label(spec) + " has " + bad + " cells that do not mirror, first " + first);
        }
    }

    @Test
    void shipsHaveTheMachinesAFlightNeeds()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            var count = new EnumMap<Kind, Integer>(Kind.class);
            ship.voxels().values().forEach(v -> count.merge(v.kind(), 1, Integer::sum));
            assertEquals(1, count.getOrDefault(Kind.COMPUTER, 0), label(spec) + " has one helm");
            assertEquals(1, count.getOrDefault(Kind.DOCK_BASE, 0), label(spec) + " has one docking base");
            // The docking door sits on its base; every other door is a room door.
            long docking = ship.voxels().entrySet().stream().filter(e -> e.getValue().kind() == Kind.DOOR_LOWER && ship.kind(e.getKey().below()) == Kind.DOCK_BASE).count();
            assertEquals(1, docking, label(spec) + " has one docking door");
            assertTrue(count.getOrDefault(Kind.DOOR_LOWER, 0) > 1, label(spec) + " has a door on its rooms");
            assertTrue(count.getOrDefault(Kind.VENT, 0) >= 2, label(spec) + " has air vents");
            assertEquals(1, count.getOrDefault(Kind.CAMERA, 0), label(spec) + " has the forward camera");
            for (var k : List.of(Kind.GENERATOR, Kind.BATTERY, Kind.ENGINE, Kind.AIR_SUPPLY, Kind.TERMINAL, Kind.SCANNER, Kind.MONITOR))
                assertTrue(count.getOrDefault(k, 0) >= 1, label(spec) + " has " + k);
            assertEquals(0, count.getOrDefault(Kind.ENGINE, 0) % 2, label(spec) + " has engines in pairs");
        }
    }

    @Test
    void roomsHaveHighCeilings()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            for (var at : ship.interior())
            {
                var below = ship.kind(at.below());
                if (below == null || ship.interior().contains(at.below()))
                    continue;
                int run = 0;
                while (ship.interior().contains(at.above(run)))
                    run++;
                assertTrue(run >= 3, label(spec) + " has a ceiling only " + run + " high at " + at);
            }
        }
    }

    @Test
    void thereIsNoHiddenAir()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            var lo = new BlockPos(-1, -1, -1);
            var hi = new BlockPos(ship.width(), ship.height(), ship.depth());
            var outside = new HashSet<BlockPos>();
            var queue = new ArrayDeque<BlockPos>();
            queue.add(lo);
            outside.add(lo);
            while (!queue.isEmpty())
            {
                var at = queue.poll();
                for (var d : Direction.values())
                {
                    var n = at.relative(d);
                    if (n.getX() < lo.getX() || n.getY() < lo.getY() || n.getZ() < lo.getZ() || n.getX() > hi.getX() || n.getY() > hi.getY() || n.getZ() > hi.getZ() || ship.voxels().containsKey(n) || !outside.add(n))
                        continue;
                    queue.add(n);
                }
            }
            for (int x = 0; x < ship.width(); x++)
                for (int y = 0; y < ship.height(); y++)
                    for (int z = 0; z < ship.depth(); z++)
                    {
                        var at = new BlockPos(x, y, z);
                        assertTrue(ship.voxels().containsKey(at) || ship.interior().contains(at) || outside.contains(at), label(spec) + " has a sealed pocket of air at " + at);
                    }
        }
    }

    @Test
    void everyRoomCanBeWalkedToAndEveryMachineIsReachable()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            var walk = reachable(ship);
            for (var room : ship.rooms())
                assertTrue(walk.contains(room.walk()), label(spec) + " cannot walk to " + room.name() + " at " + room.walk());
            for (var e : ship.voxels().entrySet())
            {
                if (!e.getValue().kind().fixture() || e.getValue().kind() == Kind.CAMERA)
                    continue;
                var bedPart = e.getValue().kind() == Kind.BED || e.getValue().kind() == Kind.BED_FOOT;
                assertTrue(bedPart || Walk.usable(walk, e.getKey()), label(spec) + " has an unreachable " + e.getValue().kind() + " at " + e.getKey());
            }
            // Walking spaces stay open the whole way, and never narrow to a single block or end in a blind nook.
            int narrow = 0;
            var first = new ArrayList<BlockPos>();
            for (var p : walk)
            {
                var open = new ArrayList<Direction>();
                for (var d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST})
                    if (walk.contains(p.relative(d)))
                        open.add(d);
                boolean corridor = open.size() == 2 && open.get(0).getOpposite() == open.get(1);
                if (corridor)
                {
                    // Stairs and the step below the docking door are the only places a path may be a single block.
                    boolean stairs = ship.kind(p) == Kind.DOOR_LOWER || ship.kind(p.below()) == Kind.STAIR || ship.kind(p.relative(Direction.NORTH).below()) == Kind.STAIR || ship.kind(p.relative(Direction.SOUTH).below()) == Kind.STAIR;
                    if (!stairs)
                    {
                        narrow++;
                        if (first.size() < 4)
                            first.add(p);
                    }
                }
            }
            assertEquals(0, narrow, label(spec) + " has " + narrow + " single-block-wide or blind spots, e.g. " + first);
        }
    }

    @Test
    void shipInteriorsAreBright()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            var lit = RoomKitTest.light(ship);
            var dark = new ArrayList<String>();
            for (var at : ship.interior())
            {
                var k = ship.kind(at);
                var below = ship.kind(at.below());
                if ((k == null || !k.solid()) && below != null && (below.solid() || below == Kind.STAIR) && lit.getOrDefault(at, 0) < 7)
                    dark.add(at + "=" + lit.getOrDefault(at, 0));
            }
            assertTrue(dark.isEmpty(), label(spec) + " has " + dark.size() + " dim spots, e.g. " + dark.stream().limit(4).toList());
        }
    }

    @Test
    void everyShipHasAUseForEveryRoom()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            var uses = new HashSet<RoomUse>();
            ship.rooms().forEach(r -> uses.add(r.use()));
            assertTrue(uses.contains(RoomUse.BRIDGE) && uses.contains(RoomUse.ENGINEERING), label(spec));
            assertTrue(ship.rooms().size() >= (spec.tier() == Spec.Tier.SMALL ? 4 : 5), label(spec) + " has " + ship.rooms().size() + " rooms");
        }
    }

    @Test
    void shipsHaveWindowsAndTierSetsThePieceSize()
    {
        for (var spec : specs())
        {
            var ship = ShipGenerator.build(spec);
            long glass = ship.voxels().values().stream().filter(v -> v.kind() == Kind.GLASS).count();
            assertTrue(glass >= 6, label(spec) + " has only " + glass + " window blocks");
            int height = ship.voxels().keySet().stream().mapToInt(BlockPos::getY).max().orElse(0) + 1;
            if (spec.shape() == Spec.Shape.SPINE)
                assertEquals(spec.tier().decks, ship.decks(), label(spec) + " has the deck count of its tier");
            assertTrue(height >= spec.tier().height + 2, label(spec) + " is at least one room tall plus its hull");
        }
    }

    @Test
    void largerTiersUseLargerPiecesAndGrowTheShip()
    {
        for (var shape : Spec.Shape.values())
            for (var role : Spec.Role.values())
            {
                var small = ShipGenerator.build(new Spec(Spec.Maker.RIVET, shape, Spec.Tier.SMALL, role, 1));
                var large = ShipGenerator.build(new Spec(Spec.Maker.RIVET, shape, Spec.Tier.LARGE, role, 1));
                assertTrue(large.interior().size() > small.interior().size(), shape + " " + role + ": a large ship has more room inside than a small one");
            }
    }

    /** Writes every ship's floor plans to the file named by -Dshipgen.dump, for looking at. */
    @Test
    void dumpForReview() throws IOException
    {
        var path = System.getenv("SHIPGEN_DUMP");
        if (path == null)
            return;
        try (var out = new PrintWriter(new File(path), StandardCharsets.UTF_8))
        {
            for (var spec : specs())
                dump(out, ShipGenerator.build(spec));
        }
    }

    static void dump(PrintWriter out, Ship ship)
    {
        out.println("=== " + label(ship.spec()) + "  " + ship.width() + " x " + ship.height() + " x " + ship.depth() + "  rooms " + ship.rooms().stream().map(r -> r.use().name()).toList());
        for (int y = 0; y < ship.height(); y++)
        {
            out.println("-- y=" + y);
            for (int z = 0; z < ship.depth(); z++)
            {
                var row = new StringBuilder();
                for (int x = 0; x < ship.width(); x++)
                {
                    var at = new BlockPos(x, y, z);
                    var v = ship.voxels().get(at);
                    row.append(v == null ? (ship.interior().contains(at) ? '.' : ' ') : symbol(v.kind()));
                }
                out.println(row);
            }
        }
    }

    static char symbolOf(Kind k)
    {
        return symbol(k);
    }

    private static char symbol(Kind k)
    {
        return switch (k)
        {
            case HULL -> '#';
            case FRAME -> '%';
            case WALL -> 'W';
            case FLOOR -> '_';
            case CEILING -> '^';
            case TRIM -> 'T';
            case ACCENT -> 'a';
            case GLASS -> 'g';
            case LIGHT -> 'L';
            case DOOR_LOWER, DOOR_UPPER -> 'D';
            case DOCK_BASE -> 'B';
            case STAIR -> 'S';
            case BARREL -> 'b';
            case CHEST, CHEST_LEFT, CHEST_RIGHT -> 'c';
            case MOSS -> 'M';
            case WATER -> '~';
            case VENT -> 'v';
            case STATION_CORE -> 'K';
            case CRAFTING_TABLE -> 'C';
            case FURNACE -> 'f';
            case BLAST_FURNACE -> 'F';
            case SMOKER -> 'k';
            case ANVIL -> 'A';
            case ENCHANTING_TABLE -> 'E';
            case BOOKSHELF -> 'H';
            case BED -> 'z';
            case BED_FOOT -> 'y';
            case LECTERN -> 'l';
            case BREWING_STAND -> 'r';
            case CAULDRON -> 'u';
            case SMITHING_TABLE -> 'm';
            case STONECUTTER -> 'n';
            case LOOM -> 'o';
            case CARTOGRAPHY_TABLE -> 'p';
            case GRINDSTONE -> 'q';
            case FLETCHING_TABLE -> 'j';
            case COMPOSTER -> 'x';
            case FARMLAND -> 'v';
            case CROP, CROP2, CROP3 -> 'w';
            case SEAT -> 's';
            case TABLE -> 't';
            case CARPET -> '=';
            case PLANT -> '*';
            case COMPUTER -> 'X';
            case TERMINAL -> 'Y';
            case SCANNER -> 'Z';
            case MONITOR -> 'M';
            case CAMERA -> 'K';
            case GENERATOR -> 'G';
            case BATTERY -> 'Q';
            case ENGINE -> 'N';
            case AIR_SUPPLY -> 'O';
        };
    }
}
