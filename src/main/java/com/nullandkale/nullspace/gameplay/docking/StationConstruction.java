package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.crew.LifeSupport;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts.Port;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.space.ScenePose;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/**
 * Building a station from a docked ship: the construction berth, build checks and construction air.
 */
public final class StationConstruction
{

    public static void commissionConstruction(MinecraftServer server, CompoundTag station)
    {
        var source = DockingService.find(server, station.getStringOr("constructionShip", ""));
        if (source == null)
            return;
        var d = source.getCompoundOrEmpty("dock");
        if (!d.getBooleanOr("construction", false) || !d.getStringOr("state", "").equals("CLAMPED"))
            return;
        if (Boarding.boarding(server, DockingService.id(source)) || Boarding.boarding(server, DockingService.id(station)))
            return;
        try
        {
            var level = server.getLevel(TravelService.SPACE);
            var port = DockingPorts.ports(level, station).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Finish a station docking doorway before commissioning"));
            var sourcePort = DockingPorts.readPort(level, source, WorldStore.pos(source, "base").offset(WorldStore.pos(d, "sourcePort")), false);
            double gap = protrusion(level, source, sourcePort) + protrusion(level, station, port) + .05;
            if (gap > 4.05)
            {
                DockingManeuver.reportFlight(server, source, "Move the finished station dock outward; its hull recess exceeds four blocks", true);
                return;
            }
            var aligned = DockingManeuver.matingPose(source, sourcePort, station, port, gap);
            if (DockingManeuver.nearHull(level, source, aligned))
            {
                DockingManeuver.reportFlight(server, source, "Clear hulls around the finished station dock before connecting it", true);
                return;
            }
            // A construction platform has no hull around its virtual port. The finished room does.
            // Recompute separation before publishing the real berth; retaining the platform gap embeds
            // recessed ship noses in the new station wall and traps their departure.
            DockingManeuver.pose(source, aligned);
            d.putDouble("gap", gap);
            DockingManeuver.saveEscape(d, aligned.position().add(FlightDynamics.rotate(SceneService.pose(station).orientation(), DockingMotion.normal(port.facing())).scale(112)));
            d.putLong("targetPort", port.controller().subtract(WorldStore.pos(station, "base")).asLong());
            var marker = station.getCompoundOrEmpty("dockLinks").getCompoundOrEmpty("construction");
            station.getCompoundOrEmpty("dockLinks").remove("construction");
            station.getCompoundOrEmpty("dockLinks").put(port.id(), marker);
            d.putString("targetNode", port.id());
            d.putBoolean("construction", false);
            WorldStore.get(server).requestSave();
        }
        catch (IllegalArgumentException notReady)
        {
        }
    }

    public static void connectConstruction(MinecraftServer server, CompoundTag station)
    {
        var source = DockingService.find(server, station.getStringOr("constructionShip", ""));
        if (source == null || source.contains("dock"))
            return;
        var level = server.getLevel(TravelService.SPACE);
        var d = new CompoundTag();
        d.putString("source", DockingService.id(source));
        d.putString("target", DockingService.id(station));
        d.putLong("sourcePort", station.getLongOr("constructionSourcePort", 0));
        d.putLong("targetPort", new BlockPos(0, 5, 8).asLong());
        d.putString("targetNode", "construction");
        d.putBoolean("construction", true);
        var port = DockingPorts.readPort(level, source, WorldStore.pos(source, "base").offset(WorldStore.pos(d, "sourcePort")), true);
        d.putString("sourceNode", port.id());
        d.putString("actor", station.getStringOr("owner", ""));
        d.putString("state", "APPROACHING");
        d.putLong("expires", server.overworld().getGameTime() + 12000);
        source.put("dock", d);
        var links = new CompoundTag();
        var marker = new CompoundTag();
        marker.putString("source", DockingService.id(source));
        links.put("construction", marker);
        station.put("dockLinks", links);
        NavigationService.closeShip(server, DockingService.id(source));
        Piloting.stop(source);
        WorldStore.get(server).requestSave();
    }

    public static Vec3 constructionSite(MinecraftServer server, CompoundTag own)
    {
        var center = SceneService.pose(own).toUniverse(new Vec3(16, 16, 16));
        var obstacles = DockingManeuver.obstacles(server, own, true);
        obstacles.add(new DockingMotion.Obstacle(center, 28));
        for (int distance : new int[]{224, 320, 448})
            for (var direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN))
            {
                var origin = SceneService.pose(own).position().add(DockingMotion.normal(direction).scale(distance));
                var candidate = origin.add(16, 16, 16);
                if (DockingMotion.clear(candidate, candidate, obstacles, 36))
                    return origin;
            }
        throw new IllegalArgumentException("No clear station construction site nearby; move into open space");
    }

    /** Conservative voxel occupancy during close approach. Rotating blocks check their whole AABB. */
    static double protrusion(ServerLevel level, CompoundTag vessel, Port port)
    {
        var base = WorldStore.pos(vessel, "base");
        var normal = DockingMotion.normal(port.facing());
        var plane = DockingManeuver.localFace(vessel, port.controller(), port.facing());
        double reach = 0;
        for (var block : ShipSystems.connected(level, vessel))
        {
            double front = normal.dot(Vec3.atLowerCornerOf(block.subtract(base))) + (port.facing().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
            reach = Math.max(reach, front - normal.dot(plane));
        }
        return reach;
    }

    public record BuildProblem(BlockPos position, String message)
    {}

    /**
     * Reject a module before payment or placement if it would build through a visiting ship's exit.
     */
    public static Optional<BuildProblem> constructionProblem(ServerLevel level, CompoundTag station, Map<BlockPos, net.minecraft.world.level.block.state.BlockState> plan)
    {
        var reserved = new HashMap<BlockPos, String>();
        var stationPose = SceneService.pose(station);
        for (var tag : WorldStore.get(level.getServer()).ships())
        {
            var visitor = (CompoundTag) tag;
            var dock = visitor.getCompoundOrEmpty("dock");
            if (!dock.getStringOr("source", "").equals(DockingService.id(visitor)) || !dock.getStringOr("target", "").equals(DockingService.id(station)) || !dock.getStringOr("state", "").equals("CLAMPED") || dock.getBooleanOr("construction", false))
                continue;
            var from = SceneService.pose(visitor);
            var to = DockingManeuver.savedEscape(dock) ? new ScenePose(new Vec3(dock.getDoubleOr("escapeX", 0), dock.getDoubleOr("escapeY", 0), dock.getDoubleOr("escapeZ", 0)), from.orientation()) : from;
            for (var block : ShipSystems.connected(level, visitor))
            {
                if (level.getBlockState(block).isAir())
                    continue;
                var local = block.subtract(WorldStore.pos(visitor, "base"));
                var a = DockingManeuver.hullBox(local, from, stationPose);
                var b = DockingManeuver.hullBox(local, to, stationPose);
                int minX = Math.max(0, (int) Math.floor(Math.min(a.minX, b.minX) + 1e-6));
                int minY = Math.max(0, (int) Math.floor(Math.min(a.minY, b.minY) + 1e-6));
                int minZ = Math.max(0, (int) Math.floor(Math.min(a.minZ, b.minZ) + 1e-6));
                int maxX = Math.min(station.getIntOr("width", 32) - 1, (int) Math.floor(Math.max(a.maxX, b.maxX) - 1e-6));
                int maxY = Math.min(station.getIntOr("height", 32) - 1, (int) Math.floor(Math.max(a.maxY, b.maxY) - 1e-6));
                int maxZ = Math.min(station.getIntOr("depth", 32) - 1, (int) Math.floor(Math.max(a.maxZ, b.maxZ) - 1e-6));
                if (minX > maxX || minY > maxY || minZ > maxZ)
                    continue;
                for (var cell : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ))
                    reserved.putIfAbsent(cell.immutable(), visitor.getStringOr("home", "visiting ship"));
            }
        }
        var base = WorldStore.pos(station, "base");
        for (var entry : plan.entrySet())
        {
            var at = base.offset(entry.getKey());
            if (!entry.getValue().isAir() && !level.getBlockState(at).equals(entry.getValue()) && reserved.containsKey(entry.getKey()))
                return Optional.of(new BuildProblem(at, "This room would block " + reserved.get(entry.getKey()) + "'s departure corridor. Undock that ship before building here."));
        }
        return Optional.empty();
    }

    public static Optional<BlockPos> doorwayObstruction(ServerLevel level, CompoundTag vessel, Port port)
    {
        var collars = DockingTunnel.positions(level);
        var bounds = WorldStore.bounds(vessel);
        for (int depth = 1; depth <= 4; depth++)
            for (int y = 0; y <= 1; y++)
            {
                var at = port.outer().relative(port.facing(), depth).above(y);
                if (bounds.contains(at) && !level.getBlockState(at).isAir() && !collars.contains(at))
                    return Optional.of(at);
            }
        return Optional.empty();
    }

    public static boolean constructionAir(ServerPlayer p, CompoundTag station)
    {
        if (!StationAccess.station(station) || !LifeSupport.suited(p))
            return false;
        boolean visitor = station.getListOrEmpty("crew").stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("id", "").equals(p.getUUID().toString()) && t.getBooleanOr("construction", false));
        if (!visitor)
            return false;
        var source = DockingService.find(p.level().getServer(), station.getStringOr("constructionShip", ""));
        return source != null && source.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED") && source.getCompoundOrEmpty("dock").getStringOr("target", "").equals(DockingService.id(station)) && ShipAir.tether(p.level(), source);
    }

    private StationConstruction()
    {
    }
}
