package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import org.joml.Vector3d;

/** Server-owned docking reservations, complete autopilot maneuvers and passenger-only transfers. */
public final class DockingService {
  public record Port(
      String id,
      BlockPos controller,
      BlockPos outer,
      BlockPos inner,
      Direction facing,
      Set<BlockPos> cells,
      List<BlockPos> anchors) {}

  public record Choice(String vessel, BlockPos port, String label, String state) {}

  private static final ServerContext.Key<Map<String, List<Vec3>>> ROUTES =
      new ServerContext.Key<>();
  private static final ServerContext.Key<Set<UUID>> READY = new ServerContext.Key<>(),
      ARRIVED = new ServerContext.Key<>();
  private static final ServerContext.Key<Map<UUID, Integer>> WAIT = new ServerContext.Key<>();

  private static CompoundTag find(MinecraftServer server, String id) {
    return ShipStore.get(server).repository().find(id);
  }

  private static String id(CompoundTag s) {
    return s.getStringOr("id", "");
  }

  private static boolean exempt(ServerPlayer p) {
    return p.isCreative() || p.isSpectator();
  }

  private static boolean mobile(CompoundTag s) {
    return s != null && !StationService.station(s);
  }

  public static boolean motionLocked(CompoundTag s) {
    return StationService.station(s) || s != null && s.contains("dock");
  }

  public static boolean engaged(CompoundTag s) {
    return s.contains("dock") || !s.getCompoundOrEmpty("dockLinks").isEmpty();
  }

  public static boolean cycleOwnedInner(ServerPlayer p, CompoundTag s, BlockPos door) {
    for (var port : ports(p.level(), s))
      if (port.inner().equals(door)) {
        var d = link(p.level().getServer(), s, port.controller());
        if (d != null && !d.getStringOr("state", "").equals("CLAMPED"))
          throw new IllegalArgumentException("Wait for capture before cycling a dock-owned door");
        safeInner(p, s, readPort(p.level(), s, port.controller(), true));
        return true;
      }
    return false;
  }

  public static void commissionConstruction(MinecraftServer server, CompoundTag station) {
    var source = find(server, station.getStringOr("constructionShip", ""));
    if (source == null) return;
    var d = source.getCompoundOrEmpty("dock");
    if (!d.getBooleanOr("construction", false) || !d.getStringOr("state", "").equals("CLAMPED"))
      return;
    if (boarding(server, id(source)) || boarding(server, id(station))) return;
    try {
      var level = server.getLevel(TravelService.SPACE);
      var port =
          ports(level, station).stream()
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Finish a station docking doorway before commissioning"));
      var sourcePort =
          readPort(
              level,
              source,
              ShipStore.pos(source, "base").offset(ShipStore.pos(d, "sourcePort")),
              false);
      double gap = protrusion(level, source, sourcePort) + protrusion(level, station, port) + .05;
      if (gap > 4.05) {
        status(
            server,
            source,
            "Move the finished station dock outward; its hull recess exceeds four blocks",
            true);
        return;
      }
      var aligned = matingPose(source, sourcePort, station, port, gap);
      if (nearHull(level, source, aligned)) {
        status(
            server,
            source,
            "Clear hulls around the finished station dock before connecting it",
            true);
        return;
      }
      // A construction platform has no hull around its virtual port. The finished room does.
      // Recompute separation before publishing the real berth; retaining the platform gap embeds
      // recessed ship noses in the new station wall and traps their departure.
      pose(source, aligned);
      d.putDouble("gap", gap);
      saveEscape(
          d,
          aligned
              .position()
              .add(
                  FlightDynamics.rotate(
                          SceneService.pose(station).orientation(),
                          DockingMotion.normal(port.facing()))
                      .scale(112)));
      d.putLong("targetPort", port.controller().subtract(ShipStore.pos(station, "base")).asLong());
      var marker = station.getCompoundOrEmpty("dockLinks").getCompoundOrEmpty("construction");
      station.getCompoundOrEmpty("dockLinks").remove("construction");
      station.getCompoundOrEmpty("dockLinks").put(port.id(), marker);
      d.putString("targetNode", port.id());
      d.putBoolean("construction", false);
      ShipStore.get(server).save();
    } catch (IllegalArgumentException notReady) {
    }
  }

  public record Readiness(boolean ready, String message) {}

  public static Readiness readiness(ServerPlayer p, CompoundTag s) {
    if (com.nullandkale.nullspace.navigation.JourneyService.cruising(s))
      return new Readiness(false, "FTL underway: investigate a signal before docking");
    if (!mobile(s)
        || s.contains("dock")
        || !p.level().dimension().equals(TravelService.SPACE)
        || TravelService.busy(p)
        || TravelService.busy(p.level().getServer(), s)
        || !StationService.ready(s))
      return new Readiness(
          false, "Finish docking, construction or travel before starting another maneuver");
    if (!ShipSystems.enabled(s))
      return new Readiness(
          false,
          "This ship has no enabled power/air systems record. Open Systems > Upgrade systems.");
    if (FuelSystem.available(p.level(), s) == 0)
      return new Readiness(false, "No shared fuel. Load furnace fuel into a connected generator.");
    if (NavigationService.propulsion(p.level(), s) == 0)
      return new Readiness(false, "Controls and propulsion must have power");
    var bounds = ShipStore.bounds(s);
    var sealed = new ShipScan.LivePressure(p.level(), bounds);
    for (var crew : p.level().players())
      if (crew.isAlive() && SceneService.observer(crew) == s && !exempt(crew)) {
        var eye = BlockPos.containing(crew.getEyePosition());
        String who = crew.getPlainTextName() + " at " + eye.toShortString();
        if (!bounds.contains(eye) || !sealed.breathable(eye))
          return new Readiness(
              false,
              who
                  + " is outside a sealed cabin. Return inside, close exterior doors and check the"
                  + " hull seal.");
        if (!ShipSystems.breathable(p.level(), s, eye)) {
          var local = eye.subtract(ShipStore.pos(s, "base"));
          int oxygen =
              ShipSystems.pressure(p.level(), s, local.getX(), local.getY(), local.getZ())
                  * 100
                  / Atmosphere.FULL;
          return new Readiness(
              false,
              who
                  + " is aboard, but cabin oxygen is "
                  + oxygen
                  + "% (25% needed)."
                  + " Check air power and the supply outlet, then let the cabin refill.");
        }
      }
    return new Readiness(true, "Ready to dock");
  }

  public static void requireFree(ServerPlayer p, CompoundTag s) {
    var result = readiness(p, s);
    if (!result.ready()) throw new IllegalArgumentException(result.message());
  }

  public static Port readPort(
      ServerLevel level, CompoundTag s, BlockPos controller, boolean requirePower) {
    var state = level.getBlockState(controller);
    if (!state.is(ShipBlocks.DOCKING_BASE))
      throw new IllegalArgumentException("Docking base is missing");
    return compactPort(level, s, controller, requirePower);
  }

  private static Port compactPort(
      ServerLevel level, CompoundTag ship, BlockPos base, boolean powered) {
    var door = base.above();
    var state = level.getBlockState(door);
    if (!(state.getBlock() instanceof DoorBlock)
        || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
        || !ShipStore.bounds(ship).contains(base)
        || !ShipDoors.framed(level, door))
      throw new IllegalArgumentException(
          "Place a framed standard door directly above the Docking Base; leave walking space"
              + " inside");
    // Door placement changes its facing independently of which side is the cabin.
    // The docking hardware defines the outward approach and boarding direction.
    var facing = level.getBlockState(base).getValue(HorizontalDirectionalBlock.FACING);
    if (facing.getAxis() != state.getValue(DoorBlock.FACING).getAxis())
      throw ShipMessages.blockProblem(
          level, base, "Turn the Docking Base to face outward through the doorway, not sideways");
    var anchors = new ArrayList<BlockPos>();
    var cells = new HashSet<BlockPos>();
    cells.add(door);
    cells.add(door.above());
    for (int depth = 1; depth <= 2; depth++) {
      var at = door.relative(facing.getOpposite(), depth);
      if (ShipStore.bounds(ship).contains(at) && StationGraph.standing(level, at)) {
        anchors.add(at);
        cells.add(at);
        cells.add(at.above());
      }
    }
    if (anchors.isEmpty())
      throw new IllegalArgumentException(
          "Clear two-block-high walking space behind the docking door");
    if (powered
        && (!ShipSystems.enabled(ship)
            || ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 0
            || ShipSystems.power(level, ship, PowerBus.System.DOORS) == 0
            || !ShipSystems.active(ship, base)))
      throw new IllegalArgumentException("Docking Base needs controller and door power");
    var identities = ship.getCompoundOrEmpty("doorNodes");
    if (!ship.contains("doorNodes")) ship.put("doorNodes", identities);
    String key = Long.toString(base.subtract(ShipStore.pos(ship, "base")).asLong());
    String id = identities.getStringOr(key, "");
    if (id.isEmpty()) {
      id = UUID.randomUUID().toString();
      identities.putString(key, id);
    }
    return new Port(id, base, door, door, facing, Set.copyOf(cells), List.copyOf(anchors));
  }

  /** A captured compact connection seals its doorway without manufacturing cabin oxygen. */
  public static boolean sealedMate(ServerLevel level, BlockPos door) {
    var state = level.getBlockState(door);
    if (!(state.getBlock() instanceof DoorBlock)) return false;
    if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) door = door.below();
    var at = door.below();
    if (!level.getBlockState(at).is(ShipBlocks.DOCKING_BASE)) return false;
    var ship = ShipStore.get(level.getServer()).at(level, at);
    if (ship == null) return false;
    var d = link(level.getServer(), ship, at);
    return d != null
        && !d.getBooleanOr("construction", false)
        && d.getStringOr("state", "").equals("CLAMPED");
  }

  public static boolean useCompactDoor(ServerPlayer p, BlockPos at) {
    var level = p.level();
    var state = level.getBlockState(at);
    if (!(state.getBlock() instanceof DoorBlock)) return false;
    if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) at = at.below();
    if (!level.getBlockState(at.below()).is(ShipBlocks.DOCKING_BASE)) return false;
    var own = ShipStore.get(level.getServer()).at(level, at);
    if (own == null || !StationService.allowed(p, own) && !StationService.returningVisitor(p, own))
      return true;
    var link = link(level.getServer(), own, at.below());
    if (link != null && !link.getStringOr("state", "").equals("CLAMPED")) {
      p.sendOverlayMessage(Component.literal("Wait for docking capture before opening the door"));
      return true;
    }
    boolean opening = !ShipDoors.open(level.getBlockState(at));
    if (opening
        && level.dimension().equals(TravelService.SPACE)
        && !sealedMate(level, at)
        && !exempt(p)
        && (!SpaceSurvival.suited(p) || p.getAirSupply() < 40)) {
      p.sendOverlayMessage(
          Component.literal("This door opens to vacuum. Dock first or equip a complete suit."));
      return true;
    }
    ShipDoors.setOpen(level, at, opening);
    return true;
  }

  static void walkThrough(MinecraftServer server) {
    var level = server.getLevel(TravelService.SPACE);
    if (level == null) return;
    for (var p : List.copyOf(level.players())) {
      if (TravelService.busy(p)) continue;
      var own = com.nullandkale.nullspace.scene.SceneService.observer(p);
      if (own == null || !engaged(own)) continue;
      for (var port : ports(level, own)) {
        if (!port.inner().equals(port.outer())
            || !ShipDoors.open(level.getBlockState(port.outer()))) continue;
        var d = link(server, own, port.controller());
        if (d == null || !d.getStringOr("state", "").equals("CLAMPED")) continue;
        if (!compactEntrance(p, own, port)) continue;
        boolean source = d.getStringOr("source", "").equals(id(own));
        var other = find(server, d.getStringOr(source ? "target" : "source", ""));
        if (other == null) continue;
        try {
          boolean construction = d.getBooleanOr("construction", false);
          var targetPort =
              construction
                  ? null
                  : readPort(
                      level,
                      other,
                      ShipStore.pos(other, "base")
                          .offset(ShipStore.pos(d, source ? "targetPort" : "sourcePort")),
                      false);
          beginBoard(p, own, other, port, targetPort, construction);
        } catch (IllegalArgumentException blocked) {
          p.sendOverlayMessage(Component.literal(blocked.getMessage()));
          var safe = port.anchors().getFirst();
          p.teleportTo(safe.getX() + .5, safe.getY(), safe.getZ() + .5);
          p.setDeltaMovement(Vec3.ZERO);
        }
        break;
      }
    }
  }

  private static boolean compactEntrance(ServerPlayer p, CompoundTag ship, Port port) {
    if (!port.inner().equals(port.outer())) return false;
    var dock = link(p.level().getServer(), ship, port.controller());
    var delta = p.position().subtract(Vec3.atBottomCenterOf(port.outer()));
    double forward = delta.dot(DockingMotion.normal(port.facing()));
    return forward >= -.15
        && forward <= DockingTunnel.boardingReach(dock, id(ship), port)
        && Math.abs(delta.dot(DockingMotion.normal(port.facing().getClockWise()))) <= .8
        && delta.y >= -.2
        && delta.y <= 1.5;
  }

  public static List<Port> ports(ServerLevel level, CompoundTag s) {
    if (!StationService.ready(s)) return List.of();
    var ports = new ArrayList<Port>();
    for (var at : ShipSystems.connected(level, s))
      if (level.getBlockState(at).is(ShipBlocks.DOCKING_BASE)) {
        try {
          ports.add(readPort(level, s, at, false));
        } catch (IllegalArgumentException invalid) {
        }
      }
    ports.sort(Comparator.comparingLong(p -> p.controller().asLong()));
    return List.copyOf(ports);
  }

  public static Port firstPort(ServerLevel level, CompoundTag s) {
    var ports = ports(level, s);
    if (ports.isEmpty()) {
      for (var at : ShipSystems.connected(level, s).stream().sorted().toList())
        if (level.getBlockState(at).is(ShipBlocks.DOCKING_BASE)) {
          try {
            return readPort(level, s, at, true);
          } catch (IllegalArgumentException invalid) {
            throw ShipMessages.blockProblem(level, at, invalid.getMessage());
          }
        }
      throw new IllegalArgumentException(
          "Build a framed door over an outward-facing Docking Base"
              + " with clear walking room inside");
    }
    return readPort(level, s, ports.getFirst().controller(), true);
  }

  private static void chunks(ServerLevel level, CompoundTag s) {
    StationSpace.chunks(level, s);
  }

  private static List<Choice> choices(ServerPlayer p, CompoundTag own) {
    return destinations(p, own, false).stream().filter(c -> !c.state().equals("Occupied")).toList();
  }

  /** Registered station berths may be selected before jumping to their region. */
  public static List<Choice> destinations(ServerPlayer p, CompoundTag own, boolean remoteStations) {
    var out = new ArrayList<Choice>();
    var level = remoteStations ? p.level().getServer().getLevel(TravelService.SPACE) : p.level();
    if (level == null) return List.of();
    for (var tag : ShipStore.get(level.getServer()).ships()) {
      if (out.size() >= (remoteStations ? 384 : 64)) break;
      var s = (CompoundTag) tag;
      if (s == own
          || !s.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
          || !LocalSpaces.together(s, own) && !(remoteStations && StationService.station(s))
          || !StationService.ready(s)
          || !StationService.allowed(p, s)
          || s.contains("dock")) continue;
      // Contacts expose bounded registered ports, not arbitrary remote inventories or terrain.
      if (!StationService.station(s)
          && SceneService.pose(s).position().distanceTo(SceneService.pose(own).position()) > 4096)
        continue;
      if (remoteStations) {
        var bounds = ShipStore.bounds(s);
        boolean loaded = true;
        for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
          for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
            loaded &= level.hasChunk(x, z);
        if (!loaded) {
          // The destination picker reads registered devices without loading remote terrain.
          // Request performs the authoritative block/airlock checks before reserving anything.
          for (var tagDevice : s.getCompoundOrEmpty("systems").getListOrEmpty("devices")) {
            var device = (CompoundTag) tagDevice;
            if (!device.getStringOr("type", "").equals("nullspace:docking_base")) continue;
            var local = BlockPos.of(device.getLongOr("position", 0));
            var node =
                s.getCompoundOrEmpty("doorNodes").getStringOr(Long.toString(local.asLong()), "");
            out.add(
                new Choice(
                    id(s),
                    local,
                    device.getStringOr("label", "").isBlank()
                        ? portLabel(s, local)
                        : s.getStringOr("home", "Ship") + " / " + device.getStringOr("label", ""),
                    s.getCompoundOrEmpty("dockLinks").contains(node) ? "Occupied" : "Unverified"));
          }
          continue;
        }
      } else chunks(level, s);
      for (var port : ports(level, s))
        out.add(
            new Choice(
                id(s),
                port.controller().subtract(ShipStore.pos(s, "base")),
                level.getBlockEntity(port.controller()) instanceof MachineEntity machine
                        && !machine.label.isBlank()
                    ? s.getStringOr("home", "Ship") + " / " + machine.label
                    : portLabel(s, port.controller().subtract(ShipStore.pos(s, "base"))),
                s.getCompoundOrEmpty("dockLinks").contains(port.id()) ? "Occupied" : "Available"));
    }
    out.sort(Comparator.comparing(Choice::label));
    return out.subList(0, Math.min(remoteStations ? 384 : 64, out.size()));
  }

  private static String portLabel(CompoundTag ship, BlockPos local) {
    return ship.getStringOr("home", "Ship")
        + " / Port "
        + local.getX()
        + ","
        + local.getY()
        + ","
        + local.getZ();
  }

  public static String contacts(ServerPlayer p, CompoundTag own) {
    var choices = choices(p, own);
    if (!choices.isEmpty() && ShipDestination.selected(own) == null)
      selectDestination(p, own, choices.getFirst());
    var selected = selectedDestination(own);
    StringBuilder text =
        new StringBuilder(
            "DOCKING | " + own.getCompoundOrEmpty("dock").getStringOr("state", "Available"));
    if (choices.isEmpty())
      text.append("\nNo available berths. Local station contacts update automatically.");
    else
      for (int i = 0; i < choices.size(); i++)
        text.append(sameChoice(selected, choices.get(i)) ? "\n> " : "\n  ")
            .append(choices.get(i).label());
    text.append("\n")
        .append(own.getStringOr("flightStatus", "Select a berth, then Dock automatically."));
    return text.substring(0, Math.min(2000, text.length()));
  }

  private static boolean sameChoice(Choice a, Choice b) {
    return a != null && b != null && a.vessel().equals(b.vessel()) && a.port().equals(b.port());
  }

  private static Choice selectedDestination(CompoundTag own) {
    var destination = ShipDestination.selected(own);
    return destination != null && destination.kind() == ShipDestination.Kind.PORT
        ? new Choice(
            destination.reference(), destination.port(), destination.name(), destination.state())
        : null;
  }

  private static void selectDestination(ServerPlayer p, CompoundTag own, Choice choice) {
    var target = find(p.level().getServer(), choice.vessel());
    if (target != null)
      ShipDestination.select(
          p,
          own,
          new ShipDestination(
              ShipDestination.Kind.PORT,
              choice.vessel(),
              choice.port(),
              LocalSpaces.address(target),
              choice.label(),
              choice.state()));
  }

  private static Vec3 localFace(CompoundTag s, BlockPos controller, Direction direction) {
    long position = controller.subtract(ShipStore.pos(s, "base")).asLong();
    boolean compact =
        s.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
            .map(t -> (CompoundTag) t)
            .anyMatch(
                d ->
                    d.getLongOr("position", Long.MIN_VALUE) == position
                        && d.getStringOr("type", "").equals("nullspace:docking_base"));
    return Vec3.atCenterOf(controller.subtract(ShipStore.pos(s, "base")))
        .add(0, compact ? 1 : -2, 0)
        .add(DockingMotion.normal(direction).scale(.5));
  }

  private static CompoundTag link(MinecraftServer server, CompoundTag s, BlockPos at) {
    if (s.contains("dock")) {
      var d = s.getCompoundOrEmpty("dock");
      var owner = find(server, d.getStringOr("source", ""));
      return owner == null ? null : owner.getCompoundOrEmpty("dock");
    }
    for (var key : s.getCompoundOrEmpty("dockLinks").keySet()) {
      var marker = s.getCompoundOrEmpty("dockLinks").getCompoundOrEmpty(key);
      var source = find(server, marker.getStringOr("source", ""));
      if (source != null) {
        var d = source.getCompoundOrEmpty("dock");
        if (ShipStore.pos(d, "targetPort").offset(ShipStore.pos(s, "base")).equals(at)) return d;
      }
    }
    return null;
  }

  public static CompoundTag panel(MinecraftServer server, CompoundTag s, BlockPos at) {
    var result = new CompoundTag();
    var d = link(server, s, at);
    String state = d == null ? "UNCONNECTED" : d.getStringOr("state", "");
    result.putString("dockState", state);
    boolean source = d != null && d.getStringOr("source", "").equals(id(s));
    result.putBoolean("incoming", d != null && !source && state.equals("WAITING_CONSENT"));
    result.putBoolean("maneuver", d != null && source && !state.equals("CLAMPED"));
    result.putBoolean("clamped", state.equals("CLAMPED"));
    var partner = d == null ? null : find(server, d.getStringOr(source ? "target" : "source", ""));
    result.putString("partner", partner == null ? "None" : partner.getStringOr("home", "Vessel"));
    return result;
  }

  public static String status(ServerPlayer p, CompoundTag s, BlockPos at) {
    var port = readPort(p.level(), s, at, false);
    var d = link(p.level().getServer(), s, at);
    return "Door + docking base ready\n"
        + (d == null ? "No connection" : d.getStringOr("state", ""))
        + "\nPower controls/doors/air: "
        + ShipSystems.power(p.level(), s, PowerBus.System.CONTROLS)
        + "/"
        + ShipSystems.power(p.level(), s, PowerBus.System.DOORS)
        + "/"
        + ShipSystems.power(p.level(), s, PowerBus.System.AIR);
  }

  private static void reserveTarget(
      MinecraftServer server, CompoundTag source, CompoundTag target, Port port, CompoundTag d) {
    var links = target.getCompoundOrEmpty("dockLinks");
    if (links.contains(port.id()))
      throw new IllegalArgumentException("Berth was reserved by another ship; refresh contacts");
    var marker = new CompoundTag();
    marker.putString("source", id(source));
    links.put(port.id(), marker);
    target.put("dockLinks", links);
    if (mobile(target)) {
      var mirror = new CompoundTag();
      mirror.putString("source", id(source));
      mirror.putString("state", "REQUESTED");
      target.put("dock", mirror);
      NavigationService.closeShip(server, id(target));
    }
  }

  public static Readiness request(
      ServerPlayer p, CompoundTag own, String targetId, BlockPos targetPort) {
    var readiness = readiness(p, own);
    if (!readiness.ready()) return readiness;
    var server = p.level().getServer();
    var target = find(server, targetId);
    if (target == null
        || target == own
        || !LocalSpaces.together(own, target)
        || !StationService.allowed(p, target)
        || !StationService.ready(target)
        || TravelService.inTransit(server, target)
        || target.contains("dock"))
      throw new IllegalArgumentException("Target berth is unavailable");
    if (new Vec3(
                target.getDoubleOr("simVX", 0),
                target.getDoubleOr("simVY", 0),
                target.getDoubleOr("simVZ", 0))
            .length()
        > .01) throw new IllegalArgumentException("Target ship must stop before accepting docking");
    chunks(p.level(), target);
    var sourcePort = firstPort(p.level(), own);
    var destination =
        readPort(p.level(), target, ShipStore.pos(target, "base").offset(targetPort), true);
    for (var endpoint : List.of(Map.entry(own, sourcePort), Map.entry(target, destination))) {
      var blocked = doorwayObstruction(p.level(), endpoint.getKey(), endpoint.getValue());
      if (blocked.isPresent())
        return new Readiness(
            false,
            "Dock doorway is blocked at "
                + blocked.get().toShortString()
                + ". Keep a two-block-high passage clear in front of the door.");
    }
    double gap =
        protrusion(p.level(), own, sourcePort) + protrusion(p.level(), target, destination) + .05;
    if (gap > 4.05)
      return new Readiness(
          false,
          "The docking door is recessed more than four blocks behind the hull. Move the Docking"
              + " Base outward or clear blocks in front of it.");
    var d = new CompoundTag();
    d.putDouble("gap", gap);
    d.putString("source", id(own));
    d.putString("target", id(target));
    d.putLong("sourcePort", sourcePort.controller().subtract(ShipStore.pos(own, "base")).asLong());
    d.putLong("targetPort", targetPort.asLong());
    d.putString("sourceNode", sourcePort.id());
    d.putString("targetNode", destination.id());
    d.putString("actor", p.getUUID().toString());
    d.putString("state", StationService.station(target) ? "APPROACHING" : "WAITING_CONSENT");
    d.putLong("expires", server.overworld().getGameTime() + 12000);
    reserveTarget(server, own, target, destination, d);
    own.put("dock", d);
    status(server, own, d.getStringOr("state", ""), false);
    NavigationService.closeShip(server, id(own));
    NavigationService.stop(own);
    ShipStore.get(server).save();
    return new Readiness(true, "Docking requested");
  }

  public static void connectConstruction(MinecraftServer server, CompoundTag station) {
    var source = find(server, station.getStringOr("constructionShip", ""));
    if (source == null || source.contains("dock")) return;
    var level = server.getLevel(TravelService.SPACE);
    var d = new CompoundTag();
    d.putString("source", id(source));
    d.putString("target", id(station));
    d.putLong("sourcePort", station.getLongOr("constructionSourcePort", 0));
    d.putLong("targetPort", new BlockPos(0, 5, 8).asLong());
    d.putString("targetNode", "construction");
    d.putBoolean("construction", true);
    var port =
        readPort(
            level,
            source,
            ShipStore.pos(source, "base").offset(ShipStore.pos(d, "sourcePort")),
            true);
    d.putString("sourceNode", port.id());
    d.putString("actor", station.getStringOr("owner", ""));
    d.putString("state", "APPROACHING");
    d.putLong("expires", server.overworld().getGameTime() + 12000);
    source.put("dock", d);
    var links = new CompoundTag();
    var marker = new CompoundTag();
    marker.putString("source", id(source));
    links.put("construction", marker);
    station.put("dockLinks", links);
    NavigationService.closeShip(server, id(source));
    NavigationService.stop(source);
    ShipStore.get(server).save();
  }

  private static List<DockingMotion.Obstacle> obstacles(
      MinecraftServer server, CompoundTag own, boolean targetToo) {
    var out = new ArrayList<DockingMotion.Obstacle>();
    String target = own.getCompoundOrEmpty("dock").getStringOr("target", "");
    for (var tag : ShipStore.get(server).ships()) {
      var s = (CompoundTag) tag;
      if (s == own
          || !s.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString())
          || !LocalSpaces.together(own, s)
          || !targetToo && id(s).equals(target)) continue;
      out.add(
          new DockingMotion.Obstacle(
              SceneService.pose(s).toUniverse(center(s)), StationSpace.radius(s)));
    }
    var definition = SpaceDefinitionCodec.decode(SceneCatalog.forShip(server, own));
    double time = server.overworld().getGameTime() / 20. - definition.epoch();
    for (var body : definition.bodies())
      out.add(new DockingMotion.Obstacle(body.at(time), body.radius() * (1 + body.roughness())));
    return out;
  }

  private static Vec3 center(CompoundTag s) {
    return new Vec3(
        s.getIntOr("width", 32) / 2., s.getIntOr("height", 32) / 2., s.getIntOr("depth", 32) / 2.);
  }

  public static Vec3 constructionSite(MinecraftServer server, CompoundTag own) {
    var center = SceneService.pose(own).toUniverse(new Vec3(16, 16, 16));
    var obstacles = obstacles(server, own, true);
    obstacles.add(new DockingMotion.Obstacle(center, 28));
    for (int distance : new int[] {224, 320, 448})
      for (var direction :
          List.of(
              Direction.NORTH,
              Direction.EAST,
              Direction.SOUTH,
              Direction.WEST,
              Direction.UP,
              Direction.DOWN)) {
        var origin =
            SceneService.pose(own).position().add(DockingMotion.normal(direction).scale(distance));
        var candidate = origin.add(16, 16, 16);
        if (DockingMotion.clear(candidate, candidate, obstacles, 36)) return origin;
      }
    throw new IllegalArgumentException(
        "No clear station construction site nearby; move into open space");
  }

  /** Conservative voxel occupancy during close approach. Rotating blocks check their whole AABB. */
  private static double protrusion(ServerLevel level, CompoundTag vessel, Port port) {
    var base = ShipStore.pos(vessel, "base");
    var normal = DockingMotion.normal(port.facing());
    var plane = localFace(vessel, port.controller(), port.facing());
    double reach = 0;
    for (var block : ShipSystems.connected(level, vessel)) {
      double front =
          normal.dot(Vec3.atLowerCornerOf(block.subtract(base)))
              + (port.facing().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
      reach = Math.max(reach, front - normal.dot(plane));
    }
    return reach;
  }

  public record BuildProblem(BlockPos position, String message) {}

  /**
   * Reject a module before payment or placement if it would build through a visiting ship's exit.
   */
  public static Optional<BuildProblem> constructionProblem(
      ServerLevel level,
      CompoundTag station,
      Map<BlockPos, net.minecraft.world.level.block.state.BlockState> plan) {
    var reserved = new HashMap<BlockPos, String>();
    var stationPose = SceneService.pose(station);
    for (var tag : ShipStore.get(level.getServer()).ships()) {
      var visitor = (CompoundTag) tag;
      var dock = visitor.getCompoundOrEmpty("dock");
      if (!dock.getStringOr("source", "").equals(id(visitor))
          || !dock.getStringOr("target", "").equals(id(station))
          || !dock.getStringOr("state", "").equals("CLAMPED")
          || dock.getBooleanOr("construction", false)) continue;
      var from = SceneService.pose(visitor);
      var to =
          savedEscape(dock)
              ? new ScenePose(
                  new Vec3(
                      dock.getDoubleOr("escapeX", 0),
                      dock.getDoubleOr("escapeY", 0),
                      dock.getDoubleOr("escapeZ", 0)),
                  from.orientation())
              : from;
      for (var block : ShipSystems.connected(level, visitor)) {
        if (level.getBlockState(block).isAir()) continue;
        var local = block.subtract(ShipStore.pos(visitor, "base"));
        var a = hullBox(local, from, stationPose);
        var b = hullBox(local, to, stationPose);
        int minX = Math.max(0, (int) Math.floor(Math.min(a.minX, b.minX) + 1e-6));
        int minY = Math.max(0, (int) Math.floor(Math.min(a.minY, b.minY) + 1e-6));
        int minZ = Math.max(0, (int) Math.floor(Math.min(a.minZ, b.minZ) + 1e-6));
        int maxX =
            Math.min(
                station.getIntOr("width", 32) - 1,
                (int) Math.floor(Math.max(a.maxX, b.maxX) - 1e-6));
        int maxY =
            Math.min(
                station.getIntOr("height", 32) - 1,
                (int) Math.floor(Math.max(a.maxY, b.maxY) - 1e-6));
        int maxZ =
            Math.min(
                station.getIntOr("depth", 32) - 1,
                (int) Math.floor(Math.max(a.maxZ, b.maxZ) - 1e-6));
        if (minX > maxX || minY > maxY || minZ > maxZ) continue;
        for (var cell : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ))
          reserved.putIfAbsent(cell.immutable(), visitor.getStringOr("home", "visiting ship"));
      }
    }
    var base = ShipStore.pos(station, "base");
    for (var entry : plan.entrySet()) {
      var at = base.offset(entry.getKey());
      if (!entry.getValue().isAir()
          && !level.getBlockState(at).equals(entry.getValue())
          && reserved.containsKey(entry.getKey()))
        return Optional.of(
            new BuildProblem(
                at,
                "This room would block "
                    + reserved.get(entry.getKey())
                    + "'s departure corridor. Undock that ship before building here."));
    }
    return Optional.empty();
  }

  public static Optional<BlockPos> doorwayObstruction(
      ServerLevel level, CompoundTag vessel, Port port) {
    var collars = DockingTunnel.positions(level);
    var bounds = ShipStore.bounds(vessel);
    for (int depth = 1; depth <= 4; depth++)
      for (int y = 0; y <= 1; y++) {
        var at = port.outer().relative(port.facing(), depth).above(y);
        if (bounds.contains(at) && !level.getBlockState(at).isAir() && !collars.contains(at))
          return Optional.of(at);
      }
    return Optional.empty();
  }

  static ScenePose matingPose(
      CompoundTag own, Port source, CompoundTag target, Port destination, double gap) {
    return DockingMotion.mate(
        localFace(own, source.controller(), source.facing()),
        source.facing(),
        SceneService.pose(target),
        localFace(target, destination.controller(), destination.facing()),
        destination.facing(),
        gap);
  }

  private static boolean intersects(
      ServerLevel level, CompoundTag own, ScenePose pose, CompoundTag target) {
    return intersects(level, own, pose, target, null);
  }

  private static AABB hullBox(BlockPos local, ScenePose pose, ScenePose targetPose) {
    double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    for (int x = 0; x <= 1; x++)
      for (int y = 0; y <= 1; y++)
        for (int z = 0; z <= 1; z++) {
          var v =
              targetPose.toLocal(
                  pose.toUniverse(new Vec3(local.getX() + x, local.getY() + y, local.getZ() + z)));
          minX = Math.min(minX, v.x);
          minY = Math.min(minY, v.y);
          minZ = Math.min(minZ, v.z);
          maxX = Math.max(maxX, v.x);
          maxY = Math.max(maxY, v.y);
          maxZ = Math.max(maxZ, v.z);
        }
    return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private static double overlap(AABB box, int x, int y, int z) {
    return Math.max(0, Math.min(box.maxX, x + 1) - Math.max(box.minX, x))
        * Math.max(0, Math.min(box.maxY, y + 1) - Math.max(box.minY, y))
        * Math.max(0, Math.min(box.maxZ, z + 1) - Math.max(box.minZ, z));
  }

  /** During recovery, existing overlaps may shrink; no voxel may enter a new obstruction. */
  private static boolean intersects(
      ServerLevel level,
      CompoundTag own,
      ScenePose pose,
      CompoundTag target,
      ScenePose retreatFrom) {
    var targetPose = SceneService.pose(target);
    var targetBase = ShipStore.pos(target, "base");
    for (var block : ShipSystems.connected(level, own)) {
      if (level.getBlockState(block).isAir()) continue;
      var local = block.subtract(ShipStore.pos(own, "base"));
      var box = hullBox(local, pose, targetPose);
      double minX = box.minX, minY = box.minY, minZ = box.minZ;
      double maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ;
      AABB previous = null;
      if (maxX <= 0
          || maxY <= 0
          || maxZ <= 0
          || minX >= target.getIntOr("width", 32)
          || minY >= target.getIntOr("height", 32)
          || minZ >= target.getIntOr("depth", 32)) continue;
      for (int x = Math.max(0, (int) Math.floor(minX + 1e-6));
          x <= Math.min(target.getIntOr("width", 32) - 1, (int) Math.floor(maxX - 1e-6));
          x++)
        for (int y = Math.max(0, (int) Math.floor(minY + 1e-6));
            y <= Math.min(target.getIntOr("height", 32) - 1, (int) Math.floor(maxY - 1e-6));
            y++)
          for (int z = Math.max(0, (int) Math.floor(minZ + 1e-6));
              z <= Math.min(target.getIntOr("depth", 32) - 1, (int) Math.floor(maxZ - 1e-6));
              z++)
            if (!level.getBlockState(targetBase.offset(x, y, z)).isAir()) {
              if (retreatFrom == null) return true;
              if (previous == null) previous = hullBox(local, retreatFrom, targetPose);
              if (overlap(box, x, y, z) > overlap(previous, x, y, z) + 1e-7) return true;
            }
    }
    return false;
  }

  private static void pose(CompoundTag s, ScenePose p) {
    var angles = p.orientation().getEulerAnglesYXZ(new Vector3d());
    s.putDouble("simX", p.position().x);
    s.putDouble("simY", p.position().y);
    s.putDouble("simZ", p.position().z);
    s.putDouble("simYaw", Math.toDegrees(angles.y));
    s.putDouble("simPitch", Math.toDegrees(angles.x));
    s.putDouble("simRoll", Math.toDegrees(angles.z));
    NavigationService.stop(s);
  }

  private static boolean nearHull(ServerLevel level, CompoundTag own, ScenePose next) {
    return nearHull(level, own, next, null);
  }

  private static boolean nearHull(
      ServerLevel level, CompoundTag own, ScenePose next, ScenePose retreatFrom) {
    var center = next.toUniverse(center(own));
    for (var tag : ShipStore.get(level.getServer()).ships()) {
      var other = (CompoundTag) tag;
      if (other == own
          || !other.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
          || !LocalSpaces.together(own, other)) continue;
      if (center.distanceToSqr(SceneService.pose(other).toUniverse(center(other)))
              < Math.pow(StationSpace.radius(own) + StationSpace.radius(other) + 8, 2)
          && intersects(level, own, next, other, retreatFrom)) return true;
    }
    return false;
  }

  private static void close(ServerLevel level, Port port) {
    for (var door : List.of(port.outer(), port.inner())) {
      closeDoor(level, door);
    }
  }

  private static void closeDoor(ServerLevel level, BlockPos door) {
    if (ShipDoors.powered(level, door))
      throw new IllegalArgumentException("Airlock has a redstone override");
    if (!level.getEntities(null, new AABB(door).expandTowards(0, 1, 0)).isEmpty())
      throw new IllegalArgumentException("Move clear of the airlock doorway");
    ShipDoors.setOpen(level, door, false);
    if (!ShipDoors.sealed(level, door))
      throw new IllegalArgumentException("Airlock door did not seal");
  }

  private static boolean closeSurviving(
      ServerLevel level, CompoundTag vessel, BlockPos controller) {
    Port port;
    try {
      port = readPort(level, vessel, controller, false);
    } catch (IllegalArgumentException damaged) {
      // Old links may predate compact-port metadata. Only close real surviving doors; never
      // fabricate a seal, refill air, or require the missing controller to release the clamp.
      for (var door : List.of(controller.above(), controller.below(2))) {
        var state = level.getBlockState(door);
        if (state.getBlock() instanceof DoorBlock
            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) closeDoor(level, door);
      }
      return false;
    }
    close(level, port);
    return true;
  }

  private static void saveEscape(CompoundTag dock, Vec3 position) {
    dock.putDouble("escapeX", position.x);
    dock.putDouble("escapeY", position.y);
    dock.putDouble("escapeZ", position.z);
  }

  private static boolean savedEscape(CompoundTag dock) {
    return dock.contains("escapeX")
        && dock.contains("escapeY")
        && dock.contains("escapeZ")
        && Double.isFinite(dock.getDoubleOr("escapeX", Double.NaN))
        && Double.isFinite(dock.getDoubleOr("escapeY", Double.NaN))
        && Double.isFinite(dock.getDoubleOr("escapeZ", Double.NaN));
  }

  private static void recoverEscape(
      ServerLevel level, CompoundTag source, CompoundTag target, CompoundTag dock) {
    if (savedEscape(dock)) return;
    Vec3 normal = null;
    try {
      var port =
          readPort(
              level,
              source,
              ShipStore.pos(source, "base").offset(ShipStore.pos(dock, "sourcePort")),
              false);
      normal =
          FlightDynamics.rotate(
                  SceneService.pose(source).orientation(), DockingMotion.normal(port.facing()))
              .scale(-1);
    } catch (IllegalArgumentException damagedSource) {
      if (target != null) {
        try {
          var port =
              readPort(
                  level,
                  target,
                  ShipStore.pos(target, "base").offset(ShipStore.pos(dock, "targetPort")),
                  false);
          normal =
              FlightDynamics.rotate(
                  SceneService.pose(target).orientation(), DockingMotion.normal(port.facing()));
        } catch (IllegalArgumentException damagedTarget) {
        }
      }
    }
    if (normal == null)
      throw new IllegalArgumentException(
          "This older connection has no saved departure route. Repair either docking doorway in its"
              + " original facing, then press Undock again.");
    saveEscape(dock, SceneService.pose(source).position().add(normal.scale(112)));
  }

  private static void depart(
      MinecraftServer server, ServerLevel level, CompoundTag own, CompoundTag d) {
    if (!savedEscape(d)) {
      status(
          server,
          own,
          "No saved departure route; repair the docking doorway before trying again",
          true);
      return;
    }
    if (NavigationService.propulsion(level, own) == 0) {
      own.putInt("thrustDemand", 0);
      status(
          server, own, "Undocking paused: restore your ship's controls and propulsion power", true);
      return;
    }
    var old = SceneService.pose(own);
    var escape =
        new Vec3(
            d.getDoubleOr("escapeX", 0), d.getDoubleOr("escapeY", 0), d.getDoubleOr("escapeZ", 0));
    var next = DockingMotion.step(old, new ScenePose(escape, old.orientation()), .35, 0);
    // Use the path saved at capture, rather than recomputing it from a changed or demolished
    // station port. Legacy construction overlaps can shrink, but new obstructions still stop us.
    DockingTunnel.retract(server, d);
    if (nearHull(level, own, next, old)
        || !DockingMotion.clear(
            old.toUniverse(center(own)),
            next.toUniverse(center(own)),
            obstacles(server, own, false),
            29)) {
      own.putInt("thrustDemand", 0);
      status(
          server,
          own,
          "Undocking paused: clear the departure corridor; it will resume automatically",
          true);
      return;
    }
    own.putInt("thrustDemand", NavigationService.propulsion(level, own));
    if (!FuelSystem.spend(level, own, FuelSystem.travelCost(level, own, 2))) {
      own.putInt("thrustDemand", 0);
      status(server, own, "Undocking paused: load furnace fuel into your ship's generator", true);
      return;
    }
    pose(own, next);
    if (next.position().distanceTo(escape) < .001) release(server, own, "Undocked safely");
    else status(server, own, "UNDOCKING", false);
  }

  /** Report transitions once, including failures after the controller has been closed. */
  private static void status(
      MinecraftServer server, CompoundTag own, String message, boolean blocked) {
    if (message.equals(own.getStringOr("flightStatus", ""))) return;
    var dock = own.getCompoundOrEmpty("dock");
    var target = find(server, dock.getStringOr("target", ""));
    var details =
        new Object[] {
          blocked ? "stopped" : "status",
          message,
          own.getStringOr("home", "Ship"),
          id(own),
          target == null
              ? dock.getStringOr("target", "unavailable")
              : target.getStringOr("home", "Ship"),
          ShipStore.pos(dock, "targetPort").toShortString(),
          dock.getStringOr("state", ""),
          dock.getStringOr("actor", "")
        };
    String format = "Docking {}: {} | ship={} ({}) target={} port={} stage={} pilot={}";
    if (blocked) com.nullandkale.nullspace.NullSpace.LOGGER.warn(format, details);
    else com.nullandkale.nullspace.NullSpace.LOGGER.info(format, details);
    String text =
        switch (message) {
          case "APPROACHING" -> "Docking: approaching the selected port.";
          case "ALIGNING" -> "Docking: aligning with the selected port.";
          case "CONNECTING" -> "Docking: making the final approach.";
          case "CLAMPED" -> "Docked. Open the dock door and walk through.";
          case "UNDOCKING" -> "Undocking: backing away from the port.";
          case "WAITING_CONSENT" -> "Docking: waiting for the other ship to accept.";
          default -> "Docking " + (blocked ? "stopped: " : "") + message;
        };
    for (var player : server.getPlayerList().getPlayers())
      if (player.getUUID().toString().equals(dock.getStringOr("actor", "")))
        player.sendSystemMessage(Component.literal(text));
    own.putString("flightStatus", message);
  }

  private static void release(MinecraftServer server, CompoundTag own, String message) {
    release(server, own, message, false);
  }

  private static void release(
      MinecraftServer server, CompoundTag own, String message, boolean blocked) {
    status(server, own, message, blocked);
    var d = own.getCompoundOrEmpty("dock");
    if (d.contains("tunnel")) {
      DockingTunnel.retract(server, d);
      // Flush removed collar blocks while their recovery plan is still in the saved link.
      server.saveEverything(false, true, true);
    }
    var target = find(server, d.getStringOr("target", ""));
    if (target != null) {
      target.getCompoundOrEmpty("dockLinks").remove(d.getStringOr("targetNode", ""));
      if (mobile(target)) target.remove("dock");
    }
    own.remove("dock");
    NavigationService.stop(own);
    ServerContext.get(server).service(ROUTES, HashMap::new).remove(id(own));
    ShipStore.get(server).save();
  }

  static void tickMotion(MinecraftServer server, CompoundTag own) {
    var d = own.getCompoundOrEmpty("dock");
    if (!d.getStringOr("source", "").equals(id(own))) return;
    var target = find(server, d.getStringOr("target", ""));
    String phase = d.getStringOr("state", "");
    if (target == null) {
      release(server, own, "Target unavailable; maneuver cancelled", true);
      return;
    }
    if (!StationService.ready(target)) {
      status(server, own, "Waiting for destination station construction to finish", false);
      NavigationService.stop(own);
      return;
    }
    var level = ShipStore.level(server, own.getStringOr("dimension", ""));
    chunks(level, own);
    chunks(level, target);
    if (phase.equals("EMERGENCY_BACKOFF") || phase.equals("UNDOCKING")) {
      depart(server, level, own, d);
      return;
    }
    try {
      var source =
          readPort(
              level,
              own,
              ShipStore.pos(own, "base").offset(ShipStore.pos(d, "sourcePort")),
              !phase.equals("CLAMPED") && !phase.equals("UNDOCKING"));
      if (!source.id().equals(d.getStringOr("sourceNode", "")))
        throw new IllegalArgumentException("Docking controller was replaced");
      Port destination = null;
      Direction face = Direction.WEST;
      if (!d.getBooleanOr("construction", false)) {
        destination =
            readPort(
                level,
                target,
                ShipStore.pos(target, "base").offset(ShipStore.pos(d, "targetPort")),
                !phase.equals("CLAMPED") && !phase.equals("UNDOCKING"));
        face = destination.facing();
        if (!destination.id().equals(d.getStringOr("targetNode", "")))
          throw new IllegalArgumentException("Destination port was replaced");
      }
      if (phase.equals("CLAMPED")) {
        if (d.getBooleanOr("construction", false)) {
          commissionConstruction(server, target);
          if (!d.getBooleanOr("construction", false)) return;
        }
        DockingTunnel.ensure(server, d, source, destination);
        own.putInt("thrustDemand", 0);
        return;
      }
      if (phase.equals("UNDOCKING")) DockingTunnel.retract(server, d);
      own.putInt("thrustDemand", NavigationService.propulsion(level, own));
      if (!FuelSystem.spend(level, own, FuelSystem.travelCost(level, own, 2)))
        throw new IllegalArgumentException(
            "Shared fuel exhausted during automatic docking; load the generator");
      if (server.overworld().getGameTime() > d.getLongOr("expires", 0))
        throw new IllegalArgumentException("Docking request timed out");
      var actor = server.getPlayerList().getPlayer(UUID.fromString(d.getStringOr("actor", "")));
      if (actor == null) throw new IllegalArgumentException("Pilot disconnected before capture");
      if (!StationService.allowed(actor, target))
        throw new IllegalArgumentException("Station permission changed");
      if (phase.equals("WAITING_CONSENT")) return;
      if (NavigationService.propulsion(level, own) == 0)
        throw new IllegalArgumentException("Docking stopped: propulsion has no power");
      var sourceFace = localFace(own, source.controller(), source.facing());
      var targetFace =
          localFace(
              target, ShipStore.pos(target, "base").offset(ShipStore.pos(d, "targetPort")), face);
      var goal =
          DockingMotion.mate(
              sourceFace,
              source.facing(),
              SceneService.pose(target),
              targetFace,
              face,
              d.getDoubleOr("gap", .05));
      var normal =
          FlightDynamics.rotate(
              SceneService.pose(target).orientation(), DockingMotion.normal(face));
      var standOff = new ScenePose(goal.position().add(normal.scale(112)), goal.orientation());
      var old = SceneService.pose(own);
      ScenePose next;
      if (phase.equals("APPROACHING")) {
        var routes = ServerContext.get(server).service(ROUTES, HashMap::new);
        var path = routes.get(id(own));
        if (path == null) {
          path =
              new ArrayList<>(
                  DockingMotion.route(
                      old.toUniverse(new Vec3(16, 16, 16)),
                      standOff.toUniverse(new Vec3(16, 16, 16)),
                      obstacles(server, own, true)));
          routes.put(id(own), path);
        }
        if (path.isEmpty()) {
          d.putString("state", "ALIGNING");
          status(server, own, "ALIGNING", false);
          return;
        }
        var center = old.toUniverse(new Vec3(16, 16, 16));
        var delta = path.getFirst().subtract(center);
        var shift = delta.length() <= .8 ? delta : delta.normalize().scale(.8);
        if (!DockingMotion.clear(center, center.add(shift), obstacles(server, own, true), 29))
          throw new IllegalArgumentException("Automatic approach became obstructed");
        next = new ScenePose(old.position().add(shift), old.orientation());
        if (delta.length() <= .8) path.removeFirst();
      } else if (phase.equals("ALIGNING")) {
        // Rotate around the center at a safe stand-off, then translate along the port normal.
        var center = old.toUniverse(new Vec3(16, 16, 16));
        var q =
            DockingMotion.step(
                    old, new ScenePose(old.position(), goal.orientation()), 0, Math.toRadians(2))
                .orientation();
        next = new ScenePose(center.subtract(FlightDynamics.rotate(q, new Vec3(16, 16, 16))), q);
        if (Math.abs(q.dot(goal.orientation())) > .999999) {
          pose(own, standOff);
          d.putString("state", "CONNECTING");
          status(server, own, "CONNECTING", false);
          return;
        }
      } else if (phase.equals("CONNECTING")) {
        next = DockingMotion.step(old, goal, .35, Math.toRadians(1));
        if (nearHull(level, own, next))
          throw new IllegalArgumentException("A nearby hull obstructs final approach");
        if (intersects(level, own, next, target))
          throw new IllegalArgumentException("Hull or connector blocks obstruct final approach");
        if (next.position().distanceTo(goal.position()) < .001) {
          close(level, source);
          if (destination != null) close(level, destination);
          pose(own, next);
          d.putString("state", "CLAMPED");
          saveEscape(d, standOff.position());
          ShipStore.get(server).save();
        }
      } else throw new IllegalArgumentException("Unknown docking stage");
      if (!DockingMotion.clear(
          old.toUniverse(new Vec3(16, 16, 16)),
          next.toUniverse(new Vec3(16, 16, 16)),
          obstacles(server, own, false),
          29)) throw new IllegalArgumentException("Docking route intersects another object");
      if ((phase.equals("APPROACHING") || phase.equals("ALIGNING")) && nearHull(level, own, next))
        throw new IllegalArgumentException("A nearby hull obstructs the automatic maneuver");
      pose(own, next);
      status(server, own, d.getStringOr("state", phase), false);
    } catch (IllegalArgumentException unavailable) {
      if (phase.equals("CLAMPED") || phase.equals("UNDOCKING")) {
        d.putString("state", "CLAMPED");
        status(
            server,
            own,
            unavailable.getMessage()
                + ". Use Undock at your ship's controller to leave the damaged berth.",
            true);
        NavigationService.stop(own);
      } else release(server, own, unavailable.getMessage(), true);
    }
  }

  public static boolean ownsDoor(ServerLevel level, BlockPos at) {
    for (var tag : ShipStore.get(level.getServer()).ships()) {
      var s = (CompoundTag) tag;
      if (!s.contains("dock")) continue;
      var d = s.getCompoundOrEmpty("dock");
      if (!id(s).equals(d.getStringOr("source", ""))) continue;
      var target = find(level.getServer(), d.getStringOr("target", ""));
      for (var vessel : new CompoundTag[] {s, target})
        if (vessel != null) {
          var controller =
              ShipStore.pos(vessel, "base")
                  .offset(ShipStore.pos(d, vessel == s ? "sourcePort" : "targetPort"));
          try {
            var port = readPort(level, vessel, controller, false);
            if (at.equals(port.outer()) || at.equals(port.inner())) return true;
          } catch (IllegalArgumentException ignored) {
          }
        }
    }
    return false;
  }

  public static boolean busy(ServerPlayer p) {
    return ShipStore.get(p.level().getServer()).list("stationBoarding").stream()
        .map(t -> (CompoundTag) t)
        .anyMatch(t -> t.getStringOr("player", "").equals(p.getUUID().toString()));
  }

  public static boolean boarding(MinecraftServer server, String ship) {
    return ShipStore.get(server).list("stationBoarding").stream()
        .map(t -> (CompoundTag) t)
        .anyMatch(
            t ->
                t.getStringOr("source", "").equals(ship)
                    || t.getStringOr("target", "").equals(ship));
  }

  public static boolean ready(ServerPlayer p, TravelPackets.Ready packet) {
    for (var tag : ShipStore.get(p.level().getServer()).list("stationBoarding")) {
      var t = (CompoundTag) tag;
      if (t.getStringOr("player", "").equals(p.getUUID().toString())
          && t.getStringOr("token", "").equals(packet.id().toString())) {
        if (packet.arrived() != t.getBooleanOr("committed", false)) return true;
        ServerContext.get(p.level().getServer())
            .service(packet.arrived() ? ARRIVED : READY, HashSet::new)
            .add(packet.id());
        return true;
      }
    }
    return false;
  }

  private static void progress(ServerPlayer p, CompoundTag t, String text, boolean done) {
    if (ServerPlayNetworking.canSend(p, TravelPackets.Progress.TYPE))
      ServerPlayNetworking.send(
          p,
          new TravelPackets.Progress(
              UUID.fromString(t.getStringOr("token", "")),
              text,
              t.getBooleanOr("committed", false)
                  ? p.level().dimension().identifier().toString()
                  : "",
              done,
              TravelPackets.Kind.BOARD));
  }

  private static BlockPos anchor(ServerLevel level, Port port) {
    return port.anchors().stream()
        .filter(p -> level.getEntities(null, new AABB(p).expandTowards(0, 1, 0)).isEmpty())
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Destination chamber is occupied; wait for the previous passenger"));
  }

  private static void beginBoard(
      ServerPlayer p,
      CompoundTag source,
      CompoundTag target,
      Port port,
      Port other,
      boolean construction) {
    if (busy(p)
        || TravelService.inTransit(p.level().getServer(), source)
        || TravelService.inTransit(p.level().getServer(), target)
        || !StationService.ready(source)
        || !StationService.ready(target))
      throw new IllegalArgumentException(
          "Another transfer is in progress or this vessel is under construction");
    if (!StationService.allowed(p, target))
      throw new IllegalArgumentException("Destination access is private");
    if (port != null
        && !port.cells().contains(BlockPos.containing(p.position()))
        && !compactEntrance(p, source, port))
      throw new IllegalArgumentException("Enter the airlock chamber before boarding");
    BlockPos to;
    if (construction) to = ShipStore.pos(target, "base").offset(8, 3, 8);
    else if (other != null) to = anchor(p.level(), other);
    else {
      to = null;
      for (var cell :
          BlockPos.betweenClosed(ShipStore.bounds(target).min(), ShipStore.bounds(target).max())) {
        if (StationGraph.standing(p.level(), cell)
            && ShipSystems.breathable(p.level(), target, cell.above())
            && p.level().getEntities(null, new AABB(cell).expandTowards(0, 1, 0)).isEmpty()) {
          to = cell.immutable();
          break;
        }
      }
      if (to == null)
        throw new IllegalArgumentException(
            "Home ship has no safe cabin arrival position; repair its airlock first");
    }
    if (construction
        && !exempt(p)
        && (!SpaceSurvival.suited(p) || !ShipSystems.tether(p.level(), source)))
      throw new IllegalArgumentException(
          "Construction requires a complete suit and oxygen from the connected ship");
    if (port != null && !port.inner().equals(port.outer())) close(p.level(), port);
    if (other != null && !other.inner().equals(other.outer())) close(p.level(), other);
    var t = new CompoundTag();
    t.putString("token", UUID.randomUUID().toString());
    t.putString("player", p.getUUID().toString());
    t.putString("source", id(source));
    t.putString("target", id(target));
    t.putLong("at", to.asLong());
    t.putBoolean("construction", construction);
    t.putBoolean("committed", false);
    t.putLong(
        "sourcePort",
        port == null ? 0 : port.controller().subtract(ShipStore.pos(source, "base")).asLong());
    t.putLong(
        "targetPort",
        other == null ? 0 : other.controller().subtract(ShipStore.pos(target, "base")).asLong());
    if (port != null) t.putLong("sourceStanding", port.anchors().getFirst().asLong());
    ShipStore.get(p.level().getServer()).list("stationBoarding").add(t);
    ShipStore.get(p.level().getServer()).save();
    p.closeContainer();
    progress(p, t, "Cycling airlocks", false);
  }

  private static boolean captured(CompoundTag a, CompoundTag b) {
    for (var vessel : new CompoundTag[] {a, b}) {
      var dock = vessel.getCompoundOrEmpty("dock");
      if (!dock.getStringOr("state", "").equals("CLAMPED")
          || dock.getBooleanOr("construction", false)) continue;
      if ((dock.getStringOr("source", "").equals(id(a))
              && dock.getStringOr("target", "").equals(id(b)))
          || (dock.getStringOr("source", "").equals(id(b))
              && dock.getStringOr("target", "").equals(id(a)))) return true;
    }
    return false;
  }

  private static void crew(
      ServerPlayer p, CompoundTag source, CompoundTag target, boolean construction) {
    var store = ShipStore.get(p.level().getServer());
    String home = id(source);
    for (var tag : store.ships()) {
      var s = (CompoundTag) tag;
      for (var member : s.getListOrEmpty("crew")) {
        var m = (CompoundTag) member;
        if (m.getStringOr("id", "").equals(p.getUUID().toString()))
          home = m.getStringOr("returnShip", home);
      }
      s.getListOrEmpty("crew")
          .removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(p.getUUID().toString()));
    }
    var member = new CompoundTag();
    member.putString("id", p.getUUID().toString());
    member.putString("returnShip", home);
    member.putBoolean("construction", construction);
    var list = target.getListOrEmpty("crew");
    if (!target.contains("crew")) target.put("crew", list);
    list.add(member);
  }

  private static void tickBoarding(MinecraftServer server) {
    var store = ShipStore.get(server);
    var wait = ServerContext.get(server).service(WAIT, HashMap::new);
    var ready = ServerContext.get(server).service(READY, HashSet::new);
    var arrived = ServerContext.get(server).service(ARRIVED, HashSet::new);
    for (var tag : List.copyOf(store.list("stationBoarding"))) {
      var t = (CompoundTag) tag;
      UUID token = UUID.fromString(t.getStringOr("token", ""));
      var p = server.getPlayerList().getPlayer(UUID.fromString(t.getStringOr("player", "")));
      if (p == null) {
        if (!t.getBooleanOr("committed", false)) {
          store.list("stationBoarding").remove(t);
          store.requestSave();
          wait.remove(token);
          ready.remove(token);
          arrived.remove(token);
        }
        continue;
      }
      if (!p.isAlive() || !p.level().dimension().equals(TravelService.SPACE)) {
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
      try {
        var source = find(server, t.getStringOr("source", ""));
        var target = find(server, t.getStringOr("target", ""));
        if (source == null || target == null)
          throw new IllegalArgumentException("Boarding vessel no longer exists");
        chunks(p.level(), target);
        boolean committed = t.getBooleanOr("committed", false);
        if (!committed) {
          if (t.contains("sourceStanding")) {
            var safe = ShipStore.pos(t, "sourceStanding");
            p.teleportTo(safe.getX() + .5, safe.getY(), safe.getZ() + .5);
            p.setDeltaMovement(Vec3.ZERO);
          }
          if (ticks > 600)
            throw new IllegalArgumentException(
                "Boarding timed out; check air supply and destination occupancy");
          if (!ready.contains(token)) continue;
          if (!StationService.allowed(p, target))
            throw new IllegalArgumentException("Destination permission changed");
          boolean construction = t.getBooleanOr("construction", false);
          boolean joined = captured(source, target);
          var at = ShipStore.pos(t, "at");
          if (!StationGraph.standing(p.level(), at)
              || !p.level().getEntities(null, new AABB(at).expandTowards(0, 1, 0)).isEmpty())
            throw new IllegalArgumentException("Arrival position became obstructed");
          if (t.getLongOr("sourcePort", 0) != 0) {
            var port =
                readPort(
                    p.level(),
                    source,
                    ShipStore.pos(source, "base").offset(ShipStore.pos(t, "sourcePort")),
                    !joined);
            if (!port.inner().equals(port.outer())) close(p.level(), port);
            if (!port.cells().contains(BlockPos.containing(p.position()))
                && !compactEntrance(p, source, port))
              throw new IllegalArgumentException("Passenger left the source chamber");
          }
          if (!construction && t.getLongOr("targetPort", 0) != 0) {
            var other =
                readPort(
                    p.level(),
                    target,
                    ShipStore.pos(target, "base").offset(ShipStore.pos(t, "targetPort")),
                    !joined);
            if (!other.inner().equals(other.outer())) close(p.level(), other);
          }
          boolean breathable = ShipSystems.breathable(p.level(), target, at.above());
          if (!joined
              && !breathable
              && !exempt(p)
              && (!SpaceSurvival.suited(p) || p.getAirSupply() < 40)) continue;
          if (construction && !exempt(p) && !ShipSystems.tether(p.level(), source))
            throw new IllegalArgumentException("Construction tether has lost its oxygen source");
          crew(p, source, target, construction);
          var view =
              BoardingView.between(
                  SceneService.pose(source), SceneService.pose(target), p.getYRot(), p.getXRot());
          t.putBoolean("committed", true);
          var root = store.snapshot();
          ShipStore.target(
              root,
              p.getUUID(),
              TravelService.SPACE.identifier().toString(),
              at.getX() + .5,
              at.getY(),
              at.getZ() + .5,
              view.yaw(),
              view.pitch());
          store.document("targets", root.getListOrEmpty("targets"));
          store.save();
          p.teleportTo(
              p.level(),
              at.getX() + .5,
              at.getY(),
              at.getZ() + .5,
              Set.of(),
              view.yaw(),
              view.pitch(),
              true);
          p.setDeltaMovement(Vec3.ZERO);
          if (construction && t.getLongOr("sourcePort", 0) != 0) {
            var sourceBase = ShipStore.pos(source, "base").offset(ShipStore.pos(t, "sourcePort"));
            if (p.level().getBlockState(sourceBase).is(ShipBlocks.DOCKING_BASE))
              ShipDoors.setOpen(p.level(), sourceBase.above(), false);
          }
          server.getPlayerList().saveAll();
          progress(p, t, "Boarding", false);
          wait.put(token, 0);
        } else if (arrived.contains(token) || ticks > 200) {
          if (!arrived.contains(token)
              && ServerPlayNetworking.canSend(p, TravelPackets.Progress.TYPE))
            p.connection.disconnect(
                Component.literal("Arrival timed out. Reconnect to finish boarding safely."));
          else {
            store
                .list("targets")
                .removeIf(
                    a -> ((CompoundTag) a).getStringOr("id", "").equals(p.getUUID().toString()));
            progress(p, t, "Boarded", true);
            if (!t.getBooleanOr("construction", false) && t.getLongOr("targetPort", 0) != 0) {
              var port =
                  readPort(
                      p.level(),
                      target,
                      ShipStore.pos(target, "base").offset(ShipStore.pos(t, "targetPort")),
                      false);
              safeInner(p, target, port);
            }
          }
          store.list("stationBoarding").remove(t);
          store.save();
          wait.remove(token);
          ready.remove(token);
          arrived.remove(token);
        }
      } catch (IllegalArgumentException invalid) {
        progress(p, t, invalid.getMessage(), true);
        store.list("stationBoarding").remove(t);
        store.requestSave();
        wait.remove(token);
        ready.remove(token);
        arrived.remove(token);
      }
    }
  }

  private static void safeInner(ServerPlayer p, CompoundTag s, Port port) {
    if (port.inner().equals(port.outer())) {
      ShipDoors.setOpen(p.level(), port.outer(), true);
      return;
    }
    if (ShipDoors.open(p.level().getBlockState(port.outer())))
      throw new IllegalArgumentException("Close the exterior door before opening the inner door");
    if (ShipDoors.powered(p.level(), port.inner()))
      throw new IllegalArgumentException("Inner door is overridden by redstone");
    boolean chamber =
        port.cells().stream()
            .filter(c -> port.anchors().contains(c))
            .allMatch(c -> ShipSystems.breathable(p.level(), s, c.above()));
    var a = port.inner().relative(port.facing());
    var b = port.inner().relative(port.facing().getOpposite());
    if (!chamber
        || !ShipSystems.breathable(p.level(), s, a.above())
        || !ShipSystems.breathable(p.level(), s, b.above()))
      throw new IllegalArgumentException("Inner door remains sealed: pressure is unsafe");
    ShipDoors.setOpen(
        p.level(), port.inner(), !ShipDoors.open(p.level().getBlockState(port.inner())));
  }

  public static boolean constructionAir(ServerPlayer p, CompoundTag station) {
    if (!StationService.station(station) || !SpaceSurvival.suited(p)) return false;
    boolean visitor =
        station.getListOrEmpty("crew").stream()
            .map(t -> (CompoundTag) t)
            .anyMatch(
                t ->
                    t.getStringOr("id", "").equals(p.getUUID().toString())
                        && t.getBooleanOr("construction", false));
    if (!visitor) return false;
    var source = find(p.level().getServer(), station.getStringOr("constructionShip", ""));
    return source != null
        && source.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED")
        && source.getCompoundOrEmpty("dock").getStringOr("target", "").equals(id(station))
        && ShipSystems.tether(p.level(), source);
  }

  public static void action(ServerPlayer p, CompoundTag s, BlockPos at, String verb) {
    var notice = perform(p, s, at, verb);
    if (!busy(p)) StationService.open(p, at, notice);
  }

  /** Execute without changing screens, so the controller can keep its destination visible. */
  public static String perform(ServerPlayer p, CompoundTag s, BlockPos at, String verb) {
    if (StationService.station(s)
        && !StationService.builder(p, s)
        && Set.of("DOCK_ACCEPT", "DOCK_DECLINE", "DOCK_CANCEL", "DOCK_UNDOCK", "DOCK_EMERGENCY")
            .contains(verb))
      throw new IllegalArgumentException(
          "Only station builders control this berth. Return to your ship to undock.");
    var server = p.level().getServer();
    var d = link(server, s, at);
    String notice = "";
    switch (verb) {
      case "DOCK_NEXT" -> {
        var choices = choices(p, s);
        if (choices.isEmpty()) throw new IllegalArgumentException("No available docking berth");
        var selected = selectedDestination(s);
        int index = -1;
        for (int i = 0; i < choices.size(); i++)
          if (sameChoice(selected, choices.get(i))) index = i;
        selectDestination(p, s, choices.get((index + 1) % choices.size()));
      }
      case "DOCK_REQUEST" -> {
        var choices = choices(p, s);
        if (choices.isEmpty()) throw new IllegalArgumentException("No available docking berth");
        var choice = selectedDestination(s);
        if (choices.stream().noneMatch(c -> sameChoice(choice, c)))
          throw new IllegalArgumentException(
              "Selected berth changed or became unavailable; select another berth");
        var result = request(p, s, choice.vessel(), choice.port());
        if (!result.ready()) notice = result.message();
      }
      case "DOCK_ACCEPT" -> {
        if (d == null || !d.getStringOr("target", "").equals(id(s)))
          throw new IllegalArgumentException("No incoming request at this port");
        if (!d.getStringOr("state", "").equals("WAITING_CONSENT"))
          throw new IllegalArgumentException("No request is waiting for consent");
        d.putString("state", "APPROACHING");
        ShipStore.get(server).save();
      }
      case "DOCK_CANCEL" -> {
        if (!s.contains("dock")
            || !s.getCompoundOrEmpty("dock").getStringOr("source", "").equals(id(s)))
          throw new IllegalArgumentException("No outgoing docking maneuver");
        if (s.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"))
          throw new IllegalArgumentException("Use Undock after capture");
        release(server, s, "Docking cancelled; ship stopped");
      }
      case "DOCK_DECLINE" -> {
        if (d == null
            || !d.getStringOr("target", "").equals(id(s))
            || !d.getStringOr("state", "").equals("WAITING_CONSENT"))
          throw new IllegalArgumentException("No incoming request to decline");
        var source = find(server, d.getStringOr("source", ""));
        if (source != null) release(server, source, "Docking request declined");
      }
      case "DOCK_INNER" -> safeInner(p, s, readPort(p.level(), s, at, true));
      case "DOCK_OUTER" -> {
        if (d != null)
          throw new IllegalArgumentException(
              "Exterior door stays sealed while a docking connection is reserved");
        if (!StationService.allowed(p, s))
          throw new IllegalArgumentException("Station access is private");
        var port = readPort(p.level(), s, at, true);
        if (!ShipDoors.open(p.level().getBlockState(port.outer()))) {
          if (p.level().dimension().equals(TravelService.SPACE)
              && !exempt(p)
              && (!SpaceSurvival.suited(p) || p.getAirSupply() < 40))
            throw new IllegalArgumentException(
                "Equip a complete suit and usable oxygen before opening to vacuum");
          if (ShipDoors.powered(p.level(), port.inner()))
            throw new IllegalArgumentException("Inner door has a redstone override");
          ShipDoors.setOpen(p.level(), port.inner(), false);
          if (!ShipDoors.sealed(p.level(), port.inner()))
            throw new IllegalArgumentException("Seal the inner door first");
        }
        if (ShipDoors.powered(p.level(), port.outer()))
          throw new IllegalArgumentException("Exterior door has a redstone override");
        ShipDoors.setOpen(
            p.level(), port.outer(), !ShipDoors.open(p.level().getBlockState(port.outer())));
      }
      case "DOCK_BOARD", "DOCK_CONSTRUCTION" -> {
        if (d == null || !d.getStringOr("state", "").equals("CLAMPED"))
          throw new IllegalArgumentException("Wait for automatic capture before boarding");
        var source = find(server, d.getStringOr("source", ""));
        var target = find(server, d.getStringOr("target", ""));
        boolean forward = s == source;
        var other = forward ? target : source;
        if (!at.equals(
            ShipStore.pos(s, "base")
                .offset(ShipStore.pos(d, forward ? "sourcePort" : "targetPort"))))
          throw new IllegalArgumentException("Use the connected airlock");
        var port = readPort(p.level(), s, at, false);
        boolean construction = forward && d.getBooleanOr("construction", false);
        if (construction && !StationService.builder(p, target))
          throw new IllegalArgumentException("Construction site is private");
        var otherPort =
            construction
                ? null
                : readPort(
                    p.level(),
                    other,
                    ShipStore.pos(other, "base")
                        .offset(ShipStore.pos(d, forward ? "targetPort" : "sourcePort")),
                    false);
        beginBoard(p, s, other, port, otherPort, construction);
      }
      case "DOCK_RETURN" -> {
        String home = s.getStringOr("constructionShip", "");
        for (var tag : s.getListOrEmpty("crew")) {
          var member = (CompoundTag) tag;
          if (member.getStringOr("id", "").equals(p.getUUID().toString()))
            home = member.getStringOr("returnShip", home);
        }
        var source = find(server, home);
        if (source == null
            || !source.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED")
            || !source.getCompoundOrEmpty("dock").getStringOr("target", "").equals(id(s)))
          throw new IllegalArgumentException("Your home ship is not connected");
        Port port = null;
        try {
          port =
              readPort(
                  p.level(),
                  source,
                  ShipStore.pos(source, "base")
                      .offset(ShipStore.pos(source.getCompoundOrEmpty("dock"), "sourcePort")),
                  false);
        } catch (IllegalArgumentException damaged) {
        }
        beginBoard(p, s, source, null, port, false);
      }
      case "DOCK_EMERGENCY" -> {
        if (d == null
            || !d.getStringOr("source", "").equals(id(s))
            || !d.getStringOr("state", "").equals("CLAMPED"))
          throw new IllegalArgumentException(
              "Use emergency departure from the clamped visiting ship");
        var target = find(server, d.getStringOr("target", ""));
        if (DockingTunnel.occupied(server, d))
          throw new IllegalArgumentException(
              "Move inside a cabin before retracting the docking tunnel");
        if (target != null
            && target.getListOrEmpty("crew").stream()
                .map(t -> (CompoundTag) t)
                .anyMatch(t -> t.getStringOr("returnShip", "").equals(id(s))))
          throw new IllegalArgumentException("Return all crew before emergency departure");
        if (boarding(server, id(s)) || target != null && boarding(server, id(target)))
          throw new IllegalArgumentException("Finish boarding before releasing the clamp");
        for (var vessel : new CompoundTag[] {s, target})
          if (vessel != null) {
            var controller =
                ShipStore.pos(vessel, "base")
                    .offset(ShipStore.pos(d, vessel == s ? "sourcePort" : "targetPort"));
            try {
              close(p.level(), readPort(p.level(), vessel, controller, false));
            } catch (IllegalArgumentException damaged) {
              p.sendSystemMessage(
                  Component.literal(
                      "Damaged airlock: surviving seals could not be confirmed. Cabin pressure"
                          + " remains real."));
            }
          }
        d.putString("state", "EMERGENCY_BACKOFF");
        ShipStore.get(server).save();
      }
      case "DOCK_UNDOCK" -> {
        if (d == null || !d.getStringOr("state", "").equals("CLAMPED"))
          throw new IllegalArgumentException("No clamped docking connection");
        var source = find(server, d.getStringOr("source", ""));
        var target = find(server, d.getStringOr("target", ""));
        if (s != source)
          throw new IllegalArgumentException("Undock from the visiting ship's helm or port");
        if (DockingTunnel.occupied(server, d))
          throw new IllegalArgumentException(
              "Move inside a cabin before retracting the docking tunnel");
        if (ShipStore.get(server).list("stationBoarding").stream()
            .map(t -> (CompoundTag) t)
            .anyMatch(
                t ->
                    t.getStringOr("source", "").equals(id(s))
                        || t.getStringOr("target", "").equals(id(s))))
          throw new IllegalArgumentException("Wait for passengers to finish boarding");
        if (target != null
            && target.getListOrEmpty("crew").stream()
                .map(t -> (CompoundTag) t)
                .anyMatch(t -> t.getStringOr("returnShip", "").equals(id(source))))
          throw new IllegalArgumentException("Recall crew from the other vessel before undocking");
        if (NavigationService.propulsion(p.level(), source) == 0)
          throw new IllegalArgumentException(
              "Your ship needs controls, engine power and fuel to undock");
        recoverEscape(p.level(), source, target, d);
        boolean intact =
            closeSurviving(
                p.level(),
                source,
                ShipStore.pos(source, "base").offset(ShipStore.pos(d, "sourcePort")));
        if (!d.getBooleanOr("construction", false) && target != null)
          intact &=
              closeSurviving(
                  p.level(),
                  target,
                  ShipStore.pos(target, "base").offset(ShipStore.pos(d, "targetPort")));
        if (!intact)
          notice =
              "Undocking from the damaged berth. Surviving doors are closed; repair any hull"
                  + " leaks.";
        d.putString("state", "UNDOCKING");
        d.putLong("expires", server.overworld().getGameTime() + 12000);
        ShipStore.get(server).save();
      }
      default -> throw new IllegalArgumentException("Unsupported docking action");
    }
    return notice;
  }

  public static double sceneDistance(CompoundTag own, CompoundTag other) {
    if (own == other) return -2;
    var d = own.getCompoundOrEmpty("dock");
    if (d.getStringOr("target", "").equals(id(other))
        || d.getStringOr("source", "").equals(id(other))) return -1;
    for (var key : own.getCompoundOrEmpty("dockLinks").keySet())
      if (own.getCompoundOrEmpty("dockLinks")
          .getCompoundOrEmpty(key)
          .getStringOr("source", "")
          .equals(id(other))) return -1;
    return SceneService.pose(own).position().distanceToSqr(SceneService.pose(other).position());
  }

  public static void initialize() {
    ServerContext.tick(
        ServerContext.Phase.NAVIGATION,
        server -> {
          for (var tag : List.copyOf(ShipStore.get(server).ships())) {
            var s = (CompoundTag) tag;
            if (s.contains("dock")) tickMotion(server, s);
          }
          tickBoarding(server);
          walkThrough(server);
          if (server.getTickCount() % 40 == 0) ShipStore.get(server).requestSave();
        });
  }

  private DockingService() {}
}
