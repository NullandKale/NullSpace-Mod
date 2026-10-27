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

  public static Set<BlockPos> connected(ServerLevel level, CompoundTag ship) {
    if (!enabled(ship)) return ShipTopology.discover(level, ship);
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    return r.blocks;
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
    live.air = new int[Atmosphere.CELLS];
    Arrays.fill(live.air, -1);
    rebuild(level, ship, live, true);
    ShipStore.get(level.getServer()).requestSave();
  }

  static int local(BlockPos base, BlockPos p) {
    var d = p.subtract(base);
    return Atmosphere.index(d.getX(), d.getY(), d.getZ());
  }

  public static void changed(ServerLevel level, BlockPos p, boolean replaced) {
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
    if (!enabled(ship) || TravelService.locked(level, ShipStore.pos(ship, "base"))) return;
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
    if (!ShipStore.bounds(ship).contains(pos)) return false;
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    return r.air[local(ShipStore.pos(ship, "base"), pos)] >= Atmosphere.BREATHABLE;
  }

  public static int pressure(ServerLevel level, CompoundTag ship, int x, int y, int z) {
    if (x < 0 || x >= 32 || y < 0 || y >= 32 || z < 0 || z >= 32)
      throw new IllegalArgumentException("Cell coordinates must be 0..31");
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    return Math.max(0, r.air[Atmosphere.index(x, y, z)]);
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
          int i = local(base, at);
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
    var level = player.level();
    var ship = ShipTopology.resolve(level, controller);
    if (query.isEmpty() || !enabled(ship)) return summary(level, ship);
    if (!EngineeringCommands.readOnly(query))
      throw new IllegalArgumentException("This is not a status query");
    var r = runtime(level, ship);
    if (r.dirty) rebuild(level, ship, r, false);
    // A physical edit can remove a page while it is being watched. Keep the terminal
    // alive and tell its viewer to choose a current page instead of failing a tick.
    try {
      return configure(player, level, ship, r, query);
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }

  public static void action(
      ServerPlayer player, BlockPos controller, ComputerTerminal.Action action) {
    action(player, controller, action, "");
  }

  public static void action(
      ServerPlayer player, BlockPos controller, ComputerTerminal.Action action, String argument) {
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
    if (!connected.contains(controller))
      throw new IllegalArgumentException("This controller is disconnected from the ship");
    if (action == ComputerTerminal.Action.UPGRADE) {
      var blocks = connected;
      for (var required :
          List.of(
              ShipBlocks.GENERATOR,
              ShipBlocks.BATTERY,
              ShipBlocks.AIR_SUPPLY,
              ShipBlocks.DOOR_CONTROL))
        if (blocks.stream().noneMatch(p -> level.getBlockState(p).is(required)))
          throw new IllegalArgumentException(
              "Install a connected " + ShipMessages.block(required) + " before upgrading");
      upgrade(level, ship);
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
        readOnly && action == ComputerTerminal.Action.CONFIGURE ? argument : "");
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
    if (player.level().getBlockState(pos).is(ShipBlocks.CAMERA)
        && ShipStore.get(player.level().getServer()).at(player.level(), pos) == null) {
      com.nullandkale.nullspace.scene.SceneService.open(player, pos);
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
    }
    var base = ShipStore.pos(ship, "base");
    switch (mode) {
      case "generator" -> {
        lines.add(
            "Output: "
                + (on && !state(ship).getBooleanOr("supplyDisabled", false) ? 8 : 0)
                + " energy/t");
        lines.add(
            "Ship supply: " + (state(ship).getBooleanOr("supplyDisabled", false) ? "OFF" : "ON"));
      }
      case "battery" -> {
        lines.add("Stored energy: " + device.getIntOr("energy", 0) + " / 1200");
        lines.add("Charge / discharge limit: 4 energy/t");
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
          int cell = local(base, outlet);
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
    equipmentAccess(player, pos);
    var level = player.level();
    var mode = ShipBlocks.equipmentMode(level.getBlockState(pos));
    if (com.nullandkale.nullspace.terminal.EquipmentMenus.entries(mode).stream()
        .noneMatch(e -> e.verb().equalsIgnoreCase(action.name())))
      throw new IllegalArgumentException("Action does not belong to this equipment");
    if (action == ComputerTerminal.Action.DEVICE_REFRESH) {
      use(player, pos);
      return;
    }
    var ship = ShipTopology.resolve(level, pos);
    var r = runtime(level, ship);
    if (!connected(level, ship).contains(pos))
      throw new IllegalArgumentException("This equipment is disconnected from the ship");
    if (action == ComputerTerminal.Action.DEVICE_VIEW) {
      com.nullandkale.nullspace.scene.SceneService.open(player, pos);
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
        req[i] = (req[i] + 1) % 9;
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
    use(player, pos);
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
                .noneMatch(p -> bounds.box().inflate(80).contains(p.position()))) continue;
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
