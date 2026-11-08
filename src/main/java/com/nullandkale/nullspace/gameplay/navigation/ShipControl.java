package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets.Input;
import com.nullandkale.nullspace.gameplay.navigation.ControllerPackets.View;
import com.nullandkale.nullspace.gameplay.ownership.Claims;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.LooseBlocks;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationDeployment;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Shared controller/scanner destination. Every operation is authorized again on the server. */
public final class ShipControl
{

    public enum Action
    {
        SELECT, LAUNCH, JUMP, LAND, DOCK, UNDOCK, PILOT, CAMERA, ADVANCED, CLOSE_DOORS, AIRLOCK, DEPLOY, WAYPOINT, ACCEPT, DECLINE, CANCEL, REFRESH, CLOSE, INSPECT, SHOWCASE, EXPLAIN, SHOW_PROBLEM, INVESTIGATE, RESUME, REVIEW_LOOSE_BLOCKS, CONFIRM_LEAVE_BLOCKS, ENGAGE, DISENGAGE, MARK, BROWSE_SCANNER, RETURN_TO_CONTROLLER
    }

    static final class Session
    {
        final UUID id = UUID.randomUUID();
        final BlockPos position;
        final String dimension, ship;
        final boolean scanner;
        int expires, nextAction, nextSelection, response;
        List<ShipDestination> catalog = List.of();
        String message, setupProblem = "";
        Action problemAction = Action.LAUNCH;
        LooseBlocks.Review looseReview;

        Session(ServerPlayer p, BlockPos at, CompoundTag vessel, String message)
        {
            position = at.immutable();
            dimension = p.level().dimension().identifier().toString();
            ship = vessel.getStringOr("id", "");
            scanner = scanner(p, at);
            this.message = message;
            expires = p.level().getServer().getTickCount() + 1200;
        }
    }

    static final ServerContext.Key<Map<UUID, Session>> KEY = new ServerContext.Key<>();

    static Map<UUID, Session> sessions(MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, HashMap::new);
    }

    private static boolean scanner(ServerPlayer p, BlockPos at)
    {
        var block = p.level().getBlockState(at);
        return block.is(ModBlocks.LOCAL_SCANNER);
    }

    private static boolean accessible(ServerPlayer p, BlockPos at)
    {
        return p.isAlive() && p.level().hasChunkAt(at) && p.distanceToSqr(Vec3.atCenterOf(at)) <= 64 && (ModBlocks.navigation(p.level().getBlockState(at)) || scanner(p, at)) && p.mayInteract(p.level(), at) && StationAccess.mayUse(p, at) && !TransferLocks.busy(p) && !TransferLocks.locked(p.level(), at);
    }

    private static CompoundTag vessel(ServerPlayer p, Session s)
    {
        if (s.expires < p.level().getServer().getTickCount() || !s.dimension.equals(p.level().dimension().identifier().toString()) || !accessible(p, s.position) || scanner(p, s.position) != s.scanner)
            return null;
        var ship = WorldStore.get(p.level().getServer()).at(p.level(), s.position);
        return ship != null && ship.getStringOr("id", "").equals(s.ship) ? ship : null;
    }

    public static View open(ServerPlayer p, BlockPos at, String message)
    {
        if (!accessible(p, at))
            return null;
        if (p.containerMenu != p.inventoryMenu)
            p.closeContainer();
        // Opening a controller placed aboard a derelict whose own controller is destroyed claims it.
        var wreck = WorldStore.get(p.level().getServer()).at(p.level(), at);
        if (wreck != null && Claims.damaged(p.level(), wreck))
        {
            String claimed = Claims.claimWreck(p, wreck, at);
            if (!claimed.isEmpty())
                message = claimed;
        }
        var resolution = ShipTopology.find(p.level(), at);
        if (resolution.ship() == null)
        {
            var incomplete = new Session(p, at, new CompoundTag(), resolution.problem());
            incomplete.setupProblem = resolution.problem();
            sessions(p.level().getServer()).put(p.getUUID(), incomplete);
            var diagnostic = ControllerScreen.setupView(incomplete, true);
            send(p, diagnostic);
            return diagnostic;
        }
        CompoundTag own = resolution.ship();
        if (!scanner(p, at) && JourneyService.selectEncounterDock(p, own))
            WorldStore.get(p.level().getServer()).save();
        var session = new Session(p, at, own, message);
        if (!ShipSystems.enabled(own) && SystemsUpgrade.upgradeCheck(p.level(), own, at).ready())
            SystemsUpgrade.upgrade(p.level(), own);
        session.catalog = ControllerScreen.catalog(p, session, own);
        if (ShipDestination.selected(own) == null && !JourneyService.cruising(own))
            session.catalog.stream().filter(d -> d.kind() == ShipDestination.Kind.PAD && d.reference().equals(own.getStringOr("homeId", ""))).findFirst().ifPresent(d -> ShipDestination.select(p, own, d));
        sessions(p.level().getServer()).put(p.getUUID(), session);
        var view = ControllerScreen.view(p, session, own, true);
        send(p, view);
        return view;
    }

    private static void send(ServerPlayer p, View view)
    {
        if (ServerPlayNetworking.canSend(p, View.TYPE))
            ServerPlayNetworking.send(p, view);
    }

    /** A normal refusal is a result, including stale/forged destination selections. */
    public static String input(ServerPlayer p, Input packet)
    {
        var session = sessions(p.level().getServer()).get(p.getUUID());
        String result = applyInput(p, packet);
        if (session != null)
            session.response++;
        if (packet.action() != Action.CLOSE && packet.action() != Action.REFRESH)
        {
            var ship = session == null ? null : WorldStore.get(p.level().getServer()).ships().stream().map(t -> (CompoundTag) t).filter(s -> s.getStringOr("id", "").equals(session.ship)).findFirst().orElse(null);
            var destination = ship == null ? null : ShipDestination.selected(ship);
            String format = "Ship control {}: {} | player={} ship={} ({}) controller={} destination={}";
            var details = new Object[]{packet.action(), (result.isBlank() ? "Action accepted" : result).replace('\n', ' '), p.getPlainTextName(), ship == null ? "unavailable" : ship.getStringOr("home", "Ship"), session == null ? "unknown" : session.ship, session == null ? "unknown" : session.position.toShortString(), destination == null ? "none" : destination.name()};
            NullSpace.LOGGER.info(format, details);
        }
        return result;
    }

    private static String applyInput(ServerPlayer p, Input packet)
    {
        var session = sessions(p.level().getServer()).get(p.getUUID());
        if (session == null || !session.id.equals(packet.session()))
            return "Reopen this controller";
        if (packet.action() == Action.CLOSE)
        {
            sessions(p.level().getServer()).remove(p.getUUID());
            return "";
        }
        if (!session.setupProblem.isEmpty() && accessible(p, session.position))
        {
            if (packet.action() == Action.INSPECT || packet.action() == Action.SHOW_PROBLEM)
            {
                var next = open(p, session.position, "Ship setup checked");
                return next == null ? "Controller unavailable" : next.message();
            }
            return session.setupProblem;
        }
        var ship = vessel(p, session);
        if (ship == null)
            return "Controller unavailable, moved, or out of range";
        int tick = p.level().getServer().getTickCount();
        // Saving a destination must not throttle the travel click that follows its acknowledgement.
        // Keep each category bounded independently so repeated packets still cannot flood the server.
        if (packet.action() == Action.SELECT || packet.action() == Action.MARK)
        {
            if (tick < session.nextSelection)
                return "Wait a moment before the next action";
            session.nextSelection = tick + 2;
        }
        else
        {
            if (tick < session.nextAction)
                return "Wait a moment before the next action";
            session.nextAction = tick + 2;
        }
        session.expires = tick + 1200;
        if (packet.action() == Action.EXPLAIN)
        {
            var action = Arrays.stream(Action.values()).filter(a -> a.name().equals(packet.target()) && a != Action.EXPLAIN).findFirst();
            if (action.isEmpty())
                return "Unknown control; reopen this controller";
            if (session.scanner)
                return "Use the ship controller for travel";
            session.catalog = ControllerScreen.catalog(p, session, ship);
            session.problemAction = action.get();
            String reason = ControllerChecks.reason(p, ship, ControllerScreen.selected(p, session, ship), action.get());
            String label = action.get().name().toLowerCase(Locale.ROOT);
            label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            session.message = reason.isEmpty() ? label + " is ready now. Press it again to start." : label + " blocked: " + reason;
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(session.message));
            return session.message;
        }
        if (session.scanner && !Set.of(Action.SELECT, Action.REFRESH, Action.ADVANCED).contains(packet.action()))
            return "Use the ship controller for travel";
        String result = "";
        if (Set.of(Action.LAUNCH, Action.LAND, Action.JUMP).contains(packet.action()))
            session.problemAction = packet.action();
        try
        {
            if (packet.action() == Action.SELECT && (JourneyService.cruising(ship) || CourseService.active(ship)))
            {
                result = ControllerChecks.reason(p, ship, ControllerScreen.selected(p, session, ship), Action.SELECT);
            }
            else if (packet.action() == Action.SELECT || packet.action() == Action.MARK)
            {
                if (packet.action() == Action.MARK)
                {
                    var blocked = ControllerChecks.reason(p, ship, ControllerScreen.selected(p, session, ship), Action.MARK);
                    if (!blocked.isEmpty())
                        return blocked;
                }
                var candidate = session.catalog.stream().limit(512).filter(d -> d.id().equals(packet.target())).findFirst();
                if (candidate.isEmpty())
                    return "Choose a destination from this scanner or controller";
                var available = ControllerScreen.catalog(p, session, ship).stream().filter(d -> d.id().equals(packet.target())).findFirst();
                if (available.isEmpty())
                    return "Destination changed or is no longer available; the list updates automatically";
                ShipDestination.select(p, ship, available.get());
                ship.remove("markedDestination");
                result = "Destination set: " + available.get().name();
            }
            else
            {
                session.catalog = ControllerScreen.catalog(p, session, ship);
                var target = ControllerScreen.selected(p, session, ship);
                var reason = ControllerChecks.reason(p, ship, target, packet.action());
                if (!reason.isEmpty())
                    result = reason;
                else
                    switch (packet.action())
                    {
                        case ENGAGE -> result = CourseService.engage(p, ship, target);
                        case DISENGAGE -> result = CourseService.disengage(p.level().getServer(), ship);
                        case LAUNCH, LAND ->
                        {
                            var inspection = ShipService.preflight(p.level(), session.position);
                            if (ShipSystems.enabled(ship) && ShipSystems.power(p.level(), ship, PowerBus.System.ENGINES) == 0)
                                result = "Propulsion has no delivered power";
                            else
                                TravelService.request(p, session.position, packet.action() == Action.LAUNCH ? "" : "id:" + target.reference(), inspection);
                        }
                        case JUMP -> Jumps.jump(p, session.position, target.region());
                        case INVESTIGATE ->
                        {
                            Jumps.investigate(p, session.position);
                        }
                        case RESUME -> Jumps.resume(p, session.position);
                        case DOCK ->
                        {
                            boolean available = session.catalog.stream().anyMatch(d -> d.id().equals(target.id()));
                            if (!available)
                                result = "Selected port is unavailable; choose another destination";
                            else
                                result = DockingManeuver.request(p, ship, target.reference(), target.port()).message();
                        }
                        case UNDOCK, CANCEL, ACCEPT, DECLINE -> result = DockingService.perform(p, ship, session.position, "DOCK_" + packet.action().name());
                        case PILOT, CAMERA ->
                        {
                            NavigationService.open(p, session.position, packet.action() == Action.PILOT);
                            sessions(p.level().getServer()).remove(p.getUUID());
                        }
                        case ADVANCED ->
                        {
                            if (session.scanner)
                                MachineMenus.open(p, session.position);
                            else
                                SystemsConsole.action(p, session.position, ComputerTerminal.Action.SYSTEMS, "");
                            sessions(p.level().getServer()).remove(p.getUUID());
                        }
                        case CLOSE_DOORS -> result = SystemsConsole.closeDoors(p, session.position);
                        case AIRLOCK ->
                        {
                            StationService.open(p, session.position, "");
                            sessions(p.level().getServer()).remove(p.getUUID());
                        }
                        case DEPLOY ->
                        {
                            StationDeployment.start(p, ship);
                            result = "Station construction started";
                        }
                        case WAYPOINT, SHOWCASE -> result = "Stations are discovered automatically. Choose Destination > Ports.";
                        case REFRESH -> result = "Destinations refreshed";
                        case REVIEW_LOOSE_BLOCKS ->
                        {
                            session.looseReview = LooseBlocks.review(p.level(), session.position);
                            if (session.looseReview.loose().isEmpty())
                            {
                                session.looseReview = null;
                                result = "No loose blocks found. The connected ship passes its build and cabin checks.";
                            }
                            else
                            {
                                ShipDiagnostics.highlight(p, session.looseReview.loose().keySet().stream().limit(256).toList());
                                result = session.looseReview.message();
                            }
                        }
                        case CONFIRM_LEAVE_BLOCKS ->
                        {
                            var reviewed = session.looseReview;
                            session.looseReview = null;
                            result = LooseBlocks.confirm(p, reviewed);
                        }
                        case SHOW_PROBLEM ->
                        {
                            int base = session.problemAction == Action.JUMP && target != null ? StarSystems.jumpBase(p.level().getServer(), LocalSpaces.address(ship), target.region()) : FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size());
                            if (FuelSystem.available(p.level(), ship) < FuelSystem.travelCost(p.level(), ship, base))
                            {
                                var generator = ShipSystems.connected(p.level(), ship).stream().filter(at -> p.level().getBlockState(at).is(ModBlocks.GENERATOR)).findFirst();
                                if (generator.isPresent())
                                {
                                    ShipDiagnostics.highlight(p, List.of(generator.get()));
                                    result = "Put furnace fuel in the highlighted generator at " + generator.get().toShortString();
                                }
                                else
                                    result = "Add a generator connected to this ship's hull, then load furnace fuel";
                            }
                            else
                                try
                                {
                                    ShipService.preflight(p.level(), session.position);
                                    result = "No ship build problem found";
                                }
                                catch (HullScan.Failure failure)
                                {
                                    ShipDiagnostics.highlight(p, failure);
                                    result = ShipMessages.failure(failure);
                                }
                        }
                        case INSPECT -> result = ShipDiagnostics.summary(p.level(), ShipService.preflight(p.level(), session.position).scan());
                        default -> result = "Unsupported controller action";
                    }
            }
        }
        catch (IllegalArgumentException blocked)
        {
            if (blocked instanceof HullScan.Failure failure)
                ShipDiagnostics.highlight(p, failure);
            result = ShipMessages.failure(blocked);
        }
        if (packet.action() == Action.DOCK && !ship.contains("dock"))
            result = "Dock blocked: " + result;
        session.message = result;
        return result;
    }

    public static void initialize()
    {
        PayloadTypeRegistry.clientboundPlay().register(View.TYPE, View.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Input.TYPE, Input.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Input.TYPE, (packet, context) -> context.server().execute(() ->
        {
            try
            {
                var result = input(context.player(), packet);
                var s = sessions(context.server()).get(context.player().getUUID());
                if (s != null && s.id.equals(packet.session()) && !result.isEmpty())
                {
                    s.message = result;
                    var ship = vessel(context.player(), s);
                    if (!s.setupProblem.isEmpty())
                        send(context.player(), ControllerScreen.setupView(s, false));
                    else if (ship != null)
                        send(context.player(), ControllerScreen.view(context.player(), s, ship, false));
                    else
                    {
                        s.setupProblem = result;
                        send(context.player(), ControllerScreen.setupView(s, false));
                    }
                }
            }
            catch (Exception error)
            {
                NullSpace.LOGGER.error("Ship controls failed", error);
            }
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> sessions(server).remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
        ServerContext.tick(ServerContext.Phase.SESSIONS, server ->
        {
            if (server.getTickCount() % 20 != 0)
                return;
            var iterator = sessions(server).entrySet().iterator();
            while (iterator.hasNext())
            {
                var e = iterator.next();
                var p = server.getPlayerList().getPlayer(e.getKey());
                var s = e.getValue();
                if (p != null && !s.setupProblem.isEmpty() && accessible(p, s.position))
                {
                    s.expires = server.getTickCount() + 1200;
                    send(p, ControllerScreen.setupView(s, false));
                    continue;
                }
                var own = p == null ? null : vessel(p, s);
                if (own == null)
                {
                    iterator.remove();
                    continue;
                }
                if (server.getTickCount() % 20 == 0)
                    ControllerScreen.refreshCatalog(p, s, own);
                s.expires = server.getTickCount() + 1200;
                send(p, ControllerScreen.view(p, s, own, false));
            }
        });
    }

    private ShipControl()
    {
    }
}
