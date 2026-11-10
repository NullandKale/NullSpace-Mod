package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Each room kit on its own, at every piece size and window level it fits. */
class RoomKitTest
{
    private static List<Object[]> kits()
    {
        var out = new ArrayList<Object[]>();
        for (var use : RoomUse.values())
            for (var tier : Spec.Tier.values())
                for (int windows : new int[]{1, 2})
                    if (use.destination() && use != RoomUse.HUB && ShipGenerator.room(use, tier, windows) != null)
                        out.add(new Object[]{use, tier, windows});
        return out;
    }

    /** Kinds that must mirror: the shell and the ship's own systems. Furniture may sit on one side only. */
    static boolean mirrored(Kind kind)
    {
        return kind != Kind.TRIM && (!kind.fixture() || kind == Kind.ENGINE || kind == Kind.GENERATOR || kind == Kind.BATTERY || kind == Kind.AIR_SUPPLY);
    }

    /** Things a room has only one of: each machine, and each workbench. */
    private static final Set<Kind> ONE = EnumSet.of(Kind.COMPUTER, Kind.SCANNER, Kind.TERMINAL, Kind.CAMERA, Kind.CRAFTING_TABLE, Kind.SMITHING_TABLE, Kind.STONECUTTER, Kind.LOOM, Kind.GRINDSTONE, Kind.FLETCHING_TABLE, Kind.CARTOGRAPHY_TABLE, Kind.ENCHANTING_TABLE, Kind.LECTERN, Kind.ANVIL);

    private static final Set<Kind> DECOR = EnumSet.of(Kind.PLANT, Kind.CARPET, Kind.BOOKSHELF);

    /** The single task of each room: everything it may hold. */
    private static Set<Kind> task(RoomUse use)
    {
        return switch (use)
        {
            case BRIDGE -> EnumSet.of(Kind.COMPUTER, Kind.SCANNER, Kind.TERMINAL, Kind.MONITOR, Kind.CAMERA);
            case ENTRY -> EnumSet.of(Kind.BARREL, Kind.CARTOGRAPHY_TABLE, Kind.AIR_SUPPLY, Kind.CAMERA);
            case ENGINEERING -> EnumSet.of(Kind.GENERATOR, Kind.BATTERY, Kind.BARREL, Kind.ENGINE, Kind.AIR_SUPPLY);
            case CARGO -> EnumSet.of(Kind.BARREL);
            case STORAGE -> EnumSet.of(Kind.CHEST, Kind.CHEST_LEFT, Kind.CHEST_RIGHT);
            case CREW -> EnumSet.of(Kind.BED, Kind.BED_FOOT, Kind.CHEST);
            case CABIN -> EnumSet.of(Kind.BARREL, Kind.TABLE, Kind.SEAT, Kind.CHEST, Kind.BED, Kind.BED_FOOT, Kind.CHEST_LEFT, Kind.CHEST_RIGHT, Kind.MONITOR, Kind.TERMINAL);
            case CRAFTING -> EnumSet.of(Kind.CHEST, Kind.CHEST_LEFT, Kind.CHEST_RIGHT, Kind.CRAFTING_TABLE, Kind.SMITHING_TABLE, Kind.STONECUTTER, Kind.LOOM, Kind.GRINDSTONE, Kind.FLETCHING_TABLE, Kind.CARTOGRAPHY_TABLE, Kind.ANVIL);
            case SMELTING -> EnumSet.of(Kind.CHEST, Kind.CHEST_LEFT, Kind.CHEST_RIGHT, Kind.FURNACE, Kind.BLAST_FURNACE, Kind.SMOKER, Kind.BARREL);
            case MINING -> EnumSet.of(Kind.CHEST, Kind.CHEST_LEFT, Kind.CHEST_RIGHT, Kind.BLAST_FURNACE, Kind.BARREL, Kind.GRINDSTONE);
            case GALLEY -> EnumSet.of(Kind.CHEST, Kind.CHEST_LEFT, Kind.CHEST_RIGHT, Kind.SMOKER, Kind.CAULDRON, Kind.BARREL, Kind.TABLE, Kind.SEAT);
            case VIEWING -> EnumSet.of(Kind.MONITOR, Kind.SEAT, Kind.TERMINAL);
            case ENCHANTING -> EnumSet.of(Kind.CHEST, Kind.ENCHANTING_TABLE, Kind.BOOKSHELF, Kind.ANVIL, Kind.LECTERN);
            case BREWING -> EnumSet.of(Kind.CHEST, Kind.CHEST_LEFT, Kind.CHEST_RIGHT, Kind.BREWING_STAND, Kind.CAULDRON, Kind.BARREL);
            case HYDROPONICS -> EnumSet.of(Kind.BARREL, Kind.FARMLAND, Kind.CROP, Kind.CROP2, Kind.CROP3, Kind.COMPOSTER);
            case LOUNGE -> EnumSet.of(Kind.BOOKSHELF, Kind.SEAT, Kind.TABLE);
            case LAB -> EnumSet.of(Kind.CARTOGRAPHY_TABLE, Kind.LECTERN, Kind.TERMINAL, Kind.BARREL, Kind.MONITOR);
            default -> EnumSet.noneOf(Kind.class);
        };
    }

    /** Rooms that work with items keep a bit of storage. */
    @Test
    void roomsThatWorkWithItemsHaveStorage()
    {
        var needs = EnumSet.of(RoomUse.CRAFTING, RoomUse.SMELTING, RoomUse.MINING, RoomUse.GALLEY, RoomUse.BREWING, RoomUse.HYDROPONICS, RoomUse.ENCHANTING, RoomUse.LAB, RoomUse.ENGINEERING, RoomUse.CABIN, RoomUse.CREW, RoomUse.ENTRY);
        var storage = EnumSet.of(Kind.BARREL, Kind.CHEST, Kind.CHEST_LEFT, Kind.CHEST_RIGHT);
        var missing = new TreeSet<String>();
        for (var kit : kits())
            if (needs.contains(kit[0]) && ShipGenerator.room((RoomUse) kit[0], (Spec.Tier) kit[1], (int) kit[2]).voxels().values().stream().noneMatch(v -> storage.contains(v.kind())))
                missing.add(kit[0] + " " + kit[1]);
        assertTrue(missing.isEmpty(), "No storage in " + missing);
    }

    /** Block light (sea lanterns at 15, one less each step through open cells). */
    static Map<BlockPos, Integer> light(ShipGenerator.Ship ship)
    {
        var level = new HashMap<BlockPos, Integer>();
        var queue = new ArrayDeque<BlockPos>();
        for (var e : ship.voxels().entrySet())
            if (e.getValue().kind() == Kind.LIGHT)
            {
                level.put(e.getKey(), 15);
                queue.add(e.getKey());
            }
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            int here = level.get(at);
            if (here <= 1)
                continue;
            for (var d : Direction.values())
            {
                var n = at.relative(d);
                var k = ship.kind(n);
                boolean open = ship.interior().contains(n) && (k == null || !k.solid());
                if (open && level.getOrDefault(n, 0) < here - 1)
                {
                    level.put(n, here - 1);
                    queue.add(n);
                }
            }
        }
        return level;
    }

    @Test
    void interiorsAreBrightAndCropsAreLitAndWatered()
    {
        var problems = new TreeSet<String>();
        for (var kit : kits())
        {
            var use = (RoomUse) kit[0];
            var ship = ShipGenerator.room(use, (Spec.Tier) kit[1], (int) kit[2]);
            var name = use + " " + kit[1];
            var lit = light(ship);
            for (var at : ship.interior())
            {
                var k = ship.kind(at);
                if (k == Kind.CROP || k == Kind.CROP2 || k == Kind.CROP3)
                {
                    if (lit.getOrDefault(at, 0) < 9)
                        problems.add(name + " has a crop at " + at + " with light " + lit.getOrDefault(at, 0) + ", under the 9 it needs to grow");
                }
                else if ((k == null || !k.solid()) && ship.kind(at.below()) != null && ship.kind(at.below()).solid() && lit.getOrDefault(at, 0) < 8)
                    problems.add(name + " is dark at " + at + " (" + lit.getOrDefault(at, 0) + ")");
            }
            for (var e : ship.voxels().entrySet())
            {
                var at = e.getKey();
                if (e.getValue().kind() == Kind.WATER)
                    for (var d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN})
                    {
                        var k = ship.kind(at.relative(d));
                        if (k == null || !k.solid() && k != Kind.WATER)
                            problems.add(name + " has water that could spill " + d + " at " + at);
                    }
                if (e.getValue().kind() == Kind.FARMLAND)
                {
                    boolean wet = false;
                    for (int dx = -4; dx <= 4; dx++)
                        for (int dz = -4; dz <= 4; dz++)
                            wet |= ship.kind(at.offset(dx, 0, dz)) == Kind.WATER;
                    if (!wet)
                        problems.add(name + " has farmland with no water within four blocks at " + at);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems.stream().limit(30).toList()));
    }

    @Test
    void everyKitDoesOneTaskAndRepeatsNoMachineOrBench()
    {
        var all = new ArrayList<String>();
        for (var kit : kits())
        {
            var use = (RoomUse) kit[0];
            var ship = ShipGenerator.room(use, (Spec.Tier) kit[1], (int) kit[2]);
            var counts = new EnumMap<Kind, Integer>(Kind.class);
            for (var v : ship.voxels().values())
                if (v.kind().fixture())
                    counts.merge(v.kind(), 1, Integer::sum);
            for (var e : counts.entrySet())
            {
                var kind = e.getKey();
                if (!task(use).contains(kind) && !DECOR.contains(kind))
                    all.add(use + " " + kit[1] + " holds " + kind + ", which is another room's task");
                if (ONE.contains(kind) && e.getValue() > 1 && !(kind == Kind.CAMERA))
                    all.add(use + " " + kit[1] + " has " + e.getValue() + " of " + kind);
            }
        }
        assertTrue(new TreeSet<>(all).isEmpty(), String.join(System.lineSeparator(), new TreeSet<>(all)));
    }

    @Test
    void everyUseHasAKitAtSomeSize()
    {
        for (var use : RoomUse.values())
            if (use.destination() && use != RoomUse.HUB)
                assertTrue(kits().stream().anyMatch(k -> k[0] == use), use + " fits no piece size");
    }

    @Test
    void kitsMirrorAndEveryFixtureCanBeReachedWithoutNarrowOrBlindSpots()
    {
        var all = new ArrayList<String>();
        for (var kit : kits())
        {
            var use = (RoomUse) kit[0];
            var tier = (Spec.Tier) kit[1];
            int windows = (int) kit[2];
            var name = use + " " + tier + " windows " + windows;
            var problems = new TreeSet<String>();
            var ship = ShipGenerator.room(use, tier, windows);
            assertEquals(1, ship.width() % 2, name + " has a centre line");
            for (var e : ship.voxels().entrySet())
            {
                var at = e.getKey();
                var other = ship.voxels().get(new BlockPos(ship.width() - 1 - at.getX(), at.getY(), at.getZ()));
                if (mirrored(e.getValue().kind()) && (other == null || other.kind() != e.getValue().kind()))
                    problems.add("does not mirror at " + at + " " + e.getValue().kind());
            }
            var cells = new Walk.Cells()
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
            BlockPos start = null;
            for (var at : ship.interior())
                if (at.getX() == ship.width() / 2 && Walk.standable(cells, at) && (start == null || at.getZ() < start.getZ()))
                    start = at;
            var walk = Walk.reachable(cells, start);
            for (var room : ship.rooms())
                if (!walk.contains(room.walk()))
                    problems.add("cannot walk to the room");
            for (var e : ship.voxels().entrySet())
                if (e.getValue().kind().fixture() && e.getValue().kind() != Kind.FARMLAND && e.getValue().kind() != Kind.CAMERA && e.getValue().kind() != Kind.BED && e.getValue().kind() != Kind.BED_FOOT)
                    if (!Walk.usable(walk, e.getKey()))
                        problems.add("unreachable " + e.getValue().kind() + " at " + e.getKey());
            for (var p : walk)
            {
                // The lane round a table's bookshelf ring (an enchanting ring) is an alcove, not a corridor.
                boolean alcove = false;
                for (int dx = -3; dx <= 3; dx++)
                    for (int dz = -3; dz <= 3; dz++)
                        alcove |= ship.kind(p.offset(dx, 0, dz)) == Kind.ENCHANTING_TABLE;
                // A chair against a wall is a seat, not a corridor.
                if (alcove || ship.kind(p) == Kind.SEAT || ship.kind(p) == Kind.DOOR_LOWER)
                    continue;
                var open = new ArrayList<Direction>();
                for (var d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST})
                    if (walk.contains(p.relative(d)))
                        open.add(d);
                boolean line = open.size() == 2 && open.get(0).getOpposite() == open.get(1);
                if (line)
                    problems.add("single-block-wide or blind spot at " + p);
            }
            if (!problems.isEmpty())
                all.add(name + ": " + problems.size() + " problems, e.g. " + problems.first());
        }
        try
        {
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/room-kit-audit.txt"), String.join(System.lineSeparator(), all));
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
        assertTrue(all.isEmpty(), all.size() + " kits break the rules; see build/room-kit-audit.txt");
    }
}
