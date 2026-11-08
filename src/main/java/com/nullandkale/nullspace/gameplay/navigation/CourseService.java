package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.docking.DockingTargets;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationSpace;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSensors;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** A saved destination intent; existing travel services retain ownership of every physical step. */
public final class CourseService {
  private static final String KEY = "course";

  public static boolean active(CompoundTag ship) {
    return ship != null
        && Set.of("ACTIVE", "STOPPING")
            .contains(ship.getCompoundOrEmpty(KEY).getStringOr("state", ""));
  }

  public static String summary(CompoundTag ship) {
    var course = ship.getCompoundOrEmpty(KEY);
    return course.isEmpty() ? "" : course.getStringOr("message", "");
  }

  private static ShipDestination resolve(
      ServerPlayer player, CompoundTag ship, ShipDestination target) {
    if (target == null) return null;
    if ((target.kind() == ShipDestination.Kind.VESSEL || target.kind() == ShipDestination.Kind.PORT)
        && ship.getCompoundOrEmpty("dock").getStringOr("target", "").equals(target.reference())) {
      var partner =
          WorldStore.get(player.level().getServer()).repository().find(target.reference());
      if (partner != null && StationAccess.allowed(player, partner))
        return new ShipDestination(
            target.kind(),
            target.reference(),
            target.port(),
            LocalSpaces.address(partner),
            target.name(),
            "Connected");
    }
    return ShipDestination.navigationCatalog(player, ship).stream()
        .filter(d -> d.id().equals(target.id()))
        .findFirst()
        .orElse(null);
  }

  private static ShipDestination follow(
      ServerPlayer player, CompoundTag ship, ShipDestination saved) {
    if (saved == null) return null;
    if (saved.kind() != ShipDestination.Kind.VESSEL && saved.kind() != ShipDestination.Kind.PORT)
      return resolve(player, ship, saved);
    var vessel = WorldStore.get(player.level().getServer()).repository().find(saved.reference());
    if (vessel == null
        || !StationAccess.allowed(player, vessel)
        || !StationAccess.ready(vessel)
        || !vessel.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()))
      return null;
    return new ShipDestination(
        saved.kind(),
        saved.reference(),
        saved.port(),
        LocalSpaces.address(vessel),
        vessel.getStringOr("home", saved.name()),
        "Course destination");
  }

  private static boolean atPad(CompoundTag ship, ShipDestination target) {
    return !ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString())
        && target.kind() == ShipDestination.Kind.PAD
        && ship.getStringOr("homeId", "").equals(target.reference());
  }

  private static boolean atDock(CompoundTag ship, ShipDestination target) {
    var dock = ship.getCompoundOrEmpty("dock");
    return (target.kind() == ShipDestination.Kind.VESSEL
            || target.kind() == ShipDestination.Kind.PORT)
        && dock.getStringOr("state", "").equals("CLAMPED")
        && dock.getStringOr("target", "").equals(target.reference());
  }

  /** Once the encounter visit is docked, Engage continues the paid trip rather than re-docking. */
  public static ShipDestination departureTarget(
      MinecraftServer server, CompoundTag ship, ShipDestination selected) {
    if (selected == null
        || !JourneyService.active(ship)
        || JourneyService.cruising(ship)
        || !atDock(ship, selected)) return selected;
    var journey = ship.getCompoundOrEmpty("journey");
    if (!LocalSpaces.address(ship).equals(journey.getStringOr("encounter", ""))) return selected;
    String region = journey.getStringOr("destination", "");
    var saved = ShipDestination.read(journey.getCompoundOrEmpty("selection"));
    return saved != null && saved.region().equals(region)
        ? saved
        : ShipDestination.region(server, region);
  }

  public static String preview(ServerPlayer player, CompoundTag ship, ShipDestination target) {
    target = departureTarget(player.level().getServer(), ship, target);
    if (target == null) return "Choose a destination";
    if (atPad(ship, target) || atDock(ship, target)) return "Already at " + target.name();
    var steps = new ArrayList<String>();
    if (ship.contains("dock")) steps.add("Undock");
    if (!player.level().dimension().equals(TravelService.SPACE)) steps.add("Launch");
    if (!LocalSpaces.address(ship).equals(target.region()))
      steps.add(
          StarSystems.sameSystem(
                  player.level().getServer(), LocalSpaces.address(ship), target.region())
              ? "Local travel"
              : "Interstellar jump");
    steps.add(
        switch (target.kind()) {
          case PAD -> "Land";
          case PORT, VESSEL -> "Dock";
          case REGION -> "Hold in orbit";
        });
    return "Trip: " + String.join(" -> ", steps);
  }

  public static String scannerReason(ServerPlayer player, CompoundTag ship, String region) {
    return StarSystems.sameSystem(player.level().getServer(), LocalSpaces.address(ship), region)
            || ShipSensors.installed(player.level(), ship, false)
        ? ""
        : "Interstellar travel needs an enabled, powered Scanner. Local travel does not.";
  }

  public static int minimumFuel(ServerPlayer player, CompoundTag ship, ShipDestination target) {
    target = departureTarget(player.level().getServer(), ship, target);
    if (target == null || atPad(ship, target) || atDock(ship, target)) return 0;
    int transfer = FuelSystem.transfer(ShipSystems.connected(player.level(), ship).size());
    int cost =
        player.level().dimension().equals(TravelService.SPACE)
            ? 0
            : FuelSystem.travelCost(player.level(), ship, transfer);
    if (target.kind() == ShipDestination.Kind.PAD)
      cost += FuelSystem.travelCost(player.level(), ship, transfer);
    if (!JourneyService.active(ship) && !LocalSpaces.address(ship).equals(target.region()))
      cost +=
          FuelSystem.travelCost(
              player.level(),
              ship,
              StarSystems.jumpBase(
                  player.level().getServer(), LocalSpaces.address(ship), target.region()));
    return cost;
  }

  public static String reason(ServerPlayer player, CompoundTag ship, ShipDestination target) {
    target = departureTarget(player.level().getServer(), ship, target);
    var previous = ship.getCompoundOrEmpty(KEY);
    var previousTarget = ShipDestination.read(previous.getCompoundOrEmpty("destination"));
    boolean completedDock =
        previous.getStringOr("state", "").equals("ACTIVE")
            && previousTarget != null
            && atDock(ship, previousTarget);
    if (active(ship) && !completedDock) return "Course already engaged";
    if (StationAccess.station(ship)) return "Stations are anchored";
    if (target == null) return "Choose a destination";
    if (ShipStorage.active(player)
        || TransferLocks.busy(player.level().getServer(), ship)
        || NavigationService.pending(player.level().getServer(), ship.getStringOr("id", "")))
      return "Finish the current transfer first";
    if (atPad(ship, target) || atDock(ship, target)) return "Already at this destination";
    if (target.kind() == ShipDestination.Kind.REGION
        && player.level().dimension().equals(TravelService.SPACE)
        && !ship.contains("dock")
        && LocalSpaces.address(ship).equals(target.region())) return "Already in this orbit";
    if (JourneyService.active(ship)
        && !ship.getCompoundOrEmpty("journey")
            .getStringOr("destination", "")
            .equals(target.region())
        && !LocalSpaces.address(ship).equals(target.region()))
      return "Finish the paid journey before choosing another region";
    if (ship.contains("dock")
        && !ship.getCompoundOrEmpty("dock")
            .getStringOr("source", "")
            .equals(ship.getStringOr("id", "")))
      return "The other ship is managing this connection; separate there first";
    if (!JourneyService.active(ship)) {
      String scanner = scannerReason(player, ship, target.region());
      if (!scanner.isEmpty()) return scanner;
      if (!LocalSpaces.address(ship).equals(target.region())) {
        String range = StarSystems.rangeReason(player.level(), ship, target.region());
        if (!range.isEmpty()) return range;
      }
    }
    if (NavigationService.propulsion(player.level(), ship) == 0)
      return "Load fuel and power controls and an engine";
    int cost = minimumFuel(player, ship, target);
    if (FuelSystem.available(player.level(), ship) < cost)
      return "Fuel "
          + FuelSystem.display(FuelSystem.available(player.level(), ship))
          + "/"
          + FuelSystem.display(cost)
          + ": load furnace fuel in a generator (docking also uses fuel)";
    return "";
  }

  public static String engage(ServerPlayer player, CompoundTag ship, ShipDestination target) {
    target = departureTarget(player.level().getServer(), ship, target);
    target = resolve(player, ship, target);
    String blocked = reason(player, ship, target);
    if (!blocked.isEmpty()) return blocked;
    var stuck = SystemsConsole.closeDoorsNow(player.level(), ship);
    if (!stuck.isEmpty())
      return "Engage stopped: the door at "
          + stuck.getFirst().toShortString()
          + " will not close (redstone or a block in the way). Close it, then Engage again";
    if (!JourneyService.cruising(ship)
        && (ShipDestination.selected(ship) == null
            || !ShipDestination.selected(ship).id().equals(target.id())))
      ShipDestination.select(player, ship, target);
    var course = new CompoundTag();
    course.putInt("version", 1);
    course.putString("state", "ACTIVE");
    course.putString("actor", player.getUUID().toString());
    course.put("destination", target.save());
    course.putString("message", preview(player, ship, target));
    ship.put(KEY, course);
    WorldStore.get(player.level().getServer()).save();
    return "Course engaged: " + target.name();
  }

  public static String disengage(MinecraftServer server, CompoundTag ship) {
    if (!active(ship)) return "No engaged course";
    var course = ship.getCompoundOrEmpty(KEY);
    course.putString("state", "STOPPING");
    course.putString("message", "Disengaging: finishing the current safe transfer or departure");
    WorldStore.get(server).save();
    return course.getStringOr("message", "");
  }

  /** Investigate is the player's consent to approach the signal, not another Engage checkpoint. */
  public static void investigate(ServerPlayer player, CompoundTag ship) {
    var course = new CompoundTag();
    course.putInt("version", 1);
    course.putString("state", "ACTIVE");
    course.putString("actor", player.getUUID().toString());
    course.putBoolean("awaitingEncounter", true);
    course.putString("step", "INVESTIGATE");
    ship.put(KEY, course);
    WorldStore.get(player.level().getServer()).save();
  }

  /** Retain automatic arrival for an explicit manual jump to a vessel as well. */
  public static void prepareArrival(ServerPlayer player, CompoundTag ship, ShipDestination target) {
    if (active(ship)
        || target == null
        || target.kind() != ShipDestination.Kind.PORT
            && target.kind() != ShipDestination.Kind.VESSEL) return;
    var course = new CompoundTag();
    course.putInt("version", 1);
    course.putString("state", "ACTIVE");
    course.putString("actor", player.getUUID().toString());
    course.putString("step", "JUMP");
    course.put("destination", target.save());
    ship.put(KEY, course);
    WorldStore.get(player.level().getServer()).save();
  }

  private static void finish(
      MinecraftServer server, CompoundTag ship, String state, String message) {
    var course = ship.getCompoundOrEmpty(KEY);
    course.putString("state", state);
    course.putString("message", message);
    WorldStore.get(server).save();
    server.getPlayerList().getPlayers().stream()
        .filter(p -> SceneObserver.aboard(p, ship))
        .forEach(p -> p.sendSystemMessage(Component.literal(message)));
    NullSpace.LOGGER.info("Course {}: {} | ship={}", state, message, ship.getStringOr("id", ""));
  }

  private static final class SceneObserver {
    static boolean aboard(ServerPlayer p, CompoundTag ship) {
      return p.isAlive()
          && p.level().dimension().identifier().toString().equals(ship.getStringOr("dimension", ""))
          && SceneService.observer(p) == ship;
    }
  }

  private static void step(MinecraftServer server, CompoundTag ship) {
    var course = ship.getCompoundOrEmpty(KEY);
    if (TransferLocks.busy(server, ship)
        || NavigationService.pending(server, ship.getStringOr("id", ""))) return;
    var player = server.getPlayerList().getPlayer(UUID.fromString(course.getStringOr("actor", "")));
    if (player == null || !SceneObserver.aboard(player, ship)) {
      finish(
          server,
          ship,
          "PAUSED",
          "Course paused: pilot disconnected or left the ship. Engage again aboard.");
      return;
    }
    var dock = ship.getCompoundOrEmpty("dock");
    String phase = dock.getStringOr("state", "");
    if (course.getStringOr("state", "").equals("STOPPING")) {
      if (JourneyService.cruising(ship)
          || phase.equals("UNDOCKING")
          || phase.equals("EMERGENCY_BACKOFF")) return;
      if (!phase.isBlank() && !phase.equals("CLAMPED")) {
        DockingService.perform(player, ship, WorldStore.pos(ship, "controller"), "DOCK_CANCEL");
        course.putString("message", "Disengaging: backing away from the dock safely");
        return;
      }
      finish(server, ship, "STOPPED", "Course disengaged; ship stopped safely");
      return;
    }
    if (course.getBooleanOr("awaitingEncounter", false)) {
      var selected = ShipDestination.selected(ship);
      if (JourneyService.cruising(ship)
          || selected == null
          || selected.kind() != ShipDestination.Kind.PORT
              && selected.kind() != ShipDestination.Kind.VESSEL
          || !selected.region().equals(LocalSpaces.address(ship))) {
        finish(server, ship, "PAUSED", "Destination moved or is unavailable. Choose a new course.");
        return;
      }
      course.put("destination", selected.save());
      course.remove("awaitingEncounter");
      course.remove("step");
    }
    var saved = ShipDestination.read(course.getCompoundOrEmpty("destination"));
    var target = follow(player, ship, saved);
    if (target == null || !target.region().equals(saved.region())) {
      finish(server, ship, "PAUSED", "Destination moved or is unavailable. Choose a new course.");
      return;
    }
    if (atPad(ship, target) || atDock(ship, target)) {
      finish(
          server,
          ship,
          "ARRIVED",
          "Arrived at "
              + target.name()
              + (atDock(ship, target) ? ". Open the dock door to walk through." : ""));
      return;
    }
    if (JourneyService.cruising(ship) || !phase.isBlank() && !phase.equals("CLAMPED")) return;
    String action;
    if (phase.equals("CLAMPED")) action = "UNDOCK";
    else if (!player.level().dimension().equals(TravelService.SPACE)) action = "LAUNCH";
    else if (!LocalSpaces.address(ship).equals(target.region()))
      action = JourneyService.active(ship) ? "RESUME" : "JUMP";
    else if (target.kind() == ShipDestination.Kind.PAD) action = "LAND";
    else if (target.kind() == ShipDestination.Kind.REGION) {
      finish(server, ship, "ARRIVED", "Arrived in orbit: " + target.name());
      return;
    } else action = "DOCK";
    if (action.equals(course.getStringOr("step", ""))) {
      finish(
          server,
          ship,
          "PAUSED",
          action + " did not complete. Check the ship/obstruction, then Engage again.");
      return;
    }
    course.putString("step", action);
    course.putString(
        "message", "Course: " + action.toLowerCase(Locale.ROOT) + " -> " + target.name());
    WorldStore.get(server)
        .save(); // The next service's journal snapshots this course checkpoint too.
    switch (action) {
      case "UNDOCK" ->
          DockingService.perform(player, ship, WorldStore.pos(ship, "controller"), "DOCK_UNDOCK");
      case "LAUNCH", "LAND" ->
          TravelService.request(
              player,
              WorldStore.pos(ship, "controller"),
              action.equals("LAUNCH") ? "" : "id:" + target.reference(),
              ShipService.preflight(player.level(), WorldStore.pos(ship, "controller")));
      case "JUMP" -> Jumps.courseJump(player, ship, target.region());
      case "RESUME" -> Jumps.courseResume(player, ship);
      case "DOCK" -> {
        // Discovery deliberately avoids loading remote terrain. At the chosen destination we must
        // load its real ports before allocating a berth, just as request() does for an exact port.
        var vessel = WorldStore.get(server).repository().find(target.reference());
        if (vessel != null) {
          StationSpace.chunks(player.level(), vessel);
          ShipSystems.loaded(player.level(), vessel);
        }
        var berth =
            DockingTargets.destinations(player, ship, true).stream()
                .filter(
                    p ->
                        p.vessel().equals(target.reference())
                            && (target.kind() != ShipDestination.Kind.PORT
                                || p.port().equals(target.port()))
                            && !p.state().equalsIgnoreCase("Occupied"))
                .findFirst();
        if (berth.isEmpty()) {
          if (vessel != null && DockingPorts.ports(player.level(), vessel).isEmpty())
            DockingPorts.firstPort(player.level(), vessel); // Report the actual damaged doorway.
          NullSpace.LOGGER.info(
              "Course berth unavailable: target={} region={} phase={} ports={} registered={}",
              target.reference(),
              vessel == null ? "missing" : LocalSpaces.address(vessel),
              vessel == null
                  ? "missing"
                  : vessel.getCompoundOrEmpty("dock").getStringOr("state", ""),
              vessel == null ? 0 : DockingPorts.ports(player.level(), vessel).size(),
              vessel == null
                  ? 0
                  : vessel.getCompoundOrEmpty("systems").getListOrEmpty("devices").size());
          finish(
              server,
              ship,
              "PAUSED",
              "No free berth at "
                  + target.name()
                  + ". Holding safely; Engage when a berth is available.");
          return;
        }
        var result = DockingManeuver.request(player, ship, target.reference(), berth.get().port());
        if (!result.ready()) finish(server, ship, "PAUSED", result.message());
      }
    }
  }

  public static void initialize() {
    ServerContext.tick(
        ServerContext.Phase.NAVIGATION,
        server -> {
          if (server.getTickCount() % 10 != 0) return;
          for (var entry : List.copyOf(WorldStore.get(server).ships())) {
            var ship = (CompoundTag) entry;
            if (!active(ship)) continue;
            try {
              step(server, ship);
            } catch (IllegalArgumentException blocked) {
              finish(server, ship, "PAUSED", ShipMessages.failure(blocked));
            } catch (RuntimeException error) {
              NullSpace.LOGGER.error("Course failed for {}", ship.getStringOr("id", ""), error);
              finish(
                  server,
                  ship,
                  "PAUSED",
                  "Course stopped after an unexpected error; see server log");
            }
          }
        });
  }

  private CourseService() {}
}
