package com.nullandkale.nullspace.scene;

import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/**
 * Bounded observation leases. Scene snapshots never change the player's chunk center or spawn fake
 * players.
 */
public final class SceneService {
  public static final int MAX_SUBJECTS = 4, INTERVAL = 20;

  public record Definition(CompoundTag data) implements CustomPacketPayload {
    public static final Type<Definition> TYPE =
        new Type<>(Identifier.parse("nullspace:space_definition"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Definition> CODEC =
        StreamCodec.of((b, p) -> b.writeNbt(p.data), b -> new Definition(b.readNbt()));

    @Override
    public Type<Definition> type() {
      return TYPE;
    }
  }

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, String>>
      SENT_DEFINITIONS = new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, String> definitions(MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server)
        .service(SENT_DEFINITIONS, HashMap::new);
  }

  private static void deliver(ServerPlayer player, Frame frame) {
    if (frame.scene.getBooleanOr("universe", false)) {
      var definition =
          SceneCatalog.region(
              player.level().getServer(),
              frame.scene.getStringOr(
                  "region",
                  com.nullandkale.nullspace.navigation.LocalSpaces.address(observer(player))));
      String key = definition.getStringOr("id", "") + ":" + definition.getLongOr("revision", 0);
      if (!key.equals(definitions(player.level().getServer()).get(player.getUUID()))) {
        SpaceDefinitionCodec.decode(definition);
        ServerPlayNetworking.send(player, new Definition(definition.copy()));
        definitions(player.level().getServer()).put(player.getUUID(), key);
      }
      frame.scene.remove("catalog");
      frame.scene.putString("sceneId", definition.getStringOr("id", ""));
      frame.scene.putLong("sceneRevision", definition.getLongOr("revision", 0));
      frame.scene.putDouble(
          "simulationTime", player.level().getServer().overworld().getGameTime() / 20.0);
    }
    ServerPlayNetworking.send(player, frame);
  }

  public record Frame(UUID lease, int revision, CompoundTag scene) implements CustomPacketPayload {
    public static final Type<Frame> TYPE = new Type<>(Identifier.parse("nullspace:scene_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Frame> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.lease);
              b.writeInt(p.revision);
              b.writeNbt(p.scene);
            },
            b -> new Frame(b.readUUID(), b.readInt(), b.readNbt()));

    @Override
    public Type<Frame> type() {
      return TYPE;
    }
  }

  public record KeepAlive(UUID lease, boolean close, int selection) implements CustomPacketPayload {
    public static final Type<KeepAlive> TYPE =
        new Type<>(Identifier.parse("nullspace:scene_lease"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KeepAlive> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.lease);
              b.writeBoolean(p.close);
              b.writeVarInt(p.selection);
            },
            b -> new KeepAlive(b.readUUID(), b.readBoolean(), b.readVarInt()));

    @Override
    public Type<KeepAlive> type() {
      return TYPE;
    }
  }

  private static final class Lease {
    final UUID id = UUID.randomUUID();
    final String dimension;
    final BlockPos console;
    final BlockPos freeCamera;
    final float yaw, pitch;
    int expires, revision, selection;

    Lease(ServerPlayer player, BlockPos console, BlockPos freeCamera, float yaw, float pitch) {
      dimension = player.level().dimension().identifier().toString();
      this.console = console;
      this.freeCamera = freeCamera;
      this.yaw = yaw;
      this.pitch = pitch;
    }
  }

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, Lease>> LEASES =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, Lease> leases(MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(LEASES, HashMap::new);
  }

  public static ScenePose pose(CompoundTag ship) {
    return ShipRepository.pose(ship);
  }

  public static void open(ServerPlayer player, BlockPos console) {
    if (!player.level().hasChunkAt(console)
        || !(player.level().getBlockState(console).is(ShipBlocks.MONITOR)
            || player.level().getBlockState(console).is(ShipBlocks.CAMERA))
        || player.distanceToSqr(Vec3.atCenterOf(console)) > 64
        || !player.mayInteract(player.level(), console)
        || TravelService.busy(player)
        || TravelService.locked(player.level(), console))
      throw new IllegalArgumentException("Camera console unavailable, out of range, or in transit");
    var lease = new Lease(player, console, null, 0, 0);
    var owner = ShipStore.get(player.level().getServer()).at(player.level(), console);
    if (owner != null)
      lease.selection =
          owner
              .getCompoundOrEmpty("monitorChannels")
              .getIntOr(Long.toString(console.subtract(ShipStore.pos(owner, "base")).asLong()), 0);
    lease.expires = player.level().getServer().getTickCount() + 100;
    leases(player.level().getServer()).put(player.getUUID(), lease);
    send(player, lease);
  }

  private static boolean valid(ServerPlayer player, Lease lease) {
    return player.isAlive()
        && player.level().dimension().identifier().toString().equals(lease.dimension)
        && player.level().getServer().getTickCount() <= lease.expires
        && !TravelService.busy(player)
        && (lease.freeCamera != null
                && player
                    .permissions()
                    .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
            || lease.freeCamera == null
                && player.distanceToSqr(Vec3.atCenterOf(lease.console)) <= 64
                && player.level().hasChunkAt(lease.console)
                && (player.level().getBlockState(lease.console).is(ShipBlocks.MONITOR)
                    || player.level().getBlockState(lease.console).is(ShipBlocks.CAMERA))
                && player.mayInteract(player.level(), lease.console)
                && !TravelService.locked(player.level(), lease.console));
  }

  private static CompoundTag subject(ServerLevel level, CompoundTag ship) {
    var base = ShipStore.pos(ship, "base");
    var data = new CompoundTag();
    data.putString("id", ship.getStringOr("id", ""));
    data.putString("name", ship.getStringOr("home", "Ship"));
    data.putLong("base", base.asLong());
    var pose = pose(ship);
    data.putDouble("x", pose.position().x);
    data.putDouble("y", pose.position().y);
    data.putDouble("z", pose.position().z);
    data.putDouble("yaw", ship.getDoubleOr("simYaw", 0));
    data.putDouble("pitch", ship.getDoubleOr("simPitch", 0));
    data.putDouble("roll", ship.getDoubleOr("simRoll", 0));
    geometry(data, SceneSnapshots.capture(level, base, ShipStore.bounds(ship)));
    data.put(
        "players",
        players(
            level,
            base,
            ShipStore.bounds(ship)
                .box()
                .inflate(level.dimension().equals(TravelService.SPACE) ? 72 : 2)));
    return data;
  }

  private static ListTag players(
      ServerLevel level, BlockPos base, net.minecraft.world.phys.AABB bounds) {
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

  private static void geometry(CompoundTag subject, SceneSnapshots.Snapshot snapshot) {
    subject.putString("geometryId", snapshot.id());
    subject.putLong("geometryRevision", snapshot.revision());
    subject.put("geometry", snapshot.geometry());
    subject.putBoolean("ready", snapshot.ready());
  }

  private static void send(ServerPlayer player, Lease lease) {
    if (!valid(player, lease)) return;
    var level = player.level();
    var ship =
        ShipStore.get(level.getServer())
            .at(level, lease.freeCamera == null ? lease.console : lease.freeCamera);
    var result = new CompoundTag();
    result.putString("dimension", lease.dimension);
    result.putLong("time", level.getServer().getTickCount());
    result.putLong("seed", SceneCatalog.get(level.getServer()).getLongOr("seed", 79419843L));
    result.putInt("selection", lease.selection);
    result.putBoolean(
        "fixedCamera",
        lease.freeCamera == null && level.getBlockState(lease.console).is(ShipBlocks.CAMERA));
    if (lease.freeCamera == null
        && ship != null
        && !ShipSystems.connected(level, ship).contains(lease.console)) {
      result.putString("status", "DISCONNECTED EQUIPMENT");
      deliver(player, new Frame(lease.id, ++lease.revision, result));
      return;
    }
    if (lease.freeCamera == null
        && ship != null
        && ShipSystems.enabled(ship)
        && (ShipSystems.power(level, ship, PowerBus.System.CAMERAS) == 0
            || !ShipSystems.active(ship, lease.console))) {
      result.putString("status", "NO POWER");
      deliver(player, new Frame(lease.id, ++lease.revision, result));
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
                      level.getBlockState(p).is(ShipBlocks.CAMERA)
                          && (!ShipSystems.enabled(ship) || ShipSystems.active(ship, p)))
              .sorted(Comparator.comparingLong(BlockPos::asLong))
              .toList();
      if (!cameras.isEmpty()) {
        boolean fixed = level.getBlockState(lease.console).is(ShipBlocks.CAMERA);
        result.putBoolean("fixedCamera", fixed);
        int channel =
            fixed ? cameras.indexOf(lease.console) : Math.floorMod(lease.selection, cameras.size());
        if (channel < 0) {
          result.putString("status", "CAMERA DISABLED");
          deliver(player, new Frame(lease.id, ++lease.revision, result));
          return;
        }
        lease.selection = channel;
        result.putInt("selection", channel);
        if (ShipSystems.enabled(ship)
            && channel >= ShipSystems.power(level, ship, PowerBus.System.CAMERAS)) {
          result.putString("status", "NO POWER for camera " + (channel + 1));
          deliver(player, new Frame(lease.id, ++lease.revision, result));
          return;
        }
        camera = cameras.get(channel);
      }
    }
    if (camera == null && ship == null && level.getBlockState(lease.console).is(ShipBlocks.CAMERA))
      camera = lease.console;
    if (camera == null) {
      result.putString("status", "Install a connected camera. Left/Right changes feed.");
      deliver(player, new Frame(lease.id, ++lease.revision, result));
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
    var origin = ship != null ? ShipStore.pos(ship, "base") : camera.offset(-16, -16, -16);
    var cameraLocal = eye.subtract(Vec3.atLowerCornerOf(origin));
    result.putDouble("cameraX", cameraLocal.x);
    result.putDouble("cameraY", cameraLocal.y);
    result.putDouble("cameraZ", cameraLocal.z);
    if (space && ship != null) {
      result.putString("observer", ship.getStringOr("id", ""));
      var nearby =
          ShipStore.get(level.getServer()).ships().stream()
              .map(t -> (CompoundTag) t)
              .filter(
                  t ->
                      t.getStringOr("dimension", "").equals(lease.dimension)
                          && com.nullandkale.nullspace.navigation.LocalSpaces.together(ship, t))
              .sorted(
                  Comparator.comparingDouble(
                      t -> pose(t).position().distanceToSqr(pose(ship).position())))
              .limit(MAX_SUBJECTS)
              .toList();
      for (var other : nearby) subjects.add(subject(level, other));
    } else {
      var item = new CompoundTag();
      item.putString("id", "world-" + origin.asLong());
      item.putString("name", "Camera");
      item.putLong("base", origin.asLong());
      geometry(
          item,
          SceneSnapshots.capture(
              level, origin, new ShipScan.Bounds(origin, origin.offset(31, 31, 31))));
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
    deliver(player, new Frame(lease.id, ++lease.revision, result));
  }

  private static void background(ServerPlayer player) {
    var level = player.level();
    if (TravelService.physicalBusy(player) || !ServerPlayNetworking.canSend(player, Frame.TYPE))
      return;
    if (!level.dimension().equals(TravelService.SPACE)) {
      if (com.nullandkale.nullspace.navigation.PlanetSurfaces.address(level) == null) return;
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
        deliver(
            player,
            new Frame(
                UUID.nameUUIDFromBytes(
                    (level.dimension().identifier() + ":surface")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                level.getServer().getTickCount(),
                data));
      return;
    }
    var ship = observer(player);
    if (ship == null) return;
    var data = new CompoundTag();
    data.putBoolean("background", true);
    data.putBoolean("universe", true);
    data.putString("dimension", level.dimension().identifier().toString());
    data.putString("observer", ship.getStringOr("id", ""));
    var subjects = new ListTag();
    ShipStore.get(level.getServer()).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(
            t ->
                t.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
                    && com.nullandkale.nullspace.navigation.LocalSpaces.together(ship, t))
        .sorted(
            Comparator.comparingDouble(
                t -> pose(t).position().distanceToSqr(pose(ship).position())))
        .limit(MAX_SUBJECTS)
        .forEach(t -> subjects.add(subject(level, t)));
    data.put("subjects", subjects);
    data.putLong("seed", SceneCatalog.get(level.getServer()).getLongOr("seed", 79419843));
    deliver(
        player,
        new Frame(
            UUID.fromString(ship.getStringOr("id", "")), level.getServer().getTickCount(), data));
  }

  /** EVA stays in the owner's physical frame, even outside its hull bounds. */
  public static CompoundTag observer(ServerPlayer player) {
    return ShipStore.get(player.level().getServer()).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(
            t ->
                t.getStringOr("dimension", "")
                        .equals(player.level().dimension().identifier().toString())
                    && ShipStore.bounds(t).box().inflate(72).contains(player.position()))
        .min(
            Comparator.comparingDouble(
                t -> ShipStore.bounds(t).box().getCenter().distanceToSqr(player.position())))
        .orElse(null);
  }

  /**
   * Two nearest installed displays share one bounded snapshot. No per-frame readback or recursive
   * feed.
   */
  public static void refresh(MinecraftServer server) {
    for (var p : server.getPlayerList().getPlayers()) background(p);
  }

  public static void shipView(
      ServerPlayer p, CompoundTag ship, UUID lease, int revision, boolean pilot, int power) {
    var data = new CompoundTag();
    data.putBoolean("pilotView", true);
    data.putBoolean("universe", true);
    data.putBoolean("canPilot", pilot);
    data.putString("dimension", p.level().dimension().identifier().toString());
    data.putString("observer", ship.getStringOr("id", ""));
    data.putString("location", com.nullandkale.nullspace.navigation.LocalSpaces.label(ship));
    data.putString(
        "status", "LIVE | " + ship.getStringOr("flightStatus", pilot ? "PILOT" : "OBSERVER"));
    data.putInt("propulsion", power);
    data.putDouble(
        "speed",
        new Vec3(
                ship.getDoubleOr("simVX", 0),
                ship.getDoubleOr("simVY", 0),
                ship.getDoubleOr("simVZ", 0))
            .length());
    var connected = ShipSystems.connected(p.level(), ship);
    var base = ShipStore.pos(ship, "base");
    int extent = 1;
    for (int axis = 0; axis < 3; axis++) {
      final int a = axis;
      var stats =
          connected.stream()
              .mapToInt(
                  pos ->
                      a == 0
                          ? pos.getX() - base.getX()
                          : a == 1 ? pos.getY() - base.getY() : pos.getZ() - base.getZ())
              .summaryStatistics();
      data.putDouble(
          "target" + "XYZ".charAt(axis),
          stats.getCount() == 0 ? 16 : (stats.getMin() + stats.getMax() + 1) * .5);
      if (stats.getCount() > 0) extent = Math.max(extent, stats.getMax() - stats.getMin() + 1);
    }
    data.putDouble("viewDistance", Math.clamp(extent * 2.2, 28, 100));
    var helmState = p.level().getBlockState(ShipStore.pos(ship, "controller"));
    data.putFloat(
        "helmYaw",
        helmState.hasProperty(HorizontalDirectionalBlock.FACING)
            ? helmState.getValue(HorizontalDirectionalBlock.FACING).getOpposite().toYRot()
            : 180);
    var subjects = new ListTag();
    ShipStore.get(p.level().getServer()).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(
            t ->
                t.getStringOr("dimension", "").equals(data.getStringOr("dimension", ""))
                    && com.nullandkale.nullspace.navigation.LocalSpaces.together(ship, t))
        .sorted(
            Comparator.comparingDouble(
                t -> pose(t).position().distanceToSqr(pose(ship).position())))
        .limit(MAX_SUBJECTS)
        .forEach(t -> subjects.add(subject(p.level(), t)));
    data.put("subjects", subjects);
    deliver(p, new Frame(lease, revision, data));
  }

  private static void panels(ServerPlayer player) {
    if (!ServerPlayNetworking.canSend(player, Frame.TYPE) || TravelService.busy(player)) return;
    var level = player.level();
    var ship = observer(player);
    var data = new CompoundTag();
    data.putBoolean("panels", true);
    data.putString("dimension", level.dimension().identifier().toString());
    var displays = new ListTag();
    if (ship != null && !TravelService.locked(level, ShipStore.pos(ship, "base"))) {
      var blocks = ShipSystems.connected(level, ship);
      var cameras =
          blocks.stream()
              .filter(
                  p ->
                      level.getBlockState(p).is(ShipBlocks.CAMERA)
                          && (!ShipSystems.enabled(ship) || ShipSystems.active(ship, p)))
              .sorted(Comparator.comparingLong(BlockPos::asLong))
              .toList();
      blocks.stream()
          .filter(
              p ->
                  level.getBlockState(p).is(ShipBlocks.MONITOR)
                      && player.distanceToSqr(Vec3.atCenterOf(p)) <= 144)
          .sorted(Comparator.comparingDouble(p -> player.distanceToSqr(Vec3.atCenterOf(p))))
          .limit(2)
          .forEach(
              p -> {
                var display = new CompoundTag();
                display.putLong("position", p.asLong());
                display.putString(
                    "facing",
                    level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING).getName());
                int channel =
                    cameras.isEmpty()
                        ? 0
                        : Math.floorMod(
                            ship.getCompoundOrEmpty("monitorChannels")
                                .getIntOr(
                                    Long.toString(p.subtract(ShipStore.pos(ship, "base")).asLong()),
                                    0),
                            cameras.size());
                boolean power =
                    !ShipSystems.enabled(ship)
                        || channel < ShipSystems.power(level, ship, PowerBus.System.CAMERAS)
                            && ShipSystems.active(ship, p);
                if (power && !cameras.isEmpty()) {
                  var camera = cameras.get(channel);
                  var facing =
                      level.getBlockState(camera).getValue(HorizontalDirectionalBlock.FACING);
                  var eye =
                      Vec3.atCenterOf(camera)
                          .add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(.65))
                          .subtract(Vec3.atLowerCornerOf(ShipStore.pos(ship, "base")));
                  display.putLong("source", camera.asLong());
                  display.putInt("selection", channel);
                  display.putDouble("cameraX", eye.x);
                  display.putDouble("cameraY", eye.y);
                  display.putDouble("cameraZ", eye.z);
                  display.putFloat("cameraYaw", facing.toYRot());
                  display.putString("status", "LIVE");
                } else display.putString("status", power ? "NO CAMERA" : "NO POWER");
                displays.add(display);
              });
      if (!displays.isEmpty()) {
        boolean space = level.dimension().equals(TravelService.SPACE);
        data.putBoolean("universe", space);
        data.putLong("seed", SceneCatalog.get(level.getServer()).getLongOr("seed", 79419843));
        data.putString("observer", ship.getStringOr("id", ""));
        var subjects = new ListTag();
        if (space)
          ShipStore.get(level.getServer()).ships().stream()
              .map(t -> (CompoundTag) t)
              .filter(
                  t ->
                      t.getStringOr("dimension", "")
                              .equals(level.dimension().identifier().toString())
                          && com.nullandkale.nullspace.navigation.LocalSpaces.together(ship, t))
              .sorted(
                  Comparator.comparingDouble(
                      t -> pose(t).position().distanceToSqr(pose(ship).position())))
              .limit(MAX_SUBJECTS)
              .forEach(t -> subjects.add(subject(level, t)));
        else {
          var source = subject(level, ship);
          source.putDouble("x", 0);
          source.putDouble("y", 0);
          source.putDouble("z", 0);
          source.putDouble("yaw", 0);
          source.putDouble("pitch", 0);
          source.putDouble("roll", 0);
          source.put(
              "players",
              players(
                  level,
                  ShipStore.pos(ship, "base"),
                  new net.minecraft.world.phys.AABB(ShipStore.pos(ship, "controller"))
                      .inflate(48)));
          subjects.add(source);
        }
        data.put("subjects", subjects);
        if (!space) {
          PlanetSky.decorate(data, level, ShipStore.pos(ship, "base"));
          data.put(
              "terrain",
              TerrainService.collect(
                  player, ShipStore.pos(ship, "controller"), ShipStore.pos(ship, "base")));
          data.put(
              "entities",
              TerrainService.entities(
                  level, ShipStore.pos(ship, "base"), ShipStore.pos(ship, "controller")));
        }
        if (subjects.stream().anyMatch(t -> !((CompoundTag) t).getBooleanOr("ready", false)))
          for (Tag t : displays) ((CompoundTag) t).putString("status", "LOADING");
      }
    }
    data.put("displays", displays);
    deliver(
        player,
        new Frame(
            UUID.nameUUIDFromBytes(
                (System.identityHashCode(level.getServer()) + ":" + player.getUUID())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            level.getServer().getTickCount(),
            data));
  }

  public static void cycleMonitor(ServerPlayer player, BlockPos console) {
    var ship = ShipStore.get(player.level().getServer()).at(player.level(), console);
    if (ship == null || TravelService.busy(player)) return;
    if (!ship.contains("monitorChannels")) ship.put("monitorChannels", new CompoundTag());
    var channels = ship.getCompoundOrEmpty("monitorChannels");
    var key = Long.toString(console.subtract(ShipStore.pos(ship, "base")).asLong());
    channels.putInt(key, Math.floorMod(channels.getIntOr(key, 0) + 1, 10000));
    ShipStore.get(player.level().getServer()).requestSave();
    panels(player);
  }

  public static void initialize() {
    SceneSnapshots.initialize();
    PayloadTypeRegistry.clientboundPlay().register(Definition.TYPE, Definition.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(Frame.TYPE, Frame.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(KeepAlive.TYPE, KeepAlive.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        KeepAlive.TYPE,
        (p, c) ->
            c.server()
                .execute(
                    () -> {
                      var lease = leases(c.server()).get(c.player().getUUID());
                      if (lease == null || !lease.id.equals(p.lease)) return;
                      if (p.close) {
                        leases(c.server()).remove(c.player().getUUID());
                        return;
                      }
                      if (Math.abs((long) p.selection) > 10000 || !valid(c.player(), lease)) return;
                      lease.expires = c.server().getTickCount() + 100;
                      lease.selection = p.selection;
                    }));
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.SCENES,
        server -> {
          if (server.getTickCount() % INTERVAL != 0) {
            if (com.nullandkale.nullspace.navigation.NavigationService.moving(server)
                && server.getTickCount() % 2 == 0) refresh(server);
            return;
          }
          SceneSnapshots.expire(server);
          for (var p : server.getPlayerList().getPlayers()) {
            background(p);
            panels(p);
          }
          leases(server)
              .entrySet()
              .removeIf(
                  e -> {
                    var player = server.getPlayerList().getPlayer(e.getKey());
                    if (player == null || !valid(player, e.getValue())) return true;
                    send(player, e.getValue());
                    return false;
                  });
        });
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> {
          leases(server).remove(handler.player.getUUID());
          definitions(server).remove(handler.player.getUUID());
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          com.nullandkale.nullspace.server.ServerContext.get(server).remove(LEASES);
          com.nullandkale.nullspace.server.ServerContext.get(server).remove(SENT_DEFINITIONS);
          SceneSnapshots.clear(server);
        });
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) ->
            dispatcher.register(
                Commands.literal("nullspace")
                    .then(
                        Commands.literal("viewport")
                            .requires(
                                source ->
                                    source
                                        .permissions()
                                        .hasPermission(
                                            net.minecraft.server.permissions.Permissions
                                                .COMMANDS_GAMEMASTER))
                            .then(
                                Commands.argument(
                                        "position",
                                        net.minecraft.commands.arguments.coordinates
                                            .BlockPosArgument.blockPos())
                                    .executes(
                                        c -> {
                                          var p = c.getSource().getPlayerOrException();
                                          var at =
                                              net.minecraft.commands.arguments.coordinates
                                                  .BlockPosArgument.getBlockPos(c, "position");
                                          var l =
                                              new Lease(
                                                  p,
                                                  p.blockPosition(),
                                                  at,
                                                  p.getYRot(),
                                                  p.getXRot());
                                          l.expires = p.level().getServer().getTickCount() + 100;
                                          leases(p.level().getServer()).put(p.getUUID(), l);
                                          send(p, l);
                                          return 1;
                                        })))));
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) -> {
          String[] fields = {"x", "y", "z", "yaw", "pitch", "roll"};
          var last =
              Commands.argument(
                      "roll",
                      com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-360, 360))
                  .executes(
                      c -> {
                        var player = c.getSource().getPlayerOrException();
                        var ship = observer(player);
                        if (ship == null
                            || !player.level().dimension().equals(TravelService.SPACE)
                            || TravelService.busy(player)
                            || TravelService.locked(player.level(), ShipStore.pos(ship, "base"))) {
                          c.getSource()
                              .sendFailure(
                                  net.minecraft.network.chat.Component.literal(
                                      "Board a ship in space and finish travel before setting its"
                                          + " scene pose"));
                          return 0;
                        }
                        String[] keys = {"simX", "simY", "simZ", "simYaw", "simPitch", "simRoll"};
                        ShipStore.get(c.getSource().getServer())
                            .repository()
                            .mutate(
                                new ShipRepository.ShipId(ship.getStringOr("id", "")),
                                ShipRepository.Mutation.SIMULATION,
                                candidate -> {
                                  for (int n = 0; n < fields.length; n++)
                                    candidate.putDouble(
                                        keys[n],
                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(
                                            c, fields[n]));
                                });
                        for (var viewer : c.getSource().getServer().getPlayerList().getPlayers())
                          background(viewer);
                        c.getSource()
                            .sendSuccess(
                                () ->
                                    net.minecraft.network.chat.Component.literal(
                                        "Simulated ship pose saved; physical blocks and crew stay"
                                            + " in their storage frame"),
                                false);
                        return 1;
                      });
          for (int n = 4; n >= 0; n--)
            last =
                Commands.argument(
                        fields[n],
                        com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(
                            n < 3 ? -1e12 : -360, n < 3 ? 1e12 : 360))
                    .then(last);
          dispatcher.register(
              Commands.literal("nullspace")
                  .then(
                      Commands.literal("scene")
                          .requires(
                              source ->
                                  source
                                      .permissions()
                                      .hasPermission(
                                          net.minecraft.server.permissions.Permissions
                                              .COMMANDS_GAMEMASTER))
                          .then(Commands.literal("pose").then(last))));
        });
  }

  private SceneService() {}
}
