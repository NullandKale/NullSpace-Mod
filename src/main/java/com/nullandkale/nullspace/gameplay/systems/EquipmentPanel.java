package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ContentCatalog;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.scene.ScenePanels;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.ui.menu.EquipmentMenus;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

/** One machine's screen: its readings, its on/off and power controls, and the actions it offers. */
public final class EquipmentPanel {

  public static void use(ServerPlayer player, BlockPos pos) {
    if (player.level().getBlockState(pos).is(ModBlocks.LOCAL_SCANNER)) {
      equipmentAccess(player, pos);
      // The Scanner layout explains itself; keep the message line free for results.
      ShipControl.open(player, pos, "");
      return;
    }
    if (player.level().getBlockEntity(pos) instanceof MachineBlockEntity) {
      MachineMenus.open(player, pos);
      return;
    }
    openEquipment(player, pos);
  }

  public static void openEquipment(ServerPlayer player, BlockPos pos) {
    if (player.level().getBlockState(pos).is(ModBlocks.CAMERA)
        && WorldStore.get(player.level().getServer()).at(player.level(), pos) == null) {
      ScenePanels.equipmentView(player, pos);
      return;
    }
    equipmentAccess(player, pos);
    var message = equipmentStatus(player.level(), pos);
    var ship = WorldStore.get(player.level().getServer()).at(player.level(), pos);
    ComputerTerminal.send(
        player,
        pos,
        message,
        ModBlocks.equipmentMode(player.level().getBlockState(pos)),
        Integer.toString(ship == null ? 0 : ShipSystems.revision(player.level(), ship)));
  }

  static void equipmentAccess(ServerPlayer player, BlockPos pos) {
    if (!player.isAlive()
        || !player.level().hasChunkAt(pos)
        || ModBlocks.equipmentMode(player.level().getBlockState(pos)).isEmpty()
        || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 64
        || !player.mayInteract(player.level(), pos)
        || TransferLocks.busy(player)
        || TransferLocks.locked(player.level(), pos))
      throw new IllegalArgumentException("Equipment unavailable, out of range, or in transit");
  }

  static CompoundTag equipmentDevice(CompoundTag ship, BlockPos pos) {
    long local = pos.subtract(WorldStore.pos(ship, "base")).asLong();
    return ShipSystems.state(ship).getListOrEmpty("devices").stream()
        .map(t -> (CompoundTag) t)
        .filter(d -> d.getLongOr("position", Long.MIN_VALUE) == local)
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Equipment identity is not ready; refresh"));
  }

  static PowerBus.System subsystem(String mode) {
    return switch (mode) {
      case "air_supply" -> PowerBus.System.AIR;
      case "engine" -> PowerBus.System.ENGINES;
      case "camera", "monitor", "local_scanner" -> PowerBus.System.CAMERAS;
      case "weapon" -> PowerBus.System.WEAPONS;
      default -> null;
    };
  }

  public static String equipmentStatus(ServerLevel level, BlockPos pos) {
    var block = level.getBlockState(pos);
    String mode = ModBlocks.equipmentMode(block);
    if (mode.isEmpty()) return "Equipment was removed or replaced. Reopen the block.";
    var facing = block.getValue(HorizontalDirectionalBlock.FACING);
    var lines = new ArrayList<String>();
    CompoundTag ship;
    try {
      ship = ShipTopology.resolve(level, pos);
    } catch (IllegalArgumentException unavailable) {
      return "OFFLINE: no recognized ship bus.\n" + unavailable.getMessage();
    }
    var r = ShipSystems.runtime(level, ship);
    if (ShipSystems.enabled(ship) && r.dirty) ShipSystems.rebuild(level, ship, r, false);
    if (!(ShipSystems.enabled(ship) ? r.blocks : ShipTopology.discover(level, ship)).contains(pos))
      return "DISCONNECTED\nAttach this device to the ship's connected hull.";
    if (!ShipSystems.enabled(ship))
      return "SYSTEMS NOT SET UP\nConnect a generator and an Air Supply, then set up systems at"
          + " the ship controller.";
    var device = equipmentDevice(ship, pos);
    boolean on = device.getBooleanOr("enabled", true);
    lines.add(on ? "CONNECTED | Switch ON" : "Switch OFF (disabled)");
    var system = subsystem(mode);
    int delivered = system == null ? 0 : ShipSystems.grant(r, system);
    if (system != null) {
      int[] requests =
          Arrays.copyOf(ShipSystems.state(ship).getIntArray("requests").orElse(new int[0]), 6);
      lines.add(
          "Power: "
              + delivered
              + " delivered / "
              + requests[system.ordinal()]
              + (system == PowerBus.System.AIR ? " per Air Supply" : "")
              + " requested");
      if (on && requests[system.ordinal()] == 0)
        lines.add("Power allocation is off. Increase this system's allocation.");
      else if (on && delivered == 0)
        lines.add(
            "No power reaches this system. Check fuel, generation and higher-priority loads.");
    }
    var base = WorldStore.pos(ship, "base");
    switch (mode) {
      case "generator" -> {
        lines.add(
            "Output: "
                + (on && !ShipSystems.state(ship).getBooleanOr("supplyDisabled", false)
                    ? FuelSystem.generatorOutput(FuelSystem.tier(level, pos))
                    : 0)
                + " energy/t");
        lines.add(
            "Ship supply: "
                + (ShipSystems.state(ship).getBooleanOr("supplyDisabled", false) ? "OFF" : "ON"));
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
        int share = on ? ShipAir.supplyShare(level, ship, r, pos, delivered) : 0;
        lines.add(
            "This pump: "
                + share
                + " power, "
                + SpatialAir.output(share) * 100 / SpatialAir.FULL_OUTPUT
                + "% output (4 is normal, 8 gives 150%)");
        var outlet = pos.relative(facing);
        if (!WorldStore.bounds(ship).contains(outlet))
          lines.add("OUTLET OUTSIDE SHIP: rotate/place the pump facing a room.");
        else {
          int cell = ShipSystems.local(ship, outlet);
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
      case "camera", "monitor" -> {
        var cameras =
            r.equipment.stream()
                .filter(
                    p -> level.getBlockState(p).is(ModBlocks.CAMERA) && ShipSystems.active(ship, p))
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
      case "local_scanner" -> {
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
    var ship = WorldStore.get(level.getServer()).at(level, at);
    String mode = ModBlocks.equipmentMode(level.getBlockState(at));
    if (!ContentCatalog.equipment(mode).map(d -> d.has("power_allocation")).orElse(false))
      return -1;
    var system = subsystem(mode);
    if (ship == null || system == null) return -1;
    var requests =
        Arrays.copyOf(ShipSystems.state(ship).getIntArray("requests").orElse(new int[0]), 6);
    return requests[system.ordinal()];
  }

  static void equipmentAction(
      ServerPlayer player,
      BlockPos pos,
      ComputerTerminal.Action action,
      boolean reopen,
      int requested) {
    equipmentAccess(player, pos);
    var level = player.level();
    var mode = ModBlocks.equipmentMode(level.getBlockState(pos));
    if (EquipmentMenus.entries(mode).stream()
        .noneMatch(e -> e.verb().equalsIgnoreCase(action.name())))
      throw new IllegalArgumentException("Action does not belong to this equipment");
    if (action == ComputerTerminal.Action.DEVICE_REFRESH) {
      openEquipment(player, pos);
      return;
    }
    var ship = ShipTopology.resolve(level, pos);
    var r = ShipSystems.runtime(level, ship);
    if (!ShipSystems.connected(level, ship).contains(pos))
      throw new IllegalArgumentException("This equipment is disconnected from the ship");
    if (action == ComputerTerminal.Action.DEVICE_VIEW) {
      ScenePanels.equipmentView(player, pos);
      return;
    }
    if (!ShipSystems.enabled(ship))
      throw new IllegalArgumentException("Upgrade systems at the systems controller first");
    if (r.dirty) ShipSystems.rebuild(level, ship, r, false);
    var device = equipmentDevice(ship, pos);
    switch (action) {
      case DEVICE_ENABLE, DEVICE_DISABLE -> {
        device.putBoolean("enabled", action == ComputerTerminal.Action.DEVICE_ENABLE);
        r.power = null;
      }
      case DEVICE_RESET -> {
        device.putBoolean("enabled", true);
        ShipSystems.state(ship).putBoolean("supplyDisabled", false);
        var req =
            Arrays.copyOf(ShipSystems.state(ship).getIntArray("requests").orElse(new int[0]), 6);
        req[0] = 1;
        ShipSystems.state(ship).putIntArray("requests", req);
        ShipSystems.state(ship).putString("profile", "custom");
        r.power = null;
      }
      case DEVICE_ALLOCATE -> {
        if (ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 0)
          throw new IllegalArgumentException(
              "Controls have no power. Select Manual restart at a generator or charged battery.");
        int i = subsystem(mode).ordinal();
        var req =
            Arrays.copyOf(ShipSystems.state(ship).getIntArray("requests").orElse(new int[0]), 6);
        req[i] = requested >= 0 ? requested : (req[i] + 1) % 9;
        ShipSystems.state(ship).putIntArray("requests", req);
        ShipSystems.state(ship).putString("profile", "custom");
        r.power = null;
      }
      case DEVICE_CHANNEL -> ScenePanels.cycleMonitor(player, pos);
      case CLOSE_DOORS, OPEN_DOORS -> {
        if (!ShipSystems.active(r, pos)
            || ShipSystems.power(level, ship, PowerBus.System.CONTROLS) == 0
            || ShipSystems.power(level, ship, PowerBus.System.DOORS) == 0)
          throw new IllegalArgumentException("Enable this device and power controls + doors first");
        r.result =
            SystemsConsole.configure(
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
    WorldStore.get(level.getServer()).requestSave();
    if (reopen) openEquipment(player, pos);
  }

  private EquipmentPanel() {}
}
