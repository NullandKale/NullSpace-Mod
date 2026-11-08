package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingTunnel;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets.Definition;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets.Frame;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets.KeepAlive;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.station.StationSpace;
import com.nullandkale.nullspace.gameplay.systems.EquipmentPanel;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.ShipHeading;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.ScenePose;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/**
 * Bounded observation leases. Scene snapshots never change the player's chunk center or spawn fake
 * players.
 */
public final class SceneService {
  public static final int MAX_SUBJECTS = 12, MAX_RENDER_SECTIONS = 64, INTERVAL = 20;

  static final ServerContext.Key<Map<UUID, String>> SENT_DEFINITIONS = new ServerContext.Key<>();

  static Map<UUID, String> definitions(MinecraftServer server) {
    return ServerContext.get(server).service(SENT_DEFINITIONS, HashMap::new);
  }

  static void deliver(ServerPlayer player, Frame frame) {
    if (frame.scene().getBooleanOr("universe", false)) {
      var definition =
          SceneCatalog.region(
              player.level().getServer(),
              frame.scene().getStringOr("region", LocalSpaces.address(observer(player))));
      String key = definition.getStringOr("id", "") + ":" + definition.getLongOr("revision", 0);
      if (!key.equals(definitions(player.level().getServer()).get(player.getUUID()))) {
        SpaceDefinitionCodec.decode(definition);
        ServerPlayNetworking.send(player, new Definition(definition.copy()));
        definitions(player.level().getServer()).put(player.getUUID(), key);
      }
      frame.scene().remove("catalog");
      frame.scene().putString("sceneId", definition.getStringOr("id", ""));
      frame.scene().putLong("sceneRevision", definition.getLongOr("revision", 0));
      frame
          .scene()
          .putDouble("simulationTime", player.level().getServer().overworld().getGameTime() / 20.0);
    }
    ServerPlayNetworking.send(player, frame);
  }

  static final class Lease {
    final UUID id = UUID.randomUUID();
    final String dimension;
    final BlockPos console;
    final BlockPos freeCamera;
    final float yaw, pitch;
    int expires, revision, selection;
    boolean machinePreview, returnMachine, returnEquipment;
    String pinnedCamera = "";

    Lease(ServerPlayer player, BlockPos console, BlockPos freeCamera, float yaw, float pitch) {
      dimension = player.level().dimension().identifier().toString();
      this.console = console;
      this.freeCamera = freeCamera;
      this.yaw = yaw;
      this.pitch = pitch;
    }
  }

  static final ServerContext.Key<Map<UUID, Lease>> LEASES = new ServerContext.Key<>();

  static Map<UUID, Lease> leases(MinecraftServer server) {
    return ServerContext.get(server).service(LEASES, HashMap::new);
  }

  public static ScenePose pose(CompoundTag ship) {
    return ShipRepository.pose(ship);
  }

  public static void open(ServerPlayer player, BlockPos console) {
    open(player, console, false, false, false);
  }

  public static void preview(ServerPlayer player, BlockPos console) {
    open(player, console, true, false, false);
  }

  static void open(
      ServerPlayer player,
      BlockPos console,
      boolean preview,
      boolean returnMachine,
      boolean returnEquipment) {
    if (!player.level().hasChunkAt(console)
        || !(player.level().getBlockState(console).is(ModBlocks.MONITOR)
            || player.level().getBlockState(console).is(ModBlocks.CAMERA))
        || player.distanceToSqr(Vec3.atCenterOf(console)) > 64
        || !player.mayInteract(player.level(), console)
        || TransferLocks.busy(player)
        || TransferLocks.locked(player.level(), console))
      throw new IllegalArgumentException("Camera console unavailable, out of range, or in transit");
    var lease = new Lease(player, console, null, 0, 0);
    lease.machinePreview = preview;
    lease.returnMachine = returnMachine;
    lease.returnEquipment = returnEquipment;
    if (player.level().getBlockState(console).is(ModBlocks.MONITOR)
        && player.level().getBlockEntity(console) instanceof MachineBlockEntity machine)
      lease.pinnedCamera = machine.camera;
    var owner = WorldStore.get(player.level().getServer()).at(player.level(), console);
    if (owner != null)
      lease.selection =
          owner
              .getCompoundOrEmpty("monitorChannels")
              .getIntOr(Long.toString(console.subtract(WorldStore.pos(owner, "base")).asLong()), 0);
    lease.expires = player.level().getServer().getTickCount() + 100;
    leases(player.level().getServer()).put(player.getUUID(), lease);
    SceneFrames.send(player, lease);
  }

  static boolean valid(ServerPlayer player, Lease lease) {
    return player.isAlive()
        && player.level().dimension().identifier().toString().equals(lease.dimension)
        && player.level().getServer().getTickCount() <= lease.expires
        && !TransferLocks.busy(player)
        && (lease.freeCamera != null
                && player
                    .permissions()
                    .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
            || lease.freeCamera == null
                && player.distanceToSqr(Vec3.atCenterOf(lease.console)) <= 64
                && player.level().hasChunkAt(lease.console)
                && (player.level().getBlockState(lease.console).is(ModBlocks.MONITOR)
                    || player.level().getBlockState(lease.console).is(ModBlocks.CAMERA))
                && player.mayInteract(player.level(), lease.console)
                && !TransferLocks.locked(player.level(), lease.console));
  }

  public static void addSubject(ListTag subjects, ServerLevel level, CompoundTag ship) {
    int used = 0;
    for (var tag : subjects) used += 1 + ((CompoundTag) tag).getListOrEmpty("sections").size();
    int needed = StationSpace.sections(ship).size() + DockingTunnel.sections(level, ship);
    if (subjects.size() < MAX_SUBJECTS && used + needed <= MAX_RENDER_SECTIONS)
      subjects.add(subject(level, ship));
  }

  public static CompoundTag subject(ServerLevel level, CompoundTag ship) {
    var base = WorldStore.pos(ship, "base");
    var data = new CompoundTag();
    data.putString("id", ship.getStringOr("id", ""));
    data.putString("name", ship.getStringOr("home", "Ship"));
    data.putLong("base", base.asLong());
    data.putInt("width", ship.getIntOr("width", 32));
    data.putInt("height", ship.getIntOr("height", 32));
    data.putInt("depth", ship.getIntOr("depth", 32));
    var pose = pose(ship);
    data.putDouble("x", pose.position().x);
    data.putDouble("y", pose.position().y);
    data.putDouble("z", pose.position().z);
    data.putDouble("yaw", ship.getDoubleOr("simYaw", 0));
    data.putDouble("pitch", ship.getDoubleOr("simPitch", 0));
    data.putDouble("roll", ship.getDoubleOr("simRoll", 0));
    var bow = ShipHeading.forward(level, ship);
    data.putString("bow", bow.getName());
    var journey = ship.getCompoundOrEmpty("journey");
    data.putBoolean("ftl", JourneyService.cruising(ship));
    double now = level.getServer().overworld().getGameTime() / 20.0;
    double elapsed = journey.getIntOr("elapsed", 0) / 20.0;
    double visualAge = Math.max(0, now - journey.getDoubleOr("visualStarted", now - elapsed));
    data.putDouble("ftlElapsed", visualAge);
    data.putDouble(
        "ftlDuration", visualAge + Math.max(0, journey.getIntOr("duration", 0) / 20.0 - elapsed));

    SceneFrames.geometry(data, SceneSnapshots.capture(level, base, WorldStore.bounds(ship)));
    if (ship.getIntOr("width", 32) > 32 || ship.getIntOr("depth", 32) > 32) {
      var sections = new ListTag();
      boolean ready = data.getBooleanOr("ready", false);
      for (var offset : StationSpace.sections(ship)) {
        if (offset.equals(BlockPos.ZERO)) continue;
        var section = new CompoundTag();
        section.putString(
            "id", ship.getStringOr("id", "") + "/" + offset.getX() + "_" + offset.getZ());
        section.putString("parent", ship.getStringOr("id", ""));
        section.putLong("base", base.offset(offset).asLong());
        var at = pose.toUniverse(Vec3.atLowerCornerOf(offset));
        section.putDouble("x", at.x);
        section.putDouble("y", at.y);
        section.putDouble("z", at.z);
        section.putDouble("yaw", ship.getDoubleOr("simYaw", 0));
        section.putDouble("pitch", ship.getDoubleOr("simPitch", 0));
        section.putDouble("roll", ship.getDoubleOr("simRoll", 0));
        SceneFrames.geometry(
            section, SceneSnapshots.capture(level, base.offset(offset), WorldStore.bounds(ship)));
        if (ShipTopology.loaded(level, ship))
          section.putIntArray("interiorMask", ShipAir.interiorMask(level, ship, offset));
        ready &= section.getBooleanOr("ready", false);
        sections.add(section);
      }
      if (ShipTopology.loaded(level, ship))
        data.putIntArray("interiorMask", ShipAir.interiorMask(level, ship, BlockPos.ZERO));
      data.put("sections", sections);
      data.putBoolean("ready", ready);
    }
    DockingTunnel.render(level, ship, data);
    data.put(
        "players",
        SceneFrames.players(
            level,
            base,
            WorldStore.bounds(ship)
                .box()
                .inflate(level.dimension().equals(TravelService.SPACE) ? 72 : 2)));
    return data;
  }

  /** EVA stays in the owner's physical frame, even outside its hull bounds. */
  public static CompoundTag observer(ServerPlayer player) {
    return WorldStore.get(player.level().getServer()).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(
            t ->
                t.getStringOr("dimension", "")
                        .equals(player.level().dimension().identifier().toString())
                    && WorldStore.bounds(t).box().inflate(72).contains(player.position()))
        .min(
            Comparator.comparingDouble(
                t -> WorldStore.bounds(t).box().getCenter().distanceToSqr(player.position())))
        .orElse(null);
  }

  /**
   * Two nearest installed displays share one bounded snapshot. No per-frame readback or recursive
   * feed.
   */
  public static void refresh(MinecraftServer server) {
    for (var p : server.getPlayerList().getPlayers()) SceneFrames.background(p);
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
                      if (lease == null || !lease.id.equals(p.lease())) return;
                      if (p.close()) {
                        leases(c.server()).remove(c.player().getUUID());
                        if (lease.returnEquipment) {
                          try {
                            EquipmentPanel.openEquipment(c.player(), lease.console);
                          } catch (IllegalArgumentException unavailable) {
                            c.player()
                                .sendSystemMessage(
                                    net.minecraft.network.chat.Component.literal(
                                        "View closed: " + unavailable.getMessage()));
                          }
                        }
                        if (lease.returnMachine && !MachineMenus.open(c.player(), lease.console))
                          c.player()
                              .sendSystemMessage(
                                  net.minecraft.network.chat.Component.literal(
                                      "View closed: camera or monitor is no longer reachable"));
                        return;
                      }
                      if (Math.abs((long) p.selection()) > 10000 || !valid(c.player(), lease))
                        return;
                      lease.expires = c.server().getTickCount() + 100;
                      if (lease.selection != p.selection()) lease.pinnedCamera = "";
                      lease.selection = p.selection();
                    }));
    ServerContext.tick(
        ServerContext.Phase.SCENES,
        server -> {
          if (server.getTickCount() % INTERVAL != 0) {
            if (NavigationService.moving(server) && server.getTickCount() % 2 == 0) refresh(server);
            return;
          }
          SceneSnapshots.expire(server);
          for (var p : server.getPlayerList().getPlayers()) {
            SceneFrames.background(p);
            ScenePanels.panels(p);
          }
          leases(server)
              .entrySet()
              .removeIf(
                  e -> {
                    var player = server.getPlayerList().getPlayer(e.getKey());
                    if (player == null || !valid(player, e.getValue())) return true;
                    SceneFrames.send(player, e.getValue());
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
          ServerContext.get(server).remove(LEASES);
          ServerContext.get(server).remove(SENT_DEFINITIONS);
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
                                          SceneFrames.send(p, l);
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
                            || TransferLocks.busy(player)
                            || TransferLocks.locked(player.level(), WorldStore.pos(ship, "base"))) {
                          c.getSource()
                              .sendFailure(
                                  net.minecraft.network.chat.Component.literal(
                                      "Board a ship in space and finish travel before setting its"
                                          + " scene pose"));
                          return 0;
                        }
                        String[] keys = {"simX", "simY", "simZ", "simYaw", "simPitch", "simRoll"};
                        WorldStore.get(c.getSource().getServer())
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
                          SceneFrames.background(viewer);
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
