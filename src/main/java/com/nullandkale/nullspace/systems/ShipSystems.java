package com.nullandkale.nullspace.systems;

import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

/**
 * Ship-owned state is included in the existing before/after transfer journal. Legacy ships are
 * opt-in.
 */
public final class ShipSystems {
  private static final String UPGRADE_QUERY = "upgrade readiness";
  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<String, ShipRuntime>>
      KEY = new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<String, ShipRuntime> live(MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(KEY, HashMap::new);
  }

  public static void edited(MinecraftServer server, ShipRepository.Change change) {
    if (!change.fields().contains("systems")
        && !change.fields().contains("base")
        && !change.fields().contains("dimension")) return;
    var runtimes = com.nullandkale.nullspace.server.ServerContext.get(server).existing(KEY);
    var r = runtimes == null ? null : runtimes.get(change.ship().value());
    if (r != null) r.source = null;
  }

  public static void replaced(MinecraftServer server) {
    var runtimes = com.nullandkale.nullspace.server.ServerContext.get(server).existing(KEY);
    if (runtimes != null)
      for (var r : runtimes.values()) {
        r.doors.clear();
        r.commander = null;
        r.commandController = null;
        r.dirty = true;
        r.power = null;
      }
  }

  public static boolean enabled(CompoundTag ship) {
    return ship != null && ship.contains("systems");
  }

  public static int deviceEnergy(CompoundTag ship, BlockPos pos) {
    long local = pos.subtract(ShipStore.pos(ship, "base")).asLong();
    return state(ship).getListOrEmpty("devices").stream()
        .map(t -> (CompoundTag) t)
        .filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local)
        .mapToInt(d -> d.getIntOr("energy", 0))
        .sum();
  }

  public static String deviceId(CompoundTag ship, BlockPos pos) {
    long local = pos.subtract(ShipStore.pos(ship, "base")).asLong();
    return state(ship).getListOrEmpty("devices").stream()
        .map(t -> (CompoundTag) t)
        .filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local)
        .map(d -> d.getStringOr("id", ""))
        .findFirst()
        .orElse("");
  }

  public static void setEnabled(
      ServerLevel level, CompoundTag ship, BlockPos pos, boolean enabled) {
    for (var tag : state(ship).getListOrEmpty("devices")) {
      var d = (CompoundTag) tag;
      if (d.getLongOr("position", Long.MIN_VALUE)
          == pos.subtract(ShipStore.pos(ship, "base")).asLong()) d.putBoolean("enabled", enabled);
    }
    var r = runtime(level, ship);
    r.power = null;
    r.revision++;
    ShipStore.get(level.getServer()).requestSave();
  }

  public static Set<BlockPos> connected(ServerLevel level, CompoundTag ship) {
    if (!enabled(ship)) return ShipTopology.discover(level, ship);
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    return r.blocks;
  }

  /** Re-read a vessel after its terrain has been loaded, preserving saved device and air state. */
  public static void loaded(ServerLevel level, CompoundTag ship) {
    var r = runtime(level, ship);
    r.dirty = true;
    r.power = null;
  }

  public static List<BlockPos> equipment(ServerLevel level, CompoundTag ship) {
    if (!enabled(ship))
      return connected(level, ship).stream()
          .filter(p -> DeviceInventory.device(level.getBlockState(p).getBlock()))
          .sorted(Comparator.comparingLong(BlockPos::asLong))
          .toList();
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    return r.equipment;
  }

  private static ShipRuntime runtime(ServerLevel level, CompoundTag ship) {
    var runtimes = live(level.getServer());
    String id = ship.getStringOr("id", "");
    var r = runtimes.get(id);
    if (r == null || r.source != ship) {
      var replacement = new ShipRuntime();
      replacement.source = ship;
      if (r != null) replacement.revision = r.revision + 1;
      r = replacement;
      runtimes.put(id, r);
    }
    return r;
  }

  static CompoundTag state(CompoundTag ship) {
    return ship.getCompoundOrEmpty("systems");
  }

  public static int revision(ServerLevel level, CompoundTag ship) {
    var r = runtime(level, ship);
    if (enabled(ship) && r.dirty) rebuild(level, ship, r, false);
    return r.revision;
  }

  public static void cancel(ServerPlayer player, BlockPos controller) {
    for (var r : live(player.level().getServer()).values())
      if (r.commander == player && controller.equals(r.commandController) && !r.doors.isEmpty()) {
        r.result = "Door batch CANCELLED: terminal closed";
        r.doorResults.add(r.result);
        r.doors.clear();
      }
  }

  public static void cancelTransit(ServerLevel level, CompoundTag ship) {
    var r = runtime(level, ship);
    if (!r.doors.isEmpty()) {
      r.result = "Door batch CANCELLED: transfer";
      r.doorResults.add(r.result);
      r.doors.clear();
    }
    r.dirty = true;
  }

  public static void upgrade(ServerLevel level, CompoundTag ship) {
    if (enabled(ship)) return;
    var s = new CompoundTag();
    s.putInt("version", 1);
    s.putString("profile", "cruise");
    s.putIntArray("requests", new int[] {1, 2, 1, 3, 1, 0});
    s.put("devices", new ListTag());
    ship.put("systems", s);
    var live = runtime(level, ship);
    live.dirty = true;
    live.air = new int[grid(ship).cells()];
    Arrays.fill(live.air, -1);
    rebuild(level, ship, live, true);
    ShipStore.get(level.getServer()).requestSave();
  }

  public static void resizeStation(ServerLevel level, CompoundTag ship, int width, int depth) {
    if (!com.nullandkale.nullspace.station.StationService.station(ship))
      throw new IllegalArgumentException("Only stations can expand");
    var previous = grid(ship);
    var next = new Atmosphere.Grid(width, 32, depth);
    var runtime = runtime(level, ship);
    if (enabled(ship) && runtime.dirty) rebuild(level, ship, runtime, false);
    if (enabled(ship)) {
      int[] air = new int[next.cells()];
      Arrays.fill(air, -1);
      for (int cell = 0; cell < previous.cells(); cell++)
        air[next.index(previous.x(cell), previous.y(cell), previous.z(cell))] = runtime.air[cell];
      state(ship).putIntArray("air", air);
    }
    ship.putInt("width", width);
    ship.putInt("depth", depth);
    live(level.getServer()).remove(ship.getStringOr("id", ""));
  }

  public static Atmosphere.Grid grid(CompoundTag ship) {
    return com.nullandkale.nullspace.station.StationService.station(ship)
        ? new Atmosphere.Grid(ship.getIntOr("width", 32), 32, ship.getIntOr("depth", 32))
        : Atmosphere.SHIP;
  }

  static int local(CompoundTag ship, BlockPos p) {
    var d = p.subtract(ShipStore.pos(ship, "base"));
    return grid(ship).index(d.getX(), d.getY(), d.getZ());
  }

  public static void changed(ServerLevel level, BlockPos p, boolean replaced) {
    com.nullandkale.nullspace.station.StationService.changed(level, p, replaced);
    for (Tag t : ShipStore.get(level.getServer()).ships()) {
      var ship = (CompoundTag) t;
      if (!enabled(ship)
          || !ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
          || !ShipStore.bounds(ship).contains(p)) continue;
      var r = runtime(level, ship);
      r.dirty = true;
      // A destroyed/disconnected supply cannot authorize a shot or launch using last
      // tick's grant. Journal placement preserves the frozen transfer allocation.
      if (!TravelService.worldMutation()) r.power = null;
      // Journal replay temporarily removes the same blocks before restoring them.
      // The journal owns device identities and energy during that mutation.
      if (replaced && !TravelService.worldMutation()) {
        long pos = p.subtract(ShipStore.pos(ship, "base")).asLong();
        state(ship)
            .getListOrEmpty("devices")
            .removeIf(d -> ((CompoundTag) d).getLongOr("position", Long.MIN_VALUE) == pos);
      }
    }
  }

  static void rebuild(ServerLevel level, CompoundTag ship, ShipRuntime r, boolean initialize) {
    DeviceInventory.rebuild(level, ship, r);
    AirSystem.rebuild(level, ship, r, initialize);
    r.dirty = false;
    r.revision++;
  }

  static boolean active(ShipRuntime r, BlockPos pos) {
    var d =
        r.devices.get(
            BlockPos.asLong(
                pos.getX() - r.base.getX(),
                pos.getY() - r.base.getY(),
                pos.getZ() - r.base.getZ()));
    return d == null || d.getBooleanOr("enabled", true);
  }

  public static void tick(ServerLevel level, CompoundTag ship) {
    if (!enabled(ship)
        || !com.nullandkale.nullspace.station.StationService.ready(ship)
        || TravelService.locked(level, ShipStore.pos(ship, "base"))) return;
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    PowerSystem.tick(level, ship, r);
    DoorSystem.tick(level, r);
    AirSystem.tick(level, ship, r);
  }

  static int grant(ShipRuntime r, PowerBus.System system) {
    return r.power == null ? 0 : r.power.delivered().getOrDefault(system, 0);
  }

  public static int power(ServerLevel level, CompoundTag ship, PowerBus.System system) {
    if (!enabled(ship)) return Integer.MAX_VALUE;
    var r = runtime(level, ship);
    if (r.dirty && !TravelService.worldMutation()) rebuild(level, ship, r, false);
    if (r.power == null) {
      var a = PowerSystem.allocate(level, ship, r);
      var p = a.result();
      r.power =
          new PowerBus.Result(
              a.stored(),
              p.generated(),
              p.discharged(),
              p.consumed(),
              p.charged(),
              p.curtailed(),
              p.delivered());
    }
    return grant(r, system);
  }

  public static boolean breathable(ServerLevel level, CompoundTag ship, BlockPos pos) {
    if (com.nullandkale.nullspace.station.DockingService.sealedMate(level, pos)) {
      var state = level.getBlockState(pos);
      var inside = pos.relative(state.getValue(DoorBlock.FACING).getOpposite());
      return breathable(level, ship, inside);
    }
    if (!ShipStore.bounds(ship).contains(pos)) return false;
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    return r.air[local(ship, pos)] >= Atmosphere.BREATHABLE;
  }

  public static int pressure(ServerLevel level, CompoundTag ship, int x, int y, int z) {
    if (!grid(ship).contains(x, y, z))
      throw new IllegalArgumentException("Cell coordinates are outside this vessel");
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    return Math.max(0, r.air[grid(ship).index(x, y, z)]);
  }

  public static int[] interiorMask(ServerLevel level, CompoundTag ship, BlockPos offset) {
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    var grid = grid(ship);
    var result = new int[1024];
    for (var room : r.rooms)
      if (room.leaks() == 0)
        for (int cell : room.cells()) {
          int x = grid.x(cell) - offset.getX(),
              y = grid.y(cell) - offset.getY(),
              z = grid.z(cell) - offset.getZ();
          if (x >= 0 && x < 32 && y >= 0 && y < 32 && z >= 0 && z < 32) {
            int index = Atmosphere.index(x, y, z);
            result[index >>> 5] |= 1 << (index & 31);
          }
        }
    return result;
  }

  public static int[] powerStats(ServerLevel level, CompoundTag ship) {
    power(level, ship, PowerBus.System.CONTROLS);
    var r = runtime(level, ship);
    var p = r.power;
    return p == null
        ? new int[4]
        : new int[] {p.generated() - p.curtailed(), p.consumed(), p.charged(), p.energy()};
  }

  /** Read actual generation/use separately from demand, including unmet demand. */
  public static int[] powerDisplay(ServerLevel level, CompoundTag ship) {
    var actual = powerStats(level, ship);
    var allocation = PowerSystem.allocate(level, ship, runtime(level, ship));
    return new int[] {
      actual[0], actual[1], actual[2], actual[3], allocation.demand(), allocation.capacity()
    };
  }

  public static RoomReading roomAt(ServerLevel level, CompoundTag ship, BlockPos pos) {
    if (!enabled(ship) || !ShipStore.bounds(ship).contains(pos)) return null;
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    int number = r.atmosphere.roomAt()[local(ship, pos)];
    return number < 0 ? null : rooms(level, ship).get(number);
  }

  public record RoomReading(int number, int volume, int oxygenPercent, int leaks, long quantity) {
    public String description() {
      return String.format(
          Locale.ROOT,
          "R%02d Oxygen %d%% | %d cells | %s",
          number,
          oxygenPercent,
          volume,
          leaks == 0 ? "SEALED" : "LEAK " + leaks);
    }
  }

  public static List<RoomReading> rooms(ServerLevel level, CompoundTag ship) {
    if (!enabled(ship)) return List.of();
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    var result = new ArrayList<RoomReading>();
    for (var room : r.rooms) {
      long quantity = 0;
      for (int cell : room.cells()) quantity += Math.max(0, r.air[cell]);
      result.add(
          new RoomReading(
              result.size() + 1,
              room.cells().length,
              (int) Math.min(Integer.MAX_VALUE, quantity * 100 / (room.cells().length * 1000L)),
              room.leaks(),
              quantity));
    }
    return List.copyOf(result);
  }

  public static boolean tether(ServerLevel level, CompoundTag ship) {
    var r = runtime(level, ship);
    if (grant(r, PowerBus.System.AIR) == 0) return false;
    var base = ShipStore.pos(ship, "base");
    for (var p : r.equipment)
      if (level.getBlockState(p).is(ShipBlocks.AIR_SUPPLY) && active(r, p)) {
        var at = p.relative(level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING));
        if (breathable(level, ship, at)) {
          int i = local(ship, at);
          r.air[i] = Math.max(0, r.air[i] - 10);
          state(ship).putIntArray("air", r.air);
          return true;
        }
      }
    return false;
  }

  public static String summary(ServerLevel level, CompoundTag ship) {
    if (!enabled(ship))
      return "Legacy ship. Upgrade enables finite air and powered systems. Install generator,"
          + " battery, air supply, and door control before upgrading. Existing builds are"
          + " preserved.";
    var r = runtime(level, ship);
    power(level, ship, PowerBus.System.CONTROLS);
    var p = r.power;
    String priority =
        state(ship).contains("order")
            ? Arrays.toString(state(ship).getIntArray("order").orElse(new int[0]))
            : state(ship).getBooleanOr("airFirst", false) ? "Air first" : "Controls first";
    String text =
        "SYSTEMS "
            + r.revision
            + " | Profile "
            + state(ship).getStringOr("profile", "custom")
            + " | Priority "
            + priority
            + " | "
            + (p == null
                ? "Starting"
                : ("Generation "
                    + p.generated()
                    + " Draw "
                    + p.consumed()
                    + " Battery "
                    + p.energy()))
            + " | ";
    int[] req = state(ship).getIntArray("requests").orElse(new int[6]);
    if (req.length == 5) req = Arrays.copyOf(req, 6);
    for (var system : PowerBus.System.values())
      text += system + " " + grant(r, system) + "/" + req[system.ordinal()] + "; ";
    int room = 0;
    for (var a : r.rooms) {
      long q = 0;
      for (int i : a.cells()) q += Math.max(0, r.air[i]);
      text +=
          "Room "
              + (++room)
              + ": "
              + q * 100 / (a.cells().length * 1000L)
              + "%"
              + (a.leaks() > 0 ? " LEAK " : " sealed ");
      if (text.length() > 1600) break;
    }
    return text
        + " | "
        + r.result
        + " | "
        + String.join(
            "; ",
            r.doorResults.subList(Math.max(0, r.doorResults.size() - 6), r.doorResults.size()));
  }

  public static String reading(ServerPlayer player, BlockPos controller, String query) {
    // Physical edits can invalidate the controller's topology as well as its selected page.
    // Both are status messages while a terminal is watched, not fatal server tick errors.
    try {
      var level = player.level();
      var ship = ShipTopology.resolve(level, controller);
      if (query.equals(UPGRADE_QUERY)) return upgradeCheck(level, ship, controller).message();
      if (query.isEmpty() || !enabled(ship)) return summary(level, ship);
      if (!EngineeringCommands.readOnly(query))
        throw new IllegalArgumentException("This is not a status query");
      var r = runtime(level, ship);
      if (r.dirty) rebuild(level, ship, r, false);
      return configure(player, level, ship, r, query);
    } catch (IllegalArgumentException e) {
      return ShipMessages.failure(e);
    }
  }

  public static void action(
      ServerPlayer player, BlockPos controller, ComputerTerminal.Action action) {
    action(player, controller, action, "");
  }

  public record UpgradeCheck(boolean ready, String message) {}

  /** Diagnose every missing bus connection on demand without loading neighboring terrain. */
  public static UpgradeCheck upgradeCheck(
      ServerLevel level, CompoundTag ship, BlockPos controller) {
    var blocks = connected(level, ship);
    var helm = ShipStore.pos(ship, "controller");
    String connectionAdvice =
        "Join floors with face-touching walls or pillars leading to the main controller."
            + " Diagonal contact does not count.";
    if (!blocks.contains(controller))
      return new UpgradeCheck(
          false,
          "Systems controller at "
              + controller.toShortString()
              + " is disconnected from the main controller at "
              + helm.toShortString()
              + ".\n"
              + connectionAdvice);
    var required = List.of(ShipBlocks.GENERATOR, ShipBlocks.AIR_SUPPLY);
    var missing =
        required.stream()
            .filter(type -> blocks.stream().noneMatch(p -> level.getBlockState(p).is(type)))
            .toList();
    if (missing.isEmpty())
      return new UpgradeCheck(
          true, "Required equipment is connected. Choose Upgrade systems to continue.");

    var bounds = ShipStore.bounds(ship);
    var inside = new HashMap<Block, BlockPos>();
    var outside = new HashMap<Block, BlockPos>();
    boolean unloadedInside = false;
    // At most a 40-cube scan: include nearby misplaced equipment, never another storage slot.
    for (var p :
        BlockPos.betweenClosed(bounds.min().offset(-4, -4, -4), bounds.max().offset(4, 4, 4))) {
      if (!level.hasChunkAt(p)) {
        if (bounds.contains(p)) unloadedInside = true;
        continue;
      }
      var type = level.getBlockState(p).getBlock();
      if (!missing.contains(type)) continue;
      var candidates = bounds.contains(p) ? inside : outside;
      var old = candidates.get(type);
      if (old == null || p.distSqr(helm) < old.distSqr(helm)) candidates.put(type, p.immutable());
    }
    var message = new StringBuilder("Systems upgrade blocked:\n");
    for (var type : missing) {
      message.append(ShipMessages.block(type)).append(": ");
      var at = inside.get(type);
      if (at != null)
        message
            .append("installed at ")
            .append(at.toShortString())
            .append(", but no block connection reaches the main controller.");
      else if (unloadedInside)
        message.append("not found in the loaded part of the ship. Load the whole ship and retry.");
      else if ((at = outside.get(type)) != null)
        message
            .append("nearby at ")
            .append(at.toShortString())
            .append(", outside this ship's build area. Move it inside.");
      else message.append("not installed in this ship. Place it on a connected floor or wall.");
      message.append('\n');
    }
    message
        .append("Main controller: ")
        .append(helm.toShortString())
        .append("\nBuild area: ")
        .append(bounds.min().toShortString())
        .append(" to ")
        .append(bounds.max().toShortString())
        .append('\n')
        .append(connectionAdvice);
    return new UpgradeCheck(false, message.toString());
  }

  public static void action(
      ServerPlayer player, BlockPos controller, ComputerTerminal.Action action, String argument) {
    action(player, controller, action, argument, true);
  }

  public static String closeDoors(ServerPlayer player, BlockPos controller) {
    action(player, controller, ComputerTerminal.Action.CLOSE_DOORS, "", false);
    var ship = ShipStore.get(player.level().getServer()).at(player.level(), controller);
    return runtime(player.level(), ship).result;
  }

  private static void action(
      ServerPlayer player,
      BlockPos controller,
      ComputerTerminal.Action action,
      String argument,
      boolean openScreen) {
    var level = player.level();
    if (TravelService.busy(player) || TravelService.locked(level, controller))
      throw new IllegalArgumentException("Wait for boarding to finish");
    var ship = ShipTopology.resolve(level, controller);
    var r = runtime(level, ship);
    boolean readOnly =
        action == ComputerTerminal.Action.SYSTEMS
            || action == ComputerTerminal.Action.CONFIGURE
                && EngineeringCommands.readOnly(argument);
    String response = null;
    var connected = connected(level, ship);
    if (action != ComputerTerminal.Action.UPGRADE && !connected.contains(controller))
      throw new IllegalArgumentException("This controller is disconnected from the ship");
    if (action == ComputerTerminal.Action.UPGRADE) {
      var check = upgradeCheck(level, ship, controller);
      if (check.ready()) upgrade(level, ship);
      else response = check.message();
    } else if (enabled(ship)) {
      if (!readOnly && power(level, ship, PowerBus.System.CONTROLS) == 0)
        throw new IllegalArgumentException("Controls have no power; reset local supply hardware");
      if (action == ComputerTerminal.Action.CLOSE_DOORS
          || action == ComputerTerminal.Action.OPEN_DOORS) {
        if (power(level, ship, PowerBus.System.CONTROLS) == 0)
          throw new IllegalArgumentException(
              "Controls have no power; use local generator/battery reset");
        r.doors.clear();
        r.open = action == ComputerTerminal.Action.OPEN_DOORS;
        r.doorResults.clear();
        r.revision++;
        r.commander = player;
        r.commandController = controller;
        r.blocks.stream()
            .filter(p -> ShipDoors.root(level.getBlockState(p)))
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .forEach(r.doors::addLast);
        r.result = "Queued " + r.doors.size() + " doors to " + (r.open ? "open" : "close");
      } else if (action == ComputerTerminal.Action.POWER_AIR
          || action == ComputerTerminal.Action.POWER_ENGINES
          || action == ComputerTerminal.Action.POWER_WEAPONS) {
        var req = state(ship).getIntArray("requests").orElse(new int[] {1, 2, 1, 3, 1, 0});
        if (req.length == 5) req = Arrays.copyOf(req, 6);
        int i =
            action == ComputerTerminal.Action.POWER_AIR
                ? 1
                : action == ComputerTerminal.Action.POWER_ENGINES ? 3 : 5;
        req[i] = (req[i] + 1) % 9;
        state(ship).putIntArray("requests", req);
        state(ship).putString("profile", "custom");
        r.power = null;
        r.revision++;
      } else if (action == ComputerTerminal.Action.PRIORITY) {
        state(ship).remove("order");
        state(ship).putBoolean("airFirst", !state(ship).getBooleanOr("airFirst", false));
        state(ship).putString("profile", "custom");
        r.power = null;
        r.revision++;
      } else if (action == ComputerTerminal.Action.CONFIGURE) {
        var result = configure(player, level, ship, r, argument);
        if (readOnly) response = result;
        else {
          r.result = result;
          r.commander = player;
          r.commandController = controller;
          r.power = null;
          r.revision++;
        }
      }
    } else if (action != ComputerTerminal.Action.SYSTEMS)
      throw new IllegalArgumentException("Upgrade systems first");
    if (!readOnly) ShipStore.get(level.getServer()).requestSave();
    if (!openScreen) return;
    ComputerTerminal.send(
        player,
        controller,
        response != null
            ? response
            : action == ComputerTerminal.Action.CONFIGURE ? r.result : summary(level, ship),
        "systems",
        Integer.toString(r.revision));
    ComputerTerminal.follow(
        player,
        controller,
        action == ComputerTerminal.Action.UPGRADE && response != null
            ? UPGRADE_QUERY
            : readOnly && action == ComputerTerminal.Action.CONFIGURE ? argument : "");
  }

  public static boolean active(CompoundTag ship, BlockPos pos) {
    long local = pos.subtract(ShipStore.pos(ship, "base")).asLong();
    return state(ship).getListOrEmpty("devices").stream()
        .map(t -> (CompoundTag) t)
        .filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local)
        .allMatch(d -> d.getBooleanOr("enabled", true));
  }

  static List<CompoundTag> doors(CompoundTag ship, ShipRuntime r) {
    var base = ShipStore.pos(ship, "base");
    return state(ship).getListOrEmpty("devices").stream()
        .map(t -> (CompoundTag) t)
        .filter(
            d ->
                (d.getStringOr("type", "").endsWith("_door")
                        || d.getStringOr("type", "").endsWith("_trapdoor"))
                    && r.blocks.contains(base.offset(BlockPos.of(d.getLongOr("position", 0)))))
        .sorted(Comparator.comparing(d -> d.getStringOr("id", "")))
        .toList();
  }

  private static String configure(
      ServerPlayer player, ServerLevel level, CompoundTag ship, ShipRuntime r, String input) {
    return EngineeringExecutor.execute(player, level, ship, r, ShipCommand.parse(input));
  }

  public static String automation(
      ServerLevel level, CompoundTag ship, BlockPos controller, String input) {
    if (input.length() > 128) throw new IllegalArgumentException("Device command is too long");
    if (TravelService.locked(level, controller))
      throw new com.nullandkale.nullspace.computer.ScriptLanguage.Unavailable("Ship is in transit");
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    if (!r.blocks.contains(controller) || !active(r, controller))
      throw new IllegalArgumentException("Computer is disconnected or disabled");
    if (!enabled(ship)) throw new IllegalArgumentException("Upgrade ship systems first");
    boolean read = EngineeringCommands.readOnly(input);
    if (!read && power(level, ship, PowerBus.System.CONTROLS) == 0)
      throw new IllegalArgumentException("Controls have no power");
    if (input.equals("room here"))
      throw new IllegalArgumentException("Use rooms from an unattended computer");
    String result = configure(null, level, ship, r, input);
    if (!read) {
      r.commander = null;
      r.commandController = controller;
      r.power = null;
      r.revision++;
    }
    return result;
  }

  public static void use(ServerPlayer player, BlockPos pos) {
    if (player.level().getBlockState(pos).is(ShipBlocks.LOCAL_SCANNER)
        || player.level().getBlockState(pos).is(ShipBlocks.LONG_RANGE_SCANNER)) {
      equipmentAccess(player, pos);
      // The Scanner layout explains itself; keep the message line free for results.
      com.nullandkale.nullspace.navigation.ShipControl.open(player, pos, "");
      return;
    }
    if (player.level().getBlockEntity(pos) instanceof MachineEntity) {
      MachineMenus.open(player, pos);
      return;
    }
    openEquipment(player, pos);
  }

  public static void openEquipment(ServerPlayer player, BlockPos pos) {
    if (player.level().getBlockState(pos).is(ShipBlocks.CAMERA)
        && ShipStore.get(player.level().getServer()).at(player.level(), pos) == null) {
      com.nullandkale.nullspace.scene.SceneService.equipmentView(player, pos);
      return;
    }
    equipmentAccess(player, pos);
    var message = equipmentStatus(player.level(), pos);
    var ship = ShipStore.get(player.level().getServer()).at(player.level(), pos);
    ComputerTerminal.send(
        player,
        pos,
        message,
        ShipBlocks.equipmentMode(player.level().getBlockState(pos)),
        Integer.toString(ship == null ? 0 : revision(player.level(), ship)));
  }

  private static void equipmentAccess(ServerPlayer player, BlockPos pos) {
    if (!player.isAlive()
        || !player.level().hasChunkAt(pos)
        || ShipBlocks.equipmentMode(player.level().getBlockState(pos)).isEmpty()
        || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 64
        || !player.mayInteract(player.level(), pos)
        || TravelService.busy(player)
        || TravelService.locked(player.level(), pos))
      throw new IllegalArgumentException("Equipment unavailable, out of range, or in transit");
  }

  private static CompoundTag equipmentDevice(CompoundTag ship, BlockPos pos) {
    long local = pos.subtract(ShipStore.pos(ship, "base")).asLong();
    return state(ship).getListOrEmpty("devices").stream()
        .map(t -> (CompoundTag) t)
        .filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local)
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Equipment identity is not ready; refresh"));
  }

  private static PowerBus.System subsystem(String mode) {
    return switch (mode) {
      case "air_supply" -> PowerBus.System.AIR;
      case "engine" -> PowerBus.System.ENGINES;
      case "door_control" -> PowerBus.System.DOORS;
      case "camera", "monitor", "local_scanner", "long_range_scanner" -> PowerBus.System.CAMERAS;
      case "weapon" -> PowerBus.System.WEAPONS;
      default -> null;
    };
  }

  public static String equipmentStatus(ServerLevel level, BlockPos pos) {
    var block = level.getBlockState(pos);
    String mode = ShipBlocks.equipmentMode(block);
    if (mode.isEmpty()) return "Equipment was removed or replaced. Reopen the block.";
    var facing = block.getValue(HorizontalDirectionalBlock.FACING);
    var lines = new ArrayList<String>();
    CompoundTag ship;
    try {
      ship = ShipTopology.resolve(level, pos);
    } catch (IllegalArgumentException unavailable) {
      return "OFFLINE: no recognized ship bus.\n" + unavailable.getMessage();
    }
    var r = runtime(level, ship);
    if (enabled(ship) && r.dirty) rebuild(level, ship, r, false);
    if (!(enabled(ship) ? r.blocks : ShipTopology.discover(level, ship)).contains(pos))
      return "DISCONNECTED\nAttach this device to the ship's connected hull.";
    if (!enabled(ship)) return "LEGACY BUS\nEnable power and finite air at the systems controller.";
    var device = equipmentDevice(ship, pos);
    boolean on = device.getBooleanOr("enabled", true);
    lines.add(on ? "CONNECTED | Switch ON" : "Switch OFF (disabled)");
    var system = subsystem(mode);
    int delivered = system == null ? 0 : grant(r, system);
    if (system != null) {
      int[] requests = Arrays.copyOf(state(ship).getIntArray("requests").orElse(new int[0]), 6);
      lines.add(
          "Power: " + delivered + " delivered / " + requests[system.ordinal()] + " requested");
      if (on && requests[system.ordinal()] == 0)
        lines.add("Power allocation is off. Increase this system's allocation.");
      else if (on && delivered == 0)
        lines.add(
            "No power reaches this system. Check fuel, generation and higher-priority loads.");
    }
    var base = ShipStore.pos(ship, "base");
    switch (mode) {
      case "generator" -> {
        lines.add(
            "Output: "
                + (on && !state(ship).getBooleanOr("supplyDisabled", false)
                    ? 8 + 4 * FuelSystem.tier(level, pos)
                    : 0)
                + " energy/t");
        lines.add(
            "Ship supply: " + (state(ship).getBooleanOr("supplyDisabled", false) ? "OFF" : "ON"));
      }
      case "battery" -> {
        lines.add(
            "Stored energy: "
                + device.getIntOr("energy", 0)
                + " / "
                + FuelSystem.capacity(level, pos));
        lines.add(
            "Charge / discharge limit: " + (4 * (1 + FuelSystem.tier(level, pos))) + " energy/t");
      }
      case "air_supply" -> {
        int assigned = delivered;
        for (var p : r.equipment) {
          if (!level.getBlockState(p).is(ShipBlocks.AIR_SUPPLY) || !active(ship, p)) continue;
          if (p.equals(pos)) break;
          if (ShipStore.bounds(ship)
              .contains(
                  p.relative(level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING))))
            assigned = Math.max(0, assigned - 2);
        }
        lines.add(
            "This pump: "
                + (on && ShipStore.bounds(ship).contains(pos.relative(facing))
                    ? Math.min(2, assigned)
                    : 0)
                + " / 2 power");
        var outlet = pos.relative(facing);
        if (!ShipStore.bounds(ship).contains(outlet))
          lines.add("OUTLET OUTSIDE SHIP: rotate/place the pump facing a room.");
        else {
          int cell = local(ship, outlet);
          var room =
              r.rooms.stream()
                  .filter(a -> Arrays.stream(a.cells()).anyMatch(i -> i == cell))
                  .findFirst()
                  .orElse(null);
          if (room == null) lines.add("OUTLET BLOCKED: face the pump into room air.");
          else {
            long total = 0;
            for (int i : room.cells()) total += Math.max(0, r.air[i]);
            lines.add(
                "Room oxygen: "
                    + total * 100 / (room.cells().length * 1000L)
                    + "% | "
                    + room.leaks()
                    + " leak edges");
          }
        }
      }
      case "engine" -> {
        lines.add(!on ? "DISABLED" : delivered == 0 ? "NO POWER" : "Power available for travel");
        lines.add("Exhaust: " + facing.getName());
      }
      case "door_control" -> {
        var doors = doors(ship, r);
        long open =
            doors.stream()
                .filter(
                    d -> {
                      var b =
                          level.getBlockState(base.offset(BlockPos.of(d.getLongOr("position", 0))));
                      return b.hasProperty(DoorBlock.OPEN) && b.getValue(DoorBlock.OPEN);
                    })
                .count();
        lines.add("Doors: " + open + " open / " + doors.size() + " total");
        if (!r.doors.isEmpty()) lines.add("Queued: " + r.doors.size());
        if (!r.doorResults.isEmpty())
          lines.add(
              r.doorResults
                  .getLast()
                  .replaceAll(" at -?\\d+, -?\\d+, -?\\d+", "")
                  .replaceAll(" -?\\d+, -?\\d+, -?\\d+$", ""));
      }
      case "camera", "monitor" -> {
        var cameras =
            r.equipment.stream()
                .filter(p -> level.getBlockState(p).is(ShipBlocks.CAMERA) && active(ship, p))
                .toList();
        int channel =
            mode.equals("camera")
                ? cameras.indexOf(pos)
                : cameras.isEmpty()
                    ? -1
                    : Math.floorMod(
                        ship.getCompoundOrEmpty("monitorChannels")
                            .getIntOr(Long.toString(pos.subtract(base).asLong()), 0),
                        cameras.size());
        lines.add(
            "Feed: "
                + (!on
                    ? "DISABLED"
                    : channel < 0 ? "NO CAMERA" : channel >= delivered ? "NO POWER" : "LIVE")
                + " | Camera "
                + (channel < 0 ? "none" : (channel + 1) + " / " + cameras.size()));
        if (channel >= 0)
          lines.add(
              "View direction: "
                  + level
                      .getBlockState(cameras.get(channel))
                      .getValue(HorizontalDirectionalBlock.FACING)
                      .getName());
      }
      case "local_scanner", "long_range_scanner" -> {
        try {
          lines.add(ShipSensors.equipment(level, pos));
        } catch (IllegalArgumentException error) {
          lines.add(error.getMessage());
        }
      }
      case "weapon" -> {
        lines.add(
            "Muzzle: " + (level.getBlockState(pos.relative(facing)).isAir() ? "CLEAR" : "BLOCKED"));
        long wait = Math.max(0, ship.getLongOr("weaponReadyAt", 0) - level.getGameTime());
        lines.add(wait == 0 ? "Recharge: READY" : "Recharge: " + wait + " ticks");
        lines.add("Fire from the helm in space.");
      }
    }
    return String.join("\n", lines);
  }

  public static void equipmentAction(
      ServerPlayer player, BlockPos pos, ComputerTerminal.Action action) {
    equipmentAction(player, pos, action, true, -1);
  }

  public static void restartSupply(ServerPlayer player, BlockPos pos) {
    equipmentAction(player, pos, ComputerTerminal.Action.DEVICE_RESET, false, -1);
  }

  public static void allocatePower(ServerPlayer player, BlockPos pos, int request) {
    if (request < 0 || request > 8) throw new IllegalArgumentException("Choose power from 0 to 8");
    equipmentAction(player, pos, ComputerTerminal.Action.DEVICE_ALLOCATE, false, request);
  }

  public static void equipmentAction(
      ServerPlayer player, BlockPos pos, ComputerTerminal.Action action, String value) {
    int request = -1;
    if (action == ComputerTerminal.Action.DEVICE_ALLOCATE && !value.isBlank()) {
      if (!value.matches("[0-8]")) throw new IllegalArgumentException("Choose power from 0 to 8");
      request = Integer.parseInt(value);
    }
    equipmentAction(player, pos, action, true, request);
  }

  public static int requestedAllocation(ServerLevel level, BlockPos at) {
    var ship = ShipStore.get(level.getServer()).at(level, at);
    String mode = ShipBlocks.equipmentMode(level.getBlockState(at));
    if (!com.nullandkale.nullspace.content.ContentCatalog.equipment(mode)
        .map(d -> d.has("power_allocation"))
        .orElse(false)) return -1;
    var system = subsystem(mode);
    if (ship == null || system == null) return -1;
    var requests = Arrays.copyOf(state(ship).getIntArray("requests").orElse(new int[0]), 6);
    return requests[system.ordinal()];
  }

  private static void equipmentAction(
      ServerPlayer player,
      BlockPos pos,
      ComputerTerminal.Action action,
      boolean reopen,
      int requested) {
    equipmentAccess(player, pos);
    var level = player.level();
    var mode = ShipBlocks.equipmentMode(level.getBlockState(pos));
    if (com.nullandkale.nullspace.terminal.EquipmentMenus.entries(mode).stream()
        .noneMatch(e -> e.verb().equalsIgnoreCase(action.name())))
      throw new IllegalArgumentException("Action does not belong to this equipment");
    if (action == ComputerTerminal.Action.DEVICE_REFRESH) {
      openEquipment(player, pos);
      return;
    }
    var ship = ShipTopology.resolve(level, pos);
    var r = runtime(level, ship);
    if (!connected(level, ship).contains(pos))
      throw new IllegalArgumentException("This equipment is disconnected from the ship");
    if (action == ComputerTerminal.Action.DEVICE_VIEW) {
      com.nullandkale.nullspace.scene.SceneService.equipmentView(player, pos);
      return;
    }
    if (!enabled(ship))
      throw new IllegalArgumentException("Upgrade systems at the systems controller first");
    if (r.dirty) rebuild(level, ship, r, false);
    var device = equipmentDevice(ship, pos);
    switch (action) {
      case DEVICE_ENABLE, DEVICE_DISABLE -> {
        device.putBoolean("enabled", action == ComputerTerminal.Action.DEVICE_ENABLE);
        r.power = null;
      }
      case DEVICE_RESET -> {
        device.putBoolean("enabled", true);
        state(ship).putBoolean("supplyDisabled", false);
        var req = Arrays.copyOf(state(ship).getIntArray("requests").orElse(new int[0]), 6);
        req[0] = 1;
        state(ship).putIntArray("requests", req);
        state(ship).putString("profile", "custom");
        r.power = null;
      }
      case DEVICE_ALLOCATE -> {
        if (power(level, ship, PowerBus.System.CONTROLS) == 0)
          throw new IllegalArgumentException(
              "Controls have no power. Select Manual restart at a generator or charged battery.");
        int i = subsystem(mode).ordinal();
        var req = Arrays.copyOf(state(ship).getIntArray("requests").orElse(new int[0]), 6);
        req[i] = requested >= 0 ? requested : (req[i] + 1) % 9;
        state(ship).putIntArray("requests", req);
        state(ship).putString("profile", "custom");
        r.power = null;
      }
      case DEVICE_CHANNEL -> com.nullandkale.nullspace.scene.SceneService.cycleMonitor(player, pos);
      case CLOSE_DOORS, OPEN_DOORS -> {
        if (!active(r, pos)
            || power(level, ship, PowerBus.System.CONTROLS) == 0
            || power(level, ship, PowerBus.System.DOORS) == 0)
          throw new IllegalArgumentException("Enable this device and power controls + doors first");
        r.result =
            configure(
                player,
                level,
                ship,
                r,
                "doors all " + (action == ComputerTerminal.Action.OPEN_DOORS ? "open" : "close"));
        r.commander = player;
        r.commandController = pos;
      }
      default -> throw new IllegalArgumentException("Unsupported equipment action");
    }
    r.revision++;
    ShipStore.get(level.getServer()).requestSave();
    if (reopen) openEquipment(player, pos);
  }

  public static void initialize() {
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.SYSTEMS,
        server -> {
          for (Tag t : ShipStore.get(server).ships()) {
            var ship = (CompoundTag) t;
            if (!enabled(ship)) continue;
            var level = ShipStore.level(server, ship.getStringOr("dimension", ""));
            var bounds = ShipStore.bounds(ship);
            if (level.players().stream()
                    .noneMatch(p -> bounds.box().inflate(80).contains(p.position()))
                && !com.nullandkale.nullspace.station.DockingService.engaged(ship)) continue;
            boolean loaded = true;
            for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
              for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                if (!level.hasChunk(x, z)) loaded = false;
            if (loaded) tick(level, ship);
          }
          if (server.getTickCount() % 200 == 0) ShipStore.get(server).requestSave();
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> com.nullandkale.nullspace.server.ServerContext.get(server).remove(KEY));
  }

  private ShipSystems() {}
}
