package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationGraph;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;

/**
 * Docking ports on a hull: reading them from real blocks, their doors and interlocks, and walking
 * through a mated port.
 */
public final class DockingPorts
{
    public record Port(String id, BlockPos controller, BlockPos outer, BlockPos inner, Direction facing, Set<BlockPos> cells, List<BlockPos> anchors)
    {}

    public static boolean cycleOwnedInner(ServerPlayer p, CompoundTag s, BlockPos door)
    {
        for (var port : ports(p.level(), s))
            if (port.inner().equals(door))
            {
                var d = DockingService.link(p.level().getServer(), s, port.controller());
                if (d != null && !d.getStringOr("state", "").equals("CLAMPED"))
                    throw new IllegalArgumentException("Wait for capture before cycling a dock-owned door");
                safeInner(p, s, readPort(p.level(), s, port.controller(), true));
                return true;
            }
        return false;
    }

    public static Port readPort(ServerLevel level, CompoundTag s, BlockPos controller, boolean requirePower)
    {
        var state = level.getBlockState(controller);
        if (!state.is(ModBlocks.DOCKING_BASE))
            throw new IllegalArgumentException("Docking base is missing");
        return compactPort(level, s, controller, requirePower);
    }

    static Port compactPort(ServerLevel level, CompoundTag ship, BlockPos base, boolean powered)
    {
        var door = base.above();
        var state = level.getBlockState(door);
        if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || !WorldStore.bounds(ship).contains(base) || !ShipDoors.framed(level, door))
            throw new IllegalArgumentException("Place a framed standard door directly above the Docking Base; leave walking space" + " inside");
        // Door placement changes its facing independently of which side is the cabin.
        // The docking hardware defines the outward approach and boarding direction.
        var facing = level.getBlockState(base).getValue(HorizontalDirectionalBlock.FACING);
        if (facing.getAxis() != state.getValue(DoorBlock.FACING).getAxis())
            throw ShipMessages.blockProblem(level, base, "Turn the Docking Base to face outward through the doorway, not sideways");
        var anchors = new ArrayList<BlockPos>();
        var cells = new HashSet<BlockPos>();
        cells.add(door);
        cells.add(door.above());
        for (int depth = 1; depth <= 2; depth++)
        {
            var at = door.relative(facing.getOpposite(), depth);
            if (WorldStore.bounds(ship).contains(at) && StationGraph.standing(level, at))
            {
                anchors.add(at);
                cells.add(at);
                cells.add(at.above());
            }
        }
        if (anchors.isEmpty())
            throw new IllegalArgumentException("Clear two-block-high walking space behind the docking door");
        if (powered && (!ShipSystems.enabled(ship) || ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 0 || ShipSystems.power(level, ship, PowerBus.System.DOORS) == 0 || !ShipSystems.active(ship, base)))
            throw new IllegalArgumentException("Docking Base needs controller and door power");
        var identities = ship.getCompoundOrEmpty("doorNodes");
        if (!ship.contains("doorNodes"))
            ship.put("doorNodes", identities);
        String key = Long.toString(base.subtract(WorldStore.pos(ship, "base")).asLong());
        String id = identities.getStringOr(key, "");
        if (id.isEmpty())
        {
            id = UUID.randomUUID().toString();
            identities.putString(key, id);
        }
        return new Port(id, base, door, door, facing, Set.copyOf(cells), List.copyOf(anchors));
    }

    /** A captured compact connection seals its doorway without manufacturing cabin oxygen. */
    public static boolean sealedMate(ServerLevel level, BlockPos door)
    {
        var state = level.getBlockState(door);
        if (!(state.getBlock() instanceof DoorBlock))
            return false;
        if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
            door = door.below();
        var at = door.below();
        if (!level.getBlockState(at).is(ModBlocks.DOCKING_BASE))
            return false;
        var ship = WorldStore.get(level.getServer()).at(level, at);
        if (ship == null)
            return false;
        var d = DockingService.link(level.getServer(), ship, at);
        return d != null && !d.getBooleanOr("construction", false) && d.getStringOr("state", "").equals("CLAMPED");
    }

    public static boolean useCompactDoor(ServerPlayer p, BlockPos at)
    {
        var level = p.level();
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof DoorBlock))
            return false;
        if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
            at = at.below();
        if (!level.getBlockState(at.below()).is(ModBlocks.DOCKING_BASE))
            return false;
        var own = WorldStore.get(level.getServer()).at(level, at);
        if (own == null || !StationAccess.allowed(p, own) && !StationAccess.returningVisitor(p, own))
            return true;
        var link = DockingService.link(level.getServer(), own, at.below());
        if (link != null && !link.getStringOr("state", "").equals("CLAMPED"))
        {
            p.sendOverlayMessage(Component.literal("Wait for docking capture before opening the door"));
            return true;
        }
        // Opening to vacuum is the player's choice; the air system handles the consequences.
        ShipDoors.setOpen(level, at, !ShipDoors.open(level.getBlockState(at)));
        return true;
    }

    static void walkThrough(MinecraftServer server)
    {
        var level = server.getLevel(TravelService.SPACE);
        if (level == null)
            return;
        for (var p : List.copyOf(level.players()))
        {
            if (TransferLocks.busy(p))
                continue;
            var own = SceneService.observer(p);
            if (own == null || !DockingService.engaged(own))
                continue;
            for (var port : ports(level, own))
            {
                if (!port.inner().equals(port.outer()) || !ShipDoors.open(level.getBlockState(port.outer())))
                    continue;
                var d = DockingService.link(server, own, port.controller());
                if (d == null || !d.getStringOr("state", "").equals("CLAMPED"))
                    continue;
                if (!compactEntrance(p, own, port))
                    continue;
                boolean source = d.getStringOr("source", "").equals(DockingService.id(own));
                var other = DockingService.find(server, d.getStringOr(source ? "target" : "source", ""));
                if (other == null)
                    continue;
                try
                {
                    boolean construction = d.getBooleanOr("construction", false);
                    var targetPort = construction ? null : readPort(level, other, WorldStore.pos(other, "base").offset(WorldStore.pos(d, source ? "targetPort" : "sourcePort")), false);
                    Boarding.beginBoard(p, own, other, port, targetPort, construction);
                }
                catch (IllegalArgumentException blocked)
                {
                    p.sendOverlayMessage(Component.literal(blocked.getMessage()));
                    var safe = port.anchors().getFirst();
                    p.teleportTo(safe.getX() + .5, safe.getY(), safe.getZ() + .5);
                    p.setDeltaMovement(Vec3.ZERO);
                }
                break;
            }
        }
    }

    static boolean compactEntrance(ServerPlayer p, CompoundTag ship, Port port)
    {
        if (!port.inner().equals(port.outer()))
            return false;
        var dock = DockingService.link(p.level().getServer(), ship, port.controller());
        var delta = p.position().subtract(Vec3.atBottomCenterOf(port.outer()));
        double forward = delta.dot(DockingMotion.normal(port.facing()));
        return forward >= -.15 && forward <= DockingTunnel.boardingReach(dock, DockingService.id(ship), port) && Math.abs(delta.dot(DockingMotion.normal(port.facing().getClockWise()))) <= .8 && delta.y >= -.2 && delta.y <= 1.5;
    }

    public static List<Port> ports(ServerLevel level, CompoundTag s)
    {
        if (!StationAccess.ready(s))
            return List.of();
        var ports = new ArrayList<Port>();
        for (var at : ShipSystems.connected(level, s))
            if (level.getBlockState(at).is(ModBlocks.DOCKING_BASE))
            {
                try
                {
                    ports.add(readPort(level, s, at, false));
                }
                catch (IllegalArgumentException invalid)
                {
                }
            }
        ports.sort(Comparator.comparingLong(p -> p.controller().asLong()));
        return List.copyOf(ports);
    }

    public static Port firstPort(ServerLevel level, CompoundTag s)
    {
        var ports = ports(level, s);
        if (ports.isEmpty())
        {
            for (var at : ShipSystems.connected(level, s).stream().sorted().toList())
                if (level.getBlockState(at).is(ModBlocks.DOCKING_BASE))
                {
                    try
                    {
                        return readPort(level, s, at, true);
                    }
                    catch (IllegalArgumentException invalid)
                    {
                        throw ShipMessages.blockProblem(level, at, invalid.getMessage());
                    }
                }
            throw new IllegalArgumentException("Build a framed door over an outward-facing Docking Base" + " with clear walking room inside");
        }
        return readPort(level, s, ports.getFirst().controller(), true);
    }

    static void close(ServerLevel level, Port port)
    {
        for (var door : List.of(port.outer(), port.inner()))
        {
            closeDoor(level, door);
        }
    }

    static void closeDoor(ServerLevel level, BlockPos door)
    {
        if (ShipDoors.powered(level, door))
            throw new IllegalArgumentException("Airlock has a redstone override");
        if (!level.getEntities(null, new AABB(door).expandTowards(0, 1, 0)).isEmpty())
            throw new IllegalArgumentException("Move clear of the airlock doorway");
        ShipDoors.setOpen(level, door, false);
        if (!ShipDoors.sealed(level, door))
            throw new IllegalArgumentException("Airlock door did not seal");
    }

    static boolean closeSurviving(ServerLevel level, CompoundTag vessel, BlockPos controller)
    {
        Port port;
        try
        {
            port = readPort(level, vessel, controller, false);
        }
        catch (IllegalArgumentException damaged)
        {
            // Old links may predate compact-port metadata. Only close real surviving doors; never
            // fabricate a seal, refill air, or require the missing controller to release the clamp.
            for (var door : List.of(controller.above(), controller.below(2)))
            {
                var state = level.getBlockState(door);
                if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
                    closeDoor(level, door);
            }
            return false;
        }
        close(level, port);
        return true;
    }

    public static boolean ownsDoor(ServerLevel level, BlockPos at)
    {
        for (var tag : WorldStore.get(level.getServer()).ships())
        {
            var s = (CompoundTag) tag;
            if (!s.contains("dock"))
                continue;
            var d = s.getCompoundOrEmpty("dock");
            if (!DockingService.id(s).equals(d.getStringOr("source", "")))
                continue;
            var target = DockingService.find(level.getServer(), d.getStringOr("target", ""));
            for (var vessel : new CompoundTag[]{s, target})
                if (vessel != null)
                {
                    var controller = WorldStore.pos(vessel, "base").offset(WorldStore.pos(d, vessel == s ? "sourcePort" : "targetPort"));
                    try
                    {
                        var port = readPort(level, vessel, controller, false);
                        if (at.equals(port.outer()) || at.equals(port.inner()))
                            return true;
                    }
                    catch (IllegalArgumentException ignored)
                    {
                    }
                }
        }
        return false;
    }

    static BlockPos anchor(ServerLevel level, Port port)
    {
        return port.anchors().stream().filter(p -> level.getEntities(null, new AABB(p).expandTowards(0, 1, 0)).isEmpty()).findFirst().orElseThrow(() -> new IllegalArgumentException("Destination chamber is occupied; wait for the previous passenger"));
    }

    static void safeInner(ServerPlayer p, CompoundTag s, Port port)
    {
        if (port.inner().equals(port.outer()))
        {
            ShipDoors.setOpen(p.level(), port.outer(), true);
            return;
        }
        if (ShipDoors.open(p.level().getBlockState(port.outer())))
            throw new IllegalArgumentException("Close the exterior door before opening the inner door");
        if (ShipDoors.powered(p.level(), port.inner()))
            throw new IllegalArgumentException("Inner door is overridden by redstone");
        ShipDoors.setOpen(p.level(), port.inner(), !ShipDoors.open(p.level().getBlockState(port.inner())));
    }

    private DockingPorts()
    {
    }
}
