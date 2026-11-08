package com.nullandkale.nullspace.gameplay.docking;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts.Port;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.station.StationSpace;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Server-owned docking reservations, complete autopilot maneuvers and passenger-only transfers. */
public final class DockingService
{

    static CompoundTag find(MinecraftServer server, String id)
    {
        return WorldStore.get(server).repository().find(id);
    }

    static String id(CompoundTag s)
    {
        return s.getStringOr("id", "");
    }

    static boolean exempt(ServerPlayer p)
    {
        return p.isCreative() || p.isSpectator();
    }

    static boolean mobile(CompoundTag s)
    {
        return s != null && !StationAccess.station(s);
    }

    public static boolean motionLocked(CompoundTag s)
    {
        return StationAccess.station(s) || s != null && s.contains("dock");
    }

    public static boolean engaged(CompoundTag s)
    {
        return s.contains("dock") || !s.getCompoundOrEmpty("dockLinks").isEmpty();
    }

    static void chunks(ServerLevel level, CompoundTag s)
    {
        StationSpace.chunks(level, s);
    }

    static CompoundTag link(MinecraftServer server, CompoundTag s, BlockPos at)
    {
        if (s.contains("dock"))
        {
            var d = s.getCompoundOrEmpty("dock");
            var owner = find(server, d.getStringOr("source", ""));
            return owner == null ? null : owner.getCompoundOrEmpty("dock");
        }
        for (var key : s.getCompoundOrEmpty("dockLinks").keySet())
        {
            var marker = s.getCompoundOrEmpty("dockLinks").getCompoundOrEmpty(key);
            var source = find(server, marker.getStringOr("source", ""));
            if (source != null)
            {
                var d = source.getCompoundOrEmpty("dock");
                if (WorldStore.pos(d, "targetPort").offset(WorldStore.pos(s, "base")).equals(at))
                    return d;
            }
        }
        return null;
    }

    public static void action(ServerPlayer p, CompoundTag s, BlockPos at, String verb)
    {
        var notice = perform(p, s, at, verb);
        if (!Boarding.busy(p))
            StationService.open(p, at, notice);
    }

    /** Execute without changing screens, so the controller can keep its destination visible. */
    public static String perform(ServerPlayer p, CompoundTag s, BlockPos at, String verb)
    {
        if (StationAccess.station(s) && !StationAccess.builder(p, s) && Set.of("DOCK_ACCEPT", "DOCK_DECLINE", "DOCK_CANCEL", "DOCK_UNDOCK", "DOCK_EMERGENCY").contains(verb))
            throw new IllegalArgumentException("Only station builders control this berth. Return to your ship to undock.");
        var server = p.level().getServer();
        var d = link(server, s, at);
        String notice = "";
        switch (verb)
        {
            case "DOCK_NEXT" ->
            {
                var choices = DockingTargets.choices(p, s);
                if (choices.isEmpty())
                    throw new IllegalArgumentException("No available docking berth");
                var selected = DockingTargets.selectedDestination(s);
                int index = -1;
                for (int i = 0; i < choices.size(); i++)
                    if (DockingTargets.sameChoice(selected, choices.get(i)))
                        index = i;
                DockingTargets.selectDestination(p, s, choices.get((index + 1) % choices.size()));
            }
            case "DOCK_REQUEST" ->
            {
                var choices = DockingTargets.choices(p, s);
                if (choices.isEmpty())
                    throw new IllegalArgumentException("No available docking berth");
                var choice = DockingTargets.selectedDestination(s);
                if (choices.stream().noneMatch(c -> DockingTargets.sameChoice(choice, c)))
                    throw new IllegalArgumentException("Selected berth changed or became unavailable; select another berth");
                var result = DockingManeuver.request(p, s, choice.vessel(), choice.port());
                if (!result.ready())
                    notice = result.message();
            }
            case "DOCK_ACCEPT" ->
            {
                if (d == null || !d.getStringOr("target", "").equals(id(s)))
                    throw new IllegalArgumentException("No incoming request at this port");
                if (!d.getStringOr("state", "").equals("WAITING_CONSENT"))
                    throw new IllegalArgumentException("No request is waiting for consent");
                d.putString("state", "APPROACHING");
                WorldStore.get(server).save();
            }
            case "DOCK_CANCEL" ->
            {
                if (!s.contains("dock") || !s.getCompoundOrEmpty("dock").getStringOr("source", "").equals(id(s)))
                    throw new IllegalArgumentException("No outgoing docking maneuver");
                if (s.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"))
                    throw new IllegalArgumentException("Use Undock after capture");
                DockingManeuver.release(server, s, "Docking cancelled; ship stopped");
            }
            case "DOCK_DECLINE" ->
            {
                if (d == null || !d.getStringOr("target", "").equals(id(s)) || !d.getStringOr("state", "").equals("WAITING_CONSENT"))
                    throw new IllegalArgumentException("No incoming request to decline");
                var source = find(server, d.getStringOr("source", ""));
                if (source != null)
                    DockingManeuver.release(server, source, "Docking request declined");
            }
            case "DOCK_INNER" -> DockingPorts.safeInner(p, s, DockingPorts.readPort(p.level(), s, at, true));
            case "DOCK_OUTER" ->
            {
                if (d != null)
                    throw new IllegalArgumentException("Exterior door stays sealed while a docking connection is reserved");
                if (!StationAccess.allowed(p, s))
                    throw new IllegalArgumentException("Station access is private");
                var port = DockingPorts.readPort(p.level(), s, at, true);
                if (!ShipDoors.open(p.level().getBlockState(port.outer())))
                {
                    if (ShipDoors.powered(p.level(), port.inner()))
                        throw new IllegalArgumentException("Inner door has a redstone override");
                    ShipDoors.setOpen(p.level(), port.inner(), false);
                    if (!ShipDoors.sealed(p.level(), port.inner()))
                        throw new IllegalArgumentException("Seal the inner door first");
                }
                if (ShipDoors.powered(p.level(), port.outer()))
                    throw new IllegalArgumentException("Exterior door has a redstone override");
                ShipDoors.setOpen(p.level(), port.outer(), !ShipDoors.open(p.level().getBlockState(port.outer())));
            }
            case "DOCK_BOARD", "DOCK_CONSTRUCTION" ->
            {
                if (d == null || !d.getStringOr("state", "").equals("CLAMPED"))
                    throw new IllegalArgumentException("Wait for automatic capture before boarding");
                var source = find(server, d.getStringOr("source", ""));
                var target = find(server, d.getStringOr("target", ""));
                boolean forward = s == source;
                var other = forward ? target : source;
                if (!at.equals(WorldStore.pos(s, "base").offset(WorldStore.pos(d, forward ? "sourcePort" : "targetPort"))))
                    throw new IllegalArgumentException("Use the connected airlock");
                var port = DockingPorts.readPort(p.level(), s, at, false);
                boolean construction = forward && d.getBooleanOr("construction", false);
                if (construction && !StationAccess.builder(p, target))
                    throw new IllegalArgumentException("Construction site is private");
                var otherPort = construction ? null : DockingPorts.readPort(p.level(), other, WorldStore.pos(other, "base").offset(WorldStore.pos(d, forward ? "targetPort" : "sourcePort")), false);
                Boarding.beginBoard(p, s, other, port, otherPort, construction);
            }
            case "DOCK_RETURN" ->
            {
                String home = s.getStringOr("constructionShip", "");
                for (var tag : s.getListOrEmpty("crew"))
                {
                    var member = (CompoundTag) tag;
                    if (member.getStringOr("id", "").equals(p.getUUID().toString()))
                        home = member.getStringOr("returnShip", home);
                }
                var source = find(server, home);
                if (source == null || !source.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED") || !source.getCompoundOrEmpty("dock").getStringOr("target", "").equals(id(s)))
                    throw new IllegalArgumentException("Your home ship is not connected");
                Port port = null;
                try
                {
                    port = DockingPorts.readPort(p.level(), source, WorldStore.pos(source, "base").offset(WorldStore.pos(source.getCompoundOrEmpty("dock"), "sourcePort")), false);
                }
                catch (IllegalArgumentException damaged)
                {
                }
                Boarding.beginBoard(p, s, source, null, port, false);
            }
            case "DOCK_EMERGENCY" ->
            {
                if (d == null || !d.getStringOr("source", "").equals(id(s)) || !d.getStringOr("state", "").equals("CLAMPED"))
                    throw new IllegalArgumentException("Use emergency departure from the clamped visiting ship");
                var target = find(server, d.getStringOr("target", ""));
                if (DockingTunnel.occupied(server, d))
                    throw new IllegalArgumentException("Move inside a cabin before retracting the docking tunnel");
                if (target != null && target.getListOrEmpty("crew").stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("returnShip", "").equals(id(s))))
                    throw new IllegalArgumentException("Return all crew before emergency departure");
                if (Boarding.boarding(server, id(s)) || target != null && Boarding.boarding(server, id(target)))
                    throw new IllegalArgumentException("Finish boarding before releasing the clamp");
                for (var vessel : new CompoundTag[]{s, target})
                    if (vessel != null)
                    {
                        var controller = WorldStore.pos(vessel, "base").offset(WorldStore.pos(d, vessel == s ? "sourcePort" : "targetPort"));
                        try
                        {
                            DockingPorts.close(p.level(), DockingPorts.readPort(p.level(), vessel, controller, false));
                        }
                        catch (IllegalArgumentException damaged)
                        {
                            p.sendSystemMessage(Component.literal("Damaged airlock: surviving seals could not be confirmed. Cabin pressure" + " remains real."));
                        }
                    }
                d.putString("state", "EMERGENCY_BACKOFF");
                WorldStore.get(server).save();
            }
            case "DOCK_UNDOCK" ->
            {
                if (d == null || !d.getStringOr("state", "").equals("CLAMPED"))
                    throw new IllegalArgumentException("No clamped docking connection");
                var source = find(server, d.getStringOr("source", ""));
                var target = find(server, d.getStringOr("target", ""));
                if (s != source)
                    throw new IllegalArgumentException("Undock from the visiting ship's helm or port");
                if (DockingTunnel.occupied(server, d))
                    throw new IllegalArgumentException("Move inside a cabin before retracting the docking tunnel");
                if (WorldStore.get(server).list("stationBoarding").stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("source", "").equals(id(s)) || t.getStringOr("target", "").equals(id(s))))
                    throw new IllegalArgumentException("Wait for passengers to finish boarding");
                if (target != null && target.getListOrEmpty("crew").stream().map(t -> (CompoundTag) t).anyMatch(t -> t.getStringOr("returnShip", "").equals(id(source))))
                    throw new IllegalArgumentException("Recall crew from the other vessel before undocking");
                if (NavigationService.propulsion(p.level(), source) == 0)
                    throw new IllegalArgumentException("Your ship needs controls, engine power and fuel to undock");
                DockingManeuver.recoverEscape(p.level(), source, target, d);
                boolean intact = DockingPorts.closeSurviving(p.level(), source, WorldStore.pos(source, "base").offset(WorldStore.pos(d, "sourcePort")));
                if (!d.getBooleanOr("construction", false) && target != null)
                    intact &= DockingPorts.closeSurviving(p.level(), target, WorldStore.pos(target, "base").offset(WorldStore.pos(d, "targetPort")));
                if (!intact)
                    notice = "Undocking from the damaged berth. Surviving doors are closed; repair any hull" + " leaks.";
                d.putString("state", "UNDOCKING");
                d.putLong("expires", server.overworld().getGameTime() + 12000);
                WorldStore.get(server).save();
            }
            default -> throw new IllegalArgumentException("Unsupported docking action");
        }
        return notice;
    }

    public static void initialize()
    {
        ServerContext.tick(ServerContext.Phase.NAVIGATION, server ->
        {
            for (var tag : List.copyOf(WorldStore.get(server).ships()))
            {
                var s = (CompoundTag) tag;
                if (s.contains("dock"))
                    DockingManeuver.tickMotion(server, s);
            }
            Boarding.tickBoarding(server);
            DockingPorts.walkThrough(server);
            if (server.getTickCount() % 40 == 0)
                WorldStore.get(server).requestSave();
        });
    }

    private DockingService()
    {
    }
}
