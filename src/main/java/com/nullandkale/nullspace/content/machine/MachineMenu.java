package com.nullandkale.nullspace.content.machine;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.CourseService;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.MonitorWall;
import com.nullandkale.nullspace.gameplay.systems.ShipSensors;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

public final class MachineMenu extends AbstractContainerMenu {
  public BlockPos at;
  public final Container machine;
  private final Player player;
  public CompoundTag reading = new CompoundTag();
  private int clock, nextEdit, nextPreview, response;
  private String notice = "";

  public MachineMenu(int id, Inventory inventory) {
    this(id, inventory, new SimpleContainer(MachineBlockEntity.SIZE), BlockPos.ZERO);
  }

  public MachineMenu(int id, Inventory inventory, MachineBlockEntity entity) {
    this(id, inventory, entity, entity.getBlockPos());
  }

  private MachineMenu(int id, Inventory inventory, Container machine, BlockPos at) {
    super(MachineMenus.MENU, id);
    this.at = at;
    this.machine = machine;
    player = inventory.player;
    var state = player.level().getBlockState(at);
    boolean generator = state.is(ModBlocks.GENERATOR),
        upgrades =
            generator
                || state.is(ModBlocks.BATTERY)
                || state.is(ModBlocks.ENGINE)
                || state.is(ModBlocks.LOCAL_SCANNER);
    for (int i = 0; i < MachineBlockEntity.SIZE; i++) {
      final int slot = i;
      addSlot(
          new Slot(
              machine,
              i,
              i < 3 ? 250 + i * 20 : i == 9 ? 260 : 140 + (i - 3) * 18,
              i < 3 ? 34 : 104) {
            @Override
            public boolean isActive() {
              if (machine instanceof MachineBlockEntity e)
                return slot < 3 ? e.upgrades() : e.generator();
              String mode = reading.getStringOr("mode", "");
              return slot < 3
                  ? Set.of("generator", "engine", "battery", "local_scanner").contains(mode)
                  : mode.equals("generator");
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
              if (machine instanceof MachineBlockEntity) return machine.canPlaceItem(slot, stack);
              return isActive()
                  && (slot < 3
                      ? ModBlocks.upgradeRank(stack) > 0
                      : slot < 9
                          && stack.has(net.minecraft.core.component.DataComponents.COOKING_FUEL));
            }

            @Override
            public int getMaxStackSize() {
              return slot < 3 ? 1 : 64;
            }

            @Override
            public boolean mayPickup(Player player) {
              return slot >= 3
                  || (machine instanceof MachineBlockEntity e
                      ? e.canRemoveUpgrade()
                      : !reading.getBooleanOr("removalBlocked", false));
            }
          });
    }
    // Same 36 player slots as addStandardInventorySlots, but hidden on machines with no item
    // slots of their own (Air Supply, Camera, Monitor, Docking Base), where they are only noise.
    for (int row = 0; row < 4; row++)
      for (int column = 0; column < 9; column++) {
        int index = row < 3 ? 9 + column + row * 9 : column;
        addSlot(
            new Slot(inventory, index, 126 + column * 18, row < 3 ? 154 + row * 18 : 212) {
              @Override
              public boolean isActive() {
                return machineHasSlots();
              }
            });
      }
  }

  /** Whether this machine has upgrade or fuel slots, and so needs the player inventory. */
  public boolean machineHasSlots() {
    if (machine instanceof MachineBlockEntity e) return e.upgrades() || e.generator();
    return Set.of("generator", "engine", "battery", "local_scanner")
        .contains(reading.getStringOr("mode", ""));
  }

  @Override
  public boolean stillValid(Player p) {
    if (!(p instanceof ServerPlayer)) return true;
    return p.isAlive()
        && p.level().hasChunkAt(at)
        && p.distanceToSqr(at.getX() + .5, at.getY() + .5, at.getZ() + .5) <= 64
        && p.level().getBlockEntity(at) == machine
        && (!(p instanceof ServerPlayer sp)
            || sp.mayInteract(sp.level(), at)
                && StationService.mayUse(sp, at)
                && !TravelService.busy(sp)
                && !TravelService.locked(sp.level(), at));
  }

  @Override
  public ItemStack quickMoveStack(Player p, int index) {
    var slot = slots.get(index);
    if (!slot.hasItem() || !slot.mayPickup(p)) return ItemStack.EMPTY;
    var stack = slot.getItem();
    var original = stack.copy();
    if (index < 10) {
      if (!moveItemStackTo(stack, 10, 46, true)) return ItemStack.EMPTY;
    } else if (!moveItemStackTo(stack, 0, 9, false)) return ItemStack.EMPTY;
    if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
    else slot.setChanged();
    slot.onTake(p, stack);
    return original;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (player instanceof ServerPlayer p && ++clock % 10 == 1 && stillValid(p)) {
      reading = read(p);
      if (ServerPlayNetworking.canSend(p, MachineMenus.View.TYPE))
        ServerPlayNetworking.send(p, new MachineMenus.View(containerId, reading));
    }
  }

  private CompoundTag read(ServerPlayer p) {
    var data = new CompoundTag();
    var entity = (MachineBlockEntity) machine;
    var ship = WorldStore.get(p.level().getServer()).at(p.level(), at);
    data.putLong("at", at.asLong());
    data.putInt("response", response);
    data.putInt("fuelHere", entity.available());
    data.putBoolean(
        "connected", ship != null && ShipSystems.connected(p.level(), ship).contains(at));
    data.putBoolean(
        "restart",
        ship != null && ship.getCompoundOrEmpty("systems").getBooleanOr("supplyDisabled", false));
    data.putString("name", entity.label);
    data.putString(
        "mode",
        net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(entity.getBlockState().getBlock())
            .getPath());
    data.putInt("tier", entity.tier());
    data.putString("upgradeBenefit", upgradeBenefit(data.getStringOr("mode", ""), entity.tier()));
    data.putString(
        "nextUpgradeBenefit",
        entity.tier() >= 3
            ? "Top tier: 3/3"
            : upgradeBenefit(data.getStringOr("mode", ""), entity.tier() + 1));
    data.putBoolean("removalBlocked", !entity.canRemoveUpgrade());
    data.putString(
        "facing", entity.getBlockState().getValue(HorizontalDirectionalBlock.FACING).getName());
    data.putInt("outputMax", FuelSystem.generatorOutput(entity.tier()));
    data.putInt("rate", 4 * (1 + entity.tier()));
    data.putInt("burn", entity.burn());
    data.putBoolean("enabled", ship == null || ShipSystems.active(ship, at));
    data.putBoolean("canConfigure", ship == null || StationService.builder(p, ship));
    data.putInt("allocation", ShipSystems.requestedAllocation(p.level(), at));
    data.putString(
        "status",
        ship == null
            ? "Connect this block to a ship controller on a registered pad"
            : ShipSystems.equipmentStatus(p.level(), at));
    if (ship != null) {
      data.putInt("fuel", FuelSystem.available(p.level(), ship));
      data.putString(
          "region",
          p.level().dimension().equals(TravelService.SPACE)
              ? LocalSpaces.address(ship)
              : java.util.Objects.requireNonNullElse(
                  PlanetSurfaces.address(p.level()), LocalSpaces.HOME));
      data.putInt("energy", ShipSystems.deviceEnergy(ship, at));
      int flow =
          ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
              .map(t -> (CompoundTag) t)
              .filter(
                  t ->
                      t.getLongOr("position", Long.MIN_VALUE)
                          == at.subtract(WorldStore.pos(ship, "base")).asLong())
              .mapToInt(t -> t.getIntOr("flow", 0))
              .sum();
      data.putInt("flow", data.getBooleanOr("enabled", true) ? flow : 0);
      data.putInt("capacity", FuelSystem.capacity(p.level(), at));
      var power = ShipSystems.powerDisplay(p.level(), ship);
      data.putInt("output", power[0]);
      data.putInt("demand", power[1]);
      data.putInt("charging", power[2]);
      data.putInt("powerRequested", power[4]);
      data.putInt("powerCapacity", power[5]);
      var block = entity.getBlockState();
      String state =
          !ShipSystems.connected(p.level(), ship).contains(at)
              ? "Disconnected: join this block to the hull at " + at.toShortString()
              : !ShipSystems.active(ship, at) ? "Disabled" : "Ready";
      if (state.equals("Ready") && block.is(ModBlocks.GENERATOR))
        state =
            ship.getCompoundOrEmpty("systems").getBooleanOr("supplyDisabled", false)
                ? "Ship supply is off. Use Restart ship supply below."
                : entity.available() == 0
                    ? entity.blockedReturn()
                        ? "Empty the return slot before burning container fuel"
                        : "Load any furnace fuel in these slots"
                    : power[0] == 0
                        ? "Idle: no electrical demand or charging"
                        : "Supplying ship; surplus charges batteries";
      if (block.is(ModBlocks.ENGINE)) {
        var exhaust = at.relative(block.getValue(HorizontalDirectionalBlock.FACING));
        if (state.equals("Ready")) {
          if (!p.level().getBlockState(exhaust).isAir())
            state = "Exhaust blocked at " + exhaust.toShortString();
          else if (data.getIntOr("fuel", 0) == 0)
            state = "No shared fuel: load a connected generator";
          else if (ShipSystems.power(p.level(), ship, PowerBus.System.CONTROLS) == 0)
            state = "No controller power";
        }
        data.putInt("thrustMax", Math.min(8, 2 * (1 + entity.tier())));
        data.putInt(
            "thrust",
            state.equals("Ready")
                ? Math.min(data.getIntOr("thrustMax", 0), ship.getIntOr("thrustDemand", 0))
                : 0);
      }
      if (block.is(ModBlocks.AIR_SUPPLY)) {
        var outlet = at.relative(block.getValue(HorizontalDirectionalBlock.FACING));
        var room = ShipSystems.roomAt(p.level(), ship, outlet);
        int oxygen = room == null ? 0 : Math.clamp(room.oxygenPercent(), 0, 100);
        data.putInt("oxygen", oxygen);
        data.putInt("roomCells", room == null ? 0 : room.volume());
        data.putInt(
            "roomFloors",
            room == null ? 0 : ShipSystems.roomFloors(p.level(), ship, room.number()));
        data.putInt("leaks", room == null ? 0 : room.leaks());
        var supply = ShipSystems.airSupplyReading(p.level(), ship, at);
        data.putInt("supplyOutput", supply[0]);
        data.putInt("leakCovered", supply[1]);
        data.putBoolean("breathable", oxygen >= Atmosphere.BREATHABLE * 100 / Atmosphere.FULL);
        boolean sealed =
            new HullScan.LivePressure(p.level(), WorldStore.bounds(ship)).breathable(outlet);
        if (state.equals("Ready")) {
          if (HullScan.seals(p.level(), outlet))
            state = "Outlet blocked at " + outlet.toShortString();
          else if (!sealed) state = "Leaking room; Show leak highlights the path";
          else if (ShipSystems.power(p.level(), ship, PowerBus.System.AIR) == 0)
            state = "No air power; load generator or enable supply";
          else state = "Sealed room | Pump powered";
        }
      }
      if (block.is(ModBlocks.WEAPON) && state.equals("Ready")) {
        var muzzle = at.relative(block.getValue(HorizontalDirectionalBlock.FACING));
        long cooldown = Math.max(0, ship.getLongOr("weaponReadyAt", 0) - p.level().getGameTime());
        if (!p.level().getBlockState(muzzle).isAir())
          state = "Muzzle blocked at " + muzzle.toShortString();
        else if (ShipSystems.power(p.level(), ship, PowerBus.System.WEAPONS) == 0)
          state = "No weapon power";
        else if (cooldown > 0) state = "Recharging: " + cooldown + " ticks";
        else state = "Ready | Fire from Pilot controls in space";
      }
      if (block.is(ModBlocks.BATTERY) && state.equals("Ready"))
        state =
            flow > 0
                ? "Charging: +" + flow + " / tick"
                : flow < 0 ? "Discharging: " + flow + " / tick" : "Idle: no charge transfer";
      data.putString("status", state);
      if (entity.getBlockState().is(ModBlocks.LOCAL_SCANNER)) {
        var list = new ListTag();
        boolean far = entity.tier() > 0;
        boolean powered = ShipSensors.powered(p.level(), ship, at);
        data.putBoolean("selectable", powered);
        data.putBoolean("far", far);
        data.putInt("range", 1024 << entity.tier());
        if (powered)
          for (var d :
              ShipDestination.navigationCatalog(p, ship).stream()
                  .filter(d -> d.kind() != ShipDestination.Kind.PORT)
                  .limit(512)
                  .toList()) {
            var row = new CompoundTag();
            row.putString("id", d.id());
            row.putString("name", d.name());
            row.putString("region", d.region());
            row.putString("kind", d.kind().name());
            row.putString("state", d.state());
            list.add(row);
          }
        data.put("destinations", list);
        var selected = ShipDestination.selected(ship);
        data.putString("destination", selected == null ? "None" : selected.name());
        data.putString(
            "status",
            powered
                ? "Destination catalog online" + " | Range " + (1024 << entity.tier())
                : !ShipSystems.connected(p.level(), ship).contains(at)
                    ? "Scanner disconnected: join it to the hull"
                    : !ShipSystems.active(ship, at)
                        ? "Scanner is Off. Turn Device On above."
                        : "No sensor power. Load a generator or restart ship supply.");
      }
      if (entity.getBlockState().is(ModBlocks.CAMERA)
          || entity.getBlockState().is(ModBlocks.MONITOR)) {
        var cameras = new ListTag();
        for (var pos :
            ShipSystems.connected(p.level(), ship).stream()
                .filter(pos -> p.level().getBlockState(pos).is(ModBlocks.CAMERA))
                .sorted(Comparator.comparingLong(BlockPos::asLong))
                .toList()) {
          var row = new CompoundTag();
          var camera = p.level().getBlockEntity(pos);
          row.putLong("at", pos.asLong());
          row.putString(
              "name",
              camera instanceof MachineBlockEntity e && !e.label.isBlank()
                  ? e.label
                  : "Camera " + pos.toShortString());
          cameras.add(row);
        }
        data.put("cameras", cameras);
        data.putString("feed", entity.camera);
        var tiles = MonitorWall.tiles(p.level(), at);
        data.putInt("tiles", tiles.size());
        if (state.equals("Ready")
            && ShipSystems.power(p.level(), ship, PowerBus.System.CAMERAS) == 0)
          data.putString("status", "No camera power: load generator or allocate power at a camera");
      }
      if (entity.getBlockState().is(ModBlocks.DOCKING_BASE)) {
        var panel = DockingService.panel(p.level().getServer(), ship, at);
        for (var key : panel.keySet()) data.put(key, panel.get(key));
        try {
          data.putString("status", DockingService.status(p, ship, at));
        } catch (IllegalArgumentException problem) {
          data.putString("status", problem.getMessage());
        }
      }
    }
    if (!notice.isEmpty()) data.putString("notice", notice);
    return data;
  }

  private static String upgradeBenefit(String mode, int tier) {
    return switch (mode) {
      case "generator" -> "Unit output up to " + FuelSystem.generatorOutput(tier) + "/tick";
      case "engine" ->
          "Max thrust " + Math.min(8, 2 * (1 + tier)) + "; travel uses the best engine tier";
      case "battery" -> "Capacity " + 1200 * (1 + tier) + "; transfer " + 4 * (1 + tier) + "/tick";
      case "local_scanner" -> "Range " + (1024 << tier) + "; interstellar travel";
      default -> "";
    };
  }

  public void edit(ServerPlayer p, int action, String value) {
    if (!stillValid(p)) return;
    int tick = p.level().getServer().getTickCount();
    if (action == 9) {
      if (tick < nextPreview) return;
      nextPreview = tick + 20;
    } else {
      response++;
      if (tick < nextEdit) {
        notice = "Please wait a moment, then try again";
        reply(p);
        return;
      }
      nextEdit = tick + 2;
    }
    var entity = (MachineBlockEntity) machine;
    var block = entity.getBlockState();
    boolean allowed =
        switch (action) {
          case 0, 1, 2 -> true;
          case 3, 5 ->
              block.is(ModBlocks.ENGINE)
                  || block.is(ModBlocks.WEAPON)
                  || block.is(ModBlocks.AIR_SUPPLY);
          case 4 -> block.is(ModBlocks.AIR_SUPPLY);
          case 6, 9 -> block.is(ModBlocks.CAMERA) || block.is(ModBlocks.MONITOR);
          case 7 -> block.is(ModBlocks.MONITOR);
          case 8 -> block.is(ModBlocks.LOCAL_SCANNER);
          case 10, 11, 12, 13 -> block.is(ModBlocks.DOCKING_BASE);
          case 14 -> block.is(ModBlocks.GENERATOR) || block.is(ModBlocks.BATTERY);
          case 15 -> ShipSystems.requestedAllocation(p.level(), at) >= 0;
          default -> false;
        };
    if (!allowed) {
      notice = "This action does not belong to this block";
      reply(p);
      return;
    }
    notice = "";
    var ship = WorldStore.get(p.level().getServer()).at(p.level(), at);
    if (ship != null
        && !StationService.builder(p, ship)
        && (action == 0 || action == 2 || action == 14 || action == 15)) {
      notice = "Only a station builder can change this device";
      reply(p);
      return;
    }
    try {
      switch (action) {
        case 0 -> {
          if (ship != null) {
            boolean enabled = !ShipSystems.active(ship, at);
            for (var tile :
                entity.getBlockState().is(ModBlocks.MONITOR)
                    ? MonitorWall.tiles(p.level(), at)
                    : List.of(at)) ShipSystems.setEnabled(p.level(), ship, tile, enabled);
          } else notice = "Connect this device to a ship controller before switching it";
        }
        case 15 -> {
          if (!value.equals("-1") && !value.equals("1")) notice = "Choose power from 0 to 8";
          else
            ShipSystems.allocatePower(
                p,
                at,
                Math.clamp(
                    ShipSystems.requestedAllocation(p.level(), at) + Integer.parseInt(value),
                    0,
                    8));
        }
        case 1 -> {
          if (entity.getBlockState().is(ModBlocks.DOCKING_BASE)) StationService.open(p, at, "");
          else ShipSystems.openEquipment(p, at);
          return;
        }
        case 2 -> {
          notice = "Name saved";
          for (var tile :
              entity.getBlockState().is(ModBlocks.MONITOR)
                  ? MonitorWall.tiles(p.level(), at)
                  : List.of(at))
            if (p.level().getBlockEntity(tile) instanceof MachineBlockEntity machine) {
              machine.label = value.replaceAll("[\\p{Cntrl}]", "").strip();
              if (machine.label.length() > 32) machine.label = machine.label.substring(0, 32);
              machine.setChanged();
            }
          if (ship != null) {
            ShipSystems.changed(p.level(), at, false);
            WorldStore.get(p.level().getServer()).requestSave();
          }
        }
        case 3, 4, 5 -> {
          var target =
              at.relative(entity.getBlockState().getValue(HorizontalDirectionalBlock.FACING));
          if (action == 4 && ship != null) {
            if (new HullScan.LivePressure(p.level(), WorldStore.bounds(ship)).breathable(target))
              notice = "No leak in this outlet's room";
            else {
              ShipDiagnostics.highlight(
                  p,
                  HullScan.leakTrace(
                      p.level(),
                      WorldStore.bounds(ship),
                      ShipSystems.connected(p.level(), ship),
                      target));
              notice = "Leak path highlighted from " + target.toShortString();
            }
            break;
          }
          p.level()
              .sendParticles(
                  net.minecraft.core.particles.ParticleTypes.END_ROD,
                  target.getX() + .5,
                  target.getY() + .5,
                  target.getZ() + .5,
                  20,
                  .4,
                  .4,
                  .4,
                  .01);
        }
        case 6 -> {
          p.closeContainer();
          SceneService.machineView(p, at);
          return;
        }
        case 7 -> {
          if (ship != null) {
            MonitorWall.select(p.level(), ship, at, value);
            notice = "Camera feed changed";
          } else notice = "Connect this monitor to the ship first";
        }
        case 8 -> {
          if (ship == null) notice = "Scanner disconnected: join it to the ship hull";
          else if (!ShipSensors.powered(p.level(), ship, at))
            notice = "Scanner needs to be On with sensor power from a generator";
          else {
            var chosen =
                ShipDestination.navigationCatalog(p, ship).stream()
                    .filter(d -> d.id().equals(value))
                    .findFirst();
            if (chosen.isEmpty())
              notice = "Destination changed or is out of scanner range. Choose a visible place.";
            else {
              if (CourseService.active(ship))
                notice = "Disengage the current course before changing destination";
              else {
                ShipDestination.select(p, ship, chosen.get());
                ship.remove("markedDestination");
                notice = "Destination set: " + chosen.get().name();
              }
            }
          }
        }
        case 9 -> SceneService.preview(p, at);
        case 14 -> {
          if (ship == null) notice = "Connect this generator or battery to the ship first";
          else {
            ShipSystems.restartSupply(p, at);
            notice = "Ship supply and controls restarted; fuel and charge preserved";
          }
        }
        case 10, 11, 12, 13 -> {
          if (ship != null)
            notice =
                DockingService.perform(
                    p,
                    ship,
                    at,
                    action == 10
                        ? "DOCK_ACCEPT"
                        : action == 11
                            ? "DOCK_DECLINE"
                            : action == 12 ? "DOCK_CANCEL" : "DOCK_UNDOCK");
        }
        default -> {}
      }
      reading = read(p);
    } catch (IllegalArgumentException error) {
      notice = ShipMessages.failure(error);
      reading = read(p);
    }
    if (!notice.isBlank() && action != 9)
      NullSpace.LOGGER.info(
          "Machine action {} at {} by {}: {}",
          action,
          at.toShortString(),
          p.getPlainTextName(),
          notice);
    if (ServerPlayNetworking.canSend(p, MachineMenus.View.TYPE))
      ServerPlayNetworking.send(p, new MachineMenus.View(containerId, reading));
  }

  private void reply(ServerPlayer p) {
    reading = read(p);
    if (ServerPlayNetworking.canSend(p, MachineMenus.View.TYPE))
      ServerPlayNetworking.send(p, new MachineMenus.View(containerId, reading));
  }
}
