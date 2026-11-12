package com.nullandkale.nullspace.ui.terminal;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.ownership.Claims;
import com.nullandkale.nullspace.gameplay.ownership.ShipScrap;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYardService;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.systems.EquipmentPanel;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.engineering.EngineeringCommands;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.ui.menu.EquipmentMenus;
import com.nullandkale.nullspace.ui.menu.StationMenus;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Ack;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Authorized;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Close;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Destination;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Open;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Refresh;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Rejected;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Request;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets.Update;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Server-owned, expiring sessions. Commands remain for development; the GUI uses typed packets. */
public final class ComputerTerminal
{
    public static final UUID NONE = new UUID(0, 0);

    public enum Action
    {
        INSPECT, REGISTER, LAUNCH, DESTINATIONS, LAND, // pad ship: check, register, depart, land
        SYSTEMS, UPGRADE, CLOSE_DOORS, OPEN_DOORS, POWER_AIR, POWER_ENGINES, PRIORITY, POWER_WEAPONS, FIRE, CONFIGURE, // engineering
        DEVICE_REFRESH, DEVICE_ENABLE, DEVICE_DISABLE, DEVICE_RESET, DEVICE_ALLOCATE, DEVICE_CHANNEL, DEVICE_VIEW, // one device
        PILOT, CHASE, NAVIGATION, FAST_TRAVEL, HELM, // flying and navigation
        PREVIEW_PAD, PREVIEW_SHIP, SHIP_QUOTE, SHIP_BUY, DOCKS, // previews, shipyard purchase, docks list
        STATION_ARGUMENT, STATION_REFRESH, STATION_SYSTEMS, STATION_WAYPOINT, STATION_START, STATION_MODULE, STATION_GENERATE, STATION_RENAME, STATION_INVITE, STATION_ACCESS, STATION_DOOR, // station core
        DOCK_NEXT, DOCK_REQUEST, DOCK_ACCEPT, DOCK_CANCEL, DOCK_INNER, DOCK_OUTER, DOCK_BOARD, DOCK_CONSTRUCTION, DOCK_RETURN, DOCK_UNDOCK, DOCK_EMERGENCY, // docking
        MACHINE_BACK, // back to the machine screen
        STATION_REMOVE, STATION_EXPAND, // station builders and build area
        PAD_PROBLEM, PAD_ADVANCED, // pad diagnostics
        RECALL_LIST, RECALL_SHIP, RECALL_CANCEL, RECALL_BACK, // recall
        CONTROLLER_BACK, // back to the controller screen
        SHIP_STORE, SHIP_TITLE, STATION_CLAIM, SHIP_SHOW, SHIP_SCRAP // storage, titles, claims, scrapping
    }

    static final class Session
    {
        final UUID id = UUID.randomUUID();
        final BlockPos position;
        final String dimension, mode;
        final LinkedHashMap<String, Destination> destinations = new LinkedHashMap<>();
        int expires, nextAction;
        String query = "", value = "";

        Session(ServerPlayer p, BlockPos pos, String mode)
        {
            position = pos.immutable();
            dimension = p.level().dimension().identifier().toString();
            this.mode = mode;
        }
    }

    static final ServerContext.Key<Map<UUID, Session>> KEY = new ServerContext.Key<>();

    static Map<UUID, Session> sessions(net.minecraft.server.MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, HashMap::new);
    }

    public static boolean current(ServerPlayer p, UUID id)
    {
        var s = sessions(p.level().getServer()).get(p.getUUID());
        return s != null && s.id.equals(id) && valid(p, s);
    }

    static boolean valid(ServerPlayer p, Session s)
    {
        var level = p.level();
        if (!p.isAlive() || s.expires < level.getServer().getTickCount() || !s.dimension.equals(level.dimension().identifier().toString()))
            return false;
        if (p.distanceToSqr(s.position.getX() + .5, s.position.getY() + .5, s.position.getZ() + .5) > 64 || !level.hasChunkAt(s.position))
            return false;
        return blockServes(level, s) && p.mayInteract(level, s.position) && StationAccess.mayUse(p, s.position) && !TransferLocks.busy(p) && !TransferLocks.locked(level, s.position);
    }

    /** The block at the session's position is still the kind of block this session's mode belongs to. */
    private static boolean blockServes(net.minecraft.server.level.ServerLevel level, Session s)
    {
        var state = level.getBlockState(s.position);
        if (StationMenus.supports(s.mode))
            return s.mode.equals(StationService.mode(level, s.position));
        if (s.mode.equals("shipyard"))
            return state.is(ModBlocks.SHIP_BEACON);
        if (s.mode.equals("pad") || s.mode.equals("recall"))
            return state.is(PadComputers.BLOCK);
        if (EquipmentMenus.supports(s.mode))
            return s.mode.equals(ModBlocks.equipmentMode(state));
        return ModBlocks.navigation(state) || s.mode.equals("systems") && state.is(ModBlocks.STATION_CORE);
    }

    public static Open issue(ServerPlayer player, BlockPos pos, String message, String mode, String value)
    {
        if (!mode.equals("recall") && !mode.equals("pad") && !mode.equals("ship") && !mode.equals("systems") && !mode.equals("navigation") && !mode.equals("shipyard") && !EquipmentMenus.supports(mode) && !StationMenus.supports(mode))
            throw new IllegalArgumentException("Unknown controller mode");
        var candidate = new Session(player, pos, mode);
        candidate.expires = player.level().getServer().getTickCount() + 1200;
        if (!valid(player, candidate))
            return new Open(pos, message, mode, value);
        var old = sessions(player.level().getServer()).get(player.getUUID());
        var session = old != null && valid(player, old) && old.position.equals(pos) && old.mode.equals(mode) ? old : candidate;
        session.expires = candidate.expires;
        var catalog = new ArrayList<>(mode.equals("ship") ? PadRegistry.read(player.level().getServer()).stream().filter(pad ->
        {
            var ship = WorldStore.get(player.level().getServer()).at(player.level(), pos);
            return !player.level().dimension().equals(TravelService.SPACE) || PlanetSurfaces.surfaceOf(player.level().getServer(), LocalSpaces.address(ship), pad.dimension());
        }).toList() : List.<PadRegistry.Pad>of());
        Collections.reverse(catalog);
        catalog.sort(Comparator.comparingInt(pad -> pad.name().equalsIgnoreCase(value) ? 0 : 1));
        if (mode.equals("ship"))
            for (var pad : catalog)
            {
                if (session.destinations.size() >= 128)
                    break;
                var status = PadRegistry.state(player.level().getServer(), pad);
                session.destinations.putIfAbsent(pad.name().toLowerCase(Locale.ROOT), new Destination(pad.id(), pad.name(), status.state().name()));
            }
        if (mode.equals("pad"))
        {
            session.destinations.clear();
            var inspected = PadComputers.inspect(player, pos);
            boolean registered = PadRegistry.read(player.level().getServer()).stream().anyMatch(p -> p.dimension().equals(player.level().dimension().identifier().toString()) && p.computer().equals(pos));
            session.destinations.put("pad_status", new Destination("pad_status", inspected.valid() ? inspected.pad().width() + " x " + inspected.pad().depth() : "Needs repair", !inspected.valid() ? "INVALID" : registered ? "REGISTERED" : "VALID"));
        }
        if (mode.equals("navigation"))
        {
            session.destinations.clear();
            for (var route : LocalSpaces.destinations(player.level().getServer()))
                session.destinations.put(route.id(), new Destination(route.id(), route.systemName() + " / " + route.name(), route.id().equals(value) ? "CURRENT" : "READY"));
        }
        if (mode.equals("systems"))
        {
            var own = WorldStore.get(player.level().getServer()).at(player.level(), pos);
            if (own != null)
            {
                var requests = Arrays.copyOf(own.getCompoundOrEmpty("systems").getIntArray("requests").orElse(new int[0]), 6);
                session.destinations.put("air", new Destination("air", Integer.toString(requests[1]), "ALLOCATION"));
                session.destinations.put("engines", new Destination("engines", Integer.toString(requests[3]), "ALLOCATION"));
                session.destinations.put("weapons", new Destination("weapons", Integer.toString(requests[5]), "ALLOCATION"));
            }
        }
        else if (EquipmentMenus.supports(mode))
        {
            int request = EquipmentPanel.requestedAllocation(player.level(), pos);
            if (request >= 0)
                session.destinations.put("device", new Destination("device", Integer.toString(request), "ALLOCATION"));
        }
        session.value = value;
        if (mode.equals("shipyard"))
        {
            session.destinations.clear();
            for (var preset : ShipBlueprints.PRESETS)
                session.destinations.put(preset.id(), new Destination(preset.id(), preset.name(), "FREE"));
        }
        if (mode.equals("recall"))
        {
            session.destinations.clear();
            for (var ship : ShipStorage.candidates(player, pos))
                session.destinations.put(ship.id(), ship);
            var pad = PadRegistry.read(player.level().getServer()).stream().filter(p -> p.computer().equals(pos) && p.dimension().equals(player.level().dimension().identifier().toString())).findFirst();
            pad.ifPresent(p -> session.destinations.put("recall_pad", new Destination("recall_pad", p.name(), ShipStorage.active(player) ? "ACTIVE" : "IDLE")));
        }
        if (mode.equals("station_core"))
        {
            session.destinations.clear();
            var station = WorldStore.get(player.level().getServer()).at(player.level(), pos);
            if (station != null)
            {
                boolean encounter = station.getBooleanOr("worldGenerated", false) && station.getStringOr("owner", "").isEmpty();
                session.destinations.put("station_view", new Destination("station_view", Claims.claimable(station) ? "Derelict" : !encounter && StationAccess.builder(player, station) ? "Construction" : "Visitor", "SETTING"));
                session.destinations.put("station_rights", new Destination("station_rights", StationAccess.owner(player, station) ? "Owner" : "Builder", "SETTING"));
                if (Claims.claimable(station))
                    session.destinations.put("station_claim", new Destination("station_claim", "Claim", "CLAIMABLE"));
                session.destinations.put("station_name", new Destination("station_name", station.getStringOr("home", "Station"), "SETTING"));
                session.destinations.put("station_access", new Destination("station_access", station.getStringOr("access", "private").equals("public") ? "Allowed" : "Not allowed", "SETTING"));
                if (!station.getStringOr("constructionShip", "").isEmpty())
                    session.destinations.put("construction_return", new Destination("construction_return", "My ship", "SETTING"));
                for (var online : player.level().getServer().getPlayerList().getPlayers().stream().limit(60).toList())
                    session.destinations.put("player_" + online.getUUID().toString().replace("-", ""), new Destination(online.getUUID().toString().replace("-", ""), online.getPlainTextName(), "PLAYER"));
                for (var id : station.getCompoundOrEmpty("builders").keySet())
                {
                    if (session.destinations.size() >= 60)
                        break;
                    var name = station.getCompoundOrEmpty("builderNames").getStringOr(id, id.substring(0, 8));
                    session.destinations.put(id, new Destination(id, name, "BUILDER"));
                }
                session.destinations.put("station_area", new Destination("station_area", Integer.toString(station.getIntOr("width", 32) / 32), "STATION_AREA"));
                var rooms = station.getCompoundOrEmpty("roomPieces");
                for (var cell : rooms.keySet())
                    session.destinations.put(cell, new Destination(cell, rooms.getStringOr(cell, "Room"), "ROOM"));
            }
        }
        if (old != null && old != session)
            ShipSystems.cancel(player, old.position);
        sessions(player.level().getServer()).put(player.getUUID(), session);
        return new Open(pos, message.length() > 2048 ? message.substring(0, 2048) : message, mode, value, session.id, List.copyOf(session.destinations.values()));
    }

    /** Engineering requests that can change a ship need the revision the player last saw. Reads and navigation do not. */
    private static boolean changesEngineering(String mode, Request request)
    {
        if (!mode.equals("systems") && !EquipmentMenus.supports(mode))
            return false;
        var action = request.action();
        boolean reading = action == Action.SYSTEMS || action == Action.DEVICE_REFRESH || action == Action.DEVICE_VIEW;
        boolean leaving = action == Action.MACHINE_BACK || action == Action.CONTROLLER_BACK;
        boolean readOnlyCommand = action == Action.CONFIGURE && EngineeringCommands.readOnly(request.argument());
        return !reading && !leaving && !readOnlyCommand;
    }

    /** What a session in this mode may ask for. Menus list their own verbs; the other modes have fixed sets. */
    private static EnumSet<Action> allowedActions(String mode)
    {
        var allowed = EnumSet.noneOf(Action.class);
        if (StationMenus.supports(mode))
            StationMenus.entries(mode).stream().filter(e -> !e.verb().equals("station_argument")).forEach(e -> allowed.add(Action.valueOf(e.verb().toUpperCase(Locale.ROOT))));
        else if (EquipmentMenus.supports(mode))
            EquipmentMenus.entries(mode).forEach(e -> allowed.add(Action.valueOf(e.verb().toUpperCase(Locale.ROOT))));
        else
            allowed.addAll(switch (mode)
            {
                case "shipyard" -> EnumSet.of(Action.SHIP_QUOTE, Action.SHIP_BUY);
                case "recall" -> EnumSet.of(Action.RECALL_LIST, Action.RECALL_SHIP, Action.RECALL_CANCEL, Action.RECALL_BACK, Action.SHIP_STORE, Action.SHIP_TITLE, Action.SHIP_SHOW, Action.SHIP_SCRAP);
                case "pad" -> EnumSet.of(Action.RECALL_LIST, Action.INSPECT, Action.REGISTER, Action.PREVIEW_PAD, Action.PAD_PROBLEM, Action.PAD_ADVANCED, Action.PREVIEW_SHIP);
                case "ship" -> EnumSet.of(Action.INSPECT, Action.LAUNCH, Action.LAND, Action.DESTINATIONS, Action.SYSTEMS, Action.FIRE, Action.PILOT, Action.CHASE, Action.NAVIGATION);
                case "navigation" -> EnumSet.of(Action.FAST_TRAVEL, Action.HELM);
                default -> EnumSet.of(Action.SYSTEMS, Action.UPGRADE, Action.CLOSE_DOORS, Action.OPEN_DOORS, Action.POWER_AIR, Action.POWER_ENGINES, Action.POWER_WEAPONS, Action.PRIORITY, Action.CONFIGURE);
            });
        return allowed;
    }

    /**
     * Validation is also exercised by local server tests; rejects never consume another player's
     * session.
     */
    public static Authorized authorize(ServerPlayer player, Request request)
    {
        var s = sessions(player.level().getServer()).get(player.getUUID());
        if (s == null || !s.id.equals(request.session()))
            throw new IllegalArgumentException("Terminal session expired or belongs to another player. Reopen the computer.");
        if (!valid(player, s))
        {
            sessions(player.level().getServer()).remove(player.getUUID());
            throw new IllegalArgumentException("Computer moved, changed, is out of range, or session expired. Reopen it.");
        }
        var allowed = allowedActions(s.mode);
        var at = player.level().getBlockState(s.position);
        if (s.mode.equals("ship"))
            allowed.add(Action.DOCKS);
        if (player.level().getBlockEntity(s.position) instanceof MachineBlockEntity)
            allowed.add(Action.MACHINE_BACK);
        if (ModBlocks.navigation(at) || at.is(ModBlocks.STATION_CORE))
            allowed.add(Action.CONTROLLER_BACK);
        if (!allowed.contains(request.action()))
            throw new IllegalArgumentException("Action does not belong to this controller");
        if (changesEngineering(s.mode, request))
        {
            var vessel = ShipTopology.resolve(player.level(), s.position);
            if (request.revision() != ShipSystems.revision(player.level(), vessel))
                throw new IllegalArgumentException("Systems changed. Refresh before editing.");
        }
        if (request.argument().length() > 32 || !request.argument().matches("[A-Za-z0-9 _-]*"))
            throw new IllegalArgumentException("Use at most 32 letters, numbers, spaces, underscores, or hyphens in this field.");
        String argument = request.argument();
        if ((request.action() == Action.SHIP_QUOTE || request.action() == Action.SHIP_BUY) && !s.destinations.containsKey(argument))
            throw new IllegalArgumentException("Choose a ship from this beacon's catalog.");
        if (request.action() == Action.FAST_TRAVEL && !s.destinations.containsKey(argument))
            throw new IllegalArgumentException("Choose a route from this session");
        if (request.action() == Action.LAND)
        {
            var chosen = s.destinations.get(argument.strip().toLowerCase(Locale.ROOT));
            if (chosen == null || !chosen.id().equals(request.destinationId()))
                throw new IllegalArgumentException("Choose a destination from this session's catalog. Reopen to refresh it.");
            argument = "id:" + chosen.id();
        }
        int tick = player.level().getServer().getTickCount();
        if (tick < s.nextAction)
            throw new IllegalArgumentException("Please wait a moment before the next action");
        s.nextAction = tick + 2;
        s.expires = tick + 1200;
        return new Authorized(s.position, s.mode, argument);
    }

    public static void close(ServerPlayer p, UUID id)
    {
        var s = sessions(p.level().getServer()).get(p.getUUID());
        if (s != null && s.id.equals(id))
        {
            sessions(p.level().getServer()).remove(p.getUUID());
            ShipSystems.cancel(p, s.position);
        }
    }

    /** The live engineering page belongs to this viewer, never to the ship or another viewer. */
    public static void follow(ServerPlayer player, BlockPos pos, String query)
    {
        var session = sessions(player.level().getServer()).get(player.getUUID());
        if (session != null && session.mode.equals("systems") && session.position.equals(pos))
            session.query = query;
    }

    public static String reading(ServerPlayer player, BlockPos pos)
    {
        var session = sessions(player.level().getServer()).get(player.getUUID());
        return SystemsConsole.reading(player, pos, session != null && session.position.equals(pos) ? session.query : "");
    }

    public static void initialize()
    {
        PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Refresh.TYPE, Refresh.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Ack.TYPE, Ack.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Rejected.TYPE, Rejected.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Update.TYPE, Update.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Close.TYPE, Close.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (packet, context) -> context.server().execute(() ->
        {
            var player = context.player();
            try
            {
                var a = authorize(player, packet);
                if (packet.action() == Action.CONTROLLER_BACK)
                {
                    close(player, packet.session());
                    if (player.level().getBlockState(a.position()).is(ModBlocks.STATION_CORE))
                        StationService.open(player, a.position(), "");
                    else
                        ShipControl.open(player, a.position(), "Returned from engineering");
                }
                else if (packet.action() == Action.MACHINE_BACK)
                {
                    close(player, packet.session());
                    MachineMenus.open(player, a.position());
                }
                else if (a.mode().equals("recall"))
                {
                    if (packet.action() == Action.RECALL_BACK)
                        PadComputers.action(player, a.position(), null);
                    else
                    {
                        String notice = switch (packet.action())
                        {
                            case RECALL_SHIP -> ShipStorage.request(player, a.position(), a.argument(), packet.session());
                            case RECALL_CANCEL -> ShipStorage.cancel(player);
                            case SHIP_STORE -> ShipStorage.store(player, a.position(), a.argument(), packet.session());
                            case SHIP_TITLE -> ShipStorage.title(player, a.position(), a.argument());
                            case SHIP_SHOW -> ShipScrap.show(player, a.position(), a.argument());
                            case SHIP_SCRAP -> ShipScrap.scrap(player, a.position(), a.argument());
                            default -> "";
                        };
                        ShipStorage.open(player, a.position(), notice);
                    }
                }
                else if (a.mode().equals("pad"))
                {
                    if (packet.action() == Action.RECALL_LIST)
                        ShipStorage.open(player, a.position(), "");
                    else if (packet.action() == Action.PAD_PROBLEM || packet.action() == Action.PAD_ADVANCED)
                        PadComputers.details(player, a.position(), packet.action() == Action.PAD_PROBLEM);
                    else if (packet.action() == Action.PREVIEW_PAD || packet.action() == Action.PREVIEW_SHIP)
                        PadComputers.preview(player, a.position(), packet.action() == Action.PREVIEW_SHIP);
                    else
                        PadComputers.action(player, a.position(), packet.action() == Action.REGISTER ? a.argument() : null);
                }
                else if (StationMenus.supports(a.mode()))
                {
                    if (packet.action() == Action.HELM)
                        ShipService.open(player, a.position(), "Ready");
                    else
                        StationService.action(player, a.position(), packet.action(), a.argument());
                }
                else if (a.mode().equals("shipyard"))
                    ShipYardService.action(player, a.position(), a.argument(), packet.action() == Action.SHIP_BUY);
                else if (EquipmentMenus.supports(a.mode()))
                    EquipmentPanel.equipmentAction(player, a.position(), packet.action(), a.argument());
                else
                    ShipService.act(player, a.position(), packet.action().name().toLowerCase(Locale.ROOT), a.argument());
                ServerPlayNetworking.send(player, new Ack(packet.session()));
            }
            catch (Exception error)
            {
                if (!(error instanceof IllegalArgumentException))
                    NullSpace.LOGGER.error("Computer action failed for {}", player.getPlainTextName(), error);
                ServerPlayNetworking.send(player, new Rejected(packet.session(), ShipMessages.failure(error)));
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(Close.TYPE, (packet, context) -> context.server().execute(() -> close(context.player(), packet.session())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
        {
            var s = sessions(server).remove(handler.player.getUUID());
            if (s != null)
                ShipSystems.cancel(handler.player, s.position);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
        ServerContext.tick(ServerContext.Phase.SESSIONS, server ->
        {
            var active = sessions(server);
            // Cancellation and network callbacks may close or replace another player's session.
            for (var entry : Map.copyOf(active).entrySet())
            {
                var p = server.getPlayerList().getPlayer(entry.getKey());
                var session = entry.getValue();
                if ((p == null || !valid(p, session)) && active.remove(entry.getKey(), session) && p != null)
                    ShipSystems.cancel(p, session.position);
            }
            if (server.getTickCount() % 20 != 0)
                return;
            for (var entry : Map.copyOf(active).entrySet())
            {
                var session = entry.getValue();
                if (active.get(entry.getKey()) != session)
                    continue;
                var player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null)
                    continue;
                if (session.mode.equals("shipyard"))
                {
                    var preview = ShipYardService.preview(player, session.position, session.value);
                    if (active.get(entry.getKey()) != session)
                        continue;
                    ServerPlayNetworking.send(player, new Update(session.id, preview.message(), preview.readiness()));
                    session.expires = server.getTickCount() + 1200;
                    continue;
                }
                if (session.mode.equals("recall"))
                {
                    ServerPlayNetworking.send(player, new Refresh(issue(player, session.position, "", session.mode, session.value)));
                    session.expires = server.getTickCount() + 1200;
                    continue;
                }
                boolean equipment = EquipmentMenus.supports(session.mode);
                boolean station = StationMenus.supports(session.mode);
                if ((!session.mode.equals("systems") && !equipment && !station) || !ServerPlayNetworking.canSend(player, Update.TYPE))
                    continue;
                var message = equipment ? EquipmentPanel.equipmentStatus(player.level(), session.position) : "";
                var ship = WorldStore.get(server).at(player.level(), session.position);
                if (ship == null && !equipment)
                    continue;
                if (station)
                {
                    try
                    {
                        message = StationService.status(player, ship, session.position);
                    }
                    catch (IllegalArgumentException damaged)
                    {
                        message = damaged.getMessage();
                    }
                }
                else if (!equipment)
                    message = reading(player, session.position);
                int revision = station ? server.getTickCount() : ship == null ? 0 : ShipSystems.revision(player.level(), ship);
                if (active.get(entry.getKey()) != session)
                    continue;
                if (session.mode.equals("station_core"))
                    ServerPlayNetworking.send(player, new Refresh(issue(player, session.position, message, session.mode, Integer.toString(revision))));
                else
                    ServerPlayNetworking.send(player, new Refresh(issue(player, session.position, message, session.mode, Integer.toString(revision))));
                session.expires = server.getTickCount() + 1200;
            }
        });
    }

    public static void send(ServerPlayer p, BlockPos pos, String message, String mode, String value)
    {
        if (p.containerMenu != p.inventoryMenu)
            p.closeContainer();
        ServerPlayNetworking.send(p, issue(p, pos, message, mode, value));
    }

    public static void open(ServerPlayer p, BlockPos pos, String message)
    {
        send(p, pos, message, "pad", "");
    }

    private ComputerTerminal()
    {
    }
}
