package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.Boarding;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Pending;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.*;

/** Which ships, players and areas are busy with a transfer, so two moves never overlap. */
public final class TransferLocks {

  public static boolean spaceSlotFree(MinecraftServer server, int slot) {
    if (slot < 0 || slot > 7000) return false;
    var base = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
    var area =
        new net.minecraft.world.phys.AABB(
            base.getX() - 128, -32, -128, base.getX() + 160, 256, 160);
    return TravelService.pending(server).values().stream()
            .noneMatch(
                p ->
                    p.sourceDimension.equals(TravelService.SPACE.identifier().toString())
                            && area.intersects(p.sourceBounds.box().inflate(128))
                        || p.targetDimension.equals(TravelService.SPACE.identifier().toString())
                            && area.intersects(p.targetBounds.box().inflate(128)))
        && WorldStore.get(server).ships().stream()
            .map(t -> (CompoundTag) t)
            .noneMatch(
                ship ->
                    ship.getStringOr("dimension", "")
                            .equals(TravelService.SPACE.identifier().toString())
                        && area.intersects(WorldStore.bounds(ship).box().inflate(128)));
  }

  public static boolean locked(ServerLevel level, BlockPos pos) {
    if (TravelService.worldMutation()) return false;
    // Terra Harbor is not a transit area: its shell has its own player protection.
    return ShipStorage.locked(level, pos)
        || StationAccess.buildingAt(level, pos)
        || NavigationService.locked(level, pos)
        || physicalAreaBusy(level, new HullScan.Bounds(pos, pos), null);
  }

  static boolean physicalAreaBusy(ServerLevel level, HullScan.Bounds bounds, Pending ignored) {
    String dimension = level.dimension().identifier().toString();
    return TravelService.pending(level.getServer()).values().stream()
        .anyMatch(
            p ->
                p != ignored
                    && (p.sourceDimension.equals(dimension)
                            && p.sourceBounds.box().intersects(bounds.box())
                        || p.targetDimension.equals(dimension)
                            && p.targetBounds.box().intersects(bounds.box())));
  }

  public static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds) {
    return areaBusy(level, bounds, null, null);
  }

  public static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds, UUID recallOwner) {
    return areaBusy(level, bounds, null, recallOwner);
  }

  static boolean areaBusy(
      ServerLevel level, HullScan.Bounds bounds, Pending ignored, UUID recallOwner) {
    return physicalAreaBusy(level, bounds, ignored)
        || ShipStorage.areaBusy(level, bounds, recallOwner)
        || NavigationService.areaBusy(level, bounds);
  }

  public static boolean inTransit(MinecraftServer server, CompoundTag ship) {
    if (ship == null) return false;
    String id = ship.getStringOr("id", "");
    return physicalBusy(server, ship)
        || ShipStorage.active(server, id)
        || NavigationService.pending(server, id);
  }

  public static boolean physicalBusy(MinecraftServer server, CompoundTag ship) {
    if (ship == null) return false;
    String id = ship.getStringOr("id", ""), dimension = ship.getStringOr("dimension", "");
    var bounds = WorldStore.bounds(ship);
    return TravelService.pending(server).values().stream()
        .anyMatch(
            p ->
                !id.isEmpty() && p.ship.equals(id)
                    || p.sourceDimension.equals(dimension)
                        && p.sourceBounds.box().intersects(bounds.box())
                    || p.targetDimension.equals(dimension)
                        && p.targetBounds.box().intersects(bounds.box()));
  }

  public static boolean busy(MinecraftServer server, CompoundTag ship) {
    return inTransit(server, ship)
        || ship != null && Boarding.boarding(server, ship.getStringOr("id", ""));
  }

  public static boolean active(MinecraftServer server) {
    return ShipStorage.active(server) || physicalTransferActive(server);
  }

  static boolean physicalTransferActive(MinecraftServer server) {
    return StationAccess.exclusiveConstruction(server)
        || !WorldStore.get(server).list("stationBoarding").isEmpty()
        || !TravelService.pending(server).isEmpty();
  }

  public static boolean physicalBusy(ServerPlayer player) {
    return TravelService.pending(player.level().getServer()).values().stream()
        .anyMatch(p -> p.crew.containsKey(player.getUUID()));
  }

  public static boolean busy(ServerPlayer player) {
    return Boarding.busy(player) || NavigationService.busy(player) || physicalBusy(player);
  }

  private TransferLocks() {}
}
