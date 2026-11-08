package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.model.air.Atmosphere;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

/**
 * Setting up a ship's systems: checking the generator and Air Supply are connected, then enabling
 * power and air.
 */
public final class SystemsUpgrade {

  public static void upgrade(ServerLevel level, CompoundTag ship) {
    if (ShipSystems.enabled(ship)) return;
    var s = new CompoundTag();
    s.putInt("version", 1);
    s.putString("profile", "cruise");
    s.putIntArray("requests", ShipSystems.defaultRequests());
    s.put("devices", new ListTag());
    ship.put("systems", s);
    var live = ShipSystems.runtime(level, ship);
    live.dirty = true;
    live.air = new int[ShipSystems.grid(ship).cells()];
    Arrays.fill(live.air, -1);
    ShipSystems.rebuild(level, ship, live, true);
    WorldStore.get(level.getServer()).requestSave();
  }

  public static void resizeStation(ServerLevel level, CompoundTag ship, int width, int depth) {
    if (!StationAccess.station(ship))
      throw new IllegalArgumentException("Only stations can expand");
    var previous = ShipSystems.grid(ship);
    var next = new Atmosphere.Grid(width, 32, depth);
    var runtime = ShipSystems.runtime(level, ship);
    if (ShipSystems.enabled(ship) && runtime.dirty)
      ShipSystems.rebuild(level, ship, runtime, false);
    if (ShipSystems.enabled(ship)) {
      int[] air = new int[next.cells()];
      Arrays.fill(air, -1);
      for (int cell = 0; cell < previous.cells(); cell++)
        air[next.index(previous.x(cell), previous.y(cell), previous.z(cell))] = runtime.air[cell];
      ShipSystems.state(ship).putIntArray("air", air);
    }
    ship.putInt("width", width);
    ship.putInt("depth", depth);
    ShipSystems.live(level.getServer()).remove(ship.getStringOr("id", ""));
  }

  public record UpgradeCheck(boolean ready, String message) {}

  /** Diagnose every missing bus connection on demand without loading neighboring terrain. */
  public static UpgradeCheck upgradeCheck(
      ServerLevel level, CompoundTag ship, BlockPos controller) {
    var blocks = ShipSystems.connected(level, ship);
    var helm = WorldStore.pos(ship, "controller");
    String connectionAdvice =
        "Join floors with face-touching walls or pillars leading to the main controller."
            + " Diagonal contact does not count.";
    if (!blocks.contains(controller))
      return new UpgradeCheck(
          false,
          "Controller at "
              + controller.toShortString()
              + " is disconnected from the main controller at "
              + helm.toShortString()
              + ".\n"
              + connectionAdvice);
    var required = List.of(ModBlocks.GENERATOR, ModBlocks.AIR_SUPPLY);
    var missing =
        required.stream()
            .filter(type -> blocks.stream().noneMatch(p -> level.getBlockState(p).is(type)))
            .toList();
    if (missing.isEmpty())
      return new UpgradeCheck(
          true, "Required equipment is connected. Choose Upgrade systems to continue.");

    var bounds = WorldStore.bounds(ship);
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

  private SystemsUpgrade() {}
}
