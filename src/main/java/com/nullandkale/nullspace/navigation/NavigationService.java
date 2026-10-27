package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authoritative helm controls and atomic metadata-only region jumps. Hulls never move
 * chunks.
 */
public final class NavigationService {
  public record Input(UUID session, int sequence, int buttons, boolean close)
      implements CustomPacketPayload {
    public static final Type<Input> TYPE = new Type<>(Identifier.parse("nullspace:pilot_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeVarInt(p.sequence);
              b.writeVarInt(p.buttons);
              b.writeBoolean(p.close);
            },
            b -> new Input(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readBoolean()));

    @Override
    public Type<Input> type() {
      return TYPE;
    }
  }

  private static final class Lease {
    final UUID token = UUID.randomUUID(), player;
    final String ship;
    final BlockPos helm;
    final boolean pilot;
    int expires, sequence = -1, buttons, revision;

    Lease(ServerPlayer p, CompoundTag vessel, BlockPos helm, boolean pilot) {
      player = p.getUUID();
      ship = vessel.getStringOr("id", "");
      this.helm = helm.immutable();
      this.pilot = pilot;
      expires = p.level().getServer().getTickCount() + 80;
    }
  }

  private static final class Jump {
    final UUID token = UUID.randomUUID(), actor;
    final String ship, destination;
    final BlockPos helm;
    final Set<UUID> crew = new HashSet<>(), ready = new HashSet<>(), arrived = new HashSet<>();
    int ticks;
    boolean committed;

    Jump(ServerPlayer p, CompoundTag vessel, BlockPos helm, String destination) {
      actor = p.getUUID();
      ship = vessel.getStringOr("id", "");
      this.helm = helm.immutable();
      this.destination = destination;
    }
  }

  private record Automation(String computer, BlockPos controller, int buttons, int until) {}

  private static final class Runtime {
    final Map<String, Automation> computers = new HashMap<>();
    final Map<UUID, Lease> leases = new HashMap<>();
    final Map<String, UUID> pilots = new HashMap<>();
    final Map<String, Jump> jumps = new HashMap<>();
  }

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Runtime> KEY =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Runtime state(MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(KEY, Runtime::new);
  }

  private static CompoundTag ship(MinecraftServer server, String id) {
    return ShipStore.get(server).repository().find(id);
  }

  public static boolean pending(MinecraftServer server) {
    return !state(server).jumps.isEmpty();
  }

  public static boolean busy(ServerPlayer p) {
    return state(p.level().getServer()).jumps.values().stream()
        .anyMatch(j -> j.crew.contains(p.getUUID()));
  }

  public static boolean locked(ServerLevel level, BlockPos pos) {
    return level.dimension().equals(TravelService.SPACE)
        && state(level.getServer()).jumps.keySet().stream()
            .map(id -> ship(level.getServer(), id))
            .filter(Objects::nonNull)
            .anyMatch(s -> ShipStore.bounds(s).contains(pos));
  }

  public static boolean moving(MinecraftServer server) {
    return !state(server).pilots.isEmpty() || !state(server).computers.isEmpty();
  }

  private static CompoundTag helm(ServerPlayer p, BlockPos at) {
    if (!p.isAlive()
        || !p.level().dimension().equals(TravelService.SPACE)
        || !p.level().hasChunkAt(at)
        || !ShipBlocks.navigation(p.level().getBlockState(at))
        || p.distanceToSqr(Vec3.atCenterOf(at)) > 64
        || !p.mayInteract(p.level(), at))
      throw new IllegalArgumentException("Use the helm aboard your ship in space");
    var s = ShipStore.get(p.level().getServer()).at(p.level(), at);
    if (s == null) throw new IllegalArgumentException("Helm is not aboard a registered ship");
    return s;
  }

  public static int propulsion(ServerLevel level, CompoundTag s) {
    int engines =
        (int)
            ShipSystems.connected(level, s).stream()
                .filter(
                    p ->
                        level.getBlockState(p).is(ShipBlocks.ENGINE)
                            && ShipSystems.active(s, p)
                            && level
                                .getBlockState(
                                    p.relative(
                                        level
                                            .getBlockState(p)
                                            .getValue(HorizontalDirectionalBlock.FACING)))
                                .isAir())
                .count();
    if (engines == 0) return 0;
    if (!ShipSystems.enabled(s)) return Math.min(8, engines);
    if (ShipSystems.power(level, s, PowerBus.System.CONTROLS) == 0) return 0;
    return Math.min(8, Math.min(engines * 2, ShipSystems.power(level, s, PowerBus.System.ENGINES)));
  }

  public static UUID open(ServerPlayer player, BlockPos at, boolean pilot) {
    var vessel = helm(player, at);
    if (TravelService.busy(player) || TravelService.locked(player.level(), at))
      throw new IllegalArgumentException("Finish travel before opening ship controls");
    var r = state(player.level().getServer());
    var owner = r.pilots.get(vessel.getStringOr("id", ""));
    if (pilot && owner != null && !owner.equals(player.getUUID()))
      throw new IllegalArgumentException(
          "Another crew member is piloting. Use Ship camera to observe.");
    if (pilot) state(player.level().getServer()).computers.remove(vessel.getStringOr("id", ""));
    if (owner == null) stop(vessel);
    close(player.level().getServer(), player.getUUID());
    var lease = new Lease(player, vessel, at, pilot);
    r.leases.put(player.getUUID(), lease);
    if (pilot) r.pilots.put(lease.ship, player.getUUID());
    send(player, lease, vessel);
    return lease.token;
  }

  private static boolean valid(ServerPlayer p, Lease l) {
    try {
      return p != null
          && p.level().getServer().getTickCount() <= l.expires
          && !TravelService.busy(p)
          && !TravelService.locked(p.level(), l.helm)
          && helm(p, l.helm).getStringOr("id", "").equals(l.ship);
    } catch (IllegalArgumentException unavailable) {
      return false;
    }
  }

  public static boolean input(ServerPlayer p, Input input) {
    var l = state(p.level().getServer()).leases.get(p.getUUID());
    if (l == null || !l.token.equals(input.session) || !valid(p, l)) return false;
    if (input.close) {
      close(p.level().getServer(), p.getUUID());
      return true;
    }
    if (input.sequence < 0
        || input.sequence <= l.sequence
        || (input.buttons & ~FlightDynamics.ALL) != 0
        || !l.pilot && input.buttons != 0) return false;
    l.sequence = input.sequence;
    l.buttons = input.buttons;
    l.expires = p.level().getServer().getTickCount() + 80;
    return true;
  }

  public static void stop(CompoundTag s) {
    s.putDouble("simVX", 0);
    s.putDouble("simVY", 0);
    s.putDouble("simVZ", 0);
  }

  private static boolean crewAboard(ServerLevel level, CompoundTag s) {
    var pressure =
        ShipSystems.enabled(s) ? null : new ShipScan.LivePressure(level, ShipStore.bounds(s));
    for (var p : level.players())
      if (p.isAlive() && !p.isSpectator() && SceneService.observer(p) == s) {
        var eye = BlockPos.containing(p.getEyePosition());
        if (pressure == null ? !ShipSystems.breathable(level, s, eye) : !pressure.breathable(eye))
          return false;
      }
    return true;
  }

  /**
   * Arrival cells are simulation coordinates; storage slots remain independent and may be far away.
   */
  public static Vec3 arrivalOrigin(MinecraftServer server, CompoundTag own) {
    int start =
        Math.floorMod(ShipStore.pos(own, "base").getX() / TravelService.SPACE_SPACING, 4096);
    var neighbors =
        ShipStore.get(server).ships().stream()
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
              0,
              (iz % 2 == 0 ? iz / 2 : -(iz + 1) / 2) * 160);
      var center = origin.add(16, 16, 16);
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

  private static void close(MinecraftServer server, UUID player) {
    var r = state(server);
    var l = r.leases.remove(player);
    if (l != null && l.pilot) {
      r.pilots.remove(l.ship, player);
      var s = ship(server, l.ship);
      if (s != null) {
        stop(s);
        ShipStore.get(server).save();
      }
    }
  }

  public static void closeShip(MinecraftServer server, String id) {
    var r = state(server);
    r.computers.remove(id);
    for (var l : List.copyOf(r.leases.values())) if (l.ship.equals(id)) close(server, l.player);
  }

  private static void send(ServerPlayer p, Lease l, CompoundTag vessel) {
    SceneService.shipView(p, vessel, l.token, ++l.revision, l.pilot, propulsion(p.level(), vessel));
  }

  private static FlightDynamics.State flight(CompoundTag s) {
    var pose = SceneService.pose(s);
    return new FlightDynamics.State(
        pose.toUniverse(new Vec3(16, 16, 16)),
        new Vec3(s.getDoubleOr("simVX", 0), s.getDoubleOr("simVY", 0), s.getDoubleOr("simVZ", 0)),
        s.getDoubleOr("simYaw", 0),
        s.getDoubleOr("simPitch", 0),
        s.getDoubleOr("simRoll", 0));
  }

  private static void apply(CompoundTag s, FlightDynamics.State next) {
    var origin =
        next.center()
            .subtract(
                FlightDynamics.rotate(
                    FlightDynamics.orientation(next.yaw(), next.pitch(), next.roll()),
                    new Vec3(16, 16, 16)));
    s.putDouble("simX", origin.x);
    s.putDouble("simY", origin.y);
    s.putDouble("simZ", origin.z);
    s.putDouble("simYaw", next.yaw());
    s.putDouble("simPitch", next.pitch());
    s.putDouble("simRoll", next.roll());
    s.putDouble("simVX", next.velocity().x);
    s.putDouble("simVY", next.velocity().y);
    s.putDouble("simVZ", next.velocity().z);
  }

  private static boolean blocked(
      MinecraftServer server,
      CompoundTag own,
      FlightDynamics.State old,
      FlightDynamics.State next) {
    if (next.center().length() > FlightDynamics.LOCAL_RADIUS) return true;
    var delta = next.center().subtract(old.center());
    double distance = delta.length();
    if (distance < 1e-8) return false;
    var direction = delta.scale(1 / distance);
    double radius = 28;
    var definition = SpaceDefinitionCodec.decode(SceneCatalog.forShip(server, own));
    double time = server.overworld().getGameTime() / 20.0 - definition.epoch();
    for (var body : definition.bodies()) {
      var center = body.at(time);
      double bound = body.radius() * (1 + body.roughness()) + radius;
      if (old.center().distanceToSqr(center) < bound * bound) {
        if (next.center().distanceToSqr(center) < old.center().distanceToSqr(center)) return true;
      } else if (SpaceBvh.sphere(old.center(), direction, center, bound) <= distance) return true;
    }
    for (var tag : ShipStore.get(server).ships()) {
      var other = (CompoundTag) tag;
      if (other == own
          || !other.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString())
          || !LocalSpaces.together(own, other)) continue;
      var center = SceneService.pose(other).toUniverse(new Vec3(16, 16, 16));
      double combined = radius * 2;
      if (old.center().distanceToSqr(center) < combined * combined) {
        if (next.center().distanceToSqr(center) < old.center().distanceToSqr(center)) return true;
      } else if (SpaceBvh.sphere(old.center(), direction, center, combined) <= distance)
        return true;
    }
    return false;
  }

  public static String automate(
      ServerLevel level,
      CompoundTag ship,
      BlockPos controller,
      String computer,
      int buttons,
      int ticks) {
    if (!level.dimension().equals(TravelService.SPACE))
      throw new IllegalArgumentException("Local thrust operates in space");
    if ((buttons & ~FlightDynamics.ALL) != 0 || ticks < 1 || ticks > 200)
      throw new IllegalArgumentException("Use valid flight controls for 1..200 ticks");
    if (TravelService.locked(level, controller)
        || state(level.getServer()).pilots.containsKey(ship.getStringOr("id", "")))
      throw new IllegalArgumentException("A crew pilot or transit currently owns flight control");
    if (!ShipTopology.loaded(level, ship))
      throw new com.nullandkale.nullspace.computer.ScriptLanguage.Unavailable(
          "Ship chunks are not fully loaded");
    if (!ShipSystems.connected(level, ship).contains(controller)
        || !ShipSystems.active(ship, controller)
        || propulsion(level, ship) == 0)
      throw new IllegalArgumentException(
          "Computer and propulsion must be connected, enabled and powered");
    String id = ship.getStringOr("id", "");
    var old = state(level.getServer()).computers.get(id);
    if (old != null && !old.computer.equals(computer))
      throw new IllegalArgumentException("Another computer owns flight control");
    state(level.getServer())
        .computers
        .put(
            id,
            new Automation(
                computer,
                controller.immutable(),
                buttons,
                level.getServer().getTickCount() + ticks));
    return "Flight command accepted";
  }

  public static void navigation(ServerPlayer p, BlockPos at) {
    var s = helm(p, at);
    com.nullandkale.nullspace.terminal.ComputerTerminal.send(
        p,
        at,
        "LOCAL SPACE: "
            + LocalSpaces.label(s)
            + "\n"
            + "Choose a planet for an orbital jump. Other systems use interstellar fast travel.\n"
            + "Local piloting never changes regions. Landing pads belong to the current planet.",
        "navigation",
        LocalSpaces.address(s));
  }

  private static ShipService.Inspection jumpInspection(ServerPlayer p, BlockPos at) {
    var inspected = ShipService.preflight(p.level(), at);
    var s = inspected.vessel();
    if (propulsion(p.level(), s) == 0)
      throw new IllegalArgumentException(
          "Power controls and at least one clear engine before fast travel");
    if (!inspected.scan().pressure().breathable(BlockPos.containing(p.getEyePosition())))
      throw new IllegalArgumentException("Board the sealed cabin before fast travel");
    for (var crew : p.level().players())
      if (ShipStore.bounds(s).box().inflate(72).contains(crew.position())
          && SceneService.observer(crew) == s
          && !inspected.scan().pressure().breathable(BlockPos.containing(crew.getEyePosition())))
        throw new IllegalArgumentException(
            "Recall EVA crew into a sealed cabin before fast travel");
    if (ShipStore.get(p.level().getServer()).list("pendingImpacts").stream()
        .map(t -> (CompoundTag) t)
        .anyMatch(e -> e.getStringOr("ship", "").equals(s.getStringOr("id", ""))))
      throw new IllegalArgumentException("Resolve incoming combat damage before fast travel");
    return inspected;
  }

  public static UUID jump(ServerPlayer p, BlockPos at, String destination) {
    LocalSpaces.get(destination);
    var s = helm(p, at);
    var server = p.level().getServer();
    if (TravelService.active(server) || pending(server))
      throw new IllegalArgumentException("Another ship transit is in progress");
    if (destination.equals(LocalSpaces.address(s)))
      throw new IllegalArgumentException("Already in that local space");
    jumpInspection(p, at);
    PlanetSurfaces.ensureLanding(server, destination);
    SceneCatalog.region(server, destination);
    var j = new Jump(p, s, at, destination);
    for (var crew : p.level().players())
      if (SceneService.observer(crew) == s) {
        j.crew.add(crew.getUUID());
        crew.closeContainer();
      }
    closeShip(server, j.ship);
    ShipSystems.cancelTransit(p.level(), s);
    state(server).jumps.put(j.ship, j);
    broadcast(server, j, "Preparing fast travel", "", false);
    return j.token;
  }

  public static boolean ready(ServerPlayer p, TravelPackets.Ready packet) {
    for (var j : state(p.level().getServer()).jumps.values())
      if (j.token.equals(packet.id()) && j.crew.contains(p.getUUID())) {
        if (packet.arrived() == j.committed)
          (packet.arrived() ? j.arrived : j.ready).add(p.getUUID());
        return true;
      }
    return false;
  }

  private static void broadcast(
      MinecraftServer server, Jump j, String text, String target, boolean done) {
    for (var id : j.crew) {
      var p = server.getPlayerList().getPlayer(id);
      if (p != null)
        ServerPlayNetworking.send(p, new TravelPackets.Progress(j.token, text, target, done));
    }
  }

  private static boolean allOnline(MinecraftServer server, Jump j, Set<UUID> ready) {
    return j.crew.stream()
        .filter(id -> server.getPlayerList().getPlayer(id) != null)
        .allMatch(ready::contains);
  }

  private static void finish(MinecraftServer server, Jump j, String message) {
    state(server).jumps.remove(j.ship);
    broadcast(server, j, message, "", true);
    for (var id : j.crew) {
      var p = server.getPlayerList().getPlayer(id);
      if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }
  }

  private static void tick(MinecraftServer server) {
    var r = state(server);
    for (var l : List.copyOf(r.leases.values())) {
      var p = server.getPlayerList().getPlayer(l.player);
      if (!valid(p, l)) {
        close(server, l.player);
        continue;
      }
      var s = ship(server, l.ship);
      if (l.pilot) {
        boolean aboard = crewAboard(p.level(), s);
        int power = aboard ? propulsion(p.level(), s) : 0;
        var old = flight(s);
        var next =
            FlightDynamics.step(
                old,
                l.buttons,
                power,
                p.level()
                    .getBlockState(l.helm)
                    .getValue(HorizontalDirectionalBlock.FACING)
                    .getOpposite()
                    .toYRot());
        if (blocked(server, s, old, next)) {
          next =
              new FlightDynamics.State(old.center(), Vec3.ZERO, old.yaw(), old.pitch(), old.roll());
          s.putString("flightStatus", "OBSTACLE / LOCAL BOUNDARY - use helm navigation");
        } else
          s.putString(
              "flightStatus",
              !aboard
                  ? "CREW EVA / CABIN UNSAFE - FLIGHT STOPPED"
                  : power == 0 ? "NO CONTROL / ENGINE POWER" : "PILOT");
        apply(s, next);
        if ((l.buttons & FlightDynamics.FIRE) != 0) {
          l.buttons &= ~FlightDynamics.FIRE;
          try {
            ShipCombat.fire(p, l.helm);
          } catch (IllegalArgumentException unavailable) {
            s.putString("flightStatus", unavailable.getMessage());
          }
        }
      }
      if (server.getTickCount() % 2 == 0) send(p, l, s);
    }
    var computers = r.computers.entrySet().iterator();
    while (computers.hasNext()) {
      var entry = computers.next();
      var command = entry.getValue();
      var vessel = ship(server, entry.getKey());
      var level =
          vessel == null ? null : ShipStore.level(server, vessel.getStringOr("dimension", ""));
      if (vessel == null
          || server.getTickCount() >= command.until
          || level == null
          || !level.hasChunkAt(command.controller)
          || !ShipTopology.loaded(level, vessel)
          || TravelService.locked(level, command.controller)
          || !level.getBlockState(command.controller).is(ShipBlocks.TERMINAL)
          || !ShipSystems.connected(level, vessel).contains(command.controller)
          || !ShipSystems.active(vessel, command.controller)
          || propulsion(level, vessel) == 0
          || !crewAboard(level, vessel)) {
        if (vessel != null) stop(vessel);
        computers.remove();
        continue;
      }
      var old = flight(vessel);
      var next =
          FlightDynamics.step(
              old,
              command.buttons,
              propulsion(level, vessel),
              level
                  .getBlockState(ShipStore.pos(vessel, "controller"))
                  .getValue(HorizontalDirectionalBlock.FACING)
                  .getOpposite()
                  .toYRot());
      if (blocked(server, vessel, old, next)) {
        stop(vessel);
        vessel.putString("flightStatus", "AUTOPILOT OBSTACLE");
        computers.remove();
      } else {
        apply(vessel, next);
        vessel.putString("flightStatus", "SCRIPT PILOT");
      }
    }
    for (var j : List.copyOf(r.jumps.values())) {
      j.ticks++;
      if (!j.committed) {
        var pilot = server.getPlayerList().getPlayer(j.actor);
        if (pilot == null) {
          finish(server, j, "Fast travel cancelled: pilot disconnected");
          continue;
        }
        if (j.ticks > 200) {
          finish(server, j, "Fast travel cancelled: readiness timeout");
          continue;
        }
        if (j.ticks < 20 || !allOnline(server, j, j.ready)) continue;
        try {
          var s = helm(pilot, j.helm);
          if (!s.getStringOr("id", "").equals(j.ship))
            throw new IllegalArgumentException("Ship changed");
          jumpInspection(pilot, j.helm);
          var before = s.copy();
          try {
            s.putString("localSpace", j.destination);
            stop(s);
            var arrival = arrivalOrigin(server, s);
            s.putDouble("simX", arrival.x);
            s.putDouble("simY", arrival.y);
            s.putDouble("simZ", arrival.z);
            s.putDouble("simYaw", 0);
            s.putDouble("simPitch", 0);
            s.putDouble("simRoll", 0);
            ShipStore.get(server).save();
          } catch (RuntimeException save) {
            for (var key : List.copyOf(s.keySet())) s.remove(key);
            for (var key : before.keySet()) s.put(key, before.get(key));
            throw save;
          }
          j.committed = true;
          j.ticks = 0;
          SceneService.refresh(server);
          var target = SceneCatalog.forShip(server, s).getStringOr("id", "");
          broadcast(server, j, "Arriving at " + LocalSpaces.label(s), "scene:" + target, false);
        } catch (RuntimeException error) {
          finish(server, j, "Fast travel cancelled: " + error.getMessage());
        }
      } else if (allOnline(server, j, j.arrived) || j.ticks > 200) {
        if (j.ticks > 200)
          for (var id : j.crew) {
            var p = server.getPlayerList().getPlayer(id);
            if (p != null && !j.arrived.contains(id))
              p.connection.disconnect(
                  net.minecraft.network.chat.Component.literal(
                      "Reconnect to synchronize the destination scene"));
          }
        finish(
            server,
            j,
            "Arrived in "
                + LocalSpaces.get(j.destination).systemName()
                + " / "
                + LocalSpaces.get(j.destination).name());
      }
    }
    if (!r.pilots.isEmpty() && server.getTickCount() % 100 == 0) ShipStore.get(server).save();
  }

  public static void initialize() {
    PayloadTypeRegistry.serverboundPlay().register(Input.TYPE, Input.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Input.TYPE, (p, c) -> c.server().execute(() -> input(c.player(), p)));
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.NAVIGATION, NavigationService::tick);
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> close(server, handler.player.getUUID()));
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          for (var l : List.copyOf(state(server).leases.values())) close(server, l.player);
          ShipStore.get(server).save();
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> com.nullandkale.nullspace.server.ServerContext.get(server).remove(KEY));
  }

  private NavigationService() {}
}
