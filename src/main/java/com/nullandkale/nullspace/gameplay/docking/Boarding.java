package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts.Port;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationGraph;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelPackets;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.BoardingView;

import java.util.*;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Moving passengers between docked vessels: the boarding handshake, crew records and arrival. */
public final class Boarding
{
    static final ServerContext.Key<Set<UUID>> READY = new ServerContext.Key<>(), ARRIVED = new ServerContext.Key<>();
    static final ServerContext.Key<Map<UUID, Integer>> WAIT = new ServerContext.Key<>();

    public static boolean busy(ServerPlayer p)
    {
        return WorldStore.get(p.level().getServer()).list("stationBoarding").stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("player", "").equals(p.getUUID().toString()));
    }

    public static boolean boarding(MinecraftServer server, String ship)
    {
        return WorldStore.get(server).list("stationBoarding").stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("source", "").equals(ship) || t.getStringOr("target", "").equals(ship));
    }

    public static boolean ready(ServerPlayer p, TravelPackets.Ready packet)
    {
        for (var tag : WorldStore.get(p.level().getServer()).list("stationBoarding"))
        {
            var t = (CompoundTag) tag;
            if (t.getStringOr("player", "").equals(p.getUUID().toString()) && t.getStringOr("token", "").equals(packet.id().toString()))
            {
                if (packet.arrived() != t.getBooleanOr("committed", false))
                    return true;
                ServerContext.get(p.level().getServer()).service(packet.arrived() ? ARRIVED : READY, HashSet::new).add(packet.id());
                return true;
            }
        }
        return false;
    }

    static void progress(ServerPlayer p, CompoundTag t, String text, boolean done)
    {
        if (ServerPlayNetworking.canSend(p, TravelPackets.Progress.TYPE))
            ServerPlayNetworking.send(p, new TravelPackets.Progress(UUID.fromString(t.getStringOr("token", "")), text, t.getBooleanOr("committed", false) ? p.level().dimension().identifier().toString() : "", done, TravelPackets.Kind.BOARD));
    }

    static void beginBoard(ServerPlayer p, CompoundTag source, CompoundTag target, Port port, Port other, boolean construction)
    {
        if (busy(p) || TransferLocks.inTransit(p.level().getServer(), source) || TransferLocks.inTransit(p.level().getServer(), target) || !StationAccess.ready(source) || !StationAccess.ready(target))
            throw new IllegalArgumentException("Another transfer is in progress or this vessel is under construction");
        if (!StationAccess.allowed(p, target))
            throw new IllegalArgumentException("Destination access is private");
        if (port != null && !port.cells().contains(BlockPos.containing(p.position())) && !DockingPorts.compactEntrance(p, source, port))
            throw new IllegalArgumentException("Enter the airlock chamber before boarding");
        BlockPos to;
        if (construction)
            to = WorldStore.pos(target, "base").offset(8, 3, 8);
        else if (other != null)
            to = DockingPorts.anchor(p.level(), other);
        else
        {
            // Prefer a breathable spot, but an airless ship can still be boarded.
            BlockPos airless = null;
            to = null;
            for (var cell : BlockPos.betweenClosed(WorldStore.bounds(target).min(), WorldStore.bounds(target).max()))
            {
                if (!StationGraph.standing(p.level(), cell) || !p.level().getEntities(null, new AABB(cell).expandTowards(0, 1, 0)).isEmpty())
                    continue;
                if (ShipAir.breathable(p.level(), target, cell.above()))
                {
                    to = cell.immutable();
                    break;
                }
                if (airless == null)
                    airless = cell.immutable();
            }
            if (to == null)
                to = airless;
            if (to == null)
                throw new IllegalArgumentException("Home ship has no room to stand in");
        }
        if (port != null && !port.inner().equals(port.outer()))
            DockingPorts.close(p.level(), port);
        if (other != null && !other.inner().equals(other.outer()))
            DockingPorts.close(p.level(), other);
        var t = new CompoundTag();
        t.putString("token", UUID.randomUUID().toString());
        t.putString("player", p.getUUID().toString());
        t.putString("source", DockingService.id(source));
        t.putString("target", DockingService.id(target));
        t.putLong("at", to.asLong());
        t.putBoolean("construction", construction);
        t.putBoolean("committed", false);
        t.putLong("sourcePort", port == null ? 0 : port.controller().subtract(WorldStore.pos(source, "base")).asLong());
        t.putLong("targetPort", other == null ? 0 : other.controller().subtract(WorldStore.pos(target, "base")).asLong());
        if (port != null)
            t.putLong("sourceStanding", port.anchors().getFirst().asLong());
        WorldStore.get(p.level().getServer()).list("stationBoarding").add(t);
        WorldStore.get(p.level().getServer()).save();
        p.closeContainer();
        progress(p, t, "Cycling airlocks", false);
    }

    static boolean captured(CompoundTag a, CompoundTag b)
    {
        for (var vessel : new CompoundTag[]{a, b})
        {
            var dock = vessel.getCompoundOrEmpty("dock");
            if (!dock.getStringOr("state", "").equals("CLAMPED") || dock.getBooleanOr("construction", false))
                continue;
            if ((dock.getStringOr("source", "").equals(DockingService.id(a)) && dock.getStringOr("target", "").equals(DockingService.id(b))) || (dock.getStringOr("source", "").equals(DockingService.id(b)) && dock.getStringOr("target", "").equals(DockingService.id(a))))
                return true;
        }
        return false;
    }

    static void crew(ServerPlayer p, CompoundTag source, CompoundTag target, boolean construction)
    {
        var store = WorldStore.get(p.level().getServer());
        String home = DockingService.id(source);
        for (var tag : store.ships())
        {
            var s = (CompoundTag) tag;
            for (var member : s.getListOrEmpty("crew"))
            {
                var m = (CompoundTag) member;
                if (m.getStringOr("id", "").equals(p.getUUID().toString()))
                    home = m.getStringOr("returnShip", home);
            }
            s.getListOrEmpty("crew").removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(p.getUUID().toString()));
        }
        var member = new CompoundTag();
        member.putString("id", p.getUUID().toString());
        member.putString("returnShip", home);
        member.putBoolean("construction", construction);
        var list = target.getListOrEmpty("crew");
        if (!target.contains("crew"))
            target.put("crew", list);
        list.add(member);
    }

    static void tickBoarding(MinecraftServer server)
    {
        var store = WorldStore.get(server);
        var wait = ServerContext.get(server).service(WAIT, HashMap::new);
        var ready = ServerContext.get(server).service(READY, HashSet::new);
        var arrived = ServerContext.get(server).service(ARRIVED, HashSet::new);
        for (var tag : List.copyOf(store.list("stationBoarding")))
        {
            var t = (CompoundTag) tag;
            UUID token = UUID.fromString(t.getStringOr("token", ""));
            var p = server.getPlayerList().getPlayer(UUID.fromString(t.getStringOr("player", "")));
            if (p == null)
            {
                if (!t.getBooleanOr("committed", false))
                {
                    store.list("stationBoarding").remove(t);
                    store.requestSave();
                    wait.remove(token);
                    ready.remove(token);
                    arrived.remove(token);
                }
                continue;
            }
            if (!p.isAlive() || !p.level().dimension().equals(TravelService.SPACE))
            {
                progress(p, t, "Boarding cancelled: passenger left space", true);
                store.list("stationBoarding").remove(t);
                store.requestSave();
                wait.remove(token);
                ready.remove(token);
                arrived.remove(token);
                continue;
            }
            if (!wait.containsKey(token) && t.getBooleanOr("committed", false))
                progress(p, t, "Finishing boarding", false);
            int ticks = wait.merge(token, 1, Integer::sum);
            try
            {
                var source = DockingService.find(server, t.getStringOr("source", ""));
                var target = DockingService.find(server, t.getStringOr("target", ""));
                if (source == null || target == null)
                    throw new IllegalArgumentException("Boarding vessel no longer exists");
                DockingService.chunks(p.level(), target);
                boolean committed = t.getBooleanOr("committed", false);
                if (!committed)
                {
                    if (t.contains("sourceStanding"))
                    {
                        var safe = WorldStore.pos(t, "sourceStanding");
                        p.teleportTo(safe.getX() + .5, safe.getY(), safe.getZ() + .5);
                        p.setDeltaMovement(Vec3.ZERO);
                    }
                    if (ticks > 600)
                        throw new IllegalArgumentException("Boarding timed out; check air supply and destination occupancy");
                    if (!ready.contains(token))
                        continue;
                    if (!StationAccess.allowed(p, target))
                        throw new IllegalArgumentException("Destination permission changed");
                    boolean construction = t.getBooleanOr("construction", false);
                    boolean joined = captured(source, target);
                    var at = WorldStore.pos(t, "at");
                    if (!StationGraph.standing(p.level(), at) || !p.level().getEntities(null, new AABB(at).expandTowards(0, 1, 0)).isEmpty())
                        throw new IllegalArgumentException("Arrival position became obstructed");
                    if (t.getLongOr("sourcePort", 0) != 0)
                    {
                        var port = DockingPorts.readPort(p.level(), source, WorldStore.pos(source, "base").offset(WorldStore.pos(t, "sourcePort")), !joined);
                        if (!port.inner().equals(port.outer()))
                            DockingPorts.close(p.level(), port);
                        if (!port.cells().contains(BlockPos.containing(p.position())) && !DockingPorts.compactEntrance(p, source, port))
                            throw new IllegalArgumentException("Passenger left the source chamber");
                    }
                    if (!construction && t.getLongOr("targetPort", 0) != 0)
                    {
                        var other = DockingPorts.readPort(p.level(), target, WorldStore.pos(target, "base").offset(WorldStore.pos(t, "targetPort")), !joined);
                        if (!other.inner().equals(other.outer()))
                            DockingPorts.close(p.level(), other);
                    }
                    crew(p, source, target, construction);
                    var view = BoardingView.between(SceneService.pose(source), SceneService.pose(target), p.getYRot(), p.getXRot());
                    t.putBoolean("committed", true);
                    var root = store.snapshot();
                    WorldStore.target(root, p.getUUID(), TravelService.SPACE.identifier().toString(), at.getX() + .5, at.getY(), at.getZ() + .5, view.yaw(), view.pitch());
                    store.document("targets", root.getListOrEmpty("targets"));
                    store.save();
                    p.teleportTo(p.level(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), view.yaw(), view.pitch(), true);
                    p.setDeltaMovement(Vec3.ZERO);
                    if (construction && t.getLongOr("sourcePort", 0) != 0)
                    {
                        var sourceBase = WorldStore.pos(source, "base").offset(WorldStore.pos(t, "sourcePort"));
                        if (p.level().getBlockState(sourceBase).is(ModBlocks.DOCKING_BASE))
                            ShipDoors.setOpen(p.level(), sourceBase.above(), false);
                    }
                    server.getPlayerList().saveAll();
                    progress(p, t, "Boarding", false);
                    wait.put(token, 0);
                }
                else if (arrived.contains(token) || ticks > 200)
                {
                    if (!arrived.contains(token) && ServerPlayNetworking.canSend(p, TravelPackets.Progress.TYPE))
                        p.connection.disconnect(Component.literal("Arrival timed out. Reconnect to finish boarding safely."));
                    else
                    {
                        store.list("targets").removeIf(a -> ((CompoundTag) a).getStringOr("id", "").equals(p.getUUID().toString()));
                        progress(p, t, "Boarded", true);
                        if (!t.getBooleanOr("construction", false) && t.getLongOr("targetPort", 0) != 0)
                        {
                            var port = DockingPorts.readPort(p.level(), target, WorldStore.pos(target, "base").offset(WorldStore.pos(t, "targetPort")), false);
                            DockingPorts.safeInner(p, target, port);
                        }
                    }
                    store.list("stationBoarding").remove(t);
                    store.save();
                    wait.remove(token);
                    ready.remove(token);
                    arrived.remove(token);
                }
            }
            catch (IllegalArgumentException invalid)
            {
                progress(p, t, invalid.getMessage(), true);
                store.list("stationBoarding").remove(t);
                store.requestSave();
                wait.remove(token);
                ready.remove(token);
                arrived.remove(token);
            }
        }
    }

    private Boarding()
    {
    }
}
