package com.nullandkale.nullspace.terminal;

import com.nullandkale.nullspace.pad.PadComputers;
import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Server-owned, expiring sessions. Commands remain for development; the GUI uses typed packets. */
public final class ComputerTerminal {
  public static final UUID NONE = new UUID(0, 0);

  public enum Action {
    INSPECT,
    REGISTER,
    LAUNCH,
    DESTINATIONS,
    LAND,
    SYSTEMS,
    UPGRADE,
    CLOSE_DOORS,
    OPEN_DOORS,
    POWER_AIR,
    POWER_ENGINES,
    PRIORITY,
    POWER_WEAPONS,
    FIRE,
    CONFIGURE,
    DEVICE_REFRESH,
    DEVICE_ENABLE,
    DEVICE_DISABLE,
    DEVICE_RESET,
    DEVICE_ALLOCATE,
    DEVICE_CHANNEL,
    DEVICE_VIEW,
    PILOT,
    CHASE,
    NAVIGATION,
    FAST_TRAVEL,
    HELM,
    PREVIEW_PAD,
    PREVIEW_SHIP,
    SHIP_QUOTE,
    SHIP_BUY,
    DOCKS,
    STATION_ARGUMENT,
    STATION_REFRESH,
    STATION_SYSTEMS,
    STATION_WAYPOINT,
    STATION_START,
    STATION_MODULE,
    STATION_GENERATE,
    STATION_RENAME,
    STATION_INVITE,
    STATION_ACCESS,
    STATION_DOOR,
    DOCK_NEXT,
    DOCK_REQUEST,
    DOCK_ACCEPT,
    DOCK_CANCEL,
    DOCK_INNER,
    DOCK_OUTER,
    DOCK_BOARD,
    DOCK_CONSTRUCTION,
    DOCK_RETURN,
    DOCK_UNDOCK,
    DOCK_EMERGENCY,
    MACHINE_BACK,
    STATION_REMOVE,
    STATION_EXPAND,
    PAD_PROBLEM,
    PAD_ADVANCED,
    RECALL_LIST,
    RECALL_SHIP,
    RECALL_CANCEL,
    RECALL_BACK,
    CONTROLLER_BACK,
    SHIP_STORE,
    SHIP_TITLE,
    STATION_CLAIM
  }

  public record Destination(String id, String name, String state, String detail) {
    public Destination(String id, String name, String state) {
      this(id, name, state, "");
    }
  }

  /** Session-scoped live data can never open a closed terminal. */
  public record Refresh(Open view) implements CustomPacketPayload {
    public static final Type<Refresh> TYPE =
        new Type<>(Identifier.parse("nullspace:terminal_refresh"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Refresh> CODEC =
        Open.CODEC.map(Refresh::new, Refresh::view);

    public Type<Refresh> type() {
      return TYPE;
    }
  }

  public record Ack(UUID session) implements CustomPacketPayload {
    public static final Type<Ack> TYPE = new Type<>(Identifier.parse("nullspace:terminal_ack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Ack> CODEC =
        StreamCodec.of((b, p) -> b.writeUUID(p.session), b -> new Ack(b.readUUID()));

    public Type<Ack> type() {
      return TYPE;
    }
  }

  private static final class Session {
    final UUID id = UUID.randomUUID();
    final BlockPos position;
    final String dimension, mode;
    final LinkedHashMap<String, Destination> destinations = new LinkedHashMap<>();
    int expires, nextAction;
    String query = "", value = "";

    Session(ServerPlayer p, BlockPos pos, String mode) {
      position = pos.immutable();
      dimension = p.level().dimension().identifier().toString();
      this.mode = mode;
    }
  }

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, Session>> KEY =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, Session> sessions(net.minecraft.server.MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(KEY, HashMap::new);
  }

  /** Readings never open a screen: a late update for a closed session is discarded. */
  public record Update(UUID session, String message, int revision) implements CustomPacketPayload {
    public Update {
      if (message.length() > 2048) message = message.substring(0, 2048);
    }

    public static final Type<Update> TYPE =
        new Type<>(Identifier.parse("nullspace:terminal_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeUtf(p.message, 2048);
              b.writeInt(p.revision);
            },
            b -> new Update(b.readUUID(), b.readUtf(2048), b.readInt()));

    @Override
    public Type<Update> type() {
      return TYPE;
    }
  }

  public record Open(
      BlockPos position,
      String message,
      String mode,
      String value,
      UUID session,
      List<Destination> destinations)
      implements CustomPacketPayload {
    public Open(BlockPos p, String message) {
      this(p, message, "pad", "");
    }

    public Open(BlockPos p, String message, String mode) {
      this(p, message, mode, "");
    }

    public Open(BlockPos p, String message, String mode, String value) {
      this(p, message, mode, value, NONE, List.of());
    }

    public static final Type<Open> TYPE = new Type<>(Identifier.parse("nullspace:terminal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeBlockPos(p.position);
              b.writeUtf(p.message, 2048);
              b.writeUtf(p.mode, 32);
              b.writeUtf(p.value, 32);
              b.writeUUID(p.session);
              b.writeVarInt(p.destinations.size());
              for (var d : p.destinations) {
                b.writeUtf(d.id, 36);
                b.writeUtf(d.name, 160);
                b.writeUtf(d.state, 16);
                b.writeUtf(d.detail, 256);
              }
            },
            b -> {
              var pos = b.readBlockPos();
              var message = b.readUtf(2048);
              var mode = b.readUtf(32);
              var value = b.readUtf(32);
              var id = b.readUUID();
              int count = b.readVarInt();
              if (count < 0 || count > 256)
                throw new IllegalArgumentException("Invalid terminal catalog size");
              var destinations = new ArrayList<Destination>();
              for (int i = 0; i < count; i++)
                destinations.add(
                    new Destination(b.readUtf(36), b.readUtf(160), b.readUtf(16), b.readUtf(256)));
              return new Open(pos, message, mode, value, id, List.copyOf(destinations));
            });

    @Override
    public Type<Open> type() {
      return TYPE;
    }
  }

  public record Request(
      UUID session, Action action, String argument, String destinationId, int revision)
      implements CustomPacketPayload {
    public Request(UUID session, Action action, String argument, String destinationId) {
      this(session, action, argument, destinationId, -1);
    }

    public static final Type<Request> TYPE =
        new Type<>(Identifier.parse("nullspace:terminal_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeEnum(p.action);
              b.writeUtf(p.argument, 32);
              b.writeUtf(p.destinationId, 36);
              b.writeInt(p.revision);
            },
            b ->
                new Request(
                    b.readUUID(),
                    b.readEnum(Action.class),
                    b.readUtf(32),
                    b.readUtf(36),
                    b.readInt()));

    @Override
    public Type<Request> type() {
      return TYPE;
    }
  }

  public record Close(UUID session) implements CustomPacketPayload {
    public static final Type<Close> TYPE = new Type<>(Identifier.parse("nullspace:terminal_close"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC =
        StreamCodec.of((b, p) -> b.writeUUID(p.session), b -> new Close(b.readUUID()));

    @Override
    public Type<Close> type() {
      return TYPE;
    }
  }

  public record Rejected(UUID session, String reason) implements CustomPacketPayload {
    public static final Type<Rejected> TYPE =
        new Type<>(Identifier.parse("nullspace:terminal_rejected"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Rejected> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeUtf(p.reason, 256);
            },
            b -> new Rejected(b.readUUID(), b.readUtf(256)));

    @Override
    public Type<Rejected> type() {
      return TYPE;
    }
  }

  public record Authorized(BlockPos position, String mode, String argument) {}

  public static boolean current(ServerPlayer p, UUID id) {
    var s = sessions(p.level().getServer()).get(p.getUUID());
    return s != null && s.id.equals(id) && valid(p, s);
  }

  private static boolean valid(ServerPlayer p, Session s) {
    return p.isAlive()
        && s.expires >= p.level().getServer().getTickCount()
        && s.dimension.equals(p.level().dimension().identifier().toString())
        && p.distanceToSqr(s.position.getX() + .5, s.position.getY() + .5, s.position.getZ() + .5)
            <= 64
        && p.level().hasChunkAt(s.position)
        && (com.nullandkale.nullspace.station.StationMenus.supports(s.mode)
            ? s.mode.equals(
                com.nullandkale.nullspace.station.StationService.mode(p.level(), s.position))
            : s.mode.equals("shipyard")
                ? p.level().getBlockState(s.position).is(ShipBlocks.SHIP_BEACON)
                : (s.mode.equals("pad") || s.mode.equals("recall"))
                    ? p.level().getBlockState(s.position).is(PadComputers.BLOCK)
                    : EquipmentMenus.supports(s.mode)
                        ? s.mode.equals(
                            ShipBlocks.equipmentMode(p.level().getBlockState(s.position)))
                        : ShipBlocks.navigation(p.level().getBlockState(s.position))
                            || s.mode.equals("systems")
                                && (p.level()
                                        .getBlockState(s.position)
                                        .is(ShipBlocks.SYSTEMS_CONTROLLER)
                                    || p.level()
                                        .getBlockState(s.position)
                                        .is(ShipBlocks.STATION_CORE)))
        && p.mayInteract(p.level(), s.position)
        && com.nullandkale.nullspace.station.StationService.mayUse(p, s.position)
        && !TravelService.busy(p)
        && !TravelService.locked(p.level(), s.position);
  }

  public static Open issue(
      ServerPlayer player, BlockPos pos, String message, String mode, String value) {
    if (!mode.equals("recall")
        && !mode.equals("pad")
        && !mode.equals("ship")
        && !mode.equals("systems")
        && !mode.equals("navigation")
        && !mode.equals("shipyard")
        && !EquipmentMenus.supports(mode)
        && !com.nullandkale.nullspace.station.StationMenus.supports(mode))
      throw new IllegalArgumentException("Unknown controller mode");
    var candidate = new Session(player, pos, mode);
    candidate.expires = player.level().getServer().getTickCount() + 1200;
    if (!valid(player, candidate)) return new Open(pos, message, mode, value);
    var old = sessions(player.level().getServer()).get(player.getUUID());
    var session =
        old != null && valid(player, old) && old.position.equals(pos) && old.mode.equals(mode)
            ? old
            : candidate;
    session.expires = candidate.expires;
    var catalog =
        new ArrayList<>(
            mode.equals("ship")
                ? PadRegistry.read(player.level().getServer()).stream()
                    .filter(
                        pad -> {
                          var ship =
                              ShipStore.get(player.level().getServer()).at(player.level(), pos);
                          return !player.level().dimension().equals(TravelService.SPACE)
                              || pad.dimension()
                                  .equals(
                                      com.nullandkale.nullspace.navigation.PlanetSurfaces.dimension(
                                              com.nullandkale.nullspace.navigation.LocalSpaces
                                                  .address(ship))
                                          .identifier()
                                          .toString());
                        })
                    .toList()
                : List.<PadRegistry.Pad>of());
    Collections.reverse(catalog);
    catalog.sort(Comparator.comparingInt(pad -> pad.name().equalsIgnoreCase(value) ? 0 : 1));
    if (mode.equals("ship"))
      for (var pad : catalog) {
        if (session.destinations.size() >= 128) break;
        var status = PadRegistry.state(player.level().getServer(), pad);
        session.destinations.putIfAbsent(
            pad.name().toLowerCase(Locale.ROOT),
            new Destination(pad.id(), pad.name(), status.state().name()));
      }
    if (mode.equals("pad")) {
      session.destinations.clear();
      var inspected = PadComputers.inspect(player, pos);
      boolean registered =
          PadRegistry.read(player.level().getServer()).stream()
              .anyMatch(
                  p ->
                      p.dimension().equals(player.level().dimension().identifier().toString())
                          && p.computer().equals(pos));
      session.destinations.put(
          "pad_status",
          new Destination(
              "pad_status",
              inspected.valid()
                  ? inspected.pad().width() + " x " + inspected.pad().depth()
                  : "Needs repair",
              !inspected.valid() ? "INVALID" : registered ? "REGISTERED" : "VALID"));
    }
    if (mode.equals("navigation")) {
      session.destinations.clear();
      for (var route :
          com.nullandkale.nullspace.navigation.LocalSpaces.destinations(player.level().getServer()))
        session.destinations.put(
            route.id(),
            new Destination(
                route.id(),
                route.systemName() + " / " + route.name(),
                route.id().equals(value) ? "CURRENT" : "READY"));
    }
    if (mode.equals("systems")) {
      var own = ShipStore.get(player.level().getServer()).at(player.level(), pos);
      if (own != null) {
        var requests =
            Arrays.copyOf(
                own.getCompoundOrEmpty("systems").getIntArray("requests").orElse(new int[0]), 6);
        session.destinations.put(
            "air", new Destination("air", Integer.toString(requests[1]), "ALLOCATION"));
        session.destinations.put(
            "engines", new Destination("engines", Integer.toString(requests[3]), "ALLOCATION"));
        session.destinations.put(
            "weapons", new Destination("weapons", Integer.toString(requests[5]), "ALLOCATION"));
      }
    } else if (EquipmentMenus.supports(mode)) {
      int request =
          com.nullandkale.nullspace.systems.ShipSystems.requestedAllocation(player.level(), pos);
      if (request >= 0)
        session.destinations.put(
            "device", new Destination("device", Integer.toString(request), "ALLOCATION"));
    }
    session.value = value;
    if (mode.equals("shipyard")) {
      session.destinations.clear();
      for (var preset : com.nullandkale.nullspace.shipyard.ShipBlueprints.PRESETS)
        session.destinations.put(preset.id(), new Destination(preset.id(), preset.name(), "FREE"));
    }
    if (mode.equals("recall")) {
      session.destinations.clear();
      for (var ship : ShipRecall.candidates(player, pos)) session.destinations.put(ship.id(), ship);
      var pad =
          PadRegistry.read(player.level().getServer()).stream()
              .filter(
                  p ->
                      p.computer().equals(pos)
                          && p.dimension()
                              .equals(player.level().dimension().identifier().toString()))
              .findFirst();
      pad.ifPresent(
          p ->
              session.destinations.put(
                  "recall_pad",
                  new Destination(
                      "recall_pad", p.name(), ShipRecall.active(player) ? "ACTIVE" : "IDLE")));
    }
    if (mode.equals("station_core")) {
      session.destinations.clear();
      var station = ShipStore.get(player.level().getServer()).at(player.level(), pos);
      if (station != null) {
        session.destinations.put(
            "station_rights",
            new Destination(
                "station_rights",
                com.nullandkale.nullspace.station.StationService.owner(player, station)
                    ? "Owner"
                    : "Builder",
                "SETTING"));
        if (com.nullandkale.nullspace.station.EncounterClaims.claimable(station))
          session.destinations.put(
              "station_claim", new Destination("station_claim", "Claim", "CLAIMABLE"));
        session.destinations.put(
            "station_name",
            new Destination("station_name", station.getStringOr("home", "Station"), "SETTING"));
        session.destinations.put(
            "station_access",
            new Destination(
                "station_access",
                station.getStringOr("access", "private").equals("public")
                    ? "Allowed"
                    : "Not allowed",
                "SETTING"));
        if (!station.getStringOr("constructionShip", "").isEmpty())
          session.destinations.put(
              "construction_return", new Destination("construction_return", "My ship", "SETTING"));
        for (var online :
            player.level().getServer().getPlayerList().getPlayers().stream().limit(60).toList())
          session.destinations.put(
              "player_" + online.getUUID().toString().replace("-", ""),
              new Destination(
                  online.getUUID().toString().replace("-", ""),
                  online.getPlainTextName(),
                  "PLAYER"));
        for (var id : station.getCompoundOrEmpty("builders").keySet()) {
          if (session.destinations.size() >= 60) break;
          var name = station.getCompoundOrEmpty("builderNames").getStringOr(id, id.substring(0, 8));
          session.destinations.put(id, new Destination(id, name, "BUILDER"));
        }
        session.destinations.put(
            "station_area",
            new Destination(
                "station_area",
                Integer.toString(station.getIntOr("width", 32) / 32),
                "STATION_AREA"));
        var rooms = station.getCompoundOrEmpty("roomPieces");
        for (var cell : rooms.keySet())
          session.destinations.put(
              cell, new Destination(cell, rooms.getStringOr(cell, "Room"), "ROOM"));
      }
    }
    if (old != null && old != session)
      com.nullandkale.nullspace.systems.ShipSystems.cancel(player, old.position);
    sessions(player.level().getServer()).put(player.getUUID(), session);
    return new Open(
        pos,
        message.length() > 2048 ? message.substring(0, 2048) : message,
        mode,
        value,
        session.id,
        List.copyOf(session.destinations.values()));
  }

  /**
   * Validation is also exercised by local server tests; rejects never consume another player's
   * session.
   */
  public static Authorized authorize(ServerPlayer player, Request request) {
    var s = sessions(player.level().getServer()).get(player.getUUID());
    if (s == null || !s.id.equals(request.session))
      throw new IllegalArgumentException(
          "Terminal session expired or belongs to another player. Reopen the computer.");
    if (!valid(player, s)) {
      sessions(player.level().getServer()).remove(player.getUUID());
      throw new IllegalArgumentException(
          "Computer moved, changed, is out of range, or session expired. Reopen it.");
    }
    var allowed =
        com.nullandkale.nullspace.station.StationMenus.supports(s.mode)
            ? com.nullandkale.nullspace.station.StationMenus.entries(s.mode).stream()
                .filter(e -> !e.verb().equals("station_argument"))
                .map(e -> Action.valueOf(e.verb().toUpperCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toSet())
            : EquipmentMenus.supports(s.mode)
                ? EquipmentMenus.entries(s.mode).stream()
                    .map(e -> Action.valueOf(e.verb().toUpperCase(Locale.ROOT)))
                    .collect(java.util.stream.Collectors.toSet())
                : s.mode.equals("shipyard")
                    ? Set.of(Action.SHIP_QUOTE, Action.SHIP_BUY)
                    : s.mode.equals("recall")
                        ? Set.of(
                            Action.RECALL_LIST,
                            Action.RECALL_SHIP,
                            Action.RECALL_CANCEL,
                            Action.RECALL_BACK,
                            Action.SHIP_STORE,
                            Action.SHIP_TITLE)
                        : s.mode.equals("pad")
                            ? Set.of(
                                Action.RECALL_LIST,
                                Action.INSPECT,
                                Action.REGISTER,
                                Action.PREVIEW_PAD,
                                Action.PAD_PROBLEM,
                                Action.PAD_ADVANCED,
                                Action.PREVIEW_SHIP)
                            : s.mode.equals("ship")
                                ? Set.of(
                                        Action.INSPECT,
                                        Action.LAUNCH,
                                        Action.LAND,
                                        Action.DESTINATIONS,
                                        Action.SYSTEMS,
                                        Action.FIRE,
                                        Action.PILOT,
                                        Action.CHASE,
                                        Action.NAVIGATION)
                                    .stream()
                                    .collect(java.util.stream.Collectors.toSet())
                                : s.mode.equals("navigation")
                                    ? Set.of(Action.FAST_TRAVEL, Action.HELM)
                                    : Set.of(
                                        Action.SYSTEMS,
                                        Action.UPGRADE,
                                        Action.CLOSE_DOORS,
                                        Action.OPEN_DOORS,
                                        Action.POWER_AIR,
                                        Action.POWER_ENGINES,
                                        Action.POWER_WEAPONS,
                                        Action.PRIORITY,
                                        Action.CONFIGURE);
    if (s.mode.equals("ship")) allowed.add(Action.DOCKS);
    if (player.level().getBlockEntity(s.position)
        instanceof com.nullandkale.nullspace.systems.MachineEntity) {
      allowed = new HashSet<>(allowed);
      allowed.add(Action.MACHINE_BACK);
    }
    if (ShipBlocks.navigation(player.level().getBlockState(s.position))
        || player.level().getBlockState(s.position).is(ShipBlocks.STATION_CORE)) {
      allowed = new HashSet<>(allowed);
      allowed.add(Action.CONTROLLER_BACK);
    }
    if (!allowed.contains(request.action))
      throw new IllegalArgumentException("Action does not belong to this controller");
    if ((s.mode.equals("systems") || EquipmentMenus.supports(s.mode))
        && request.action != Action.SYSTEMS
        && request.action != Action.DEVICE_REFRESH
        && request.action != Action.DEVICE_VIEW
        && request.action != Action.MACHINE_BACK
        && request.action != Action.CONTROLLER_BACK
        && !(request.action == Action.CONFIGURE
            && com.nullandkale.nullspace.systems.EngineeringCommands.readOnly(request.argument))) {
      var vessel =
          com.nullandkale.nullspace.systems.ShipTopology.resolve(player.level(), s.position);
      if (request.revision
          != com.nullandkale.nullspace.systems.ShipSystems.revision(player.level(), vessel))
        throw new IllegalArgumentException("Systems changed. Refresh before editing.");
    }
    if (request.argument.length() > 32 || !request.argument.matches("[A-Za-z0-9 _-]*"))
      throw new IllegalArgumentException(
          "Use at most 32 letters, numbers, spaces, underscores, or hyphens in this field.");
    String argument = request.argument;
    if ((request.action == Action.SHIP_QUOTE || request.action == Action.SHIP_BUY)
        && !s.destinations.containsKey(argument))
      throw new IllegalArgumentException("Choose a ship from this beacon's catalog.");
    if (request.action == Action.FAST_TRAVEL && !s.destinations.containsKey(argument))
      throw new IllegalArgumentException("Choose a route from this session");
    if (request.action == Action.LAND) {
      var chosen = s.destinations.get(argument.strip().toLowerCase(Locale.ROOT));
      if (chosen == null || !chosen.id.equals(request.destinationId))
        throw new IllegalArgumentException(
            "Choose a destination from this session's catalog. Reopen to refresh it.");
      argument = "id:" + chosen.id;
    }
    int tick = player.level().getServer().getTickCount();
    if (tick < s.nextAction)
      throw new IllegalArgumentException("Please wait a moment before the next action");
    s.nextAction = tick + 2;
    s.expires = tick + 1200;
    return new Authorized(s.position, s.mode, argument);
  }

  public static void close(ServerPlayer p, UUID id) {
    var s = sessions(p.level().getServer()).get(p.getUUID());
    if (s != null && s.id.equals(id)) {
      sessions(p.level().getServer()).remove(p.getUUID());
      com.nullandkale.nullspace.systems.ShipSystems.cancel(p, s.position);
    }
  }

  /** The live engineering page belongs to this viewer, never to the ship or another viewer. */
  public static void follow(ServerPlayer player, BlockPos pos, String query) {
    var session = sessions(player.level().getServer()).get(player.getUUID());
    if (session != null && session.mode.equals("systems") && session.position.equals(pos))
      session.query = query;
  }

  public static String reading(ServerPlayer player, BlockPos pos) {
    var session = sessions(player.level().getServer()).get(player.getUUID());
    return com.nullandkale.nullspace.systems.ShipSystems.reading(
        player, pos, session != null && session.position.equals(pos) ? session.query : "");
  }

  public static void initialize() {
    PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(Refresh.TYPE, Refresh.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(Ack.TYPE, Ack.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(Rejected.TYPE, Rejected.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(Update.TYPE, Update.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(Close.TYPE, Close.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Request.TYPE,
        (packet, context) ->
            context
                .server()
                .execute(
                    () -> {
                      var player = context.player();
                      try {
                        var a = authorize(player, packet);
                        if (packet.action == Action.CONTROLLER_BACK) {
                          close(player, packet.session);
                          if (player.level().getBlockState(a.position).is(ShipBlocks.STATION_CORE))
                            com.nullandkale.nullspace.station.StationService.open(
                                player, a.position, "");
                          else
                            com.nullandkale.nullspace.navigation.ShipControl.open(
                                player, a.position, "Returned from engineering");
                        } else if (packet.action == Action.MACHINE_BACK) {
                          close(player, packet.session);
                          com.nullandkale.nullspace.systems.MachineMenus.open(player, a.position);
                        } else if (a.mode.equals("recall")) {
                          if (packet.action == Action.RECALL_BACK)
                            PadComputers.action(player, a.position, null);
                          else {
                            String notice =
                                packet.action == Action.RECALL_SHIP
                                    ? ShipRecall.request(
                                        player, a.position, a.argument, packet.session)
                                    : packet.action == Action.RECALL_CANCEL
                                        ? ShipRecall.cancel(player)
                                        : packet.action == Action.SHIP_STORE
                                            ? ShipRecall.store(
                                                player, a.position, a.argument, packet.session)
                                            : packet.action == Action.SHIP_TITLE
                                                ? ShipRecall.title(player, a.position, a.argument)
                                                : "";
                            ShipRecall.open(player, a.position, notice);
                          }
                        } else if (a.mode.equals("pad")) {
                          if (packet.action == Action.RECALL_LIST)
                            ShipRecall.open(player, a.position, "");
                          else if (packet.action == Action.PAD_PROBLEM
                              || packet.action == Action.PAD_ADVANCED)
                            PadComputers.details(
                                player, a.position, packet.action == Action.PAD_PROBLEM);
                          else if (packet.action == Action.PREVIEW_PAD
                              || packet.action == Action.PREVIEW_SHIP)
                            PadComputers.preview(
                                player, a.position, packet.action == Action.PREVIEW_SHIP);
                          else
                            PadComputers.action(
                                player,
                                a.position,
                                packet.action == Action.REGISTER ? a.argument : null);
                        } else if (com.nullandkale.nullspace.station.StationMenus.supports(
                            a.mode)) {
                          if (packet.action == Action.HELM)
                            ShipService.open(player, a.position, "Ready");
                          else
                            com.nullandkale.nullspace.station.StationService.action(
                                player, a.position, packet.action, a.argument);
                        } else if (a.mode.equals("shipyard"))
                          com.nullandkale.nullspace.shipyard.ShipYardService.action(
                              player, a.position, a.argument, packet.action == Action.SHIP_BUY);
                        else if (EquipmentMenus.supports(a.mode))
                          com.nullandkale.nullspace.systems.ShipSystems.equipmentAction(
                              player, a.position, packet.action, a.argument);
                        else
                          ShipService.act(
                              player,
                              a.position,
                              packet.action.name().toLowerCase(Locale.ROOT),
                              a.argument);
                        ServerPlayNetworking.send(player, new Ack(packet.session));
                      } catch (Exception error) {
                        if (!(error instanceof IllegalArgumentException))
                          com.nullandkale.nullspace.NullSpace.LOGGER.error(
                              "Computer action failed for {}", player.getPlainTextName(), error);
                        ServerPlayNetworking.send(
                            player,
                            new Rejected(
                                packet.session,
                                com.nullandkale.nullspace.ship.ShipMessages.failure(error)));
                      }
                    }));
    ServerPlayNetworking.registerGlobalReceiver(
        Close.TYPE,
        (packet, context) ->
            context.server().execute(() -> close(context.player(), packet.session)));
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> {
          var s = sessions(server).remove(handler.player.getUUID());
          if (s != null)
            com.nullandkale.nullspace.systems.ShipSystems.cancel(handler.player, s.position);
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> com.nullandkale.nullspace.server.ServerContext.get(server).remove(KEY));
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.SESSIONS,
        server -> {
          var active = sessions(server);
          // Cancellation and network callbacks may close or replace another player's session.
          for (var entry : Map.copyOf(active).entrySet()) {
            var p = server.getPlayerList().getPlayer(entry.getKey());
            var session = entry.getValue();
            if ((p == null || !valid(p, session))
                && active.remove(entry.getKey(), session)
                && p != null)
              com.nullandkale.nullspace.systems.ShipSystems.cancel(p, session.position);
          }
          if (server.getTickCount() % 20 != 0) return;
          for (var entry : Map.copyOf(active).entrySet()) {
            var session = entry.getValue();
            if (active.get(entry.getKey()) != session) continue;
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            if (session.mode.equals("shipyard")) {
              var preview =
                  com.nullandkale.nullspace.shipyard.ShipYardService.preview(
                      player, session.position, session.value);
              if (active.get(entry.getKey()) != session) continue;
              ServerPlayNetworking.send(
                  player, new Update(session.id, preview.message(), preview.readiness()));
              session.expires = server.getTickCount() + 1200;
              continue;
            }
            if (session.mode.equals("recall")) {
              ServerPlayNetworking.send(
                  player,
                  new Refresh(issue(player, session.position, "", session.mode, session.value)));
              session.expires = server.getTickCount() + 1200;
              continue;
            }
            boolean equipment = EquipmentMenus.supports(session.mode);
            boolean station = com.nullandkale.nullspace.station.StationMenus.supports(session.mode);
            if ((!session.mode.equals("systems") && !equipment && !station)
                || !ServerPlayNetworking.canSend(player, Update.TYPE)) continue;
            var message =
                equipment
                    ? com.nullandkale.nullspace.systems.ShipSystems.equipmentStatus(
                        player.level(), session.position)
                    : "";
            var ship = ShipStore.get(server).at(player.level(), session.position);
            if (ship == null && !equipment) continue;
            if (station) {
              try {
                message =
                    com.nullandkale.nullspace.station.StationService.status(
                        player, ship, session.position);
              } catch (IllegalArgumentException damaged) {
                message = damaged.getMessage();
              }
            } else if (!equipment) message = reading(player, session.position);
            int revision =
                station
                    ? server.getTickCount()
                    : ship == null
                        ? 0
                        : com.nullandkale.nullspace.systems.ShipSystems.revision(
                            player.level(), ship);
            if (active.get(entry.getKey()) != session) continue;
            if (session.mode.equals("station_core"))
              ServerPlayNetworking.send(
                  player,
                  new Refresh(
                      issue(
                          player,
                          session.position,
                          message,
                          session.mode,
                          Integer.toString(revision))));
            else
              ServerPlayNetworking.send(
                  player,
                  new Refresh(
                      issue(
                          player,
                          session.position,
                          message,
                          session.mode,
                          Integer.toString(revision))));
            session.expires = server.getTickCount() + 1200;
          }
        });
  }

  public static void send(ServerPlayer p, BlockPos pos, String message, String mode, String value) {
    if (p.containerMenu != p.inventoryMenu) p.closeContainer();
    ServerPlayNetworking.send(p, issue(p, pos, message, mode, value));
  }

  public static void open(ServerPlayer p, BlockPos pos, String message) {
    send(p, pos, message, "pad", "");
  }

  private ComputerTerminal() {}
}
