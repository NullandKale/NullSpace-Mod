package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.combat.ShipCombat;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.Jumps.Jump;
import com.nullandkale.nullspace.gameplay.navigation.Jumps.Mode;
import com.nullandkale.nullspace.gameplay.navigation.Piloting.Automation;
import com.nullandkale.nullspace.gameplay.navigation.Piloting.Input;
import com.nullandkale.nullspace.gameplay.ownership.CrewRelease;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.ScenePanels;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelPackets;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.flight.ShipHeading;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authoritative helm controls and atomic metadata-only region jumps. Hulls never move
 * chunks.
 */
public final class NavigationService
{

    static final class Lease
    {
        final UUID token = UUID.randomUUID(), player;
        final String ship;
        final BlockPos helm;
        final boolean pilot;
        int expires, sequence = -1, buttons, revision;

        Lease(ServerPlayer p, CompoundTag vessel, BlockPos helm, boolean pilot)
        {
            player = p.getUUID();
            ship = vessel.getStringOr("id", "");
            this.helm = helm.immutable();
            this.pilot = pilot;
            expires = p.level().getServer().getTickCount() + 80;
        }
    }

    static final class Runtime
    {
        final Map<String, Automation> computers = new HashMap<>();
        final Map<UUID, Lease> leases = new HashMap<>();
        final Map<String, UUID> pilots = new HashMap<>();
        final Map<String, Jump> jumps = new HashMap<>();
    }

    static final ServerContext.Key<Runtime> KEY = new ServerContext.Key<>();

    static Runtime state(MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, Runtime::new);
    }

    static CompoundTag ship(MinecraftServer server, String id)
    {
        return WorldStore.get(server).repository().find(id);
    }

    public static boolean pending(MinecraftServer server)
    {
        return !state(server).jumps.isEmpty();
    }

    public static boolean pending(MinecraftServer server, String ship)
    {
        return state(server).jumps.containsKey(ship);
    }

    public static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds)
    {
        return state(level.getServer()).jumps.keySet().stream().map(id -> ship(level.getServer(), id)).filter(Objects::nonNull).anyMatch(s -> s.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && WorldStore.bounds(s).box().intersects(bounds.box()));
    }

    public static boolean busy(ServerPlayer p)
    {
        return state(p.level().getServer()).jumps.values().stream().anyMatch(j -> j.crew.contains(p.getUUID()));
    }

    public static boolean locked(ServerLevel level, BlockPos pos)
    {
        return level.dimension().equals(TravelService.SPACE) && state(level.getServer()).jumps.keySet().stream().map(id -> ship(level.getServer(), id)).filter(Objects::nonNull).anyMatch(s -> WorldStore.bounds(s).contains(pos));
    }

    public static boolean moving(MinecraftServer server)
    {
        return !state(server).pilots.isEmpty() || !state(server).computers.isEmpty();
    }

    public static int propulsion(ServerLevel level, CompoundTag s)
    {
        if (FuelSystem.available(level, s) == 0)
            return 0;
        int engines = ShipSystems.connected(level, s).stream().filter(p -> engineFires(level, s, p)).mapToInt(p -> 1 + FuelSystem.tier(level, p)).sum();
        if (engines == 0)
            return 0;
        if (!ShipSystems.enabled(s))
            return Math.min(8, engines);
        if (ShipSystems.power(level, s, PowerBus.System.CONTROLS) == 0)
            return 0;
        return Math.min(8, Math.min(engines * 2, ShipSystems.power(level, s, PowerBus.System.ENGINES)));
    }

    public static UUID open(ServerPlayer player, BlockPos at, boolean pilot)
    {
        var vessel = Piloting.helm(player, at);
        if (pilot && JourneyService.cruising(vessel))
            throw new IllegalArgumentException("FTL is steering the ship; investigate a signal before flying locally");
        if (pilot && DockingService.motionLocked(vessel))
            throw new IllegalArgumentException("Automatic docking owns movement; undock before piloting");
        if (TransferLocks.busy(player) || TransferLocks.locked(player.level(), at))
            throw new IllegalArgumentException("Finish travel before opening ship controls");
        var r = state(player.level().getServer());
        var owner = r.pilots.get(vessel.getStringOr("id", ""));
        if (pilot && owner != null && !owner.equals(player.getUUID()))
            throw new IllegalArgumentException("Another crew member is piloting. Use Ship camera to observe.");
        if (pilot)
            state(player.level().getServer()).computers.remove(vessel.getStringOr("id", ""));
        if (owner == null)
            Piloting.stop(vessel);
        close(player.level().getServer(), player.getUUID());
        if (pilot)
        {
            CrewRelease.authorize(vessel, player);
            WorldStore.get(player.level().getServer()).requestSave();
        }
        var lease = new Lease(player, vessel, at, pilot);
        r.leases.put(player.getUUID(), lease);
        if (pilot)
            r.pilots.put(lease.ship, player.getUUID());
        send(player, lease, vessel);
        return lease.token;
    }

    static boolean valid(ServerPlayer p, Lease l)
    {
        try
        {
            return p != null && p.level().getServer().getTickCount() <= l.expires && !TransferLocks.busy(p) && !TransferLocks.locked(p.level(), l.helm) && Piloting.helm(p, l.helm).getStringOr("id", "").equals(l.ship);
        }
        catch (IllegalArgumentException unavailable)
        {
            return false;
        }
    }

    static boolean crewAboard(ServerLevel level, CompoundTag s)
    {
        var pressure = ShipSystems.enabled(s) ? null : new HullScan.LivePressure(level, WorldStore.bounds(s));
        for (var p : level.players())
            if (p.isAlive() && !p.isSpectator() && SceneService.observer(p) == s)
            {
                var eye = BlockPos.containing(p.getEyePosition());
                if (pressure == null ? !ShipAir.breathable(level, s, eye) : !pressure.breathable(eye))
                    return false;
            }
        return true;
    }

    static void close(MinecraftServer server, UUID player)
    {
        var r = state(server);
        var l = r.leases.remove(player);
        if (l != null && l.pilot)
        {
            r.pilots.remove(l.ship, player);
            var s = ship(server, l.ship);
            if (s != null)
            {
                Piloting.stop(s);
                WorldStore.get(server).requestSave();
            }
        }
    }

    public static void closeShip(MinecraftServer server, String id)
    {
        var r = state(server);
        r.computers.remove(id);
        for (var l : List.copyOf(r.leases.values()))
            if (l.ship.equals(id))
                close(server, l.player);
    }

    static void send(ServerPlayer p, Lease l, CompoundTag vessel)
    {
        ScenePanels.shipView(p, vessel, l.token, ++l.revision, l.pilot, propulsion(p.level(), vessel));
    }

    public static boolean ready(ServerPlayer p, TravelPackets.Ready packet)
    {
        for (var j : state(p.level().getServer()).jumps.values())
            if (j.token.equals(packet.id()) && j.crew.contains(p.getUUID()))
            {
                if (packet.arrived() == j.committed)
                    (packet.arrived() ? j.arrived : j.ready).add(p.getUUID());
                return true;
            }
        return false;
    }

    static void broadcast(MinecraftServer server, Jump j, String text, String target, boolean done)
    {
        for (var id : j.crew)
        {
            var p = server.getPlayerList().getPlayer(id);
            if (p != null)
                ServerPlayNetworking.send(p, new TravelPackets.Progress(j.token, text, target, done, TravelPackets.Kind.JUMP));
        }
    }

    static boolean allOnline(MinecraftServer server, Jump j, Set<UUID> ready)
    {
        return j.crew.stream().filter(id -> server.getPlayerList().getPlayer(id) != null).allMatch(ready::contains);
    }

    /** An engine counts when it is on and the block behind it is clear for the exhaust. */
    private static boolean engineFires(ServerLevel level, CompoundTag s, BlockPos p)
    {
        var state = level.getBlockState(p);
        return state.is(ModBlocks.ENGINE) && ShipSystems.active(s, p) && level.getBlockState(p.relative(state.getValue(HorizontalDirectionalBlock.FACING))).isAir();
    }

    /** A computer's standing order still holds: its ship is loaded, powered, crewed and has engines, and the terminal is intact. */
    private static boolean commandHolds(MinecraftServer server, CompoundTag vessel, ServerLevel level, Automation command)
    {
        if (vessel == null || server.getTickCount() >= command.until() || level == null)
            return false;
        var controller = command.controller();
        if (!level.hasChunkAt(controller) || !ShipTopology.loaded(level, vessel) || TransferLocks.locked(level, controller))
            return false;
        if (!level.getBlockState(controller).is(ModBlocks.TERMINAL) || !ShipSystems.connected(level, vessel).contains(controller))
            return false;
        return ShipSystems.active(vessel, controller) && propulsion(level, vessel) != 0 && crewAboard(level, vessel);
    }

    /** What the crew is told when a jump finishes. */
    private static String underwayMessage(MinecraftServer server, Jump j)
    {
        if (j.mode == Mode.START || j.mode == Mode.RESUME)
            return "FTL underway. You can move around your ship.";
        if (j.mode == Mode.INVESTIGATE)
        {
            boolean selected = ship(server, j.ship).getCompoundOrEmpty("journey").getBooleanOr("encounterDockSelected", false);
            return selected ? "Signal reached. Automatic docking will begin; Disengage cancels it." : "Signal reached. Select an available docking port, then Dock.";
        }
        return "Arrived in " + LocalSpaces.label(server, ship(server, j.ship));
    }

    static void finish(MinecraftServer server, Jump j, String message)
    {
        state(server).jumps.remove(j.ship);
        broadcast(server, j, message, "", true);
        for (var id : j.crew)
        {
            var p = server.getPlayerList().getPlayer(id);
            if (p != null)
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    static void tick(MinecraftServer server)
    {
        JourneyService.tick(server);
        var r = state(server);
        for (var l : List.copyOf(r.leases.values()))
        {
            var p = server.getPlayerList().getPlayer(l.player);
            if (!valid(p, l))
            {
                close(server, l.player);
                continue;
            }
            var s = ship(server, l.ship);
            if (l.pilot)
            {
                boolean aboard = crewAboard(p.level(), s);
                int power = aboard ? propulsion(p.level(), s) : 0;
                boolean thrust = (l.buttons & ~FlightDynamics.FIRE) != 0;
                if (power > 0 && thrust && !FuelSystem.spend(p.level(), s, FuelSystem.travelCost(p.level(), s, Math.max(1, power))))
                    power = 0;
                s.putInt("thrustDemand", thrust ? power : 0);
                var old = Piloting.flight(s);
                var next = FlightDynamics.step(old, l.buttons, power, ShipHeading.forward(p.level(), s).toYRot());
                if (Piloting.blocked(server, s, old, next))
                {
                    next = new FlightDynamics.State(old.center(), Vec3.ZERO, old.yaw(), old.pitch(), old.roll());
                    s.putString("flightStatus", "OBSTACLE / LOCAL BOUNDARY - use helm navigation");
                }
                else
                    s.putString("flightStatus", !aboard ? "CREW EVA / CABIN UNSAFE - FLIGHT STOPPED" : power == 0 ? "NO CONTROL / ENGINE POWER" : "PILOT");
                Piloting.apply(s, next);
                if ((l.buttons & FlightDynamics.FIRE) != 0)
                {
                    l.buttons &= ~FlightDynamics.FIRE;
                    try
                    {
                        ShipCombat.fire(p, l.helm);
                    }
                    catch (IllegalArgumentException unavailable)
                    {
                        s.putString("flightStatus", unavailable.getMessage());
                    }
                }
            }
            if (server.getTickCount() % 2 == 0)
                send(p, l, s);
        }
        var computers = r.computers.entrySet().iterator();
        while (computers.hasNext())
        {
            var entry = computers.next();
            var command = entry.getValue();
            var vessel = ship(server, entry.getKey());
            var level = vessel == null ? null : WorldStore.level(server, vessel.getStringOr("dimension", ""));
            if (!commandHolds(server, vessel, level, command))
            {
                if (vessel != null)
                    Piloting.stop(vessel);
                computers.remove();
                continue;
            }
            var old = Piloting.flight(vessel);
            vessel.putInt("thrustDemand", command.buttons() == 0 ? 0 : propulsion(level, vessel));
            if (!FuelSystem.spend(level, vessel, FuelSystem.travelCost(level, vessel, Math.max(1, propulsion(level, vessel)))))
            {
                Piloting.stop(vessel);
                computers.remove();
                continue;
            }
            var next = FlightDynamics.step(old, command.buttons(), propulsion(level, vessel), ShipHeading.forward(level, vessel).toYRot());
            if (Piloting.blocked(server, vessel, old, next))
            {
                Piloting.stop(vessel);
                vessel.putString("flightStatus", "AUTOPILOT OBSTACLE");
                computers.remove();
            }
            else
            {
                Piloting.apply(vessel, next);
                vessel.putString("flightStatus", "SCRIPT PILOT");
            }
        }
        for (var j : List.copyOf(r.jumps.values()))
        {
            j.ticks++;
            if (!j.committed)
            {
                var pilot = server.getPlayerList().getPlayer(j.actor);
                if (pilot == null)
                {
                    finish(server, j, "Fast travel cancelled: pilot disconnected");
                    continue;
                }
                if (j.ticks > 200)
                {
                    finish(server, j, "Fast travel cancelled: readiness timeout");
                    continue;
                }
                if (j.ticks < 20 || !allOnline(server, j, j.ready))
                    continue;
                try
                {
                    var s = ship(server, j.ship);
                    if (s == null || SceneService.observer(pilot) != s)
                        throw new IllegalArgumentException("Pilot is no longer aboard this ship");
                    if (j.mode == Mode.START)
                        Jumps.jumpInspection(pilot, j.helm);
                    else if (!crewAboard(pilot.level(), s))
                        throw new IllegalArgumentException("Bring crew into safe cabins before changing regions");
                    if (j.mode == Mode.START && !FuelSystem.spend(pilot.level(), s, j.journey.getIntOr("paid", 0)))
                        throw new IllegalArgumentException("Shared fuel ran out before jump commitment");
                    var before = s.copy();
                    try
                    {
                        if (j.mode == Mode.START)
                        {
                            j.journey.putDouble("visualStarted", server.overworld().getGameTime() / 20.0);
                            JourneyService.register(server, j.journey);
                            s.put("journey", j.journey.copy());
                        }
                        else if (j.mode == Mode.ARRIVE)
                        {
                            s.remove("journey");
                        }
                        else
                        {
                            var journey = s.getCompoundOrEmpty("journey");
                            journey.putString("state", j.mode == Mode.RESUME ? "CRUISE" : "STOPPED");
                            if (j.mode == Mode.RESUME)
                                journey.putDouble("visualStarted", server.overworld().getGameTime() / 20.0);
                            if (j.mode == Mode.RESUME)
                                s.put("destination", journey.contains("selection") ? journey.getCompoundOrEmpty("selection").copy() : ShipDestination.region(server, journey.getStringOr("destination", "")).save());
                            if (j.mode == Mode.INVESTIGATE)
                                journey.putBoolean("visited", true);
                        }
                        s.putString("flightStatus", "");
                        s.putString("localSpace", j.destination);
                        Piloting.stop(s);
                        if (j.mode == Mode.ARRIVE || j.mode == Mode.INVESTIGATE)
                            Jumps.randomizeArrival(server, s);
                        var arrival = Jumps.arrivalOrigin(server, s);
                        s.putDouble("simX", arrival.x);
                        s.putDouble("simY", arrival.y);
                        s.putDouble("simZ", arrival.z);
                        if (j.mode == Mode.INVESTIGATE)
                            JourneyService.selectEncounterDock(pilot, s);
                        WorldStore.get(server).requestSave();
                    }
                    catch (RuntimeException save)
                    {
                        for (var key : List.copyOf(s.keySet()))
                            s.remove(key);
                        for (var key : before.keySet())
                            s.put(key, before.get(key));
                        throw save;
                    }
                    j.committed = true;
                    j.ticks = 0;
                    SceneService.refresh(server);
                    var target = SceneCatalog.forShip(server, s).getStringOr("id", "");
                    broadcast(server, j, switch (j.mode)
                    {
                        case START -> "Entering FTL: synchronizing the view";
                        case RESUME -> "Resuming FTL: synchronizing the view";
                        case INVESTIGATE -> "Reaching the signal: synchronizing the view";
                        case ARRIVE -> "Arriving at " + LocalSpaces.label(server, s);
                    }, "scene:" + target, false);
                }
                catch (RuntimeException error)
                {
                    finish(server, j, "Fast travel cancelled: " + error.getMessage());
                }
            }
            else if (allOnline(server, j, j.arrived) || j.ticks > 200)
            {
                if (j.ticks > 200)
                    for (var id : j.crew)
                    {
                        var p = server.getPlayerList().getPlayer(id);
                        if (p != null && !j.arrived.contains(id))
                            p.connection.disconnect(net.minecraft.network.chat.Component.literal("Reconnect to synchronize the destination scene"));
                    }
                finish(server, j, underwayMessage(server, j));
            }
        }
        if (!r.pilots.isEmpty() && server.getTickCount() % 100 == 0)
            WorldStore.get(server).requestSave();
    }

    public static void initialize()
    {
        PayloadTypeRegistry.serverboundPlay().register(Input.TYPE, Input.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Input.TYPE, (p, c) -> c.server().execute(() -> Piloting.input(c.player(), p)));
        ServerContext.tick(ServerContext.Phase.NAVIGATION, NavigationService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> close(server, handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            for (var l : List.copyOf(state(server).leases.values()))
                close(server, l.player);
            WorldStore.get(server).requestSave();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
    }

    private NavigationService()
    {
    }
}
