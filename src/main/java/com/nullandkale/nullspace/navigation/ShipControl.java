package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.station.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Shared controller/scanner destination. Every operation is authorized again on the server. */
public final class ShipControl {
  public enum Action {
    SELECT,
    LAUNCH,
    JUMP,
    LAND,
    DOCK,
    UNDOCK,
    PILOT,
    CAMERA,
    ADVANCED,
    CLOSE_DOORS,
    AIRLOCK,
    DEPLOY,
    WAYPOINT,
    ACCEPT,
    DECLINE,
    CANCEL,
    REFRESH,
    CLOSE,
    INSPECT,
    SHOWCASE,
    EXPLAIN,
    SHOW_PROBLEM,
    INVESTIGATE,
    RESUME,
    REVIEW_LOOSE_BLOCKS,
    CONFIRM_LEAVE_BLOCKS,
    ENGAGE,
    DISENGAGE,
    MARK,
    BROWSE_SCANNER,
    RETURN_TO_CONTROLLER
  }

  public record Entry(
      String id, ShipDestination.Kind kind, String name, String region, String state) {}

  /**
   * One place on the star map (P9). {@code x}/{@code y} are chart thousandths; {@code zone} is the
   * destination to select for it; fuel and range are from the ship's current place.
   */
  public record ChartPlace(
      String id,
      String name,
      String kind,
      int x,
      int y,
      String zone,
      double lightYears,
      double fuel,
      boolean inRange,
      boolean here,
      String summary,
      String route) {}

  public record Button(Action action, String reason) {
    public boolean enabled() {
      return reason.isEmpty();
    }
  }

  public record Input(UUID session, Action action, String target) implements CustomPacketPayload {
    public static final Type<Input> TYPE =
        new Type<>(Identifier.parse("nullspace:ship_control_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeEnum(p.action);
              b.writeUtf(p.target, 36);
            },
            b -> new Input(b.readUUID(), b.readEnum(Action.class), b.readUtf(36)));

    @Override
    public Type<Input> type() {
      return TYPE;
    }
  }

  public record View(
      UUID session,
      BlockPos position,
      boolean scanner,
      boolean opening,
      String ship,
      String summary,
      String selected,
      String destination,
      String location,
      String message,
      List<Entry> entries,
      List<Button> buttons,
      int response,
      String here,
      String connection,
      String details,
      List<ChartPlace> chart,
      double range)
      implements CustomPacketPayload {
    public View(
        UUID session,
        BlockPos position,
        boolean scanner,
        boolean opening,
        String ship,
        String summary,
        String selected,
        String destination,
        String location,
        String message,
        List<Entry> entries,
        List<Button> buttons,
        int response,
        String here,
        String connection,
        String details) {
      this(
          session, position, scanner, opening, ship, summary, selected, destination, location,
          message, entries, buttons, response, here, connection, details, List.of(), 0);
    }

    public View(
        UUID session,
        BlockPos position,
        boolean scanner,
        boolean opening,
        String ship,
        String summary,
        String selected,
        String destination,
        String location,
        String message,
        List<Entry> entries,
        List<Button> buttons) {
      this(
          session,
          position,
          scanner,
          opening,
          ship,
          summary,
          selected,
          destination,
          location,
          message,
          entries,
          buttons,
          0,
          "",
          "",
          "");
    }

    public View(
        UUID session,
        BlockPos position,
        boolean scanner,
        boolean opening,
        String ship,
        String summary,
        String selected,
        String destination,
        String location,
        String message,
        List<Entry> entries,
        List<Button> buttons,
        int response) {
      this(
          session,
          position,
          scanner,
          opening,
          ship,
          summary,
          selected,
          destination,
          location,
          message,
          entries,
          buttons,
          response,
          "",
          "",
          "");
    }

    public static final Type<View> TYPE = new Type<>(Identifier.parse("nullspace:ship_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeInt(p.response);
              b.writeUtf(p.here, 160);
              b.writeUtf(p.connection, 160);
              b.writeUtf(p.details, 2048);
              b.writeBlockPos(p.position);
              b.writeBoolean(p.scanner);
              b.writeBoolean(p.opening);
              b.writeUtf(p.ship, 160);
              b.writeUtf(p.summary, 160);
              b.writeUtf(p.selected, 36);
              b.writeUtf(p.destination, 160);
              b.writeUtf(p.location, 160);
              b.writeUtf(p.message, 2048);
              b.writeVarInt(p.entries.size());
              for (var e : p.entries) {
                b.writeUtf(e.id, 36);
                b.writeEnum(e.kind);
                b.writeUtf(e.name, 160);
                b.writeUtf(e.region, 32);
                b.writeUtf(e.state, 32);
              }
              b.writeVarInt(p.buttons.size());
              for (var a : p.buttons) {
                b.writeEnum(a.action);
                b.writeUtf(a.reason, 256);
              }
              b.writeDouble(p.range);
              b.writeVarInt(p.chart.size());
              for (var c : p.chart) {
                b.writeUtf(c.id, 32);
                b.writeUtf(c.name, 64);
                b.writeUtf(c.kind, 32);
                b.writeVarInt(c.x);
                b.writeVarInt(c.y);
                b.writeUtf(c.zone, 32);
                b.writeDouble(c.lightYears);
                b.writeDouble(c.fuel);
                b.writeBoolean(c.inRange);
                b.writeBoolean(c.here);
                b.writeUtf(c.summary, 160);
                b.writeUtf(c.route, 160);
              }
            },
            b -> {
              var session = b.readUUID();
              int response = b.readInt();
              String here = b.readUtf(160), connection = b.readUtf(160), details = b.readUtf(2048);
              var position = b.readBlockPos();
              boolean scanner = b.readBoolean(), opening = b.readBoolean();
              String ship = b.readUtf(160),
                  summary = b.readUtf(160),
                  selected = b.readUtf(36),
                  destination = b.readUtf(160),
                  location = b.readUtf(160),
                  message = b.readUtf(2048);
              int count = b.readVarInt();
              if (count < 0 || count > 512)
                throw new IllegalArgumentException("Invalid destination count");
              var entries = new ArrayList<Entry>();
              for (int i = 0; i < count; i++)
                entries.add(
                    new Entry(
                        b.readUtf(36),
                        b.readEnum(ShipDestination.Kind.class),
                        b.readUtf(160),
                        b.readUtf(32),
                        b.readUtf(32)));
              count = b.readVarInt();
              if (count < 0 || count > Action.values().length)
                throw new IllegalArgumentException("Invalid action count");
              var buttons = new ArrayList<Button>();
              for (int i = 0; i < count; i++)
                buttons.add(new Button(b.readEnum(Action.class), b.readUtf(256)));
              double range = b.readDouble();
              count = b.readVarInt();
              if (count < 0 || count > 64) throw new IllegalArgumentException("Invalid chart");
              var chart = new ArrayList<ChartPlace>();
              for (int i = 0; i < count; i++)
                chart.add(
                    new ChartPlace(
                        b.readUtf(32),
                        b.readUtf(64),
                        b.readUtf(32),
                        b.readVarInt(),
                        b.readVarInt(),
                        b.readUtf(32),
                        b.readDouble(),
                        b.readDouble(),
                        b.readBoolean(),
                        b.readBoolean(),
                        b.readUtf(160),
                        b.readUtf(160)));
              return new View(
                  session,
                  position,
                  scanner,
                  opening,
                  ship,
                  summary,
                  selected,
                  destination,
                  location,
                  message,
                  List.copyOf(entries),
                  List.copyOf(buttons),
                  response,
                  here,
                  connection,
                  details,
                  List.copyOf(chart),
                  range);
            });

    @Override
    public Type<View> type() {
      return TYPE;
    }
  }

  private static final class Session {
    final UUID id = UUID.randomUUID();
    final BlockPos position;
    final String dimension, ship;
    final boolean scanner;
    int expires, nextAction, nextSelection, response;
    List<ShipDestination> catalog = List.of();
    String message, setupProblem = "";
    Action problemAction = Action.LAUNCH;
    ShipDetachment.Review looseReview;

    Session(ServerPlayer p, BlockPos at, CompoundTag vessel, String message) {
      position = at.immutable();
      dimension = p.level().dimension().identifier().toString();
      ship = vessel.getStringOr("id", "");
      scanner = scanner(p, at);
      this.message = message;
      expires = p.level().getServer().getTickCount() + 1200;
    }
  }

  private static final ServerContext.Key<Map<UUID, Session>> KEY = new ServerContext.Key<>();

  private static Map<UUID, Session> sessions(MinecraftServer server) {
    return ServerContext.get(server).service(KEY, HashMap::new);
  }

  private static boolean scanner(ServerPlayer p, BlockPos at) {
    var block = p.level().getBlockState(at);
    return block.is(ShipBlocks.LOCAL_SCANNER) || block.is(ShipBlocks.LONG_RANGE_SCANNER);
  }

  private static boolean accessible(ServerPlayer p, BlockPos at) {
    return p.isAlive()
        && p.level().hasChunkAt(at)
        && p.distanceToSqr(Vec3.atCenterOf(at)) <= 64
        && (ShipBlocks.navigation(p.level().getBlockState(at)) || scanner(p, at))
        && p.mayInteract(p.level(), at)
        && StationService.mayUse(p, at)
        && !TravelService.busy(p)
        && !TravelService.locked(p.level(), at);
  }

  private static CompoundTag vessel(ServerPlayer p, Session s) {
    if (s.expires < p.level().getServer().getTickCount()
        || !s.dimension.equals(p.level().dimension().identifier().toString())
        || !accessible(p, s.position)
        || scanner(p, s.position) != s.scanner) return null;
    var ship = ShipStore.get(p.level().getServer()).at(p.level(), s.position);
    return ship != null && ship.getStringOr("id", "").equals(s.ship) ? ship : null;
  }

  public static View open(ServerPlayer p, BlockPos at, String message) {
    if (!accessible(p, at)) return null;
    if (p.containerMenu != p.inventoryMenu) p.closeContainer();
    var resolution = ShipTopology.find(p.level(), at);
    if (resolution.ship() == null) {
      var incomplete = new Session(p, at, new CompoundTag(), resolution.problem());
      incomplete.setupProblem = resolution.problem();
      sessions(p.level().getServer()).put(p.getUUID(), incomplete);
      var diagnostic = setupView(incomplete, true);
      send(p, diagnostic);
      return diagnostic;
    }
    CompoundTag own = resolution.ship();
    if (!scanner(p, at) && JourneyService.selectEncounterDock(p, own))
      ShipStore.get(p.level().getServer()).save();
    var session = new Session(p, at, own, message);
    if (!ShipSystems.enabled(own) && ShipSystems.upgradeCheck(p.level(), own, at).ready())
      ShipSystems.upgrade(p.level(), own);
    session.catalog = catalog(p, session, own);
    if (ShipDestination.selected(own) == null && !JourneyService.cruising(own))
      session.catalog.stream()
          .filter(
              d ->
                  d.kind() == ShipDestination.Kind.PAD
                      && d.reference().equals(own.getStringOr("homeId", "")))
          .findFirst()
          .ifPresent(d -> ShipDestination.select(p, own, d));
    sessions(p.level().getServer()).put(p.getUUID(), session);
    var view = view(p, session, own, true);
    send(p, view);
    return view;
  }

  private static View setupView(Session s, boolean opening) {
    var buttons =
        Arrays.stream(Action.values())
            .filter(a -> a != Action.EXPLAIN)
            .map(
                a ->
                    new Button(
                        a,
                        a == Action.INSPECT || a == Action.SHOW_PROBLEM || a == Action.CLOSE
                            ? ""
                            : s.setupProblem))
            .toList();
    return new View(
        s.id,
        s.position,
        s.scanner,
        opening,
        "Ship setup",
        "Power: unknown | Air here: unknown",
        "",
        "Not selected",
        "",
        s.message,
        List.of(),
        buttons,
        s.response);
  }

  private static ShipDestination selected(ServerPlayer p, Session s, CompoundTag ship) {
    var saved =
        CourseService.departureTarget(p.level().getServer(), ship, ShipDestination.selected(ship));
    if (saved == null)
      return s.catalog.stream()
          .filter(
              d ->
                  d.kind() == ShipDestination.Kind.REGION
                      && d.region()
                          .equals(
                              ship.getCompoundOrEmpty("journey").getStringOr("destination", "")))
          .findFirst()
          .orElse(null);
    return s.catalog.stream()
        .filter(d -> d.id().equals(saved.id()))
        .findFirst()
        .orElse(
            new ShipDestination(
                saved.kind(),
                saved.reference(),
                saved.port(),
                saved.region(),
                saved.name(),
                "UNAVAILABLE"));
  }

  private static List<ShipDestination> catalog(ServerPlayer p, Session s, CompoundTag ship) {
    if (s.scanner
        && (!ShipSystems.connected(p.level(), ship).contains(s.position)
            || !ShipSystems.active(ship, s.position)
            || !ShipSensors.installed(p.level(), ship, false))) {
      s.message = "Scanner needs a hull connection, On, and sensor power from a generator.";
      return List.of();
    }
    return ShipDestination.navigationCatalog(p, ship);
  }

  private static void refreshCatalog(ServerPlayer p, Session session, CompoundTag ship) {
    session.catalog = catalog(p, session, ship);
  }

  /** Cheap state checks for display; the operation still performs its complete preflight. */
  public static String reason(
      ServerPlayer p, CompoundTag ship, ShipDestination target, Action action) {
    boolean space = p.level().dimension().equals(TravelService.SPACE);
    var dock = ship.getCompoundOrEmpty("dock");
    String phase = dock.getStringOr("state", "");
    boolean incoming = !dock.getStringOr("source", "").equals(ship.getStringOr("id", ""));
    if (action == Action.ENGAGE) return CourseService.reason(p, ship, target);
    if (action == Action.DISENGAGE) return CourseService.active(ship) ? "" : "No engaged course";
    if (action == Action.MARK
        || action == Action.BROWSE_SCANNER
        || action == Action.RETURN_TO_CONTROLLER)
      return "Open the Scanner block to browse and set a destination";
    if (CourseService.active(ship)
        && Set.of(
                Action.SELECT,
                Action.LAUNCH,
                Action.JUMP,
                Action.LAND,
                Action.DOCK,
                Action.UNDOCK,
                Action.PILOT)
            .contains(action)) return "Disengage the current course first";
    if (action == Action.SHOW_PROBLEM
        || action == Action.ADVANCED
        || action == Action.REFRESH
        || action == Action.CLOSE
        || action == Action.INSPECT) return "";
    if (action == Action.REVIEW_LOOSE_BLOCKS || action == Action.CONFIRM_LEAVE_BLOCKS) {
      if (StationService.station(ship)) return "Stations are anchored";
      if (JourneyService.cruising(ship)) return "Finish the FTL journey first";
      return DockingService.motionLocked(ship) ? "Undock or cancel the docking maneuver first" : "";
    }
    if (action == Action.UNDOCK)
      return !phase.equals("CLAMPED") || incoming
          ? (phase.equals("CLAMPED") ? "Use the visiting ship's controller" : "Not docked")
          : NavigationService.propulsion(p.level(), ship) > 0
              ? ""
              : "Your ship needs controls, engine power and fuel to undock";
    if (action == Action.CANCEL)
      return phase.isEmpty() || phase.equals("CLAMPED") || incoming
          ? "No outgoing maneuver to cancel"
          : "";
    if (action == Action.ACCEPT || action == Action.DECLINE)
      return phase.equals("WAITING_CONSENT") && incoming ? "" : "No incoming docking request";
    if (action == Action.SELECT)
      return JourneyService.cruising(ship)
          ? "Journey underway: investigate or arrive before changing destination"
          : "";
    if (action == Action.CLOSE_DOORS || action == Action.AIRLOCK) return "";
    if (action == Action.WAYPOINT || action == Action.SHOWCASE)
      return space ? "" : "Launch into space first";
    if (action == Action.CAMERA) return space ? "" : "Launch into space first";
    if (StationService.station(ship)) return "Stations are anchored";
    if (DockingService.motionLocked(ship)) return "Undock or cancel the docking maneuver first";
    if (ShipRecall.active(p)) return "Finish or cancel your recall first";
    if (TravelService.busy(p.level().getServer(), ship))
      return "This ship is already travelling or boarding";
    if (action == Action.INVESTIGATE) return JourneyService.stopReason(p.level().getServer(), ship);
    if (action == Action.RESUME)
      return JourneyService.active(ship) && !JourneyService.cruising(ship)
          ? NavigationService.propulsion(p.level(), ship) > 0
              ? ""
              : "Load fuel and power controls and an engine before resuming"
          : "No paused journey";
    if (JourneyService.cruising(ship))
      return "FTL underway: investigate a signal or wait for arrival";
    if (action == Action.JUMP && JourneyService.active(ship))
      return "Use Resume journey to continue your paid trip";
    if (action == Action.LAUNCH)
      return space
          ? "Already in space"
          : fuelReason(p, ship, com.nullandkale.nullspace.systems.FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size()));
    if (!space) return "Launch into space first";
    if (action == Action.DEPLOY)
      return "In space, hold a Station Core and Shift-right-click empty space or a non-interactive"
          + " block";
    if (action == Action.PILOT || action == Action.CAMERA) return "";
    if (target == null) return "Choose a destination";
    if (action == Action.JUMP)
      return !CourseService.scannerReason(p, ship, target.region()).isEmpty()
          ? CourseService.scannerReason(p, ship, target.region())
          : JourneyService.special(p.level().getServer(), target.region())
              ? "Reach this signal using Investigate during a journey"
              : LocalSpaces.address(ship).equals(target.region())
                  ? "Already in the destination's region"
                  : !StarSystems.rangeReason(p.level(), ship, target.region()).isEmpty()
                      ? StarSystems.rangeReason(p.level(), ship, target.region())
                      : fuelReason(p, ship, StarSystems.jumpBase(
                          p.level().getServer(), LocalSpaces.address(ship), target.region()));
    if (action == Action.LAND && target.kind() != ShipDestination.Kind.PAD)
      return "Choose a landing pad as the destination";
    if (action == Action.DOCK && target.kind() != ShipDestination.Kind.PORT)
      return "Choose a docking port as the destination";
    if (!LocalSpaces.address(ship).equals(target.region()))
      return (JourneyService.active(ship) ? "Resume the journey to " : "Jump to ")
          + LocalSpaces.get(p.level().getServer(), target.region()).name()
          + " first";
    if ((action == Action.LAND || action == Action.DOCK)
        && (target.state().equalsIgnoreCase("OCCUPIED")
            || target.state().equalsIgnoreCase("UNAVAILABLE")))
      return "Destination "
          + (action == Action.DOCK ? "port" : "pad")
          + " is "
          + target.state().toLowerCase(Locale.ROOT);
    if (action == Action.DOCK) {
      var readiness = DockingService.readiness(p, ship);
      return readiness.ready() ? "" : readiness.message();
    }
    if (action == Action.LAND)
      return fuelReason(p, ship, com.nullandkale.nullspace.systems.FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size()));
    return "";
  }

  private static String fuelReason(ServerPlayer p, CompoundTag ship, int base) {
    int cost = FuelSystem.travelCost(p.level(), ship, base);
    return FuelSystem.available(p.level(), ship) >= cost
        ? ""
        : "Fuel "
            + FuelSystem.display(FuelSystem.available(p.level(), ship))
            + "/"
            + FuelSystem.display(cost)
            + ": load furnace fuel in a generator";
  }

  private static View view(ServerPlayer p, Session s, CompoundTag ship, boolean opening) {
    var target = selected(p, s, ship);
    var actions =
        s.scanner
            ? List.of(Action.SELECT, Action.REFRESH, Action.ADVANCED)
            : List.of(
                Action.SELECT,
                Action.LAUNCH,
                Action.JUMP,
                Action.LAND,
                Action.DOCK,
                Action.UNDOCK,
                Action.PILOT,
                Action.CAMERA,
                Action.CLOSE_DOORS,
                Action.AIRLOCK,
                Action.ADVANCED,
                Action.REFRESH,
                Action.INSPECT,
                Action.REVIEW_LOOSE_BLOCKS,
                Action.SHOW_PROBLEM,
                Action.ACCEPT,
                Action.DECLINE,
                Action.CANCEL);
    var visibleActions = new ArrayList<>(actions);
    if (!s.scanner) {
      visibleActions.addAll(List.of(Action.ENGAGE, Action.DISENGAGE));
    }
    if (!s.scanner && s.looseReview != null) visibleActions.add(Action.CONFIRM_LEAVE_BLOCKS);
    if (!s.scanner && JourneyService.active(ship))
      visibleActions.add(JourneyService.cruising(ship) ? Action.INVESTIGATE : Action.RESUME);
    var buttons =
        visibleActions.stream()
            .map(a -> new Button(a, limit(reason(p, ship, target, a), 256)))
            .toList();
    String system = LocalSpaces.get(p.level().getServer(), LocalSpaces.address(ship)).system();
    var entries =
        s.catalog.stream()
            .filter(
                d ->
                    s.scanner
                        || (d.kind() == ShipDestination.Kind.REGION
                                || d.kind() == ShipDestination.Kind.PAD)
                            && LocalSpaces.get(p.level().getServer(), d.region())
                                .system()
                                .equals(system)
                        || target != null && d.id().equals(target.id()))
            .limit(512)
            .map(
                d ->
                    new Entry(
                        d.id(), d.kind(), limit(d.name(), 160), d.region(), limit(d.state(), 32)))
            .toList();
    String location =
        target == null
            ? "No destination selected"
            : LocalSpaces.get(p.level().getServer(), target.region()).systemName()
                + " / "
                + LocalSpaces.get(p.level().getServer(), target.region()).name()
                + " | "
                + switch (target.kind()) {
                  case REGION -> "Planet/moon";
                  case PAD -> "Landing pad";
                  case PORT -> "Docking port";
                  case VESSEL -> "Station/ship";
                }
                + " | "
                + target.state();
    String summary =
        ShipSystems.enabled(ship)
            ? "Fuel: "
                + (FuelSystem.available(p.level(), ship) > 0 ? "loaded" : "empty")
                + " | Controls: "
                + (ShipSystems.power(p.level(), ship, PowerBus.System.CONTROLS) > 0
                    ? "online"
                    : "no controls power")
                + " | Engines: "
                + (NavigationService.propulsion(p.level(), ship) > 0 ? "ready" : "unavailable")
                + " | Air here: "
                + (ShipSystems.breathable(p.level(), ship, BlockPos.containing(p.getEyePosition()))
                    ? "safe here"
                    : "unsafe here")
            : "Setup incomplete: connect a generator and an Air Supply";
    int transferCost =
        FuelSystem.travelCost(p.level(), ship, com.nullandkale.nullspace.systems.FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size()));
    String costs = "Fuel costs: Launch/Land " + FuelSystem.display(transferCost);
    if (JourneyService.active(ship)) costs += " | Jump already paid; Resume has no jump fee";
    else if (target != null)
      costs +=
          " | Jump "
              + FuelSystem.display(
                  FuelSystem.travelCost(p.level(), ship, StarSystems.jumpBase(
                          p.level().getServer(), LocalSpaces.address(ship), target.region())));
    costs += " | Dock: burns while moving";
    if (target != null
        && !JourneyService.active(ship)
        && !LocalSpaces.address(ship).equals(target.region())
        && !JourneyService.special(p.level().getServer(), target.region()))
      costs +=
          "\nJourney: "
              + JourneyService.preview(p.level().getServer(), ship, target.region()).seconds()
              + " seconds; optional signals appear en route";
    var power = ShipSystems.powerStats(p.level(), ship);
    String resources =
        "Power "
            + power[0]
            + " / "
            + power[1]
            + " demand | Battery "
            + power[3]
            + " | Charging "
            + power[2]
            + "\nBow: "
            + ShipHeading.forward(p.level(), ship).getName()
            + " (opposite main engine exhaust)";
    return new View(
        s.id,
        s.position,
        s.scanner,
        opening,
        limit(ship.getStringOr("home", "Ship"), 160),
        summary,
        target == null ? "" : target.id(),
        target == null ? "Choose destination" : limit(target.name(), 160),
        limit(location, 160),
        limit(
            java.util.stream.Stream.of(
                    s.message,
                    CourseService.summary(ship),
                    JourneyService.summary(p.level().getServer(), ship),
                    ship.getStringOr("flightStatus", ""),
                    ship.contains("dock")
                        ? "Docking: " + ship.getCompoundOrEmpty("dock").getStringOr("state", "")
                        : "")
                .filter(text -> !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n")),
            2048),
        entries,
        buttons,
        s.response,
        locationHere(p, ship),
        CourseService.active(ship) ? CourseService.summary(ship) : connection(p, ship),
        CourseService.preview(p, ship, target)
            + "\nMinimum fuel: "
            + FuelSystem.display(CourseService.minimumFuel(p, ship, target))
            + " + docking reserve\nFuel aboard: "
            + FuelSystem.display(FuelSystem.available(p.level(), ship))
            + "\n"
            + costs
            + "\n"
            + resources,
        s.scanner ? chart(p, ship) : List.of(),
        s.scanner ? StarSystems.range(p.level(), ship) : 0);
  }

  /** The star map from this ship's place: every system and feature, with jump fuel and range. */
  private static List<ChartPlace> chart(ServerPlayer p, CompoundTag ship) {
    var server = p.level().getServer();
    var galaxy = StarSystems.galaxy(server);
    String address = LocalSpaces.address(ship);
    GalaxyPlan.Place here =
        StarSystems.placeOf(server, address);
    double range = StarSystems.range(p.level(), ship);
    var out = new ArrayList<ChartPlace>();
    for (var place : galaxy.places()) {
      double ly = GalaxyPlan.distance(here, place);
      String kind, zone, summary;
      if (place instanceof GalaxyPlan.StarSystem s) {
        kind = "System";
        var planets = s.planets();
        long landable = s.bodies().stream().filter(GalaxyPlan.Body::landable).count();
        long moons = s.bodies().size() - planets.size();
        zone =
            s.bodies().stream()
                .filter(b -> b.id().equals(address))
                .findFirst()
                .orElse(planets.getFirst())
                .id();
        summary =
            s.spectralClass() + " star | " + planets.size() + " planets, " + moons + " moons, "
                + landable + " landable";
      } else {
        var f = (GalaxyPlan.Feature) place;
        kind = f.type().label;
        zone = f.id();
        summary = f.type().label + (f.steppingStone() ? " | stepping stone" : "");
      }
      boolean inRange = ly <= range + 1e-9;
      double fuel = place == here ? 0 : GalaxyPlan.jumpFuel(ly);
      String route = "";
      if (!inRange) {
        // Too far for one jump: the cheapest hop route with these engines, and its total fuel.
        var hops = GalaxyPlan.route(galaxy, here.id(), place.id(), range);
        fuel = 0;
        for (int i = 1; i < hops.size(); i++)
          fuel += GalaxyPlan.jumpFuel(GalaxyPlan.distance(hops.get(i - 1), hops.get(i)));
        route =
            hops.size() < 2
                ? "no route"
                : (hops.size() - 1)
                    + " hops via "
                    + String.join(
                        ", ",
                        hops.subList(1, hops.size() - 1).stream()
                            .map(GalaxyPlan.Place::name)
                            .toList());
      }
      out.add(
          new ChartPlace(
              place.id(),
              limit(place.name(), 64),
              kind,
              (int) Math.round(place.x() / GalaxyPlan.SIZE * 1000),
              (int) Math.round(place.y() / GalaxyPlan.SIZE * 1000),
              zone,
              ly,
              fuel,
              inRange,
              place == here,
              limit(summary, 160),
              limit(route, 160)));
    }
    return List.copyOf(out);
  }

  private static String locationHere(ServerPlayer p, CompoundTag ship) {
    String id =
        p.level().dimension().equals(TravelService.SPACE)
            ? LocalSpaces.address(ship)
            : PlanetSurfaces.address(p.level());
    if (id == null) return limit(p.level().dimension().identifier() + " / Surface", 160);
    var place = LocalSpaces.get(p.level().getServer(), id);
    return limit(
        place.systemName()
            + " / "
            + place.name()
            + (p.level().dimension().equals(TravelService.SPACE) ? " / Orbit" : " / Surface"),
        160);
  }

  private static String connection(ServerPlayer p, CompoundTag ship) {
    if (JourneyService.active(ship))
      return limit(JourneyService.summary(p.level().getServer(), ship), 160);
    var dock = ship.getCompoundOrEmpty("dock");
    String phase = dock.getStringOr("state", "");
    if (phase.isBlank()) return "";
    if (!phase.equals("CLAMPED"))
      return "Docking: " + phase.replace('_', ' ').toLowerCase(Locale.ROOT);
    String other =
        dock.getStringOr("source", "").equals(ship.getStringOr("id", ""))
            ? dock.getStringOr("target", "")
            : dock.getStringOr("source", "");
    var partner = ShipStore.get(p.level().getServer()).repository().find(other);
    return limit(
        "Docked: "
            + (partner == null ? "connected vessel" : partner.getStringOr("home", "Vessel"))
            + ". Open the dock door to walk through.",
        160);
  }

  private static String limit(String text, int length) {
    return text.substring(0, Math.min(length, text.length()));
  }

  private static void send(ServerPlayer p, View view) {
    if (ServerPlayNetworking.canSend(p, View.TYPE)) ServerPlayNetworking.send(p, view);
  }

  /** A normal refusal is a result, including stale/forged destination selections. */
  public static String input(ServerPlayer p, Input packet) {
    var session = sessions(p.level().getServer()).get(p.getUUID());
    String result = applyInput(p, packet);
    if (session != null) session.response++;
    if (packet.action != Action.CLOSE && packet.action != Action.REFRESH) {
      var ship =
          session == null
              ? null
              : ShipStore.get(p.level().getServer()).ships().stream()
                  .map(t -> (CompoundTag) t)
                  .filter(s -> s.getStringOr("id", "").equals(session.ship))
                  .findFirst()
                  .orElse(null);
      var destination = ship == null ? null : ShipDestination.selected(ship);
      String format = "Ship control {}: {} | player={} ship={} ({}) controller={} destination={}";
      var details =
          new Object[] {
            packet.action,
            (result.isBlank() ? "Action accepted" : result).replace('\n', ' '),
            p.getPlainTextName(),
            ship == null ? "unavailable" : ship.getStringOr("home", "Ship"),
            session == null ? "unknown" : session.ship,
            session == null ? "unknown" : session.position.toShortString(),
            destination == null ? "none" : destination.name()
          };
      com.nullandkale.nullspace.NullSpace.LOGGER.info(format, details);
    }
    return result;
  }

  private static String applyInput(ServerPlayer p, Input packet) {
    var session = sessions(p.level().getServer()).get(p.getUUID());
    if (session == null || !session.id.equals(packet.session)) return "Reopen this controller";
    if (packet.action == Action.CLOSE) {
      sessions(p.level().getServer()).remove(p.getUUID());
      return "";
    }
    if (!session.setupProblem.isEmpty() && accessible(p, session.position)) {
      if (packet.action == Action.INSPECT || packet.action == Action.SHOW_PROBLEM) {
        var next = open(p, session.position, "Ship setup checked");
        return next == null ? "Controller unavailable" : next.message();
      }
      return session.setupProblem;
    }
    var ship = vessel(p, session);
    if (ship == null) return "Controller unavailable, moved, or out of range";
    int tick = p.level().getServer().getTickCount();
    // Saving a destination must not throttle the travel click that follows its acknowledgement.
    // Keep each category bounded independently so repeated packets still cannot flood the server.
    if (packet.action == Action.SELECT || packet.action == Action.MARK) {
      if (tick < session.nextSelection) return "Wait a moment before the next action";
      session.nextSelection = tick + 2;
    } else {
      if (tick < session.nextAction) return "Wait a moment before the next action";
      session.nextAction = tick + 2;
    }
    session.expires = tick + 1200;
    if (packet.action == Action.EXPLAIN) {
      var action =
          Arrays.stream(Action.values())
              .filter(a -> a.name().equals(packet.target) && a != Action.EXPLAIN)
              .findFirst();
      if (action.isEmpty()) return "Unknown control; reopen this controller";
      if (session.scanner) return "Use the ship controller for travel";
      session.catalog = catalog(p, session, ship);
      session.problemAction = action.get();
      String reason = reason(p, ship, selected(p, session, ship), action.get());
      String label = action.get().name().toLowerCase(Locale.ROOT);
      label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
      session.message =
          reason.isEmpty()
              ? label + " is ready now. Press it again to start."
              : label + " blocked: " + reason;
      p.sendSystemMessage(net.minecraft.network.chat.Component.literal(session.message));
      return session.message;
    }
    if (session.scanner
        && !Set.of(Action.SELECT, Action.REFRESH, Action.ADVANCED).contains(packet.action))
      return "Use the ship controller for travel";
    String result = "";
    if (Set.of(Action.LAUNCH, Action.LAND, Action.JUMP).contains(packet.action))
      session.problemAction = packet.action;
    try {
      if (packet.action == Action.SELECT
          && (JourneyService.cruising(ship) || CourseService.active(ship))) {
        result = reason(p, ship, selected(p, session, ship), Action.SELECT);
      } else if (packet.action == Action.SELECT || packet.action == Action.MARK) {
        if (packet.action == Action.MARK) {
          var blocked = reason(p, ship, selected(p, session, ship), Action.MARK);
          if (!blocked.isEmpty()) return blocked;
        }
        var candidate =
            session.catalog.stream()
                .limit(512)
                .filter(d -> d.id().equals(packet.target))
                .findFirst();
        if (candidate.isEmpty()) return "Choose a destination from this scanner or controller";
        var available =
            catalog(p, session, ship).stream()
                .filter(d -> d.id().equals(packet.target))
                .findFirst();
        if (available.isEmpty())
          return "Destination changed or is no longer available; the list updates automatically";
        ShipDestination.select(p, ship, available.get());
        ship.remove("markedDestination");
        result = "Destination set: " + available.get().name();
      } else {
        session.catalog = catalog(p, session, ship);
        var target = selected(p, session, ship);
        var reason = reason(p, ship, target, packet.action);
        if (!reason.isEmpty()) result = reason;
        else
          switch (packet.action) {
            case ENGAGE -> result = CourseService.engage(p, ship, target);
            case DISENGAGE -> result = CourseService.disengage(p.level().getServer(), ship);
            case LAUNCH, LAND -> {
              var inspection = ShipService.preflight(p.level(), session.position);
              if (ShipSystems.enabled(ship)
                  && ShipSystems.power(p.level(), ship, PowerBus.System.ENGINES) == 0)
                result = "Propulsion has no delivered power";
              else
                TravelService.request(
                    p,
                    session.position,
                    packet.action == Action.LAUNCH ? "" : "id:" + target.reference(),
                    inspection);
            }
            case JUMP -> NavigationService.jump(p, session.position, target.region());
            case INVESTIGATE -> {
              NavigationService.investigate(p, session.position);
            }
            case RESUME -> NavigationService.resume(p, session.position);
            case DOCK -> {
              boolean available =
                  session.catalog.stream().anyMatch(d -> d.id().equals(target.id()));
              if (!available) result = "Selected port is unavailable; choose another destination";
              else
                result =
                    DockingService.request(p, ship, target.reference(), target.port()).message();
            }
            case UNDOCK, CANCEL, ACCEPT, DECLINE ->
                result =
                    DockingService.perform(
                        p, ship, session.position, "DOCK_" + packet.action.name());
            case PILOT, CAMERA -> {
              NavigationService.open(p, session.position, packet.action == Action.PILOT);
              sessions(p.level().getServer()).remove(p.getUUID());
            }
            case ADVANCED -> {
              if (session.scanner)
                com.nullandkale.nullspace.systems.MachineMenus.open(p, session.position);
              else
                ShipSystems.action(
                    p,
                    session.position,
                    com.nullandkale.nullspace.terminal.ComputerTerminal.Action.SYSTEMS,
                    "");
              sessions(p.level().getServer()).remove(p.getUUID());
            }
            case CLOSE_DOORS -> result = ShipSystems.closeDoors(p, session.position);
            case AIRLOCK -> {
              StationService.open(p, session.position, "");
              sessions(p.level().getServer()).remove(p.getUUID());
            }
            case DEPLOY -> {
              StationService.start(p, ship);
              result = "Station construction started";
            }
            case WAYPOINT, SHOWCASE ->
                result = "Stations are discovered automatically. Choose Destination > Ports.";
            case REFRESH -> result = "Destinations refreshed";
            case REVIEW_LOOSE_BLOCKS -> {
              session.looseReview = ShipDetachment.review(p.level(), session.position);
              if (session.looseReview.loose().isEmpty()) {
                session.looseReview = null;
                result =
                    "No loose blocks found. The connected ship passes its build and cabin checks.";
              } else {
                ShipDiagnostics.highlight(
                    p, session.looseReview.loose().keySet().stream().limit(256).toList());
                result = session.looseReview.message();
              }
            }
            case CONFIRM_LEAVE_BLOCKS -> {
              var reviewed = session.looseReview;
              session.looseReview = null;
              result = ShipDetachment.confirm(p, reviewed);
            }
            case SHOW_PROBLEM -> {
              int base =
                  session.problemAction == Action.JUMP && target != null
                      ? StarSystems.jumpBase(
                          p.level().getServer(), LocalSpaces.address(ship), target.region())
                      : com.nullandkale.nullspace.systems.FuelSystem.transfer(ShipSystems.connected(p.level(), ship).size());
              if (FuelSystem.available(p.level(), ship)
                  < FuelSystem.travelCost(p.level(), ship, base)) {
                var generator =
                    ShipSystems.connected(p.level(), ship).stream()
                        .filter(at -> p.level().getBlockState(at).is(ShipBlocks.GENERATOR))
                        .findFirst();
                if (generator.isPresent()) {
                  ShipDiagnostics.highlight(p, List.of(generator.get()));
                  result =
                      "Put furnace fuel in the highlighted generator at "
                          + generator.get().toShortString();
                } else
                  result = "Add a generator connected to this ship's hull, then load furnace fuel";
              } else
                try {
                  ShipService.preflight(p.level(), session.position);
                  result = "No ship build problem found";
                } catch (ShipScan.Failure failure) {
                  ShipDiagnostics.highlight(p, failure);
                  result = ShipMessages.failure(failure);
                }
            }
            case INSPECT ->
                result =
                    ShipDiagnostics.summary(
                        p.level(), ShipService.preflight(p.level(), session.position).scan());
            default -> result = "Unsupported controller action";
          }
      }
    } catch (IllegalArgumentException blocked) {
      if (blocked instanceof ShipScan.Failure failure) ShipDiagnostics.highlight(p, failure);
      result = ShipMessages.failure(blocked);
    }
    if (packet.action == Action.DOCK && !ship.contains("dock")) result = "Dock blocked: " + result;
    session.message = result;
    return result;
  }

  public static void initialize() {
    PayloadTypeRegistry.clientboundPlay().register(View.TYPE, View.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(Input.TYPE, Input.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Input.TYPE,
        (packet, context) ->
            context
                .server()
                .execute(
                    () -> {
                      try {
                        var result = input(context.player(), packet);
                        var s = sessions(context.server()).get(context.player().getUUID());
                        if (s != null && s.id.equals(packet.session) && !result.isEmpty()) {
                          s.message = result;
                          var ship = vessel(context.player(), s);
                          if (!s.setupProblem.isEmpty())
                            send(context.player(), setupView(s, false));
                          else if (ship != null)
                            send(context.player(), view(context.player(), s, ship, false));
                          else {
                            s.setupProblem = result;
                            send(context.player(), setupView(s, false));
                          }
                        }
                      } catch (Exception error) {
                        com.nullandkale.nullspace.NullSpace.LOGGER.error(
                            "Ship controls failed", error);
                      }
                    }));
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> sessions(server).remove(handler.player.getUUID()));
    ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
    ServerContext.tick(
        ServerContext.Phase.SESSIONS,
        server -> {
          if (server.getTickCount() % 20 != 0) return;
          var iterator = sessions(server).entrySet().iterator();
          while (iterator.hasNext()) {
            var e = iterator.next();
            var p = server.getPlayerList().getPlayer(e.getKey());
            var s = e.getValue();
            if (p != null && !s.setupProblem.isEmpty() && accessible(p, s.position)) {
              s.expires = server.getTickCount() + 1200;
              send(p, setupView(s, false));
              continue;
            }
            var own = p == null ? null : vessel(p, s);
            if (own == null) {
              iterator.remove();
              continue;
            }
            if (server.getTickCount() % 20 == 0) refreshCatalog(p, s, own);
            s.expires = server.getTickCount() + 1200;
            send(p, view(p, s, own, false));
          }
        });
  }

  private ShipControl() {}
}
