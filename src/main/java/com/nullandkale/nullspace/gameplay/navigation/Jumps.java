package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;

/**
 * Jumping between regions and journeys: checks, departure, arrival position and resuming a paused
 * trip.
 */
public final class Jumps {

  static final class Jump {
    final UUID token = UUID.randomUUID(), actor;
    final String ship, destination;
    final BlockPos helm;
    final Set<UUID> crew = new HashSet<>(), ready = new HashSet<>(), arrived = new HashSet<>();
    int ticks;
    boolean committed;
    Mode mode = Mode.START;
    CompoundTag journey;

    Jump(ServerPlayer p, CompoundTag vessel, BlockPos helm, String destination) {
      actor = p.getUUID();
      ship = vessel.getStringOr("id", "");
      this.helm = helm.immutable();
      this.destination = destination;
    }
  }

  enum Mode {
    START,
    INVESTIGATE,
    RESUME,
    ARRIVE
  }

  /**
   * Arrival cells are simulation coordinates; storage slots remain independent and may be far away.
   */
  public static Vec3 arrivalOrigin(MinecraftServer server, CompoundTag own) {
    int start =
        Math.floorMod(WorldStore.pos(own, "base").getX() / TravelService.SPACE_SPACING, 4096);
    var neighbors =
        WorldStore.get(server).ships().stream()
            .map(t -> (CompoundTag) t)
            .filter(
                s ->
                    !s.getStringOr("id", "").equals(own.getStringOr("id", ""))
                        && s.getStringOr("dimension", "")
                            .equals(TravelService.SPACE.identifier().toString())
                        && LocalSpaces.together(own, s))
            .map(s -> SceneService.pose(s).toUniverse(new Vec3(16, 16, 16)))
            .toList();
    var definition = SpaceDefinitionCodec.decode(SceneCatalog.forShip(server, own));
    double time = server.overworld().getGameTime() / 20.0 - definition.epoch();
    for (int n = 0; n < 4096; n++) {
      int cell = (start + n) % 4096;
      int ix = cell % 64, iz = cell / 64;
      var origin =
          new Vec3(
              (ix % 2 == 0 ? ix / 2 : -(ix + 1) / 2) * 160,
              own.getDoubleOr("arrivalAltitude", 0),
              (iz % 2 == 0 ? iz / 2 : -(iz + 1) / 2) * 160);
      var center =
          origin.add(
              FlightDynamics.rotate(SceneService.pose(own).orientation(), new Vec3(16, 16, 16)));
      if (neighbors.stream().anyMatch(c -> c.distanceToSqr(center) < 160 * 160)) continue;
      if (definition.bodies().stream()
          .anyMatch(
              b ->
                  center.distanceToSqr(b.at(time))
                      < Math.pow(b.radius() * (1 + b.roughness()) + 28, 2))) continue;
      return origin;
    }
    throw new IllegalArgumentException("Destination arrival area is full");
  }

  public static void randomizeArrival(MinecraftServer server, CompoundTag ship) {
    var frame = FlightDynamics.randomArrival(new Random(server.overworld().getRandom().nextLong()));
    ship.putDouble("simYaw", frame.yaw());
    ship.putDouble("simPitch", frame.pitch());
    ship.putDouble("simRoll", frame.roll());
    ship.putDouble("arrivalAltitude", frame.altitude());
  }

  static ShipService.Inspection jumpInspection(ServerPlayer p, BlockPos at) {
    var inspected = ShipService.preflight(p.level(), at);
    var s = inspected.vessel();
    if (NavigationService.propulsion(p.level(), s) == 0)
      throw new IllegalArgumentException(
          "Power controls and at least one clear engine before fast travel");
    if (!inspected.scan().pressure().breathable(BlockPos.containing(p.getEyePosition())))
      throw new IllegalArgumentException("Board the sealed cabin before fast travel");
    for (var crew : p.level().players())
      if (WorldStore.bounds(s).box().inflate(72).contains(crew.position())
          && SceneService.observer(crew) == s
          && !inspected.scan().pressure().breathable(BlockPos.containing(crew.getEyePosition())))
        throw new IllegalArgumentException(
            "Recall EVA crew into a sealed cabin before fast travel");
    if (WorldStore.get(p.level().getServer()).list("pendingImpacts").stream()
        .map(t -> (CompoundTag) t)
        .anyMatch(e -> e.getStringOr("ship", "").equals(s.getStringOr("id", ""))))
      throw new IllegalArgumentException("Resolve incoming combat damage before fast travel");
    return inspected;
  }

  public static UUID jump(ServerPlayer p, BlockPos at, String destination) {
    return jumpTo(p, at, Piloting.helm(p, at), destination);
  }

  /** A previously authorized course can continue while its pilot walks inside the cabin. */
  public static UUID courseJump(ServerPlayer p, CompoundTag ship, String destination) {
    requireCoursePilot(p, ship);
    return jumpTo(p, WorldStore.pos(ship, "controller"), ship, destination);
  }

  static void requireCoursePilot(ServerPlayer p, CompoundTag ship) {
    var controller = WorldStore.pos(ship, "controller");
    if (!p.isAlive()
        || !p.level().dimension().equals(TravelService.SPACE)
        || SceneService.observer(p) != ship
        || !p.level().hasChunkAt(controller)
        || !ModBlocks.navigation(p.level().getBlockState(controller)))
      throw new IllegalArgumentException("Return aboard the ship to continue its course");
  }

  static UUID jumpTo(ServerPlayer p, BlockPos at, CompoundTag s, String destination) {
    var server = p.level().getServer();
    LocalSpaces.get(server, destination);
    String scanner = CourseService.scannerReason(p, s, destination);
    if (!scanner.isEmpty()) throw new IllegalArgumentException(scanner);
    if (JourneyService.active(s))
      throw new IllegalArgumentException("Finish or resume the current journey first");
    if (JourneyService.special(server, destination))
      throw new IllegalArgumentException(
          "Encounter signals are reached using Investigate during a journey");
    if (ShipStorage.active(p))
      throw new IllegalArgumentException("Finish or cancel your recall before fast travel");
    if (DockingService.motionLocked(s))
      throw new IllegalArgumentException("Undock before fast travel; stations are anchored");
    if (TransferLocks.busy(p) || TransferLocks.busy(server, s))
      throw new IllegalArgumentException("This ship or its crew is already travelling or boarding");
    if (destination.equals(LocalSpaces.address(s)))
      throw new IllegalArgumentException("Already in that local space");
    String range = StarSystems.rangeReason(p.level(), s, destination);
    if (!range.isEmpty()) throw new IllegalArgumentException(range);
    jumpInspection(p, at);
    int cost =
        FuelSystem.travelCost(
            p.level(), s, StarSystems.jumpBase(server, LocalSpaces.address(s), destination));
    if (FuelSystem.available(p.level(), s) < cost)
      throw new IllegalArgumentException(
          "Not enough fuel for this jump: needs "
              + FuelSystem.display(cost)
              + ". Load the generator.");
    PlanetSurfaces.ensureLanding(server, destination);
    SceneCatalog.region(server, destination);
    var selected = ShipDestination.selected(s);
    if (selected == null || !selected.region().equals(destination)) {
      ShipDestination.select(p, s, ShipDestination.region(server, destination));
    }
    var plan = JourneyService.plan(server, s, destination, cost);
    var j = new Jump(p, s, at, plan.getStringOr("corridor", ""));
    j.journey = plan;
    var token = begin(p, s, j);
    CourseService.prepareArrival(p, s, ShipDestination.selected(s));
    return token;
  }

  static UUID begin(ServerPlayer p, CompoundTag s, Jump j) {
    var server = p.level().getServer();
    for (var crew : p.level().players())
      if (SceneService.observer(crew) == s) {
        j.crew.add(crew.getUUID());
        crew.closeContainer();
      }
    NavigationService.closeShip(server, j.ship);
    ShipSystems.cancelTransit(p.level(), s);
    NavigationService.state(server).jumps.put(j.ship, j);
    NavigationService.broadcast(server, j, "Preparing fast travel", "", false);
    return j.token;
  }

  public static UUID investigate(ServerPlayer p, BlockPos at) {
    var s = Piloting.helm(p, at);
    String reason = JourneyService.stopReason(p.level().getServer(), s);
    if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
    var token = journeyTransition(p, s, Mode.INVESTIGATE);
    CourseService.investigate(p, s);
    return token;
  }

  public static UUID resume(ServerPlayer p, BlockPos at) {
    var s = Piloting.helm(p, at);
    if (!JourneyService.active(s) || JourneyService.cruising(s))
      throw new IllegalArgumentException("No paused journey to resume");
    jumpInspection(p, at);
    var token = journeyTransition(p, s, Mode.RESUME);
    CourseService.prepareArrival(
        p,
        s,
        ShipDestination.read(s.getCompoundOrEmpty("journey").getCompoundOrEmpty("selection")));
    return token;
  }

  public static UUID courseResume(ServerPlayer p, CompoundTag ship) {
    requireCoursePilot(p, ship);
    if (!JourneyService.active(ship) || JourneyService.cruising(ship))
      throw new IllegalArgumentException("No paused journey to resume");
    jumpInspection(p, WorldStore.pos(ship, "controller"));
    return journeyTransition(p, ship, Mode.RESUME);
  }

  static UUID journeyTransition(ServerPlayer p, CompoundTag s, Mode mode) {
    var server = p.level().getServer();
    if (DockingService.motionLocked(s) || TransferLocks.busy(p) || TransferLocks.busy(server, s))
      throw new IllegalArgumentException(
          "Undock and finish boarding before continuing the journey");
    if (!NavigationService.crewAboard(p.level(), s))
      throw new IllegalArgumentException("Bring crew into safe cabins before changing regions");
    var journey = s.getCompoundOrEmpty("journey");
    String target =
        journey.getStringOr(
            switch (mode) {
              case INVESTIGATE -> "encounter";
              case RESUME -> "corridor";
              default -> "destination";
            },
            "");
    var j = new Jump(p, s, WorldStore.pos(s, "controller"), target);
    j.mode = mode;
    return begin(p, s, j);
  }

  static void arrive(ServerPlayer p, CompoundTag s) {
    // Arrival is paid already, and does not require standing at the controller or engine power.
    if (TransferLocks.busy(p.level().getServer(), s)
        || !NavigationService.crewAboard(p.level(), s)) {
      s.putString("flightStatus", "Arrival ready: return crew to safe cabins");
      return;
    }
    journeyTransition(p, s, Mode.ARRIVE);
  }

  private Jumps() {}
}
