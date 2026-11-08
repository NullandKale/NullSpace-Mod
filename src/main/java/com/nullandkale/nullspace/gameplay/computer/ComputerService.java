package com.nullandkale.nullspace.gameplay.computer;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.combat.ShipCombat;
import com.nullandkale.nullspace.gameplay.navigation.Jumps;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSensors;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsConsole;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.computer.ComputerImageCodec;
import com.nullandkale.nullspace.model.computer.FairScheduler;
import com.nullandkale.nullspace.model.computer.ScriptLanguage;
import com.nullandkale.nullspace.model.computer.VirtualComputer;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.power.PowerBus;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;

/**
 * Authoritative virtual computers. The only guest/world crossing is the explicit device dispatcher.
 */
public final class ComputerService {
  public static final int MAX_SCREEN_LENGTH =
      131072; // A bounded 80x25 snapshot with per-cell RGB styles.

  public record Screen(UUID session, String text, boolean open) implements CustomPacketPayload {
    public static final Type<Screen> TYPE =
        new Type<>(Identifier.parse("nullspace:computer_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Screen> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeUtf(p.text, MAX_SCREEN_LENGTH);
              b.writeBoolean(p.open);
            },
            b -> new Screen(b.readUUID(), b.readUtf(MAX_SCREEN_LENGTH), b.readBoolean()));

    @Override
    public Type<Screen> type() {
      return TYPE;
    }
  }

  public record Input(UUID session, String text, boolean close) implements CustomPacketPayload {
    public static final Type<Input> TYPE = new Type<>(Identifier.parse("nullspace:computer_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.session);
              b.writeUtf(p.text, 2048);
              b.writeBoolean(p.close);
            },
            b -> new Input(b.readUUID(), b.readUtf(2048), b.readBoolean()));

    @Override
    public Type<Input> type() {
      return TYPE;
    }
  }

  private static final class Machine {
    final MinecraftServer server;
    final String id;
    String ship, dimension;
    long position;
    final UUID owner;
    final VirtualComputer computer;
    boolean powered = true, imageFault;
    String lastImage = "";
    int callTick, calls;

    Machine(
        MinecraftServer server, String ship, String dimension, long position, CompoundTag data) {
      this.server = server;
      this.ship = ship;
      this.dimension = dimension;
      this.position = position;
      id = data.getStringOr("id", UUID.randomUUID().toString());
      powered = data.getBooleanOr("powered", true);
      UUID savedOwner;
      try {
        savedOwner = UUID.fromString(data.getStringOr("owner", new UUID(0, 0).toString()));
      } catch (IllegalArgumentException invalid) {
        savedOwner = new UUID(0, 0);
        imageFault = true;
      }
      owner = savedOwner;
      computer = new VirtualComputer((name, args) -> device(this, name, args));
      try {
        if (imageFault) throw new IllegalArgumentException("Invalid saved computer owner");
        String image = ComputerImageCodec.decode(data.get("image"));
        if (!image.isEmpty()) {
          computer.restore(image);
          lastImage = image;
        }
      } catch (RuntimeException invalid) {
        imageFault = true;
        computer.write(
            "\r\nSaved computer image is preserved but cannot be restored: "
                + invalid.getMessage()
                + "\r\nThis computer is read-only until its saved image is repaired.\r\n");
      }
    }
  }

  private static final class Session {
    final UUID id = UUID.randomUUID();
    final Machine machine;
    int expires, window, bytes, lastRevision = -1;

    Session(Machine m, int tick) {
      machine = m;
      expires = tick + 100;
    }
  }

  private static final class State {
    boolean hydrated;
    int deviceTick, deviceCalls, inspections;
    int executionTick, instructions;
    final FairScheduler<Machine> scheduler = new FairScheduler<>();
    final Map<String, Machine> machines = new LinkedHashMap<>();
    final Map<UUID, Session> sessions = new HashMap<>();
  }

  private static final ServerContext.Key<State> KEY = new ServerContext.Key<>();

  private static State state(MinecraftServer s) {
    return ServerContext.get(s).service(KEY, State::new);
  }

  private static CompoundTag ship(Machine m) {
    return WorldStore.get(m.server).repository().find(m.ship);
  }

  private record Location(ServerLevel level, BlockPos position, CompoundTag ship) {}

  private static Location locate(Machine m) {
    var ship = ship(m);
    if (!m.ship.isEmpty() && ship == null)
      throw new ScriptLanguage.Unavailable("Ship metadata is unavailable");
    String dim = ship == null ? m.dimension : ship.getStringOr("dimension", "");
    var level = WorldStore.level(m.server, dim);
    var pos =
        ship == null
            ? BlockPos.of(m.position)
            : WorldStore.pos(ship, "base").offset(BlockPos.of(m.position));
    if (!level.hasChunkAt(pos) || ship != null && !ShipTopology.loaded(level, ship))
      throw new ScriptLanguage.Unavailable("Ship chunks are not fully loaded");
    if (TransferLocks.locked(level, pos))
      throw new ScriptLanguage.Unavailable("Ship is in transit");
    if (!level.getBlockState(pos).is(ModBlocks.TERMINAL))
      throw new IllegalArgumentException("Computer block was removed");
    return new Location(level, pos, ship);
  }

  private static CompoundTag record(Machine m) {
    var vessel = ship(m);
    String field = vessel == null ? "standaloneComputers" : "computers";
    var parent = vessel == null ? null : vessel;
    if (parent != null && !parent.contains(field)) parent.put(field, new CompoundTag());
    String key = vessel == null ? m.dimension + "/" + m.position : Long.toString(m.position);
    var map =
        parent == null ? WorldStore.get(m.server).section(field) : parent.getCompoundOrEmpty(field);
    if (!map.contains(key)) map.put(key, new CompoundTag());
    return map.getCompoundOrEmpty(key);
  }

  private static void save(Machine m) {
    if (m.imageFault) return;
    var r = record(m);
    boolean changed =
        !r.getStringOr("id", "").equals(m.id)
            || !r.getStringOr("owner", "").equals(m.owner.toString())
            || !r.contains("powered")
            || r.getBooleanOr("powered", true) != m.powered;
    r.putString("id", m.id);
    r.putString("owner", m.owner.toString());
    if (!m.imageFault) {
      String image = m.computer.save();
      if (!image.equals(m.lastImage) || !(r.get("image") instanceof CompoundTag)) {
        r.put("image", ComputerImageCodec.encode(image));
        m.lastImage = image;
        changed = true;
      }
    }
    r.putBoolean("powered", m.powered);
    if (changed) WorldStore.get(m.server).requestSave();
  }

  public static void replaced(MinecraftServer server, ShipRepository.Mutation mutation) {
    if (mutation != ShipRepository.Mutation.RECOVERY) return;
    var runtime = ServerContext.get(server).existing(KEY);
    if (runtime == null) return;
    for (var entry : runtime.sessions.entrySet()) {
      var player = server.getPlayerList().getPlayer(entry.getKey());
      if (player != null && ServerPlayNetworking.canSend(player, Screen.TYPE))
        ServerPlayNetworking.send(player, new Screen(entry.getValue().id, "", false));
    }
    runtime.sessions.clear();
    runtime.machines.clear();
    runtime.hydrated = false;
  }

  /** Keep running guests at the same physical terminal when a ground allocation origin changes. */
  public static void rebase(MinecraftServer server, String ship, BlockPos delta) {
    var runtime = ServerContext.get(server).existing(KEY);
    if (runtime == null || delta.equals(BlockPos.ZERO)) return;
    for (var machine : runtime.machines.values())
      if (machine.ship.equals(ship))
        machine.position = BlockPos.of(machine.position).offset(delta).asLong();
  }

  private static void hydrate(MinecraftServer server) {
    var r = state(server);
    if (r.hydrated) return;
    r.hydrated = true;
    var store = WorldStore.get(server);
    for (var t : store.ships()) {
      var s = (CompoundTag) t;
      var map = s.getCompoundOrEmpty("computers");
      for (String key : map.keySet()) {
        if (r.machines.size() >= 256) break;
        var data = map.getCompoundOrEmpty(key);
        try {
          var m = new Machine(server, s.getStringOr("id", ""), "", Long.parseLong(key), data);
          r.machines.put(m.id, m);
        } catch (RuntimeException error) {
          NullSpace.LOGGER.warn("Computer image unavailable", error);
        }
      }
    }
    var map = store.section("standaloneComputers");
    for (String key : map.keySet()) {
      if (r.machines.size() >= 256) break;
      int slash = key.lastIndexOf('/');
      if (slash < 0) continue;
      try {
        var m =
            new Machine(
                server,
                "",
                key.substring(0, slash),
                Long.parseLong(key.substring(slash + 1)),
                map.getCompoundOrEmpty(key));
        r.machines.put(m.id, m);
      } catch (RuntimeException error) {
        NullSpace.LOGGER.warn("Standalone computer image unavailable", error);
      }
    }
  }

  public static void open(ServerPlayer player, BlockPos position) {
    var level = player.level();
    if (!level.hasChunkAt(position)
        || !level.getBlockState(position).is(ModBlocks.TERMINAL)
        || player.distanceToSqr(position.getX() + .5, position.getY() + .5, position.getZ() + .5)
            > 64
        || !player.mayInteract(level, position)
        || TransferLocks.busy(player)
        || TransferLocks.locked(level, position)) return;
    var server = level.getServer();
    hydrate(server);
    var r = state(server);
    var vessel = WorldStore.get(server).at(level, position);
    if (vessel == null)
      try {
        vessel = ShipTopology.resolve(level, position);
      } catch (IllegalArgumentException ignored) {
      }
    for (var existing : r.machines.values()) adopt(existing);
    String shipId = vessel == null ? "" : vessel.getStringOr("id", "");
    long local =
        vessel == null
            ? position.asLong()
            : position.subtract(WorldStore.pos(vessel, "base")).asLong();
    String dim = vessel == null ? level.dimension().identifier().toString() : "";
    Machine machine =
        r.machines.values().stream()
            .filter(m -> m.ship.equals(shipId) && m.dimension.equals(dim) && m.position == local)
            .findFirst()
            .orElse(null);
    if (machine == null) {
      if (r.machines.size() >= 256) {
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal("World computer limit reached"));
        return;
      }
      var data = new CompoundTag();
      data.putString("id", UUID.randomUUID().toString());
      data.putString("owner", player.getUUID().toString());
      machine = new Machine(server, shipId, dim, local, data);
      r.machines.put(machine.id, machine);
      save(machine);
    }
    var session = new Session(machine, server.getTickCount());
    r.sessions.put(player.getUUID(), session);
    ServerPlayNetworking.send(player, new Screen(session.id, snapshot(machine.computer), true));
    session.lastRevision = machine.computer.revision();
  }

  private static boolean valid(ServerPlayer p, Session s) {
    if (p == null
        || !p.isAlive()
        || p.level().getServer().getTickCount() > s.expires
        || TransferLocks.busy(p)) return false;
    try {
      var l = locate(s.machine);
      return p.level() == l.level
          && p.distanceToSqr(l.position.getX() + .5, l.position.getY() + .5, l.position.getZ() + .5)
              <= 64
          && p.mayInteract(l.level, l.position);
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static void input(ServerPlayer p, Input input) {
    var s = state(p.level().getServer()).sessions.get(p.getUUID());
    if (s == null || !s.id.equals(input.session) || !valid(p, s)) return;
    if (input.close) {
      save(s.machine);
      state(p.level().getServer()).sessions.remove(p.getUUID());
      return;
    }
    if (s.machine.imageFault) return;
    int tick = p.level().getServer().getTickCount();
    if (s.window != tick / 20) {
      s.window = tick / 20;
      s.bytes = 0;
    }
    s.bytes += input.text.length();
    if (s.bytes > 8192) return;
    s.expires = tick + 100;
    s.machine.computer.input(input.text);
  }

  private static String snapshot(VirtualComputer c) {
    return c.display.snapshot();
  }

  public static UUID session(ServerPlayer player) {
    var s = state(player.level().getServer()).sessions.get(player.getUUID());
    return s != null && valid(player, s) ? s.id : new UUID(0, 0);
  }

  private static void adopt(Machine m) {
    if (!m.ship.isEmpty()) return;
    ServerLevel level;
    try {
      level = WorldStore.level(m.server, m.dimension);
    } catch (IllegalArgumentException absent) {
      return;
    }
    var old = BlockPos.of(m.position);
    var vessel = WorldStore.get(m.server).at(level, old);
    if (vessel == null) return;
    var savedRecord = record(m).copy();
    String key = m.dimension + "/" + m.position;
    WorldStore.get(m.server).section("standaloneComputers").remove(key);
    m.ship = vessel.getStringOr("id", "");
    m.dimension = "";
    m.position = old.subtract(WorldStore.pos(vessel, "base")).asLong();
    record(m).merge(savedRecord);
    save(m);
  }

  /** Per-server execution counters for diagnostics; reading them does not create runtime state. */
  public record Usage(int tick, int instructions, int deviceCalls, int inspections, int machines) {}

  public static Usage usage(MinecraftServer server) {
    var r = ServerContext.get(server).existing(KEY);
    if (r == null) return new Usage(server.getTickCount(), 0, 0, 0, 0);
    return new Usage(
        r.executionTick,
        r.instructions,
        r.deviceTick == r.executionTick ? r.deviceCalls : 0,
        r.deviceTick == r.executionTick ? r.inspections : 0,
        r.machines.size());
  }

  private static Object device(Machine m, String name, List<Object> args) {
    int tick = m.server.getTickCount();
    if (m.callTick != tick) {
      m.callTick = tick;
      m.calls = 0;
    }
    if (++m.calls > 32) throw new ScriptLanguage.Unavailable("Device call budget reached", 1);
    if (args.size() > 16 || args.stream().anyMatch(a -> ScriptLanguage.text(a).length() > 256))
      throw new IllegalArgumentException("Device arguments exceed limit");
    var l = locate(m);
    var ship = l.ship;
    if (ship == null)
      throw new ScriptLanguage.Unavailable("Connect this terminal to a registered ship");
    if (!ShipSystems.connected(l.level, ship).contains(l.position)
        || !ShipSystems.active(ship, l.position))
      throw new IllegalArgumentException("Computer is disconnected or disabled");
    if (ShipSystems.power(l.level, ship, PowerBus.System.CONTROLS) == 0)
      throw new ScriptLanguage.Unavailable("Controls have no power");
    var runtime = state(m.server);
    if (runtime.deviceTick != tick) {
      runtime.deviceTick = tick;
      runtime.deviceCalls = 0;
      runtime.inspections = 0;
    }
    if (runtime.deviceCalls >= 64 || name.equals("inspect") && runtime.inspections >= 2)
      throw new ScriptLanguage.Unavailable("Server device budget reached", 1);
    runtime.deviceCalls++;
    if (name.equals("inspect")) runtime.inspections++;
    String argument = String.join(" ", args.stream().map(ScriptLanguage::text).toList());
    var helm = WorldStore.pos(ship, "controller");
    return switch (name) {
      case "status" -> SystemsConsole.summary(l.level, ship);
      case "rooms" ->
          ShipAir.rooms(l.level, ship).stream()
              .limit(128)
              .map(
                  r ->
                      Map.of(
                          "id",
                          r.number(),
                          "oxygen",
                          r.oxygenPercent(),
                          "volume",
                          r.volume(),
                          "leaks",
                          r.leaks()))
              .toList();
      case "pressure" -> {
        if (args.size() != 3) throw new IllegalArgumentException("ship.pressure(x,y,z)");
        int x = (int) ScriptLanguage.number(args.get(0)),
            y = (int) ScriptLanguage.number(args.get(1)),
            z = (int) ScriptLanguage.number(args.get(2));
        yield ShipAir.pressure(l.level, ship, x, y, z);
      }
      case "targets" -> ShipSensors.targets(l.level, ship);
      case "contacts" -> ShipSensors.contacts(l.level, ship);
      case "scan" ->
          ShipSensors.scan(l.level, ship, argument.equals("long") || argument.equals("long_range"));
      case "fire" -> ShipCombat.fire(l.level, ship, l.position, null);
      case "stop" -> {
        Piloting.automate(l.level, ship, l.position, m.id, FlightDynamics.BRAKE, 1);
        Piloting.stop(ship);
        yield "Stopped";
      }
      case "thrust", "pilot" -> {
        if (args.size() != 2)
          throw new IllegalArgumentException("ship.thrust('forward left', ticks)");
        int buttons = 0;
        for (String word :
            ScriptLanguage.text(args.getFirst()).toLowerCase(Locale.ROOT).split(" +"))
          buttons |=
              switch (word) {
                case "forward" -> FlightDynamics.FORWARD;
                case "reverse" -> FlightDynamics.REVERSE;
                case "left" -> FlightDynamics.LEFT;
                case "right" -> FlightDynamics.RIGHT;
                case "up" -> FlightDynamics.UP;
                case "down" -> FlightDynamics.DOWN;
                case "roll_left" -> FlightDynamics.ROLL_LEFT;
                case "roll_right" -> FlightDynamics.ROLL_RIGHT;
                case "pitch_up" -> FlightDynamics.PITCH_UP;
                case "pitch_down" -> FlightDynamics.PITCH_DOWN;
                case "brake" -> FlightDynamics.BRAKE;
                default -> throw new IllegalArgumentException("Unknown flight control: " + word);
              };
        yield Piloting.automate(
            l.level, ship, l.position, m.id, buttons, (int) ScriptLanguage.number(args.get(1)));
      }
      case "inspect" ->
          ShipDiagnostics.summary(l.level, ShipService.preflight(l.level, helm).scan());
      case "launch", "land", "jump" -> {
        var actor =
            m.server.getPlayerList().getPlayers().stream()
                .filter(
                    p ->
                        p.level() == l.level
                            && p.distanceToSqr(helm.getX() + .5, helm.getY() + .5, helm.getZ() + .5)
                                <= 64
                            && p.mayInteract(l.level, helm))
                .findFirst()
                .orElseThrow(
                    () ->
                        new ScriptLanguage.Unavailable(
                            "A crew member must board the helm before travel"));
        if (name.equals("jump")) Jumps.jump(actor, helm, argument);
        else
          TravelService.request(
              actor,
              helm,
              name.equals("land") ? argument : "",
              ShipService.preflight(l.level, helm));
        yield "Travel requested";
      }
      case "cameras" ->
          ShipSystems.connected(l.level, ship).stream()
              .filter(p -> l.level.getBlockState(p).is(ModBlocks.CAMERA))
              .map(p -> p.subtract(WorldStore.pos(ship, "base")).toShortString())
              .toList();
      case "camera" -> {
        int channel = (int) ScriptLanguage.number(args.getFirst());
        long count =
            ShipSystems.connected(l.level, ship).stream()
                .filter(p -> l.level.getBlockState(p).is(ModBlocks.CAMERA))
                .count();
        if (channel < 0 || channel >= count)
          throw new IllegalArgumentException("Camera channel out of range");
        if (!ship.contains("monitorChannels")) ship.put("monitorChannels", new CompoundTag());
        for (var p : ShipSystems.connected(l.level, ship))
          if (l.level.getBlockState(p).is(ModBlocks.MONITOR))
            ship.getCompoundOrEmpty("monitorChannels")
                .putInt(Long.toString(p.subtract(WorldStore.pos(ship, "base")).asLong()), channel);
        yield "Selected camera " + channel;
      }
      case "power",
          "doors",
          "profile",
          "order",
          "devices",
          "device",
          "groups",
          "group",
          "supply",
          "air" ->
          SystemsConsole.automation(
              l.level, ship, l.position, name + (argument.isBlank() ? "" : " " + argument));
      default -> throw new IllegalArgumentException("Unknown ship API: " + name);
    };
  }

  public static void flush(MinecraftServer server) {
    var s = ServerContext.get(server).existing(KEY);
    if (s != null)
      for (var m : s.machines.values()) {
        adopt(m);
        save(m);
      }
  }

  public static void initialize() {
    PayloadTypeRegistry.clientboundPlay().register(Screen.TYPE, Screen.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(Input.TYPE, Input.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Input.TYPE, (p, c) -> c.server().execute(() -> input(c.player(), p)));
    ServerContext.tick(
        ServerContext.Phase.COMPUTERS,
        server -> {
          hydrate(server);
          var r = state(server);
          r.executionTick = server.getTickCount();
          int budget = 4096, remaining = r.machines.size();
          var machines = r.scheduler.order(r.machines.values());
          for (var m : machines) {
            boolean loaded = false;
            try {
              var l = locate(m);
              loaded = true;
              m.powered =
                  l.ship == null
                      || ShipSystems.connected(l.level, l.ship).contains(l.position)
                          && ShipSystems.active(l.ship, l.position)
                          && ShipSystems.power(l.level, l.ship, PowerBus.System.CONTROLS) > 0;
            } catch (ScriptLanguage.Unavailable waiting) {
            } catch (IllegalArgumentException removed) {
              m.powered = false;
            }
            boolean attended =
                r.sessions.entrySet().stream()
                    .anyMatch(
                        e ->
                            e.getValue().machine == m
                                && valid(
                                    server.getPlayerList().getPlayer(e.getKey()), e.getValue()));
            budget -=
                m.computer.tick(
                    attended && loaded,
                    m.powered && !m.imageFault,
                    FairScheduler.quantum(budget, remaining--, 256));
          }
          r.instructions = 4096 - budget;
          var sessions = r.sessions.entrySet().iterator();
          while (sessions.hasNext()) {
            var e = sessions.next();
            var p = server.getPlayerList().getPlayer(e.getKey());
            var s = e.getValue();
            if (!valid(p, s)) {
              if (p != null && ServerPlayNetworking.canSend(p, Screen.TYPE))
                ServerPlayNetworking.send(p, new Screen(s.id, "", false));
              sessions.remove();
              continue;
            }
            if (server.getTickCount() % 2 == 0 && s.lastRevision != s.machine.computer.revision()) {
              ServerPlayNetworking.send(p, new Screen(s.id, snapshot(s.machine.computer), false));
              s.lastRevision = s.machine.computer.revision();
            }
          }
          if (server.getTickCount() % 20 == 0) flush(server);
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          flush(server);
          WorldStore.get(server).save();
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> {
          var r = ServerContext.get(server).existing(KEY);
          if (r != null) {
            var s = r.sessions.remove(handler.player.getUUID());
            if (s != null) save(s.machine);
          }
        });
  }

  private ComputerService() {}
}
