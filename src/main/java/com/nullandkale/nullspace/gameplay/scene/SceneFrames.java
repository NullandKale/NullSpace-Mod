package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets.Frame;
import com.nullandkale.nullspace.gameplay.scene.SceneService.Lease;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.model.space.PlanetSky;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Assembling one scene frame: background, nearby ship geometry and players. */
public final class SceneFrames {

  static ListTag players(ServerLevel level, BlockPos base, net.minecraft.world.phys.AABB bounds) {
    var result = new ListTag();
    for (var player : level.players()) {
      if (result.size() >= 32) break;
      if (!player.isAlive()
          || player.isSpectator()
          || player.isInvisible()
          || !bounds.intersects(player.getBoundingBox())) continue;
      var p = new CompoundTag();
      p.putString("id", player.getUUID().toString());
      p.putDouble("x", player.getX() - base.getX());
      p.putDouble("y", player.getY() - base.getY());
      p.putDouble("z", player.getZ() - base.getZ());
      p.putFloat("bodyYaw", player.yBodyRot);
      p.putFloat("headYaw", player.getYHeadRot() - player.yBodyRot);
      p.putFloat("pitch", player.getXRot());
      p.putBoolean("crouching", player.isCrouching());
      p.putFloat("age", player.tickCount);
      p.putFloat("walk", player.tickCount * .3f);
      p.putFloat("speed", (float) Math.min(1, player.getDeltaMovement().horizontalDistance() * 4));
      for (var slot :
          List.of(
              net.minecraft.world.entity.EquipmentSlot.HEAD,
              net.minecraft.world.entity.EquipmentSlot.CHEST,
              net.minecraft.world.entity.EquipmentSlot.LEGS,
              net.minecraft.world.entity.EquipmentSlot.FEET))
        p.putString(
            slot.getName(),
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(player.getItemBySlot(slot).getItem())
                .toString());
      result.add(p);
    }
    return result;
  }

  static void geometry(CompoundTag subject, SceneSnapshots.Snapshot snapshot) {
    subject.putString("geometryId", snapshot.id());
    subject.putLong("geometryRevision", snapshot.revision());
    subject.put("geometry", snapshot.geometry());
    subject.putBoolean("ready", snapshot.ready());
  }

  static void send(ServerPlayer player, Lease lease) {
    if (!SceneService.valid(player, lease)) return;
    var level = player.level();
    var ship =
        WorldStore.get(level.getServer())
            .at(level, lease.freeCamera == null ? lease.console : lease.freeCamera);
    var result = new CompoundTag();
    result.putString("dimension", lease.dimension);
    result.putBoolean("machinePreview", lease.machinePreview);
    result.putBoolean("returnMachine", lease.returnMachine || lease.returnEquipment);
    result.putLong("console", lease.console.asLong());
    result.putLong("time", level.getServer().getTickCount());
    result.putLong("seed", SceneCatalog.get(level.getServer()).getLongOr("seed", 79419843L));
    result.putInt("selection", lease.selection);
    result.putBoolean(
        "fixedCamera",
        lease.freeCamera == null && level.getBlockState(lease.console).is(ModBlocks.CAMERA));
    if (lease.freeCamera == null
        && ship != null
        && !ShipSystems.connected(level, ship).contains(lease.console)) {
      result.putString("status", "DISCONNECTED EQUIPMENT");
      SceneService.deliver(player, new Frame(lease.id, ++lease.revision, result));
      return;
    }
    if (lease.freeCamera == null
        && ship != null
        && ShipSystems.enabled(ship)
        && (ShipSystems.power(level, ship, PowerBus.System.CAMERAS) == 0
            || !ShipSystems.active(ship, lease.console))) {
      result.putString("status", "NO POWER");
      SceneService.deliver(player, new Frame(lease.id, ++lease.revision, result));
      return;
    }
    boolean space = level.dimension().equals(TravelService.SPACE);
    result.putBoolean("universe", space && ship != null);
    var subjects = new ListTag();
    BlockPos camera = lease.freeCamera;
    if (camera == null && ship != null) {
      var cameras =
          ShipSystems.connected(level, ship).stream()
              .filter(
                  p ->
                      level.getBlockState(p).is(ModBlocks.CAMERA)
                          && (!ShipSystems.enabled(ship) || ShipSystems.active(ship, p)))
              .sorted(Comparator.comparingLong(BlockPos::asLong))
              .toList();
      if (!cameras.isEmpty()) {
        boolean fixed = level.getBlockState(lease.console).is(ModBlocks.CAMERA);
        result.putBoolean("fixedCamera", fixed);
        int channel =
            fixed ? cameras.indexOf(lease.console) : Math.floorMod(lease.selection, cameras.size());
        if (!fixed && !lease.pinnedCamera.isEmpty()) {
          channel = -1;
          for (int i = 0; i < cameras.size(); i++)
            if (lease.pinnedCamera.equals("id:" + ShipSystems.deviceId(ship, cameras.get(i)))
                || lease.pinnedCamera.equals(Long.toString(cameras.get(i).asLong()))) channel = i;
        }
        if (channel < 0) {
          result.putString("status", "CAMERA DISABLED");
          SceneService.deliver(player, new Frame(lease.id, ++lease.revision, result));
          return;
        }
        lease.selection = channel;
        result.putInt("selection", channel);
        if (ShipSystems.enabled(ship)
            && channel >= ShipSystems.power(level, ship, PowerBus.System.CAMERAS)) {
          result.putString("status", "NO POWER for camera " + (channel + 1));
          SceneService.deliver(player, new Frame(lease.id, ++lease.revision, result));
          return;
        }
        camera = cameras.get(channel);
      }
    }
    if (camera == null && ship == null && level.getBlockState(lease.console).is(ModBlocks.CAMERA))
      camera = lease.console;
    if (camera == null) {
      result.putString("status", "Install a connected camera. Left/Right changes feed.");
      SceneService.deliver(player, new Frame(lease.id, ++lease.revision, result));
      return;
    }
    var facing =
        level.getBlockState(camera).hasProperty(HorizontalDirectionalBlock.FACING)
            ? level.getBlockState(camera).getValue(HorizontalDirectionalBlock.FACING)
            : Direction.SOUTH;
    Vec3 eye = Vec3.atCenterOf(camera).add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(.65));
    float yaw = lease.freeCamera == null ? facing.toYRot() : lease.yaw, pitch = lease.pitch;
    result.putFloat("cameraYaw", yaw);
    result.putFloat("cameraPitch", pitch);
    result.putLong("source", camera.asLong());
    var origin = ship != null ? WorldStore.pos(ship, "base") : camera.offset(-16, -16, -16);
    var cameraLocal = eye.subtract(Vec3.atLowerCornerOf(origin));
    result.putDouble("cameraX", cameraLocal.x);
    result.putDouble("cameraY", cameraLocal.y);
    result.putDouble("cameraZ", cameraLocal.z);
    if (space && ship != null) {
      result.putString("observer", ship.getStringOr("id", ""));
      var nearby =
          WorldStore.get(level.getServer()).ships().stream()
              .map(t -> (CompoundTag) t)
              .filter(
                  t ->
                      t.getStringOr("dimension", "").equals(lease.dimension)
                          && LocalSpaces.together(ship, t))
              .sorted(Comparator.comparingDouble(t -> DockingManeuver.sceneDistance(ship, t)))
              .limit(SceneService.MAX_SUBJECTS)
              .toList();
      for (var other : nearby) SceneService.addSubject(subjects, level, other);
    } else {
      var item = new CompoundTag();
      item.putString("id", "world-" + origin.asLong());
      item.putString("name", "Camera");
      item.putLong("base", origin.asLong());
      geometry(
          item,
          SceneSnapshots.capture(
              level, origin, new HullScan.Bounds(origin, origin.offset(31, 31, 31))));
      item.put(
          "players", players(level, origin, new net.minecraft.world.phys.AABB(camera).inflate(48)));
      subjects.add(item);
      result.putString("observer", "world-" + origin.asLong());
    }
    if (!space) {
      result.put("terrain", TerrainService.collect(player, camera, origin));
      result.put("entities", TerrainService.entities(level, origin, camera));
    }
    result.put("subjects", subjects);
    if (!space) PlanetSky.decorate(result, level, origin);
    result.putString(
        "status",
        (subjects.stream().anyMatch(t -> !((CompoundTag) t).getBooleanOr("ready", true))
                ? "LOADING"
                : "LIVE")
            + " | feed "
            + (lease.selection + 1));
    SceneService.deliver(player, new Frame(lease.id, ++lease.revision, result));
  }

  static void background(ServerPlayer player) {
    var level = player.level();
    if (TransferLocks.physicalBusy(player) || !ServerPlayNetworking.canSend(player, Frame.TYPE))
      return;
    if (!level.dimension().equals(TravelService.SPACE)) {
      if (PlanetSurfaces.address(level) == null) return;
      var data = new CompoundTag();
      data.putBoolean("background", true);
      data.putString("dimension", level.dimension().identifier().toString());
      data.putString("observer", "surface");
      var subjects = new ListTag();
      var subject = new CompoundTag();
      subject.putString("id", "surface");
      var origin = new BlockPos(0, 65, 0);
      subject.putLong("base", origin.asLong());
      subjects.add(subject);
      data.put("subjects", subjects);
      if (PlanetSky.decorate(data, level, origin))
        SceneService.deliver(
            player,
            new Frame(
                UUID.nameUUIDFromBytes(
                    (level.dimension().identifier() + ":surface")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                level.getServer().getTickCount(),
                data));
      return;
    }
    var ship = SceneService.observer(player);
    if (ship == null) return;
    var data = new CompoundTag();
    data.putBoolean("background", true);
    data.putBoolean("universe", true);
    data.putString("dimension", level.dimension().identifier().toString());
    data.putString("observer", ship.getStringOr("id", ""));
    var subjects = new ListTag();
    WorldStore.get(level.getServer()).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(
            t ->
                t.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
                    && LocalSpaces.together(ship, t))
        .sorted(Comparator.comparingDouble(t -> DockingManeuver.sceneDistance(ship, t)))
        .limit(SceneService.MAX_SUBJECTS)
        .forEach(t -> SceneService.addSubject(subjects, level, t));
    data.put("subjects", subjects);
    data.putLong("seed", SceneCatalog.get(level.getServer()).getLongOr("seed", 79419843));
    SceneService.deliver(
        player,
        new Frame(
            UUID.fromString(ship.getStringOr("id", "")), level.getServer().getTickCount(), data));
  }

  private SceneFrames() {}
}
