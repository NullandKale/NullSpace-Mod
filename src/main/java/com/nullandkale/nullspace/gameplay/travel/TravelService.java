package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.ControllerChecks;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.LooseBlocks;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

public final class TravelService {
  public static final int SPACE_SPACING = 4096;
  static final String DETACH = "@leave-loose-blocks";

  public static final ResourceKey<Level> SPACE =
      ResourceKey.create(Registries.DIMENSION, Identifier.parse("nullspace:space"));
  static final ServerContext.Key<Map<UUID, Pending>> PENDING = new ServerContext.Key<>();

  static Map<UUID, Pending> pending(MinecraftServer server) {
    return ServerContext.get(server).service(PENDING, LinkedHashMap::new);
  }

  private static final ServerContext.Key<Set<UUID>> ARRIVALS = new ServerContext.Key<>();

  public static boolean worldMutation() {
    return TransferReplay.worldMutation();
  }

  public static Consumer<String> failureInjector = stage -> {};

  public static final class SimulatedCrash extends Error {}

  public enum Outcome {
    COMPLETED,
    WAITING,
    REJECTED
  }

  /** Expected transfer outcomes never enter exception recovery or write an error stack trace. */
  public record Result(Outcome outcome, String message) {
    public static Result rejected(String message) {
      return new Result(Outcome.REJECTED, message);
    }

    public static Result waiting() {
      return new Result(Outcome.WAITING, "Ship passengers are still loading");
    }
  }

  static Result blocked(ServerLevel level, BlockPos at, String advice) {
    return Result.rejected(
        advice + "\nBlock: " + ShipMessages.block(level, at) + "\nLocation: " + at.toShortString());
  }

  static final class Pending {
    final UUID id = UUID.randomUUID();
    final UUID actor;
    final BlockPos computer;
    final String destination;
    final String sourceDimension;
    final HullScan.Bounds sourceBounds;
    String ship;
    HullScan.Bounds targetBounds;
    BlockPos targetBase;
    final Map<UUID, Vec3> crew = new HashMap<>();
    final Set<UUID> ready = new HashSet<>(), arrived = new HashSet<>();
    int ticks;
    boolean moved;
    String targetDimension = "";

    Pending(
        ServerPlayer actor, BlockPos pos, String destination, ShipService.Inspection inspection) {
      this.actor = actor.getUUID();
      this.computer = pos;
      this.destination = destination;
      sourceDimension = actor.level().dimension().identifier().toString();
      ship = inspection.vessel() == null ? "" : inspection.vessel().getStringOr("id", "");
      int width =
          inspection.pad() != null
              ? inspection.pad().bounds().width()
              : inspection.vessel().getIntOr("width", 32);
      int depth =
          inspection.pad() != null
              ? inspection.pad().bounds().depth()
              : inspection.vessel().getIntOr("depth", 32);
      sourceBounds =
          new HullScan.Bounds(
              inspection.base(), inspection.base().offset(width - 1, 31, depth - 1));
    }
  }

  public static void initialize() {
    TravelPackets.initialize();
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          WorldStore.get(server);
          TransferRecovery.recover(server);
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          ServerContext.get(server).remove(PENDING);
          ServerContext.get(server).remove(ARRIVALS);
          WorldStore.forget(server);
        });
    ServerContext.tick(ServerContext.Phase.TRAVEL, TravelService::tick);
    // Fabric JOIN can run before PlayerList finishes adding the player to its captured
    // login level. Cross-dimension teleporting there leaves a callback in the wrong world.
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) ->
            ServerContext.get(server)
                .service(ARRIVALS, HashSet::new)
                .add(handler.player.getUUID()));
  }

  public static void ready(ServerPlayer player, TravelPackets.Ready packet) {
    var pending = pending(player.level().getServer()).get(packet.id());
    if (pending != null
        && pending.id.equals(packet.id())
        && pending.crew.containsKey(player.getUUID())) {
      if (packet.arrived()
          && pending.moved
          && player.level().dimension().identifier().toString().equals(pending.targetDimension))
        pending.arrived.add(player.getUUID());
      else if (!packet.arrived()) pending.ready.add(player.getUUID());
    }
  }

  public static void request(
      ServerPlayer player,
      BlockPos controller,
      String destination,
      ShipService.Inspection inspection) {
    request(player, controller, destination, inspection, false);
  }

  public static void requestDetachment(
      ServerPlayer player, BlockPos controller, ShipService.Inspection inspection) {
    request(player, controller, DETACH, inspection, true);
  }

  private static void request(
      ServerPlayer player,
      BlockPos controller,
      String destination,
      ShipService.Inspection inspection,
      boolean detaching) {
    var server = player.level().getServer();
    if (detaching
        && (!player.level().dimension().equals(SPACE)
            || LooseBlocks.approved(player.level(), controller).isEmpty()))
      throw new IllegalArgumentException(
          "Review and confirm loose blocks before leaving them behind");
    if (JourneyService.cruising(inspection.vessel()))
      throw new IllegalArgumentException("Finish the FTL journey before landing");
    if (!player.level().dimension().equals(SPACE) && !ShipSystems.enabled(inspection.vessel()))
      throw new IllegalArgumentException(ControllerChecks.NO_SYSTEMS);
    if (ShipStorage.active(player))
      throw new IllegalArgumentException("Finish or cancel your recall before travelling");
    if (DockingService.motionLocked(inspection.vessel()))
      throw new IllegalArgumentException(
          "Undock and finish station construction before travel; stations cannot launch or land");
    if (TransferLocks.busy(player) || TransferLocks.busy(server, inspection.vessel()))
      throw new IllegalArgumentException("This ship or its crew is already travelling or boarding");

    Pending pending = new Pending(player, controller, destination, inspection);
    if (TransferLocks.areaBusy(player.level(), pending.sourceBounds))
      throw new IllegalArgumentException("This ship's area is already reserved for travel");
    if (destination.isBlank() || detaching) {
      int slot = 0;
      while (slot <= 7000 && !TransferLocks.spaceSlotFree(server, slot)) slot++;
      if (slot > 7000) throw new IllegalArgumentException("No free physical ship regions remain");
      pending.targetBase = new BlockPos(slot * SPACE_SPACING, 96, 0);
      pending.targetDimension = SPACE.identifier().toString();
      pending.targetBounds =
          new HullScan.Bounds(pending.targetBase, pending.targetBase.offset(31, 31, 31));
    }

    if (player.level().dimension().equals(SPACE) && !destination.isBlank() && !detaching) {
      var pad =
          PadRegistry.read(server).stream()
              .filter(
                  p ->
                      destination.startsWith("id:")
                          ? p.id().equals(destination.substring(3))
                          : p.name().equalsIgnoreCase(destination.strip()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Unknown landing pad"));
      if (!PlanetSurfaces.surfaceOf(
          server, LocalSpaces.address(inspection.vessel()), pad.dimension()))
        throw new IllegalArgumentException(
            "Jump to "
                + (pad.dimension().equals("minecraft:overworld")
                    ? "Sol / Terra"
                    : "the destination planet")
                + " before landing at this pad");
      pending.targetBase =
          new BlockPos(pad.bounds().minX(), pad.bounds().y() + 1, pad.bounds().minZ());
      pending.targetDimension = pad.dimension();
      pending.targetBounds =
          new HullScan.Bounds(
              pending.targetBase,
              pending.targetBase.offset(pad.bounds().width() - 1, 31, pad.bounds().depth() - 1));
      var target = WorldStore.level(server, pad.dimension());
      if (TransferLocks.areaBusy(target, pending.targetBounds))
        throw new IllegalArgumentException("This landing pad is already reserved by another ship");
    }
    if (inspection.vessel() != null)
      NavigationService.closeShip(server, inspection.vessel().getStringOr("id", ""));
    if (player.level().dimension().equals(SPACE) && destination.isBlank())
      throw new IllegalArgumentException("Choose a registered landing destination");
    if (!player.level().dimension().equals(SPACE) && !destination.isBlank())
      throw new IllegalArgumentException("Launch to space before selecting a landing destination");
    if (!inspection.scan().pressure().breathable(BlockPos.containing(player.getEyePosition())))
      throw new IllegalArgumentException("Board the sealed cabin before travel");
    if (inspection.vessel() != null) ShipSystems.cancelTransit(player.level(), inspection.vessel());
    for (ServerPlayer member : player.level().players())
      if (inspection.scan().bounds().box().intersects(member.getBoundingBox())) {
        if (!inspection.scan().pressure().breathable(BlockPos.containing(member.getEyePosition())))
          throw new IllegalArgumentException("All passengers must be inside a sealed cabin");
        pending.crew.put(member.getUUID(), member.position());
        member.closeContainer();
      }
    if (inspection.vessel() != null)
      for (Tag tag : inspection.vessel().getListOrEmpty("crew")) {
        var id = UUID.fromString(((CompoundTag) tag).getStringOr("id", ""));
        var member = server.getPlayerList().getPlayer(id);
        if (member != null && !pending.crew.containsKey(id))
          throw new IllegalArgumentException("Recall tethered crew from EVA before travel");
      }
    pending(server).put(pending.id, pending);
    broadcast(server, pending, "Preparing ship", "", false);
  }

  private static void broadcast(
      MinecraftServer server, Pending pending, String message, String dimension, boolean done) {
    for (UUID id : pending.crew.keySet()) {
      var player = server.getPlayerList().getPlayer(id);
      if (player != null)
        ServerPlayNetworking.send(
            player, new TravelPackets.Progress(pending.id, message, dimension, done));
    }
  }

  private static boolean allOnline(MinecraftServer server, Pending p, Set<UUID> set) {
    return p.crew.keySet().stream()
        .filter(id -> server.getPlayerList().getPlayer(id) != null)
        .allMatch(set::contains);
  }

  private static void tick(MinecraftServer server) {
    var arrivals = ServerContext.get(server).remove(ARRIVALS);
    if (arrivals != null)
      for (var id : arrivals) {
        var player = server.getPlayerList().getPlayer(id);
        if (player != null) TransferRecovery.resolveArrival(player);
      }
    for (var job : List.copyOf(pending(server).values())) tick(server, job);
  }

  private static void tick(MinecraftServer server, Pending pending) {
    pending.ticks++;
    if (!pending.moved) {
      if (pending.ticks > 200) {
        finish(server, pending, "Travel cancelled: client readiness timed out");
        return;
      }
      if (pending.ticks < 2 || !allOnline(server, pending, pending.ready)) return;
      var actor = server.getPlayerList().getPlayer(pending.actor);
      if (actor == null) {
        finish(server, pending, "Travel cancelled: pilot disconnected");
        return;
      }
      try {
        ShipService.Inspection inspected;
        try {
          inspected = ShipService.inspect(actor.level(), pending.computer);
        } catch (IllegalArgumentException changed) {
          String message = "Travel cancelled: " + ShipMessages.failure(changed);
          NullSpace.LOGGER.warn(
              "{} | player={} controller={}",
              message.replace('\n', ' '),
              actor.getPlainTextName(),
              pending.computer.toShortString());
          if (changed instanceof HullScan.Failure problem)
            ShipDiagnostics.highlight(actor, problem);
          finish(server, pending, message);
          return;
        }
        if (!inspected.scan().pressure().breathable(BlockPos.containing(actor.getEyePosition()))) {
          finish(server, pending, "Travel cancelled: pilot left the cabin");
          return;
        }
        var result = transfer(actor, pending.computer, pending.destination, inspected);
        if (result.outcome() == Outcome.WAITING) return;
        if (result.outcome() == Outcome.REJECTED) {
          finish(server, pending, "Travel cancelled: " + result.message());
          return;
        }
        pending.moved = true;
        pending.ticks = 0;
        pending.targetDimension = actor.level().dimension().identifier().toString();
        var vessel = WorldStore.get(server).at(actor.level(), actor.blockPosition());
        if (vessel != null) pending.ship = vessel.getStringOr("id", pending.ship);
        broadcast(server, pending, "Boarding", pending.targetDimension, false);
      } catch (Exception error) {
        NullSpace.LOGGER.error("Ship transfer failed", error);
        try {
          TransferRecovery.recover(server);
        } catch (Exception recovery) {
          throw new IllegalStateException(
              "Transfer recovery failed; server must stop before further world interactions",
              recovery);
        }
        finish(server, pending, "Travel failed: " + ShipMessages.failure(error));
      }
    } else if (allOnline(server, pending, pending.arrived) || pending.ticks > 200) {
      for (UUID id : pending.crew.keySet()) {
        var player = server.getPlayerList().getPlayer(id);
        if (player != null && !pending.arrived.contains(id))
          player.connection.disconnect(
              net.minecraft.network.chat.Component.literal(
                  "Arrival timed out. Reconnect to finish boarding safely."));
        else if (player != null) TransferRecovery.clearTarget(player);
      }
      finish(server, pending, "Arrived");
    }
  }

  private static void finish(MinecraftServer server, Pending pending, String message) {
    broadcast(server, pending, message, pending.targetDimension, true);
    pending(server).remove(pending.id, pending);
    var actor = server.getPlayerList().getPlayer(pending.actor);
    if (actor != null)
      actor.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
  }

  /**
   * Mutations occur in one server task: machines, mobs and menus cannot tick between journal
   * stages.
   */
  public static Result transfer(
      ServerPlayer actor,
      BlockPos controller,
      String destination,
      ShipService.Inspection inspected) {
    return transferFrom(actor, actor.level(), controller, destination, inspected, false);
  }

  public static Result transferRecall(
      ServerPlayer actor,
      ServerLevel source,
      BlockPos controller,
      String destination,
      ShipService.Inspection inspected) {
    return transferFrom(actor, source, controller, destination, inspected, true);
  }

  private static Result transferFrom(
      ServerPlayer actor,
      ServerLevel source,
      BlockPos controller,
      String destination,
      ShipService.Inspection inspected,
      boolean recall) {
    return new ShipTransfer(actor, source, controller, destination, inspected, recall).run();
  }

  private TravelService() {}
}
