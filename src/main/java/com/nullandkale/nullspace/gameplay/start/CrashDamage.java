package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.content.BrokenMachines;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * What a crash landing does to a ship that is already sitting on its pad. Two things, both deterministic from the seed:
 *
 * <ul>
 * <li><b>Broken machines.</b> Half (rounded up, so at least one, and at most {@link #MOST_BROKEN}) of each of the ship's Engines,
 * Generators and Air Supplies become their {@link BrokenMachines} wrecks, in place and facing the same way. These are the machines a ship cannot fly without, so
 * the player is told by the blocks themselves what to build and where it goes. The ship computer is never touched.</li>
 * <li><b>Hull wounds.</b> A few balls of hull are torn out of the outside of the lower half of the ship, scorched at the edges, and
 * whatever hung on the torn blocks falls away. The helm, the doors, the docking hardware and every machine are never cut, and
 * nothing with an inventory or a second half is carved, so the wreck is a ship that was hurt, not one that is unplayable.</li>
 * </ul>
 */
public final class CrashDamage
{
    /** {@code broken} are the wrecks' positions and {@code woundCenters} the middle of each tear, for pointing the player at the damage. */
    public record Report(int engines, int generators, int airSupplies, int wounds, int removed, int fixtures, List<BlockPos> broken, List<BlockPos> woundCenters)
    {}

    /** The most machines of one kind that are broken, so a big ship is not a mountain of crafting. */
    public static final int MOST_BROKEN = 2;

    /** The share of a ship's furnishings (chests, furnaces and the like) the crash destroys, by size class: none, a quarter, half. */
    private static final double[] FIXTURES_LOST = {0, .25, .5};

    /** How far from the controller nothing is cut: the cabin the player wakes in. */
    private static final int CABIN = 4;

    /**
     * {@code vacuum} is true on a planet with no air. On every planet the cabin keeps the ship's own air: the Generator nearest the helm and the Air
     * Supply that feeds the cabin are never broken, and no tear reaches a block that touches the cabin's air zone.
     */
    public static Report apply(ServerLevel level, CompoundTag ship, long seed, boolean vacuum)
    {
        var bounds = WorldStore.bounds(ship);
        var min = bounds.min();
        var max = bounds.max();
        var controller = WorldStore.pos(ship, "controller");
        var random = new Random(seed ^ ship.getStringOr("id", "").hashCode() * 0x9E3779B97F4A7C15L);

        var engines = new ArrayList<BlockPos>();
        var generators = new ArrayList<BlockPos>();
        var airs = new ArrayList<BlockPos>();
        var protectedCells = new HashSet<BlockPos>();
        var ways = new ArrayList<BlockPos>();
        int hull = 0;
        for (var at : BlockPos.betweenClosed(min, max))
        {
            var state = level.getBlockState(at);
            if (state.isAir())
                continue;
            hull++;
            var pos = at.immutable();
            if (state.is(ModBlocks.ENGINE))
                engines.add(pos);
            else if (state.is(ModBlocks.GENERATOR))
                generators.add(pos);
            else if (state.is(ModBlocks.AIR_SUPPLY))
                airs.add(pos);
            boolean cabin = Math.abs(pos.getX() - controller.getX()) <= CABIN && Math.abs(pos.getZ() - controller.getZ()) <= CABIN && Math.abs(pos.getY() - controller.getY()) <= 3;
            if (cabin || state.is(ModBlocks.COMPUTER))
                protectedCells.add(pos);
            if (state.getBlock() instanceof DoorBlock || state.is(ModBlocks.DOCKING_BASE))
                ways.add(pos);
        }
        // Everything within two blocks of a door or a docking base is spared, so the way in and out stays whole.
        for (var way : ways)
            for (var near : BlockPos.betweenClosed(way.offset(-2, -2, -2), way.offset(2, 2, 2)))
                protectedCells.add(near.immutable());

        // The cabin's own air zone: the air connected to the cell in front of the helm, and every block touching it, is spared.
        var facing = level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING);
        var cabinCell = controller.relative(facing);
        var cabin = new HashSet<BlockPos>();
        var open = new ArrayDeque<BlockPos>();
        if (level.getBlockState(cabinCell).isAir())
        {
            cabin.add(cabinCell);
            open.add(cabinCell);
        }
        while (!open.isEmpty() && cabin.size() < 6000)
        {
            var at = open.poll();
            for (var side : Direction.values())
            {
                var next = at.relative(side);
                if (bounds.contains(next) && level.getBlockState(next).isAir() && cabin.add(next))
                    open.add(next);
            }
        }
        for (var cell : cabin)
            for (var near : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1)))
                protectedCells.add(near.immutable());
        // Every start leaves the bridge breathing on the ship's own air, whatever the planet: the Generator nearest the helm and the Air Supply that blows
        // into the cabin (the nearest one if none does) are never broken. Up to 0.9.0 only a planet without air spared them.
        generators.sort(Comparator.comparingDouble(p -> p.distSqr(controller)));
        airs.sort(Comparator.comparingDouble(p -> p.distSqr(controller)));
        if (!generators.isEmpty())
            generators.removeFirst();
        if (!airs.isEmpty())
            airs.remove(cabinSupply(level, ship, airs, cabinCell));
        int shipClass = shipClass(ship);
        var wrecks = new ArrayList<BlockPos>();
        wrecks.addAll(breakHalf(level, engines, random, shipClass));
        int e = wrecks.size();
        wrecks.addAll(breakHalf(level, generators, random, shipClass));
        int g = wrecks.size() - e;
        wrecks.addAll(breakHalf(level, airs, random, shipClass));
        int a = wrecks.size() - e - g;

        // The ship's outside: air that connects to the edge of the box, found by flood fill.
        var outside = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        for (var at : BlockPos.betweenClosed(min.offset(-1, -1, -1), max.offset(1, 1, 1)))
        {
            boolean edge = at.getX() < min.getX() || at.getX() > max.getX() || at.getY() < min.getY() || at.getY() > max.getY() || at.getZ() < min.getZ() || at.getZ() > max.getZ();
            if (edge && level.getBlockState(at).isAir())
            {
                outside.add(at.immutable());
                queue.add(at.immutable());
            }
        }
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (var side : Direction.values())
            {
                var next = at.relative(side);
                if (next.getX() < min.getX() - 1 || next.getX() > max.getX() + 1 || next.getY() < min.getY() - 1 || next.getY() > max.getY() + 1 || next.getZ() < min.getZ() - 1 || next.getZ() > max.getZ() + 1)
                    continue;
                if (level.getBlockState(next).isAir() && outside.add(next))
                    queue.add(next);
            }
        }
        // Candidates are outer hull blocks: solid and touching the outside, preferring the lower part, which hit the ground first.
        int split = min.getY() + (max.getY() - min.getY() + 1) * 6 / 10;
        var low = new ArrayList<BlockPos>();
        var any = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(min, max))
        {
            var pos = at.immutable();
            if (!carvable(level.getBlockState(pos)) || protectedCells.contains(pos))
                continue;
            boolean exposed = false;
            for (var side : Direction.values())
                exposed |= outside.contains(pos.relative(side));
            if (!exposed)
                continue;
            any.add(pos);
            if (pos.getY() <= split)
                low.add(pos);
        }
        var pool = low.size() >= 6 ? low : any;
        pool.sort(Comparator.comparingLong(BlockPos::asLong));
        Collections.shuffle(pool, random);
        int radius = hull > 700 ? 2 : 1;
        int wanted = Math.max(3, Math.min(6, hull / 200));
        var centers = new ArrayList<BlockPos>();
        for (var candidate : pool)
        {
            if (centers.size() >= wanted)
                break;
            boolean apart = true;
            for (var other : centers)
                apart &= candidate.distSqr(other) >= 36;
            if (apart)
                centers.add(candidate);
        }
        var removed = new HashSet<BlockPos>();
        for (var center : centers)
            for (int dx = -radius; dx <= radius; dx++)
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dz = -radius; dz <= radius; dz++)
                    {
                        if (dx * dx + dy * dy + dz * dz > radius * radius + 1)
                            continue;
                        var at = center.offset(dx, dy, dz);
                        if (!bounds.contains(at) || protectedCells.contains(at) || !carvable(level.getBlockState(at)))
                            continue;
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
                        removed.add(at);
                    }
        // Scorch and cracks on what the tear left standing. Only the ship itself: the pad floor under it must stay valid.
        for (var gone : removed)
            for (var side : Direction.values())
            {
                var at = gone.relative(side);
                var state = level.getBlockState(at);
                if (!bounds.contains(at) || removed.contains(at) || protectedCells.contains(at) || !carvable(state) || !state.isCollisionShapeFullBlock(level, at))
                    continue;
                int roll = random.nextInt(100);
                if (roll < 40)
                    level.setBlock(at, Blocks.BLACKSTONE.defaultBlockState(), 18);
                else if (roll < 65)
                    level.setBlock(at, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 18);
            }
        // Whatever hung on a torn block lets go, until nothing is left without support.
        for (int pass = 0; pass < 4 && !removed.isEmpty(); pass++)
        {
            boolean changed = false;
            for (var gone : List.copyOf(removed))
                for (var side : Direction.values())
                {
                    var at = gone.relative(side);
                    var state = level.getBlockState(at);
                    if (!bounds.contains(at) || state.isAir() || protectedCells.contains(at) || !carvable(state) || state.canSurvive(level, at))
                        continue;
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
                    removed.add(at);
                    changed = true;
                }
            if (!changed)
                break;
        }
        // A bigger ship has more furnishings to pick through: some are lost in the crash.
        int stripped = 0;
        double share = FIXTURES_LOST[Math.clamp(shipClass, 0, 2)];
        if (share > 0)
            for (var at : BlockPos.betweenClosed(min, max))
            {
                var pos = at.immutable();
                if (fixture(level.getBlockState(pos)) && !protectedCells.contains(pos) && random.nextDouble() < share)
                {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                    stripped++;
                }
            }
        return new Report(e, g, a, centers.size(), removed.size(), stripped, List.copyOf(wrecks), List.copyOf(centers));
    }

    /** How many of {@code machines} machines of one kind are broken: half, rounded up, and no more than {@link #MOST_BROKEN}. */
    public static int broken(int machines)
    {
        return broken(machines, 0);
    }

    /** As above for a ship of a size class: a medium ship breaks at most three of a kind, a large one four. */
    public static int broken(int machines, int shipClass)
    {
        return Math.min(MOST_BROKEN + Math.clamp(shipClass, 0, 2), (machines + 1) / 2);
    }

    /** The ship's size class (0 small, 1 medium, 2 large) by its decks; 0 when the design is unknown. */
    static int shipClass(CompoundTag ship)
    {
        try
        {
            return com.nullandkale.nullspace.gameplay.start.StartDifficulty.shipClass(com.nullandkale.nullspace.worldgen.ships.ShipBlueprints.get(ship.getStringOr("blueprint", "")).decks());
        }
        catch (RuntimeException unknown)
        {
            return 0;
        }
    }

    /** A furnishing worth salvaging: a container, a furnace, a workstation. Machines, doors and beds are not. */
    private static boolean fixture(BlockState state)
    {
        if (state.isAir() || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("nullspace") || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof BedBlock)
            return false;
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.hasBlockEntity() && !name.contains("sign") && !name.contains("banner") && !name.contains("head") || name.endsWith("_table") || name.contains("anvil") || name.equals("grindstone") || name.equals("stonecutter") || name.equals("loom") || name.equals("composter") || name.equals("bookshelf");
    }

    /** Replaces some of the machines with their wrecks (see {@link #broken}). Returns how many were broken. */
    /** The Air Supply whose outlet opens into the cabin's room; the first (nearest) one if none does. {@code airs} is sorted nearest first. */
    static BlockPos cabinSupply(ServerLevel level, CompoundTag ship, List<BlockPos> airs, BlockPos cabinCell)
    {
        var cabin = com.nullandkale.nullspace.gameplay.systems.ShipAir.roomAt(level, ship, cabinCell);
        if (cabin != null)
            for (var supply : airs)
            {
                var room = com.nullandkale.nullspace.gameplay.systems.ShipAir.roomAt(level, ship, supply.relative(level.getBlockState(supply).getValue(HorizontalDirectionalBlock.FACING)));
                if (room != null && room.number() == cabin.number())
                    return supply;
            }
        return airs.getFirst();
    }

    private static List<BlockPos> breakHalf(ServerLevel level, List<BlockPos> machines, Random random, int shipClass)
    {
        if (machines.isEmpty())
            return List.of();
        machines.sort(Comparator.comparingLong(BlockPos::asLong));
        var order = new ArrayList<>(machines);
        Collections.shuffle(order, random);
        int count = broken(machines.size(), shipClass);
        var broken = new ArrayList<BlockPos>();
        for (int i = 0; i < count; i++)
        {
            var at = order.get(i);
            var state = level.getBlockState(at);
            var wreck = BrokenMachines.of(state.getBlock());
            level.setBlock(at, wreck.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, state.getValue(HorizontalDirectionalBlock.FACING)), 18);
            broken.add(at);
        }
        return broken;
    }

    /** True for a block the crash may tear out: plain hull and fittings, never machines, anything with an inventory or a second half. */
    private static boolean carvable(BlockState state)
    {
        if (state.isAir() || state.hasBlockEntity() || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof BedBlock || state.getBlock() instanceof LiquidBlock || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || state.hasProperty(BlockStateProperties.BED_PART) || state.is(Blocks.BEDROCK))
            return false;
        return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("nullspace");
    }

    private CrashDamage()
    {
    }
}
