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
                // The pilot's hands are off: the ship keeps its momentum, and the computer takes over (see unattended flight).
                s.putInt("thrustDemand", 0);
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

    /** A jump waits for no client, so an acknowledgement of one (from a client of an earlier version) is accepted and changes nothing. */
    public static boolean ready(ServerPlayer p, TravelPackets.Ready packet)
    {
        return state(p.level().getServer()).jumps.values().stream().anyMatch(j -> j.token.equals(packet.id()) && j.crew.contains(p.getUUID()));
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

    /** The hull's mass in blocks, saved on the ship and counted again every ten seconds, so building changes how it handles. */
    static double mass(ServerLevel level, CompoundTag ship, long tick)
    {
        int cached = ship.getIntOr("simMass", 0);
        if (cached > 0 && tick % 200 != 0)
            return cached;
        var bounds = WorldStore.bounds(ship);
        int blocks = 0;
        for (var at : net.minecraft.core.BlockPos.betweenClosed(bounds.min(), bounds.max()))
            if (!level.getBlockState(at).isAir())
                blocks++;
        ship.putInt("simMass", Math.max(150, blocks));
        return Math.max(150, blocks);
    }

    /**
     * One tick of physical flight for a ship: the model, then fuel for the thrust it actually used (braking costs as much as
     * pushing), then the exhaust demand the plume follows. A ship out of fuel or power coasts at the speed it had.
     */
    public static FlightDynamics.State fly(ServerLevel level, CompoundTag ship, int buttons, int power, float forward, boolean assist, long tick)
    {
        var old = Piloting.flight(ship);
        double mass = mass(level, ship, tick);
        var result = FlightDynamics.simulate(old, buttons, new FlightDynamics.Craft(mass, power, assist), forward);
        int used = (int) Math.ceil(result.thrust() * power);
        if (power > 0 && used > 0 && !FuelSystem.spend(level, ship, FuelSystem.travelCost(level, ship, used)))
        {
            result = FlightDynamics.simulate(old, buttons, new FlightDynamics.Craft(mass, 0, assist), forward);
            used = 0;
        }
        ship.putInt("thrustDemand", result.thrust() > .03 ? Math.max(1, used) : 0);
        // What the exhaust follows: how hard the engines are working, and how fast the ship is going for its power.
        ship.putDouble("thrustShare", result.thrust());
        ship.putDouble("speedShare", Math.min(1, result.state().velocity().length() / Math.max(1, FlightDynamics.governor(new FlightDynamics.Craft(mass, Math.max(1, power), true)))));
        return result.state();
    }

    /**
     * Ships nobody is flying keep their momentum. With no pilot and no script, the computer holds assist on: it brakes the ship
     * with whatever the engines and fuel allow, so a ship that was left moving slows to rest over seconds, and one with no
     * power or fuel drifts on at the speed it had. A ship that docks, jumps, is stored or is moving between worlds is not touched
     * here: those already set its motion themselves.
     */
    private static void unattended(MinecraftServer server, Runtime r)
    {
        var space = server.getLevel(TravelService.SPACE);
        if (space == null)
            return;
        for (var tag : WorldStore.get(server).ships())
        {
            var ship = (CompoundTag) tag;
            if (!ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()) || !moving(ship))
                continue;
            String id = ship.getStringOr("id", "");
            if (r.pilots.containsKey(id) || r.computers.containsKey(id) || DockingService.motionLocked(ship) || JourneyService.cruising(ship) || TransferLocks.busy(server, ship))
                continue;
            var old = Piloting.flight(ship);
            var next = fly(space, ship, 0, propulsion(space, ship), ShipHeading.forward(space, ship).toYRot(), true, server.getTickCount());
            if (Piloting.blocked(server, ship, old, next))
            {
                Piloting.stop(ship);
                ship.putString("flightStatus", "DRIFT STOPPED / OBSTACLE");
            }
            else
            {
                Piloting.apply(ship, next);
                ship.putString("flightStatus", propulsion(space, ship) == 0 ? "DRIFTING / NO ENGINE POWER" : "BRAKING / NO PILOT");
            }
        }
    }

    /** True while a ship has any velocity or spin to keep simulating. */
    static boolean moving(CompoundTag ship)
    {
        return Math.abs(ship.getDoubleOr("simVX", 0)) + Math.abs(ship.getDoubleOr("simVY", 0)) + Math.abs(ship.getDoubleOr("simVZ", 0)) + Math.abs(ship.getDoubleOr("simSpinYaw", 0)) + Math.abs(ship.getDoubleOr("simSpinPitch", 0)) + Math.abs(ship.getDoubleOr("simSpinRoll", 0)) > 1e-9;
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
                var old = Piloting.flight(s);
                var next = fly(p.level(), s, l.buttons, power, ShipHeading.forward(p.level(), s).toYRot(), (l.buttons & FlightDynamics.ASSIST_OFF) == 0, server.getTickCount());
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
                    vessel.putInt("thrustDemand", 0);
                computers.remove();
                continue;
            }
            var old = Piloting.flight(vessel);
            // A script always flies with assist on: it asks for a motion and the computer gets there within the engines' limits.
            var next = fly(level, vessel, command.buttons() & ~FlightDynamics.ASSIST_OFF, propulsion(level, vessel), ShipHeading.forward(level, vessel).toYRot(), true, server.getTickCount());
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
        unattended(server, r);
        // A jump moves no blocks and no players: the ship's saved region and the sky change. It is committed on the first tick after the request, and each crew
        // client flashes while its sky changes; nothing waits for the clients and nobody is held.
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
                    SceneService.refresh(server);
                    var target = SceneCatalog.forShip(server, s).getStringOr("id", "");
                    // The flash: the scene to show is named so each client can hold the flash until its sky has changed.
                    broadcast(server, j, switch (j.mode)
                    {
                        case START -> "Entering FTL";
                        case RESUME -> "Resuming FTL";
                        case INVESTIGATE -> "Reaching the signal";
                        case ARRIVE -> "Arriving at " + LocalSpaces.label(server, s);
                    }, "scene:" + target, false);
                    com.nullandkale.nullspace.gameplay.sound.TravelSounds.jump(server, s);
                    finish(server, j, underwayMessage(server, j));
                }
                catch (RuntimeException error)
                {
                    finish(server, j, "Fast travel cancelled: " + error.getMessage());
                }
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
