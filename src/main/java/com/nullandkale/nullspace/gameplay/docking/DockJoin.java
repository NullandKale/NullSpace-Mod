package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts.Port;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.DockMove;
import com.nullandkale.nullspace.gameplay.travel.ShipTransform;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.space.ScenePose;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Joined docking: a docked visitor's blocks stand beside its berth, so the crew walk between the two hulls through a docking arm.
 *
 * <p>Every ship and station owns a box of the space dimension, and the game finds which vessel a block belongs to by those boxes, so two boxes must never
 * overlap. A berth's port is often well inside its vessel's box (a hull rarely fills its 32-block area, and a station's area is rounded up to whole sections),
 * so the visitor clamps just outside the host's box and a walkable arm joins the two doors. The docking approach ends at the same gap, so the hulls seen from
 * outside match the blocks. A docked hull's own box fits it exactly (its width and depth are those of the hull), so the visitor's box starts at its hull.
 *
 * <p>At capture the visitor's hull is moved, with the crew aboard, from its own area to the berth, turned a quarter turn as needed so its door faces the
 * berth's: the docking pose already matches the berth's orientation up to that turn, so afterwards both vessels have the same orientation and their blocks sit
 * as they do in the simulation. Before it backs away it is moved to a free area of its own again. Each move is an ordinary journaled ship move.
 */
public final class DockJoin
{
    /** The longest docking arm, in blocks (it is drawn as one 32-block section): a berth set deeper than this in its station is docked without joining. */
    static final int LONGEST_ARM = 31;
    /** How many times a join is tried before the dock falls back to boarding through the door. */
    static final int ATTEMPTS = 3;

    /** How far the host's box reaches beyond its port, along the port's facing: where the visitor's box can start. */
    static int reach(CompoundTag host, Port port)
    {
        var plane = DockingManeuver.localFace(host, port.controller(), port.facing());
        var normal = DockingMotion.normal(port.facing());
        var face = new Vec3(normal.x > 0 ? host.getIntOr("width", 32) : 0, normal.y > 0 ? host.getIntOr("height", 32) : 0, normal.z > 0 ? host.getIntOr("depth", 32) : 0);
        return (int) Math.round(normal.dot(face) - normal.dot(plane));
    }

    /** The gap between the two doors of a joined dock: the visitor's hull ahead of its door, and the host's area ahead of its port. */
    static int gap(ServerLevel level, CompoundTag own, Port source, CompoundTag host, Port destination)
    {
        int arm = (int) Math.round(StationConstruction.protrusion(level, own, source)) + reach(host, destination);
        if (arm > LONGEST_ARM)
            throw new IllegalArgumentException("This berth needs a " + arm + "-block docking arm, longer than the " + LONGEST_ARM + " a ship can reach: its door is set deep inside its station's area. Choose a berth on the station's outer edge.");
        return arm;
    }

    /** The box around a vessel's blocks. */
    static HullScan.Bounds hull(ServerLevel level, CompoundTag ship)
    {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (var at : ShipSystems.connected(level, ship))
        {
            lo[0] = Math.min(lo[0], at.getX());
            lo[1] = Math.min(lo[1], at.getY());
            lo[2] = Math.min(lo[2], at.getZ());
            hi[0] = Math.max(hi[0], at.getX());
            hi[1] = Math.max(hi[1], at.getY());
            hi[2] = Math.max(hi[2], at.getZ());
        }
        if (lo[0] > hi[0])
            throw new IllegalArgumentException("This ship's blocks are not loaded");
        return new HullScan.Bounds(new BlockPos(lo[0], lo[1], lo[2]), new BlockPos(hi[0], hi[1], hi[2]));
    }

    /**
     * Where the visitor's hull goes to join the berth: turned so its door faces the berth's door, {@code gap} blocks from it, in a box that fits the hull. The
     * box must be clear of every other vessel's.
     */
    static DockMove.Placement placement(ServerLevel level, CompoundTag own, Port source, CompoundTag host, Port destination, int gap, HullScan.Bounds hull)
    {
        var base = WorldStore.pos(own, "base");
        int turns = Math.floorMod(destination.facing().getOpposite().get2DDataValue() - source.facing().get2DDataValue(), 4);
        int width = own.getIntOr("width", 32), depth = own.getIntOr("depth", 32);
        var turned = new ShipTransform(base, BlockPos.ZERO, width, depth, turns);
        var box = turned.bounds(hull);
        var door = turned.apply(source.outer()).subtract(box.min());
        var newBase = destination.outer().relative(destination.facing(), gap + 1).subtract(door);
        var transform = new ShipTransform(base, newBase, width, depth, turns, BlockPos.ZERO.subtract(box.min()));
        int w = box.max().getX() - box.min().getX() + 1, d = box.max().getZ() - box.min().getZ() + 1;
        var area = new HullScan.Bounds(newBase, newBase.offset(w - 1, 31, d - 1));
        for (var tag : WorldStore.get(level.getServer()).ships())
        {
            var other = (CompoundTag) tag;
            if (other != own && other.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && WorldStore.bounds(other).box().intersects(area.box()))
                throw new IllegalArgumentException("This berth is too crowded for a docking arm: " + other.getStringOr("home", "another vessel") + " is in the way");
        }
        // The arm's walkway inside the host's area must be open.
        for (int depthIn = 1; depthIn <= Math.min(gap, reach(host, destination)); depthIn++)
            for (int y = 0; y <= 1; y++)
            {
                var at = destination.outer().relative(destination.facing(), depthIn).above(y);
                if (!level.getBlockState(at).isAir() && !DockingTunnel.positions(level).contains(at))
                    throw new IllegalArgumentException("The docking arm's path is blocked at " + at.toShortString() + ". Keep a two-block-high passage clear in front of the berth door.");
            }
        return new DockMove.Placement(transform, w, d, 32);
    }

    /** The dock's two ports, read from the saved connection. */
    private static Port[] ports(ServerLevel level, CompoundTag own, CompoundTag host, CompoundTag d)
    {
        var source = DockingPorts.readPort(level, own, WorldStore.pos(own, "base").offset(WorldStore.pos(d, "sourcePort")), false);
        var destination = DockingPorts.readPort(level, host, WorldStore.pos(host, "base").offset(WorldStore.pos(d, "targetPort")), false);
        return new Port[]{source, destination};
    }

    private static UUID actor(CompoundTag d)
    {
        try
        {
            return UUID.fromString(d.getStringOr("actor", ""));
        }
        catch (IllegalArgumentException none)
        {
            return new UUID(0, 0);
        }
    }

    /** Starts joining a clamped visitor to its berth, unless a move is already under way or the last try failed just now. */
    static void requestJoin(MinecraftServer server, ServerLevel level, CompoundTag own, CompoundTag host)
    {
        var d = own.getCompoundOrEmpty("dock");
        if (TransferLocks.busy(server, own) || server.overworld().getGameTime() < d.getLongOr("joinRetryAt", 0))
            return;
        String id = DockingService.id(own), hostId = DockingService.id(host);
        var move = new DockMove("dock", scan ->
        {
            var visitor = DockingService.find(server, id);
            var berth = DockingService.find(server, hostId);
            if (visitor == null || berth == null)
                throw new IllegalArgumentException("The berth is gone");
            var dock = visitor.getCompoundOrEmpty("dock");
            var both = ports(level, visitor, berth, dock);
            return placement(level, visitor, both[0], berth, both[1], (int) Math.round(dock.getDoubleOr("gap", 0)), scan.bounds());
        }, (next, transform) -> joined(level, next, hostId, transform), failure -> joinFailed(server, id, failure));
        String waiting = TravelService.requestDock(level, own, actor(d), move);
        if (waiting != null)
        {
            d.putLong("joinRetryAt", server.overworld().getGameTime() + 20);
            DockingManeuver.reportFlight(server, own, "Docked: " + waiting + " before joining the docking arm", false);
        }
    }

    /** The joined visitor's record: its port where its blocks now are, the berth's orientation, and a way out straight back from the berth. */
    private static void joined(ServerLevel level, CompoundTag next, String hostId, ShipTransform transform)
    {
        var host = DockingService.find(level.getServer(), hostId);
        var d = next.getCompoundOrEmpty("dock");
        d.putBoolean("joined", true);
        d.putLong("sourcePort", transform.local(WorldStore.pos(d, "sourcePort")).asLong());
        d.remove("tunnel");
        d.remove("tunnelRetracted");
        var pose = SceneService.pose(host);
        var position = pose.toUniverse(Vec3.atLowerCornerOf(WorldStore.pos(next, "base").subtract(WorldStore.pos(host, "base"))));
        DockingManeuver.pose(next, new ScenePose(position, pose.orientation()));
        var berth = DockingPorts.readPort(level, host, WorldStore.pos(host, "base").offset(WorldStore.pos(d, "targetPort")), false);
        DockingManeuver.saveEscape(d, position.add(FlightDynamics.rotate(pose.orientation(), DockingMotion.normal(berth.facing())).scale(112)));
    }

    private static void joinFailed(MinecraftServer server, String id, String failure)
    {
        var own = DockingService.find(server, id);
        if (own == null)
            return;
        var d = own.getCompoundOrEmpty("dock");
        int attempts = d.getIntOr("joinAttempts", 0) + 1;
        d.putInt("joinAttempts", attempts);
        d.putLong("joinRetryAt", server.overworld().getGameTime() + 40);
        if (attempts >= ATTEMPTS)
        {
            // Clamped and safe, but apart: boarding goes through the door, as for older docks.
            d.putBoolean("join", false);
            DockingManeuver.reportFlight(server, own, "Docked, but the hulls could not be joined (" + failure + "). Walk through the docking door to board.", true);
        }
        WorldStore.get(server).requestSave();
    }

    /** Starts moving a joined visitor to a free area of its own, before it backs away. */
    static void requestSeparation(MinecraftServer server, ServerLevel level, CompoundTag own)
    {
        var d = own.getCompoundOrEmpty("dock");
        if (TransferLocks.busy(server, own) || server.overworld().getGameTime() < d.getLongOr("joinRetryAt", 0))
            return;
        String id = DockingService.id(own);
        var move = new DockMove("undock", scan ->
        {
            int slot = 0;
            while (slot <= 7000 && !TransferLocks.spaceSlotFree(server, slot))
                slot++;
            if (slot > 7000)
                throw new IllegalArgumentException("No free physical ship regions remain");
            var visitor = DockingService.find(server, id);
            var target = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
            return new DockMove.Placement(new ShipTransform(WorldStore.pos(visitor, "base"), target, visitor.getIntOr("width", 32), visitor.getIntOr("depth", 32), 0), 32, 32, 32);
        }, (next, transform) ->
        {
            var dock = next.getCompoundOrEmpty("dock");
            dock.putBoolean("joined", false);
            dock.remove("tunnel");
            dock.remove("tunnelRetracted");
        }, failure -> separationFailed(server, id, failure));
        String waiting = TravelService.requestDock(level, own, actor(d), move);
        if (waiting != null)
        {
            d.putLong("joinRetryAt", server.overworld().getGameTime() + 20);
            DockingManeuver.reportFlight(server, own, "Undocking: " + waiting, false);
        }
    }

    private static void separationFailed(MinecraftServer server, String id, String failure)
    {
        var own = DockingService.find(server, id);
        if (own == null)
            return;
        var d = own.getCompoundOrEmpty("dock");
        d.putString("state", "CLAMPED");
        d.remove("then");
        d.remove("releaseSound");
        DockingManeuver.reportFlight(server, own, "Undocking stopped: " + failure + ". Press Undock to try again.", true);
        WorldStore.get(server).requestSave();
    }

    /** A visitor whose blocks stand at its berth. */
    public static boolean joined(CompoundTag ship)
    {
        var d = ship.getCompoundOrEmpty("dock");
        return d.getBooleanOr("joined", false) && d.getStringOr("source", "").equals(DockingService.id(ship));
    }

    /**
     * The vessels whose blocks stand together with {@code ship}'s: its host and every visitor joined to that host, including {@code ship}. A host never docks
     * elsewhere and a visitor hosts nobody, so this is the host and its joined visitors.
     */
    public static List<CompoundTag> group(MinecraftServer server, CompoundTag ship)
    {
        var host = joined(ship) ? DockingService.find(server, ship.getCompoundOrEmpty("dock").getStringOr("target", "")) : ship;
        if (host == null)
            return List.of(ship);
        var out = new ArrayList<CompoundTag>();
        out.add(host);
        for (var tag : WorldStore.get(server).ships())
        {
            var other = (CompoundTag) tag;
            if (other != host && joined(other) && other.getCompoundOrEmpty("dock").getStringOr("target", "").equals(DockingService.id(host)))
                out.add(other);
        }
        return out;
    }

    /**
     * Walking from one joined vessel into another makes the player its crew, as boarding through a door does: their air, tether and return journey follow
     * the vessel they stand in. A crew member remembers the ship they came from, so the visitor cannot undock and leave them behind.
     */
    static void crossings(MinecraftServer server)
    {
        var level = server.getLevel(TravelService.SPACE);
        if (level == null)
            return;
        var store = WorldStore.get(server);
        for (var p : List.copyOf(level.players()))
        {
            if (!p.isAlive() || p.isSpectator() || TransferLocks.busy(p))
                continue;
            var here = store.at(level, p.blockPosition());
            if (here == null)
                continue;
            var group = group(server, here);
            if (group.size() < 2)
                continue;
            String player = p.getUUID().toString();
            for (var vessel : group)
                if (vessel != here && vessel.getListOrEmpty("crew").stream().anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(player)))
                {
                    Boarding.crew(p, vessel, here, false);
                    store.requestSave();
                    break;
                }
        }
    }

    private DockJoin()
    {
    }
}
