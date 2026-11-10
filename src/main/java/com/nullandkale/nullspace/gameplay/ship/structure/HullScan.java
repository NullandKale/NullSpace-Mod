package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.mixin.ShipBlockEventsAccessor;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.docking.DockingTunnel;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;

public final class HullScan
{
    public record Bounds(BlockPos min, BlockPos max)
    {
        public boolean contains(BlockPos p)
        {
            return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }

        public Bounds expand()
        {
            return new Bounds(min.offset(-1, -1, -1), max.offset(1, 1, 1));
        }

        public AABB box()
        {
            return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        }
    }

    public record Result(Set<BlockPos> blocks, Bounds bounds, Pressure pressure, BlockPos boarding)
    {}

    public static Result inspect(ServerLevel level, BlockPos controller, PadScanner.Pad pad, boolean sky)
    {
        return inspect(level, controller, pad, sky, Set.of(), false);
    }

    public static Result inspect(ServerLevel level, BlockPos controller, PadScanner.Pad pad, boolean sky, Set<BlockPos> approvedDetached, boolean reviewing)
    {
        Bounds allowed = new Bounds(new BlockPos(pad.minX(), pad.y() + 1, pad.minZ()), new BlockPos(pad.maxX(), pad.y() + 32, pad.maxZ()));
        if (!allowed.contains(controller) || !ModBlocks.navigation(level.getBlockState(controller)))
            fail(level, "The helm is outside this pad. Move it above the grass or dirt interior.", controller);
        Set<BlockPos> blocks = new HashSet<>();
        var docking = DockingTunnel.positions(level);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(controller);
        int engines = 0, computers = 0, doors = 0;
        while (!queue.isEmpty())
        {
            BlockPos pos = queue.removeFirst();
            if (blocks.contains(pos) || pos.getY() <= pad.y() || docking.contains(pos))
                continue;
            var state = level.getBlockState(pos);
            // Snow that settled on the hull is weather, not ship: it never joins the scan.
            if (state.isAir() || state.is(Blocks.SNOW) && !pos.equals(controller))
                continue;
            // A pad's own computer and a delivery beacon stand at the pad edge, and are never part of the ship, even when the hull's ground step touches one.
            if (state.is(com.nullandkale.nullspace.gameplay.pads.PadComputers.BLOCK) || state.is(ModBlocks.SHIP_BEACON))
                continue;
            if (!allowed.contains(pos))
                fail(level, "This connected block extends outside the pad or above the 32-block height limit. Move" + " it inside the build area.", pos);
            BlockSupport.validate(level, pos);
            blocks.add(pos);
            if (ModBlocks.navigation(state))
                computers++;
            if (state.is(ModBlocks.ENGINE))
            {
                engines++;
                var direction = state.getValue(HorizontalDirectionalBlock.FACING);
                BlockPos exhaust = pos.relative(direction);
                if (!level.getBlockState(exhaust).isAir())
                    fail(level, "Engine exhaust is blocked. Leave the block directly in front of the engine empty.", exhaust);
            }
            if (ShipDoors.isDoor(state))
            {
                if ((state.getBlock() instanceof DoorBlock || ShipDoors.framed(level, pos)) && !ShipDoors.sealed(level, pos))
                    fail(level, state.getBlock() instanceof TrapDoorBlock ? "Close this trapdoor and frame all four horizontal sides with full blocks. A" + " closed hatch seals a floor or ceiling opening." : "Close both halves of this door and surround its opening with full frame" + " blocks.", pos);
                if (ShipDoors.root(state) && ShipDoors.sealed(level, pos))
                    doors++;
            }
            for (Direction d : Direction.values())
                queue.add(pos.relative(d));
        }
        BlockSupport.validatePistons(level, blocks);
        for (var event : ((ShipBlockEventsAccessor) level).nullspace$blockEvents())
            if (blocks.contains(event.pos()))
                fail(level, "This block has a queued action. Let pistons and other mechanisms finish, then inspect" + " again.", event.pos());
        if (computers != 1)
            fail(level, "Install exactly one helm or ship computer. Remove any extra helms or ship computers;" + " systems controllers are allowed.", controller);
        if (engines < 1)
            fail(level, "Install at least one engine with its exhaust pointing outside the hull.", controller);
        if (doors < 1)
            fail(level, "Install a closed, framed door or trapdoor hatch for a usable exit.", controller);
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (BlockPos p : blocks)
        {
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
        }
        Bounds bounds = new Bounds(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
        // Approval covers exact loose pieces, never a block in the hull's vertical launch path.
        Map<Long, Integer> columns = new HashMap<>();
        for (BlockPos p : blocks)
            columns.merge(BlockPos.asLong(p.getX(), 0, p.getZ()), p.getY(), Math::min);
        for (int x = pad.minX(); x <= pad.maxX(); x++)
            for (int z = pad.minZ(); z <= pad.maxZ(); z++)
                for (int y = pad.y() + 1; y <= (sky ? level.getMaxY() : pad.y() + 32); y++)
                {
                    var p = new BlockPos(x, y, z);
                    var state = level.getBlockState(p);
                    if (!state.isAir() && !cover(state) && !blocks.contains(p) && !docking.contains(p))
                    {
                        if (sky && columns.getOrDefault(BlockPos.asLong(x, 0, z), Integer.MAX_VALUE) < y)
                            fail(level, "Launch path obstruction: clear this block above the connected hull before" + " launching.", p);
                        if (reviewing || approvedDetached.contains(p))
                            continue;
                        fail(level, "This block is disconnected from the ship. Reconnect it, remove it, or use" + " Review loose blocks at the controller to leave it behind.", p);
                    }
                }
        Pressure pressure = pressure(level, bounds, blocks);
        var facing = level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING);
        BlockPos boarding = controller.relative(facing);
        if (!pressure.breathable(boarding) || !pressure.breathable(boarding.above()) || !level.getBlockState(boarding.below()).isCollisionShapeFullBlock(level, boarding.below()))
            throw new Failure("The helm front needs a sealed cabin with two blocks of standing room and a solid ship" + " floor. Close doors and hatches, repair leaks, and face the screen into the" + " cabin.", boarding, leakTrace(level, bounds, blocks, boarding));
        if (!CabinPaths.reachesDoor(level, boarding, pressure, blocks))
            fail(level, "There is no player-sized path from the helm to an exit. Add walkable floors, stairs or a" + " ladder to a framed hatch, and leave enough headroom.", boarding);
        for (BlockPos p : blocks)
            if (level.getBlockState(p).is(ModBlocks.ENGINE))
            {
                var exhaust = p.relative(level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING));
                if (pressure.breathable(exhaust))
                    fail(level, "The engine exhaust points into the cabin. Turn the engine so its exhaust faces open" + " space.", p);
            }
        return new Result(Set.copyOf(blocks), bounds, pressure, boarding);
    }

    public record Pressure(Bounds bounds, Set<BlockPos> exterior, Set<BlockPos> seals)
    {
        public boolean breathable(BlockPos p)
        {
            return bounds.contains(p) && !exterior.contains(p) && !seals.contains(p);
        }
    }

    /**
     * Per-tick room queries visit occupied compartments rather than flooding an entire empty ship
     * allocation.
     */
    public static final class LivePressure
    {
        private final ServerLevel level;
        private final Bounds bounds;
        private final Map<BlockPos, Boolean> rooms = new HashMap<>();

        public LivePressure(ServerLevel level, Bounds bounds)
        {
            this.level = level;
            this.bounds = bounds;
        }

        public boolean breathable(BlockPos start)
        {
            if (!bounds.contains(start) || seals(level, start))
                return false;
            if (rooms.containsKey(start))
                return rooms.get(start);
            var visited = new HashSet<BlockPos>();
            var queue = new ArrayDeque<BlockPos>();
            queue.add(start);
            boolean sealed = true;
            while (!queue.isEmpty())
            {
                var p = queue.removeFirst();
                if (!bounds.contains(p))
                {
                    sealed = false;
                    break;
                }
                if (seals(level, p) || !visited.add(p))
                    continue;
                if (rooms.containsKey(p))
                {
                    sealed = rooms.get(p);
                    break;
                }
                for (Direction d : Direction.values())
                    queue.add(p.relative(d));
            }
            for (var p : visited)
                rooms.put(p, sealed);
            return sealed;
        }
    }

    public static Pressure pressure(ServerLevel level, Bounds ship, Set<BlockPos> captured)
    {
        Bounds bounds = ship.expand();
        Set<BlockPos> seals = new HashSet<>(), outside = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos cursor : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            BlockPos p = cursor.immutable();
            if ((captured == null || captured.contains(p)) && seals(level, p))
                seals.add(p);
        }
        queue.add(bounds.min());
        while (!queue.isEmpty())
        {
            BlockPos p = queue.removeFirst();
            if (!bounds.contains(p) || seals.contains(p) || !outside.add(p))
                continue;
            for (Direction d : Direction.values())
                queue.add(p.relative(d));
        }
        return new Pressure(ship, outside, seals);
    }

    /**
     * Loose ground cover that launch checks ignore: snow layers and replaceable plants such as grass
     * and flowers on the pad. Never fluids. Plants attached to the ship still travel with it.
     */
    public static boolean cover(net.minecraft.world.level.block.state.BlockState state)
    {
        return !state.isAir() && (state.is(Blocks.SNOW) || state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /** Removes snow resting on top of the ship's blocks at departure, so none is left floating. */
    public static void clearCover(ServerLevel level, Set<BlockPos> blocks)
    {
        for (var p : blocks)
        {
            var above = p.above();
            if (!blocks.contains(above) && level.getBlockState(above).is(Blocks.SNOW))
                level.setBlock(above, Blocks.AIR.defaultBlockState(), 18);
        }
    }

    public static boolean seals(ServerLevel level, BlockPos pos)
    {
        if (DockingPorts.sealedMate(level, pos))
            return true;
        var state = level.getBlockState(pos);
        if (ShipDoors.isDoor(state))
            return ShipDoors.sealed(level, pos);
        return !state.isAir() && state.isCollisionShapeFullBlock(level, pos) && !(state.getBlock() instanceof LeavesBlock);
    }

    public static boolean validDoor(ServerLevel level, BlockPos pos)
    {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock))
            return false;
        var base = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        return framedDoor(level, pos) && !level.getBlockState(base).getValue(DoorBlock.OPEN) && !level.getBlockState(base.above()).getValue(DoorBlock.OPEN);
    }

    public static boolean framedDoor(ServerLevel level, BlockPos pos)
    {
        var s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof DoorBlock))
            return false;
        BlockPos base = s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        var low = level.getBlockState(base);
        var high = level.getBlockState(base.above());
        if (low.getBlock() != high.getBlock() || !low.hasProperty(DoorBlock.HALF) || !high.hasProperty(DoorBlock.HALF))
            return false;
        if (low.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || high.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER || low.getValue(DoorBlock.FACING) != high.getValue(DoorBlock.FACING) || low.getValue(DoorBlock.HINGE) != high.getValue(DoorBlock.HINGE))
            return false;
        Direction side = low.getValue(DoorBlock.FACING).getClockWise();
        for (BlockPos p : List.of(base.below(), base.above(2), base.relative(side), base.relative(side.getOpposite()), base.above().relative(side), base.above().relative(side.getOpposite())))
            if (!level.getBlockState(p).isCollisionShapeFullBlock(level, p))
                return false;
        return true;
    }

    public static java.util.List<BlockPos> leakTrace(ServerLevel level, Bounds bounds, Set<BlockPos> captured, BlockPos start)
    {
        var queue = new ArrayDeque<BlockPos>();
        var parent = new HashMap<BlockPos, BlockPos>();
        queue.add(start);
        parent.put(start, start);
        while (!queue.isEmpty())
        {
            var pos = queue.removeFirst();
            if (!bounds.contains(pos))
            {
                var result = new ArrayList<BlockPos>();
                var p = pos;
                while (!p.equals(start))
                {
                    result.add(p);
                    p = parent.get(p);
                }
                result.add(start);
                Collections.reverse(result);
                return result;
            }
            for (Direction d : Direction.values())
            {
                var next = pos.relative(d);
                if (parent.containsKey(next) || (captured.contains(next) && seals(level, next)))
                    continue;
                parent.put(next, pos);
                queue.add(next);
            }
        }
        return java.util.List.of(start);
    }

    public static final class Failure extends IllegalArgumentException
    {
        public final BlockPos location;
        public final java.util.List<BlockPos> trace;

        public Failure(String message, BlockPos location, java.util.List<BlockPos> trace)
        {
            super(message + "\nLocation: " + location.toShortString());
            this.location = location;
            this.trace = java.util.List.copyOf(trace);
        }
    }

    private static void fail(ServerLevel level, String message, BlockPos pos)
    {
        throw ShipMessages.blockProblem(level, pos, message);
    }

    private HullScan()
    {
    }
}
