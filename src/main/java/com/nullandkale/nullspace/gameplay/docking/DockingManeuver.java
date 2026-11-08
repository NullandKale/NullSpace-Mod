package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts.Port;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationSpace;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

import org.joml.Vector3d;

/**
 * The docking autopilot: reserving a berth, approaching without collisions, capture, undocking and
 * escape.
 */
public final class DockingManeuver
{

    static final ServerContext.Key<Map<String, List<Vec3>>> ROUTES = new ServerContext.Key<>();

    public record Readiness(boolean ready, String message)
    {}

    public static Readiness readiness(ServerPlayer p, CompoundTag s)
    {
        if (JourneyService.cruising(s))
            return new Readiness(false, "FTL underway: investigate a signal before docking");
        if (!DockingService.mobile(s) || s.contains("dock") || !p.level().dimension().equals(TravelService.SPACE) || TransferLocks.busy(p) || TransferLocks.busy(p.level().getServer(), s) || !StationAccess.ready(s))
            return new Readiness(false, "Finish docking, construction or travel before starting another maneuver");
        if (!ShipSystems.enabled(s))
            return new Readiness(false, "This ship has no enabled power/air systems record. Open Systems > Upgrade systems.");
        if (FuelSystem.available(p.level(), s) == 0)
            return new Readiness(false, "No shared fuel. Load furnace fuel into a connected generator.");
        if (NavigationService.propulsion(p.level(), s) == 0)
            return new Readiness(false, "Controls and propulsion must have power");
        var bounds = WorldStore.bounds(s);
        var sealed = new HullScan.LivePressure(p.level(), bounds);
        for (var crew : p.level().players())
            if (crew.isAlive() && SceneService.observer(crew) == s && !DockingService.exempt(crew))
            {
                var eye = BlockPos.containing(crew.getEyePosition());
                String who = crew.getPlainTextName() + " at " + eye.toShortString();
                if (!bounds.contains(eye) || !sealed.breathable(eye))
                    return new Readiness(false, who + " is outside a sealed cabin. Return inside, close exterior doors and check the" + " hull seal.");
                if (!ShipAir.breathable(p.level(), s, eye))
                {
                    var local = eye.subtract(WorldStore.pos(s, "base"));
                    int oxygen = ShipAir.pressure(p.level(), s, local.getX(), local.getY(), local.getZ()) * 100 / Atmosphere.FULL;
                    return new Readiness(false, who + " is aboard, but cabin oxygen is " + oxygen + "% (25% needed)." + " Check air power and the supply outlet, then let the cabin refill.");
                }
            }
        return new Readiness(true, "Ready to dock");
    }

    public static void requireFree(ServerPlayer p, CompoundTag s)
    {
        var result = readiness(p, s);
        if (!result.ready())
            throw new IllegalArgumentException(result.message());
    }

    static Vec3 localFace(CompoundTag s, BlockPos controller, Direction direction)
    {
        long position = controller.subtract(WorldStore.pos(s, "base")).asLong();
        boolean compact = s.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream().map(t -> (CompoundTag) t).anyMatch(d -> d.getLongOr("position", Long.MIN_VALUE) == position && d.getStringOr("type", "").equals("nullspace:docking_base"));
        return Vec3.atCenterOf(controller.subtract(WorldStore.pos(s, "base"))).add(0, compact ? 1 : -2, 0).add(DockingMotion.normal(direction).scale(.5));
    }

    static void reserveTarget(MinecraftServer server, CompoundTag source, CompoundTag target, Port port, CompoundTag d)
    {
        var links = target.getCompoundOrEmpty("dockLinks");
        if (links.contains(port.id()))
            throw new IllegalArgumentException("Berth was reserved by another ship; refresh contacts");
        var marker = new CompoundTag();
        marker.putString("source", DockingService.id(source));
        links.put(port.id(), marker);
        target.put("dockLinks", links);
        if (DockingService.mobile(target))
        {
            var mirror = new CompoundTag();
            mirror.putString("source", DockingService.id(source));
            mirror.putString("state", "REQUESTED");
            target.put("dock", mirror);
            NavigationService.closeShip(server, DockingService.id(target));
        }
    }

    public static Readiness request(ServerPlayer p, CompoundTag own, String targetId, BlockPos targetPort)
    {
        var readiness = readiness(p, own);
        if (!readiness.ready())
            return readiness;
        var server = p.level().getServer();
        var target = DockingService.find(server, targetId);
        if (target == null || target == own || !LocalSpaces.together(own, target) || !StationAccess.allowed(p, target) || !StationAccess.ready(target) || TransferLocks.inTransit(server, target) || target.contains("dock"))
            throw new IllegalArgumentException("Target berth is unavailable");
        if (new Vec3(target.getDoubleOr("simVX", 0), target.getDoubleOr("simVY", 0), target.getDoubleOr("simVZ", 0)).length() > .01)
            throw new IllegalArgumentException("Target ship must stop before accepting docking");
        DockingService.chunks(p.level(), target);
        var sourcePort = DockingPorts.firstPort(p.level(), own);
        var destination = DockingPorts.readPort(p.level(), target, WorldStore.pos(target, "base").offset(targetPort), true);
        for (var endpoint : List.of(Map.entry(own, sourcePort), Map.entry(target, destination)))
        {
            var blocked = StationConstruction.doorwayObstruction(p.level(), endpoint.getKey(), endpoint.getValue());
            if (blocked.isPresent())
                return new Readiness(false, "Dock doorway is blocked at " + blocked.get().toShortString() + ". Keep a two-block-high passage clear in front of the door.");
        }
        double gap = StationConstruction.protrusion(p.level(), own, sourcePort) + StationConstruction.protrusion(p.level(), target, destination) + .05;
        if (gap > 4.05)
            return new Readiness(false, "The docking door is recessed more than four blocks behind the hull. Move the Docking" + " Base outward or clear blocks in front of it.");
        var d = new CompoundTag();
        d.putDouble("gap", gap);
        d.putString("source", DockingService.id(own));
        d.putString("target", DockingService.id(target));
        d.putLong("sourcePort", sourcePort.controller().subtract(WorldStore.pos(own, "base")).asLong());
        d.putLong("targetPort", targetPort.asLong());
        d.putString("sourceNode", sourcePort.id());
        d.putString("targetNode", destination.id());
        d.putString("actor", p.getUUID().toString());
        d.putString("state", StationAccess.station(target) ? "APPROACHING" : "WAITING_CONSENT");
        d.putLong("expires", server.overworld().getGameTime() + 12000);
        reserveTarget(server, own, target, destination, d);
        own.put("dock", d);
        reportFlight(server, own, d.getStringOr("state", ""), false);
        NavigationService.closeShip(server, DockingService.id(own));
        Piloting.stop(own);
        WorldStore.get(server).save();
        return new Readiness(true, "Docking requested");
    }

    static List<DockingMotion.Obstacle> obstacles(MinecraftServer server, CompoundTag own, boolean targetToo)
    {
        var out = new ArrayList<DockingMotion.Obstacle>();
        String target = own.getCompoundOrEmpty("dock").getStringOr("target", "");
        for (var tag : WorldStore.get(server).ships())
        {
            var s = (CompoundTag) tag;
            if (s == own || !s.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()) || !LocalSpaces.together(own, s) || !targetToo && DockingService.id(s).equals(target))
                continue;
            out.add(new DockingMotion.Obstacle(SceneService.pose(s).toUniverse(center(s)), StationSpace.radius(s)));
        }
        var definition = SpaceDefinitionCodec.decode(SceneCatalog.forShip(server, own));
        double time = server.overworld().getGameTime() / 20. - definition.epoch();
        for (var body : definition.bodies())
            out.add(new DockingMotion.Obstacle(body.at(time), body.radius() * (1 + body.roughness())));
        return out;
    }

    static Vec3 center(CompoundTag s)
    {
        return new Vec3(s.getIntOr("width", 32) / 2., s.getIntOr("height", 32) / 2., s.getIntOr("depth", 32) / 2.);
    }

    static ScenePose matingPose(CompoundTag own, Port source, CompoundTag target, Port destination, double gap)
    {
        return DockingMotion.mate(localFace(own, source.controller(), source.facing()), source.facing(), SceneService.pose(target), localFace(target, destination.controller(), destination.facing()), destination.facing(), gap);
    }

    static boolean intersects(ServerLevel level, CompoundTag own, ScenePose pose, CompoundTag target)
    {
        return intersects(level, own, pose, target, null);
    }

    static AABB hullBox(BlockPos local, ScenePose pose, ScenePose targetPose)
    {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int x = 0; x <= 1; x++)
            for (int y = 0; y <= 1; y++)
                for (int z = 0; z <= 1; z++)
                {
                    var v = targetPose.toLocal(pose.toUniverse(new Vec3(local.getX() + x, local.getY() + y, local.getZ() + z)));
                    minX = Math.min(minX, v.x);
                    minY = Math.min(minY, v.y);
                    minZ = Math.min(minZ, v.z);
                    maxX = Math.max(maxX, v.x);
                    maxY = Math.max(maxY, v.y);
                    maxZ = Math.max(maxZ, v.z);
                }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static double overlap(AABB box, int x, int y, int z)
    {
        return Math.max(0, Math.min(box.maxX, x + 1) - Math.max(box.minX, x)) * Math.max(0, Math.min(box.maxY, y + 1) - Math.max(box.minY, y)) * Math.max(0, Math.min(box.maxZ, z + 1) - Math.max(box.minZ, z));
    }

    /** During recovery, existing overlaps may shrink; no voxel may enter a new obstruction. */
    static boolean intersects(ServerLevel level, CompoundTag own, ScenePose pose, CompoundTag target, ScenePose retreatFrom)
    {
        var targetPose = SceneService.pose(target);
        var targetBase = WorldStore.pos(target, "base");
        for (var block : ShipSystems.connected(level, own))
        {
            if (level.getBlockState(block).isAir())
                continue;
            var local = block.subtract(WorldStore.pos(own, "base"));
            var box = hullBox(local, pose, targetPose);
            double minX = box.minX, minY = box.minY, minZ = box.minZ;
            double maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ;
            AABB previous = null;
            if (maxX <= 0 || maxY <= 0 || maxZ <= 0 || minX >= target.getIntOr("width", 32) || minY >= target.getIntOr("height", 32) || minZ >= target.getIntOr("depth", 32))
                continue;
            for (int x = Math.max(0, (int) Math.floor(minX + 1e-6)); x <= Math.min(target.getIntOr("width", 32) - 1, (int) Math.floor(maxX - 1e-6)); x++)
                for (int y = Math.max(0, (int) Math.floor(minY + 1e-6)); y <= Math.min(target.getIntOr("height", 32) - 1, (int) Math.floor(maxY - 1e-6)); y++)
                    for (int z = Math.max(0, (int) Math.floor(minZ + 1e-6)); z <= Math.min(target.getIntOr("depth", 32) - 1, (int) Math.floor(maxZ - 1e-6)); z++)
                        if (!level.getBlockState(targetBase.offset(x, y, z)).isAir())
                        {
                            if (retreatFrom == null)
                                return true;
                            if (previous == null)
                                previous = hullBox(local, retreatFrom, targetPose);
                            if (overlap(box, x, y, z) > overlap(previous, x, y, z) + 1e-7)
                                return true;
                        }
        }
        return false;
    }

    static void pose(CompoundTag s, ScenePose p)
    {
        var angles = p.orientation().getEulerAnglesYXZ(new Vector3d());
        s.putDouble("simX", p.position().x);
        s.putDouble("simY", p.position().y);
        s.putDouble("simZ", p.position().z);
        s.putDouble("simYaw", Math.toDegrees(angles.y));
        s.putDouble("simPitch", Math.toDegrees(angles.x));
        s.putDouble("simRoll", Math.toDegrees(angles.z));
        Piloting.stop(s);
    }

    static boolean nearHull(ServerLevel level, CompoundTag own, ScenePose next)
    {
        return nearHull(level, own, next, null);
    }

    static boolean nearHull(ServerLevel level, CompoundTag own, ScenePose next, ScenePose retreatFrom)
    {
        var center = next.toUniverse(center(own));
        for (var tag : WorldStore.get(level.getServer()).ships())
        {
            var other = (CompoundTag) tag;
            if (other == own || !other.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) || !LocalSpaces.together(own, other))
                continue;
            if (center.distanceToSqr(SceneService.pose(other).toUniverse(center(other))) < Math.pow(StationSpace.radius(own) + StationSpace.radius(other) + 8, 2) && intersects(level, own, next, other, retreatFrom))
                return true;
        }
        return false;
    }

    static void saveEscape(CompoundTag dock, Vec3 position)
    {
        dock.putDouble("escapeX", position.x);
        dock.putDouble("escapeY", position.y);
        dock.putDouble("escapeZ", position.z);
    }

    static boolean savedEscape(CompoundTag dock)
    {
        return dock.contains("escapeX") && dock.contains("escapeY") && dock.contains("escapeZ") && Double.isFinite(dock.getDoubleOr("escapeX", Double.NaN)) && Double.isFinite(dock.getDoubleOr("escapeY", Double.NaN)) && Double.isFinite(dock.getDoubleOr("escapeZ", Double.NaN));
    }

    static void recoverEscape(ServerLevel level, CompoundTag source, CompoundTag target, CompoundTag dock)
    {
        if (savedEscape(dock))
            return;
        Vec3 normal = null;
        try
        {
            var port = DockingPorts.readPort(level, source, WorldStore.pos(source, "base").offset(WorldStore.pos(dock, "sourcePort")), false);
            normal = FlightDynamics.rotate(SceneService.pose(source).orientation(), DockingMotion.normal(port.facing())).scale(-1);
        }
        catch (IllegalArgumentException damagedSource)
        {
            if (target != null)
            {
                try
                {
                    var port = DockingPorts.readPort(level, target, WorldStore.pos(target, "base").offset(WorldStore.pos(dock, "targetPort")), false);
                    normal = FlightDynamics.rotate(SceneService.pose(target).orientation(), DockingMotion.normal(port.facing()));
                }
                catch (IllegalArgumentException damagedTarget)
                {
                }
            }
        }
        if (normal == null)
            throw new IllegalArgumentException("This older connection has no saved departure route. Repair either docking doorway in its" + " original facing, then press Undock again.");
        saveEscape(dock, SceneService.pose(source).position().add(normal.scale(112)));
    }

    static void depart(MinecraftServer server, ServerLevel level, CompoundTag own, CompoundTag d)
    {
        if (!savedEscape(d))
        {
            reportFlight(server, own, "No saved departure route; repair the docking doorway before trying again", true);
            return;
        }
        if (NavigationService.propulsion(level, own) == 0)
        {
            own.putInt("thrustDemand", 0);
            reportFlight(server, own, "Undocking paused: restore your ship's controls and propulsion power", true);
            return;
        }
        var old = SceneService.pose(own);
        var escape = new Vec3(d.getDoubleOr("escapeX", 0), d.getDoubleOr("escapeY", 0), d.getDoubleOr("escapeZ", 0));
        var next = DockingMotion.step(old, new ScenePose(escape, old.orientation()), .35, 0);
        // Use the path saved at capture, rather than recomputing it from a changed or demolished
        // station port. Legacy construction overlaps can shrink, but new obstructions still stop us.
        DockingTunnel.retract(server, d);
        if (nearHull(level, own, next, old) || !DockingMotion.clear(old.toUniverse(center(own)), next.toUniverse(center(own)), obstacles(server, own, false), 29))
        {
            own.putInt("thrustDemand", 0);
            reportFlight(server, own, "Undocking paused: clear the departure corridor; it will resume automatically", true);
            return;
        }
        own.putInt("thrustDemand", NavigationService.propulsion(level, own));
        if (!FuelSystem.spend(level, own, FuelSystem.travelCost(level, own, 2)))
        {
            own.putInt("thrustDemand", 0);
            reportFlight(server, own, "Undocking paused: load furnace fuel into your ship's generator", true);
            return;
        }
        pose(own, next);
        if (next.position().distanceTo(escape) < .001)
            release(server, own, "Undocked safely");
        else
            reportFlight(server, own, "UNDOCKING", false);
    }

    /** Report transitions once, including failures after the controller has been closed. */
    static void reportFlight(MinecraftServer server, CompoundTag own, String message, boolean blocked)
    {
        if (message.equals(own.getStringOr("flightStatus", "")))
            return;
        var dock = own.getCompoundOrEmpty("dock");
        var target = DockingService.find(server, dock.getStringOr("target", ""));
        var details = new Object[]{blocked ? "stopped" : "status", message, own.getStringOr("home", "Ship"), DockingService.id(own), target == null ? dock.getStringOr("target", "unavailable") : target.getStringOr("home", "Ship"), WorldStore.pos(dock, "targetPort").toShortString(), dock.getStringOr("state", ""), dock.getStringOr("actor", "")};
        String format = "Docking {}: {} | ship={} ({}) target={} port={} stage={} pilot={}";
        if (blocked)
            NullSpace.LOGGER.warn(format, details);
        else
            NullSpace.LOGGER.info(format, details);
        String text = switch (message)
        {
            case "APPROACHING" -> "Docking: approaching the selected port.";
            case "ALIGNING" -> "Docking: aligning with the selected port.";
            case "CONNECTING" -> "Docking: making the final approach.";
            case "CLAMPED" -> "Docked. Open the dock door and walk through.";
            case "UNDOCKING" -> "Undocking: backing away from the port.";
            case "WAITING_CONSENT" -> "Docking: waiting for the other ship to accept.";
            default -> "Docking " + (blocked ? "stopped: " : "") + message;
        };
        for (var player : server.getPlayerList().getPlayers())
            if (player.getUUID().toString().equals(dock.getStringOr("actor", "")))
                player.sendSystemMessage(Component.literal(text));
        own.putString("flightStatus", message);
    }

    static void release(MinecraftServer server, CompoundTag own, String message)
    {
        release(server, own, message, false);
    }

    static void release(MinecraftServer server, CompoundTag own, String message, boolean blocked)
    {
        reportFlight(server, own, message, blocked);
        var d = own.getCompoundOrEmpty("dock");
        if (d.contains("tunnel"))
        {
            DockingTunnel.retract(server, d);
            // Flush removed collar blocks while their recovery plan is still in the saved link.
            server.saveEverything(false, true, true);
        }
        var target = DockingService.find(server, d.getStringOr("target", ""));
        if (target != null)
        {
            target.getCompoundOrEmpty("dockLinks").remove(d.getStringOr("targetNode", ""));
            if (DockingService.mobile(target))
                target.remove("dock");
        }
        own.remove("dock");
        Piloting.stop(own);
        ServerContext.get(server).service(ROUTES, HashMap::new).remove(DockingService.id(own));
        WorldStore.get(server).save();
    }

    static void tickMotion(MinecraftServer server, CompoundTag own)
    {
        var d = own.getCompoundOrEmpty("dock");
        if (!d.getStringOr("source", "").equals(DockingService.id(own)))
            return;
        var target = DockingService.find(server, d.getStringOr("target", ""));
        String phase = d.getStringOr("state", "");
        if (target == null)
        {
            release(server, own, "Target unavailable; maneuver cancelled", true);
            return;
        }
        if (!StationAccess.ready(target))
        {
            reportFlight(server, own, "Waiting for destination station construction to finish", false);
            Piloting.stop(own);
            return;
        }
        var level = WorldStore.level(server, own.getStringOr("dimension", ""));
        DockingService.chunks(level, own);
        DockingService.chunks(level, target);
        if (phase.equals("EMERGENCY_BACKOFF") || phase.equals("UNDOCKING"))
        {
            depart(server, level, own, d);
            return;
        }
        try
        {
            var source = DockingPorts.readPort(level, own, WorldStore.pos(own, "base").offset(WorldStore.pos(d, "sourcePort")), !phase.equals("CLAMPED") && !phase.equals("UNDOCKING"));
            if (!source.id().equals(d.getStringOr("sourceNode", "")))
                throw new IllegalArgumentException("Docking controller was replaced");
            Port destination = null;
            Direction face = Direction.WEST;
            if (!d.getBooleanOr("construction", false))
            {
                destination = DockingPorts.readPort(level, target, WorldStore.pos(target, "base").offset(WorldStore.pos(d, "targetPort")), !phase.equals("CLAMPED") && !phase.equals("UNDOCKING"));
                face = destination.facing();
                if (!destination.id().equals(d.getStringOr("targetNode", "")))
                    throw new IllegalArgumentException("Destination port was replaced");
            }
            if (phase.equals("CLAMPED"))
            {
                if (d.getBooleanOr("construction", false))
                {
                    StationConstruction.commissionConstruction(server, target);
                    if (!d.getBooleanOr("construction", false))
                        return;
                }
                DockingTunnel.ensure(server, d, source, destination);
                own.putInt("thrustDemand", 0);
                return;
            }
            if (phase.equals("UNDOCKING"))
                DockingTunnel.retract(server, d);
            own.putInt("thrustDemand", NavigationService.propulsion(level, own));
            if (!FuelSystem.spend(level, own, FuelSystem.travelCost(level, own, 2)))
                throw new IllegalArgumentException("Shared fuel exhausted during automatic docking; load the generator");
            if (server.overworld().getGameTime() > d.getLongOr("expires", 0))
                throw new IllegalArgumentException("Docking request timed out");
            var actor = server.getPlayerList().getPlayer(UUID.fromString(d.getStringOr("actor", "")));
            if (actor == null)
                throw new IllegalArgumentException("Pilot disconnected before capture");
            if (!StationAccess.allowed(actor, target))
                throw new IllegalArgumentException("Station permission changed");
            if (phase.equals("WAITING_CONSENT"))
                return;
            if (NavigationService.propulsion(level, own) == 0)
                throw new IllegalArgumentException("Docking stopped: propulsion has no power");
            var sourceFace = localFace(own, source.controller(), source.facing());
            var targetFace = localFace(target, WorldStore.pos(target, "base").offset(WorldStore.pos(d, "targetPort")), face);
            var goal = DockingMotion.mate(sourceFace, source.facing(), SceneService.pose(target), targetFace, face, d.getDoubleOr("gap", .05));
            var normal = FlightDynamics.rotate(SceneService.pose(target).orientation(), DockingMotion.normal(face));
            var standOff = new ScenePose(goal.position().add(normal.scale(112)), goal.orientation());
            var old = SceneService.pose(own);
            ScenePose next;
            if (phase.equals("APPROACHING"))
            {
                var routes = ServerContext.get(server).service(ROUTES, HashMap::new);
                var path = routes.get(DockingService.id(own));
                if (path == null)
                {
                    path = new ArrayList<>(DockingMotion.route(old.toUniverse(new Vec3(16, 16, 16)), standOff.toUniverse(new Vec3(16, 16, 16)), obstacles(server, own, true)));
                    routes.put(DockingService.id(own), path);
                }
                if (path.isEmpty())
                {
                    d.putString("state", "ALIGNING");
                    reportFlight(server, own, "ALIGNING", false);
                    return;
                }
                var center = old.toUniverse(new Vec3(16, 16, 16));
                var delta = path.getFirst().subtract(center);
                var shift = delta.length() <= .8 ? delta : delta.normalize().scale(.8);
                if (!DockingMotion.clear(center, center.add(shift), obstacles(server, own, true), 29))
                    throw new IllegalArgumentException("Automatic approach became obstructed");
                next = new ScenePose(old.position().add(shift), old.orientation());
                if (delta.length() <= .8)
                    path.removeFirst();
            }
            else if (phase.equals("ALIGNING"))
            {
                // Rotate around the center at a safe stand-off, then translate along the port normal.
                var center = old.toUniverse(new Vec3(16, 16, 16));
                var q = DockingMotion.step(old, new ScenePose(old.position(), goal.orientation()), 0, Math.toRadians(2)).orientation();
                next = new ScenePose(center.subtract(FlightDynamics.rotate(q, new Vec3(16, 16, 16))), q);
                if (Math.abs(q.dot(goal.orientation())) > .999999)
                {
                    pose(own, standOff);
                    d.putString("state", "CONNECTING");
                    reportFlight(server, own, "CONNECTING", false);
                    return;
                }
            }
            else if (phase.equals("CONNECTING"))
            {
                next = DockingMotion.step(old, goal, .35, Math.toRadians(1));
                if (nearHull(level, own, next))
                    throw new IllegalArgumentException("A nearby hull obstructs final approach");
                if (intersects(level, own, next, target))
                    throw new IllegalArgumentException("Hull or connector blocks obstruct final approach");
                if (next.position().distanceTo(goal.position()) < .001)
                {
                    DockingPorts.close(level, source);
                    if (destination != null)
                        DockingPorts.close(level, destination);
                    pose(own, next);
                    d.putString("state", "CLAMPED");
                    saveEscape(d, standOff.position());
                    WorldStore.get(server).save();
                }
            }
            else
                throw new IllegalArgumentException("Unknown docking stage");
            if (!DockingMotion.clear(old.toUniverse(new Vec3(16, 16, 16)), next.toUniverse(new Vec3(16, 16, 16)), obstacles(server, own, false), 29))
                throw new IllegalArgumentException("Docking route intersects another object");
            if ((phase.equals("APPROACHING") || phase.equals("ALIGNING")) && nearHull(level, own, next))
                throw new IllegalArgumentException("A nearby hull obstructs the automatic maneuver");
            pose(own, next);
            reportFlight(server, own, d.getStringOr("state", phase), false);
        }
        catch (IllegalArgumentException unavailable)
        {
            if (phase.equals("CLAMPED") || phase.equals("UNDOCKING"))
            {
                d.putString("state", "CLAMPED");
                reportFlight(server, own, unavailable.getMessage() + ". Use Undock at your ship's controller to leave the damaged berth.", true);
                Piloting.stop(own);
            }
            else
                release(server, own, unavailable.getMessage(), true);
        }
    }

    public static double sceneDistance(CompoundTag own, CompoundTag other)
    {
        if (own == other)
            return -2;
        var d = own.getCompoundOrEmpty("dock");
        if (d.getStringOr("target", "").equals(DockingService.id(other)) || d.getStringOr("source", "").equals(DockingService.id(other)))
            return -1;
        for (var key : own.getCompoundOrEmpty("dockLinks").keySet())
            if (own.getCompoundOrEmpty("dockLinks").getCompoundOrEmpty(key).getStringOr("source", "").equals(DockingService.id(other)))
                return -1;
        return SceneService.pose(own).position().distanceToSqr(SceneService.pose(other).position());
    }

    private DockingManeuver()
    {
    }
}
