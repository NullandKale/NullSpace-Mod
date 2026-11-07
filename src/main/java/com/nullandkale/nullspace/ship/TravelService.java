package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.NullSpace;
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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

public final class TravelService {
  public static final int SPACE_SPACING = 4096;
  private static final String DETACH = "@leave-loose-blocks";

  public static boolean spaceSlotFree(MinecraftServer server, int slot) {
    if (slot < 0 || slot > 7000) return false;
    var base = new BlockPos(slot * SPACE_SPACING, 96, 0);
    var area =
        new net.minecraft.world.phys.AABB(
            base.getX() - 128, -32, -128, base.getX() + 160, 256, 160);
    return pending(server).values().stream()
            .noneMatch(
                p ->
                    p.sourceDimension.equals(SPACE.identifier().toString())
                            && area.intersects(p.sourceBounds.box().inflate(128))
                        || p.targetDimension.equals(SPACE.identifier().toString())
                            && area.intersects(p.targetBounds.box().inflate(128)))
        && ShipStore.get(server).ships().stream()
            .map(t -> (CompoundTag) t)
            .noneMatch(
                ship ->
                    ship.getStringOr("dimension", "").equals(SPACE.identifier().toString())
                        && area.intersects(ShipStore.bounds(ship).box().inflate(128)));
  }

  public static final ResourceKey<Level> SPACE =
      ResourceKey.create(Registries.DIMENSION, Identifier.parse("nullspace:space"));
  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, Pending>>
      PENDING = new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, Pending> pending(MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server)
        .service(PENDING, LinkedHashMap::new);
  }

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Set<UUID>> ARRIVALS =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  public static boolean worldMutation() {
    return ShipPlacement.worldMutation();
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
    static Result rejected(String message) {
      return new Result(Outcome.REJECTED, message);
    }

    static Result waiting() {
      return new Result(Outcome.WAITING, "Ship passengers are still loading");
    }
  }

  private static Result blocked(ServerLevel level, BlockPos at, String advice) {
    return Result.rejected(
        advice + "\nBlock: " + ShipMessages.block(level, at) + "\nLocation: " + at.toShortString());
  }

  private static final class Pending {
    final UUID id = UUID.randomUUID();
    final UUID actor;
    final BlockPos computer;
    final String destination;
    final String sourceDimension;
    final ShipScan.Bounds sourceBounds;
    String ship;
    ShipScan.Bounds targetBounds;
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
          new ShipScan.Bounds(
              inspection.base(), inspection.base().offset(width - 1, 31, depth - 1));
    }
  }

  public static void initialize() {
    TravelPackets.initialize();
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          ShipStore.get(server);
          recover(server);
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          com.nullandkale.nullspace.server.ServerContext.get(server).remove(PENDING);
          com.nullandkale.nullspace.server.ServerContext.get(server).remove(ARRIVALS);
          ShipStore.forget(server);
        });
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.TRAVEL, TravelService::tick);
    // Fabric JOIN can run before PlayerList finishes adding the player to its captured
    // login level. Cross-dimension teleporting there leaves a callback in the wrong world.
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) ->
            com.nullandkale.nullspace.server.ServerContext.get(server)
                .service(ARRIVALS, HashSet::new)
                .add(handler.player.getUUID()));
  }

  public static boolean locked(ServerLevel level, BlockPos pos) {
    if (worldMutation()) return false;
    // Terra Harbor is not a transit area: its shell has its own player protection.
    return ShipRecall.locked(level, pos)
        || com.nullandkale.nullspace.station.StationService.buildingAt(level, pos)
        || com.nullandkale.nullspace.navigation.NavigationService.locked(level, pos)
        || physicalAreaBusy(level, new ShipScan.Bounds(pos, pos), null);
  }

  private static boolean physicalAreaBusy(
      ServerLevel level, ShipScan.Bounds bounds, Pending ignored) {
    String dimension = level.dimension().identifier().toString();
    return pending(level.getServer()).values().stream()
        .anyMatch(
            p ->
                p != ignored
                    && (p.sourceDimension.equals(dimension)
                            && p.sourceBounds.box().intersects(bounds.box())
                        || p.targetDimension.equals(dimension)
                            && p.targetBounds.box().intersects(bounds.box())));
  }

  public static boolean areaBusy(ServerLevel level, ShipScan.Bounds bounds) {
    return areaBusy(level, bounds, null, null);
  }

  static boolean areaBusy(ServerLevel level, ShipScan.Bounds bounds, UUID recallOwner) {
    return areaBusy(level, bounds, null, recallOwner);
  }

  private static boolean areaBusy(
      ServerLevel level, ShipScan.Bounds bounds, Pending ignored, UUID recallOwner) {
    return physicalAreaBusy(level, bounds, ignored)
        || ShipRecall.areaBusy(level, bounds, recallOwner)
        || com.nullandkale.nullspace.navigation.NavigationService.areaBusy(level, bounds);
  }

  public static boolean inTransit(MinecraftServer server, CompoundTag ship) {
    if (ship == null) return false;
    String id = ship.getStringOr("id", "");
    return physicalBusy(server, ship)
        || ShipRecall.active(server, id)
        || com.nullandkale.nullspace.navigation.NavigationService.pending(server, id);
  }

  public static boolean physicalBusy(MinecraftServer server, CompoundTag ship) {
    if (ship == null) return false;
    String id = ship.getStringOr("id", ""), dimension = ship.getStringOr("dimension", "");
    var bounds = ShipStore.bounds(ship);
    return pending(server).values().stream()
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
        || ship != null
            && com.nullandkale.nullspace.station.DockingService.boarding(
                server, ship.getStringOr("id", ""));
  }

  public static boolean active(MinecraftServer server) {
    return ShipRecall.active(server) || physicalTransferActive(server);
  }

  static boolean physicalTransferActive(MinecraftServer server) {
    return com.nullandkale.nullspace.station.StationService.exclusiveConstruction(server)
        || !ShipStore.get(server).list("stationBoarding").isEmpty()
        || !pending(server).isEmpty();
  }

  public static boolean physicalBusy(ServerPlayer player) {
    return pending(player.level().getServer()).values().stream()
        .anyMatch(p -> p.crew.containsKey(player.getUUID()));
  }

  public static boolean busy(ServerPlayer player) {
    return com.nullandkale.nullspace.station.DockingService.busy(player)
        || com.nullandkale.nullspace.navigation.NavigationService.busy(player)
        || physicalBusy(player);
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

  static void requestDetachment(
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
            || ShipDetachment.approved(player.level(), controller).isEmpty()))
      throw new IllegalArgumentException(
          "Review and confirm loose blocks before leaving them behind");
    if (com.nullandkale.nullspace.navigation.JourneyService.cruising(inspection.vessel()))
      throw new IllegalArgumentException("Finish the FTL journey before landing");
    if (!player.level().dimension().equals(SPACE)
        && !com.nullandkale.nullspace.systems.ShipSystems.enabled(inspection.vessel()))
      throw new IllegalArgumentException(
          com.nullandkale.nullspace.navigation.ShipControl.NO_SYSTEMS);
    if (ShipRecall.active(player))
      throw new IllegalArgumentException("Finish or cancel your recall before travelling");
    if (com.nullandkale.nullspace.station.DockingService.motionLocked(inspection.vessel()))
      throw new IllegalArgumentException(
          "Undock and finish station construction before travel; stations cannot launch or land");
    if (busy(player) || busy(server, inspection.vessel()))
      throw new IllegalArgumentException("This ship or its crew is already travelling or boarding");

    Pending pending = new Pending(player, controller, destination, inspection);
    if (areaBusy(player.level(), pending.sourceBounds))
      throw new IllegalArgumentException("This ship's area is already reserved for travel");
    if (destination.isBlank() || detaching) {
      int slot = 0;
      while (slot <= 7000 && !spaceSlotFree(server, slot)) slot++;
      if (slot > 7000) throw new IllegalArgumentException("No free physical ship regions remain");
      pending.targetBase = new BlockPos(slot * SPACE_SPACING, 96, 0);
      pending.targetDimension = SPACE.identifier().toString();
      pending.targetBounds =
          new ShipScan.Bounds(pending.targetBase, pending.targetBase.offset(31, 31, 31));
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
      if (!com.nullandkale.nullspace.navigation.PlanetSurfaces.surfaceOf(
          server,
          com.nullandkale.nullspace.navigation.LocalSpaces.address(inspection.vessel()),
          pad.dimension()))
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
          new ShipScan.Bounds(
              pending.targetBase,
              pending.targetBase.offset(pad.bounds().width() - 1, 31, pad.bounds().depth() - 1));
      var target = ShipStore.level(server, pad.dimension());
      if (areaBusy(target, pending.targetBounds))
        throw new IllegalArgumentException("This landing pad is already reserved by another ship");
    }
    if (inspection.vessel() != null)
      com.nullandkale.nullspace.navigation.NavigationService.closeShip(
          server, inspection.vessel().getStringOr("id", ""));
    if (player.level().dimension().equals(SPACE) && destination.isBlank())
      throw new IllegalArgumentException("Choose a registered landing destination");
    if (!player.level().dimension().equals(SPACE) && !destination.isBlank())
      throw new IllegalArgumentException("Launch to space before selecting a landing destination");
    if (!inspection.scan().pressure().breathable(BlockPos.containing(player.getEyePosition())))
      throw new IllegalArgumentException("Board the sealed cabin before travel");
    if (inspection.vessel() != null)
      com.nullandkale.nullspace.systems.ShipSystems.cancelTransit(
          player.level(), inspection.vessel());
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
    var arrivals = com.nullandkale.nullspace.server.ServerContext.get(server).remove(ARRIVALS);
    if (arrivals != null)
      for (var id : arrivals) {
        var player = server.getPlayerList().getPlayer(id);
        if (player != null) resolveArrival(player);
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
          if (changed instanceof ShipScan.Failure problem)
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
        var vessel = ShipStore.get(server).at(actor.level(), actor.blockPosition());
        if (vessel != null) pending.ship = vessel.getStringOr("id", pending.ship);
        broadcast(server, pending, "Boarding", pending.targetDimension, false);
      } catch (Exception error) {
        NullSpace.LOGGER.error("Ship transfer failed", error);
        try {
          recover(server);
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
        else if (player != null) clearTarget(player);
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

  static Result transferRecall(
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
    var server = source.getServer();
    var current =
        recall
            ? null
            : pending(server).values().stream()
                .filter(
                    p ->
                        p.actor.equals(actor.getUUID())
                            && p.computer.equals(controller)
                            && p.destination.equals(destination)
                            && p.sourceDimension.equals(source.dimension().identifier().toString()))
                .findFirst()
                .orElse(null);
    boolean detaching = current != null && current.destination.equals(DETACH);
    if (detaching && ShipDetachment.approved(source, controller).isEmpty())
      return Result.rejected("Loose blocks changed; review and confirm them again");
    if (areaBusy(source, inspected.scan().bounds(), current, recall ? actor.getUUID() : null))
      return Result.rejected("This ship's area is reserved by another travel or recall");
    com.nullandkale.nullspace.computer.ComputerService.flush(source.getServer());
    if (inspected.vessel() != null) {
      if (!recall
          && !detaching
          && com.nullandkale.nullspace.systems.ShipSystems.enabled(inspected.vessel())
          && com.nullandkale.nullspace.systems.ShipSystems.power(
                  source,
                  inspected.vessel(),
                  com.nullandkale.nullspace.systems.PowerBus.System.ENGINES)
              == 0) return Result.rejected("Propulsion lost power before transfer");
      com.nullandkale.nullspace.systems.ShipSystems.cancelTransit(source, inspected.vessel());
    }
    var store = ShipStore.get(server);
    var scan = inspected.scan();
    var sourceBase = inspected.base();
    boolean leavingPieces = !recall && !ShipDetachment.approved(source, controller).isEmpty();
    ServerLevel target;
    BlockPos targetBase;
    PadRegistry.Pad landing = null;
    var vessel = inspected.vessel();
    String id = vessel == null ? UUID.randomUUID().toString() : vessel.getStringOr("id", "");
    if (source.dimension().equals(SPACE) && !detaching) {
      landing =
          PadRegistry.read(server).stream()
              .filter(
                  p ->
                      destination.startsWith("id:")
                          ? p.id().equals(destination.substring(3))
                          : p.name().equalsIgnoreCase(destination.strip()))
              .findFirst()
              .orElse(null);
      if (landing == null)
        return Result.rejected("Unknown landing pad; use destinations to list names");
      if (!recall
          && !com.nullandkale.nullspace.navigation.PlanetSurfaces.surfaceOf(
              server,
              com.nullandkale.nullspace.navigation.LocalSpaces.address(vessel),
              landing.dimension()))
        return Result.rejected("Jump to the destination planet before landing at this pad");
      target =
          server.getLevel(
              ResourceKey.create(Registries.DIMENSION, Identifier.parse(landing.dimension())));
      if (target == null) return Result.rejected("Destination dimension is unavailable");
      var validation = PadRegistry.check(target, landing);
      if (validation.state() != PadRegistry.State.READY)
        return Result.rejected(validation.detail());
      var b = landing.bounds();
      targetBase = new BlockPos(b.minX(), b.y() + 1, b.minZ());
      if (scan.bounds().max().getX() - sourceBase.getX() >= b.width()
          || scan.bounds().max().getZ() - sourceBase.getZ() >= b.depth())
        return Result.rejected("Ship does not fit this pad");
      for (int x = b.minX(); x <= b.maxX(); x++)
        for (int z = b.minZ(); z <= b.maxZ(); z++)
          for (int y = b.y() + 1; y <= target.getMaxY(); y++)
            if (!target.getBlockState(new BlockPos(x, y, z)).isAir()
                && !ShipScan.cover(target.getBlockState(new BlockPos(x, y, z))))
              return blocked(
                  target,
                  new BlockPos(x, y, z),
                  "The landing column is obstructed. Clear this block above the destination pad,"
                      + " then try landing again.");
    } else {
      target = server.getLevel(SPACE);
      if (target == null)
        return Result.rejected("Space dimension is not loaded; restart with NullSpace enabled");
      if (current != null) targetBase = current.targetBase;
      else {
        int slot = 0;
        while (slot <= 7000 && !spaceSlotFree(server, slot)) slot++;
        if (slot > 7000) return Result.rejected("No free physical ship regions remain");
        targetBase = new BlockPos(slot * SPACE_SPACING, 96, 0);
      }
    }
    BlockPos delta = targetBase.subtract(sourceBase),
        targetMin = scan.bounds().min().offset(delta),
        targetMax = scan.bounds().max().offset(delta);
    if (areaBusy(
        target,
        new ShipScan.Bounds(targetMin, targetMax),
        current,
        recall ? actor.getUUID() : null))
      return Result.rejected("This destination is reserved by another ship's travel or recall");
    if (targetMin.getY() < target.getMinY()
        || targetMax.getY() > target.getMaxY()
        || !target.getWorldBorder().isWithinBounds(targetMin)
        || !target.getWorldBorder().isWithinBounds(targetMax))
      return Result.rejected("Destination exceeds world limits");
    for (BlockPos pos : BlockPos.betweenClosed(targetMin, targetMax)) {
      target.getChunk(pos);
      if (recall && !actor.mayInteract(target, pos))
        return Result.rejected("You cannot build on part of this landing pad");
      if (!target.getBlockState(pos).isAir() && !ShipScan.cover(target.getBlockState(pos)))
        return blocked(
            target,
            pos,
            "The destination area is occupied. Clear this block or choose another landing pad.");
    }
    for (int x = targetMin.getX() >> 4; x <= targetMax.getX() >> 4; x++)
      for (int z = targetMin.getZ() >> 4; z <= targetMax.getZ() >> 4; z++)
        target
            .getChunkSource()
            .addTicketWithRadius(
                net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(x, z), 2);
    if (!ShipEntities.loaded(target, new ShipScan.Bounds(targetMin, targetMax)))
      return Result.waiting();
    var blockers = ShipEntities.inBounds(target, new ShipScan.Bounds(targetMin, targetMax));
    if (!blockers.isEmpty()) {
      var blocker = blockers.getFirst();
      NullSpace.LOGGER.info(
          "Ship destination occupied: {} {} at {} in {}",
          blocker.getType(),
          blocker.getUUID(),
          blocker.position(),
          target.dimension().identifier());
      return Result.rejected(
          "The destination is occupied. Move this occupant clear or choose another landing pad."
              + "\nOccupant: "
              + ShipMessages.entity(blocker)
              + "\nLocation: "
              + blocker.blockPosition().toShortString());
    }
    if (!ShipEntities.loaded(source, scan.bounds())) return Result.waiting();
    var occupants = ShipOccupants.inspect(source, scan, recall);
    var cargo = occupants.cargo();
    var crew = occupants.crew();
    if (recall && !crew.isEmpty())
      return Result.rejected("Recall cancelled: someone boarded the ship");
    if (current != null
        && !crew.stream()
            .map(Entity::getUUID)
            .collect(java.util.stream.Collectors.toSet())
            .equals(
                current.crew.keySet().stream()
                    .filter(uuid -> server.getPlayerList().getPlayer(uuid) != null)
                    .collect(java.util.stream.Collectors.toSet())))
      return Result.rejected("Passenger list changed; inspect and launch again");
    var fuelShip = vessel == null ? new CompoundTag() : vessel;
    if (vessel == null) {
      fuelShip.putLong("base", sourceBase.asLong());
      fuelShip.putLong("controller", controller.asLong());
    }
    int fuelCost =
        com.nullandkale.nullspace.systems.FuelSystem.travelCost(
            source, fuelShip, com.nullandkale.nullspace.systems.FuelSystem.transfer(scan.blocks().size()));
    if (!recall
        && !detaching
        && !com.nullandkale.nullspace.systems.FuelSystem.spend(source, fuelShip, fuelCost))
      return Result.rejected(
          "Not enough fuel for travel: needs "
              + com.nullandkale.nullspace.systems.FuelSystem.display(fuelCost)
              + ". Load furnace fuel into a connected generator.");
    StructureTemplate template = new StructureTemplate();
    BlockPos min = scan.bounds().min(), max = scan.bounds().max();
    template.fillFromWorld(
        source,
        min,
        new Vec3i(
            max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1),
        false,
        List.of());
    if (target.dimension().equals(SPACE))
      com.nullandkale.nullspace.scene.SceneCatalog.forShip(server, vessel);
    CompoundTag journal = new CompoundTag();
    journal.putInt("version", 1);
    journal.putString("id", UUID.randomUUID().toString());
    journal.putString("source", source.dimension().identifier().toString());
    journal.putString("target", target.dimension().identifier().toString());
    journal.putLong("min", min.asLong());
    journal.putLong("max", max.asLong());
    journal.putLong("delta", delta.asLong());
    journal.put("template", template.save(new CompoundTag()));
    VesselRespawns.captureSurface(source,id,sourceBase,scan.blocks());
    journal.put("before", store.snapshot());
    ListTag entities = new ListTag();
    var cargoIds = new HashSet<UUID>();
    for (Entity entity : cargo) cargoIds.add(entity.getUUID());
    // Roots are entities not riding other cargo; riders move and are journaled with their mount.
    var roots = new ArrayList<Entity>();
    for (Entity entity : cargo) {
      var vehicle = entity.getVehicle();
      if (vehicle != null && cargoIds.contains(vehicle.getUUID())) continue;
      if (vehicle != null) entity.stopRiding(); // Riding something that stays behind.
      roots.add(entity);
    }
    for (Entity entity : roots) {
      var output =
          TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess());
      if (!entity.save(output))
        return Result.rejected(
            "This passenger cannot be saved for travel. Move it off the ship and try again."
                + "\nOccupant: "
                + ShipMessages.entity(entity)
                + "\nLocation: "
                + entity.blockPosition().toShortString());
      var entry = new CompoundTag();
      entry.putString("id", entity.getUUID().toString());
      entry.put("data", output.buildResult());
      var riders = new ListTag();
      for (Entity rider : entity.getIndirectPassengers())
        if (!(rider instanceof net.minecraft.world.entity.player.Player))
          riders.add(StringTag.valueOf(rider.getUUID().toString()));
      entry.put("riders", riders);
      entities.add(entry);
    }
    journal.put("entities", entities);
    CompoundTag after = store.snapshot();
    ListTag ships = after.getListOrEmpty("ships");
    if (!after.contains("ships")) {
      ships = new ListTag();
      after.put("ships", ships);
    }
    ships.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
    if (detaching) {
      // Independent physical allocation and scene actor: the debris cannot follow the ship
      // through later local flight or an FTL region change. It keeps its original inventories.
      var debris = new CompoundTag();
      debris.putString("id", UUID.randomUUID().toString());
      debris.putString("kind", "debris");
      debris.putString("home", "Loose blocks");
      debris.putString("dimension", source.dimension().identifier().toString());
      debris.putLong("base", sourceBase.asLong());
      for (String key :
          List.of(
              "width",
              "depth",
              "height",
              "localSpace",
              "simX",
              "simY",
              "simZ",
              "simYaw",
              "simPitch",
              "simRoll",
              "owner",
              "access")) if (vessel.contains(key)) debris.put(key, vessel.get(key).copy());
      ships.add(debris);
    }
    var next = vessel == null ? new CompoundTag() : vessel.copy();
    next.putString("id", id);
    next.putString("dimension", target.dimension().identifier().toString());
    if (!target.dimension().equals(SPACE)) next.remove("journey");
    next.putInt("width", landing == null ? 32 : landing.bounds().width());
    next.putInt("depth", landing == null ? 32 : landing.bounds().depth());
    next.putInt("height", landing == null ? 32 : PadRegistry.buildHeight(target, landing.bounds()));
    next.putLong("base", targetBase.asLong());
    if (!source.dimension().equals(SPACE)) {
      String address = com.nullandkale.nullspace.navigation.PlanetSurfaces.address(source);
      if (address != null) next.putString("localSpace", address);
    }
    if (target.dimension().equals(SPACE) && !next.contains("simX")) {
      com.nullandkale.nullspace.navigation.NavigationService.randomizeArrival(server, next);
      var arrival =
          com.nullandkale.nullspace.navigation.NavigationService.arrivalOrigin(server, next);
      next.putDouble("simX", arrival.x);
      next.putDouble("simY", arrival.y);
      next.putDouble("simZ", arrival.z);
    }
    next.putLong("controller", controller.offset(delta).asLong());
    var home = landing != null ? landing : inspected.pad();
    if (home != null) {
      next.putString("home", home.name());
      next.putString("homeId", home.id());
    }
    ShipRecall.authorize(next, actor);
    // Recall lands an empty ship on a planet pad; storing moves one into space instead.
    if (recall && !target.dimension().equals(SPACE)) {
      String region = com.nullandkale.nullspace.navigation.PlanetSurfaces.address(target);
      if (region != null) next.putString("localSpace", region);
      com.nullandkale.nullspace.navigation.NavigationService.stop(next);
      for (String field : List.of("simX", "simY", "simZ")) next.remove(field);
    }
    ListTag members = new ListTag();
    if (vessel != null)
      for (Tag t : vessel.getListOrEmpty("crew")) {
        var member = ((CompoundTag) t).copy();
        UUID uuid = UUID.fromString(member.getStringOr("id", ""));
        if (server.getPlayerList().getPlayer(uuid) == null) {
          if (leavingPieces) safeOfflineArrival(source, scan, sourceBase, member);
          members.add(member);
          ShipStore.target(
              after,
              uuid,
              target.dimension().identifier().toString(),
              targetBase.getX() + member.getDoubleOr("x", 0),
              targetBase.getY() + member.getDoubleOr("y", 0),
              targetBase.getZ() + member.getDoubleOr("z", 0),
              member.getFloatOr("yaw", 0),
              member.getFloatOr("pitch", 0));
        }
      }
    ListTag passengers = new ListTag();
    for (ServerPlayer player : crew) {
      var member = new CompoundTag();
      member.putString("id", player.getUUID().toString());
      member.putDouble("x", player.getX() - sourceBase.getX());
      member.putDouble("y", player.getY() - sourceBase.getY());
      member.putDouble("z", player.getZ() - sourceBase.getZ());
      member.putFloat("yaw", player.getYRot());
      member.putFloat("pitch", player.getXRot());
      members.add(member);
      var original = new CompoundTag();
      original.putString("id", player.getUUID().toString());
      original.putDouble("x", player.getX());
      original.putDouble("y", player.getY());
      original.putDouble("z", player.getZ());
      original.putFloat("yaw", player.getYRot());
      original.putFloat("pitch", player.getXRot());
      passengers.add(original);
      ShipStore.target(
          after,
          player.getUUID(),
          target.dimension().identifier().toString(),
          player.getX() + delta.getX(),
          player.getY() + delta.getY(),
          player.getZ() + delta.getZ(),
          player.getYRot(),
          player.getXRot());
    }
    // A passenger can disconnect after agreeing to the loading screen, including on the first
    // launch.
    if (current != null)
      for (var entry : current.crew.entrySet()) {
        UUID uuid = entry.getKey();
        if (server.getPlayerList().getPlayer(uuid) != null) continue;
        if (members.stream()
            .anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(uuid.toString())))
          continue;
        Vec3 position = entry.getValue();
        var member = new CompoundTag();
        member.putString("id", uuid.toString());
        member.putDouble("x", position.x - sourceBase.getX());
        member.putDouble("y", position.y - sourceBase.getY());
        member.putDouble("z", position.z - sourceBase.getZ());
        if (leavingPieces) safeOfflineArrival(source, scan, sourceBase, member);
        members.add(member);
        ShipStore.target(
            after,
            uuid,
            target.dimension().identifier().toString(),
            targetBase.getX() + member.getDoubleOr("x", 0),
            targetBase.getY() + member.getDoubleOr("y", 0),
            targetBase.getZ() + member.getDoubleOr("z", 0),
            0,
            0);
        var original = new CompoundTag();
        original.putString("id", uuid.toString());
        original.putDouble("x", position.x);
        original.putDouble("y", position.y);
        original.putDouble("z", position.z);
        passengers.add(original);
      }
    next.put("crew", members);
    // Boarding another ship commits one authoritative crew assignment. An old ship
    // must not later recall or relocate this passenger through a stale crew entry.
    var boarded =
        members.stream()
            .map(t -> ((CompoundTag) t).getStringOr("id", ""))
            .collect(java.util.stream.Collectors.toSet());
    for (var tag : ships)
      ((CompoundTag) tag)
          .getListOrEmpty("crew")
          .removeIf(t -> boarded.contains(((CompoundTag) t).getStringOr("id", "")));
    ships.add(next);
    journal.put("players", passengers);
    for (Entity entity : cargo)
      authority(
          after,
          entity.getUUID(),
          target.dimension().identifier().toString(),
          source.dimension().identifier().toString(),
          min,
          max);
    journal.put("after", after);
    Path file =
        store.directory.resolve("transfers").resolve(journal.getStringOr("id", "") + ".nbt");
    checkpoint(file, journal, "PREPARED");
    // Force close any menus retaining references to source containers. Only real container
    // menus need closing: a terminal screen (for example My ships beside the pad) holds no
    // ship container, and closing it mid-session left players stuck (playtest 2).
    for (ServerPlayer player : source.players())
      if (scan.bounds().box().inflate(8).contains(player.position())
          && player.containerMenu != player.inventoryMenu) player.closeContainer();
    var destinationSnapshot =
        ShipBlockSupport.destinationTemplate(
            ShipBlockSupport.connectedTemplate(
                journal.getCompoundOrEmpty("template"), min, scan.blocks()),
            scan.blocks(),
            delta);
    var destinationTemplate = new StructureTemplate();
    destinationTemplate.load(
        target.registryAccess().lookupOrThrow(Registries.BLOCK), destinationSnapshot);
    // Snow or grass on the pad gives way to the landing ship instead of ending up inside it.
    for (var at : BlockPos.betweenClosed(targetMin, targetMax))
      if (ShipScan.cover(target.getBlockState(at)))
        target.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
    place(target, destinationTemplate, targetMin);
    var area = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(min, max);
    target.getBlockTicks().copyAreaFrom(source.getBlockTicks(), area, delta);
    target.getFluidTicks().copyAreaFrom(source.getFluidTicks(), area, delta);
    checkpoint(file, journal, "DESTINATION_STAGED");
    ShipScan.clearCover(source, scan.blocks());
    ShipPlacement.clear(source, scan.blocks());
    checkpoint(file, journal, "SOURCE_CLEARED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    // Leashes held by something that travels too are detached quietly and reattached after.
    var leashes = new LinkedHashMap<UUID, UUID>();
    var crewIds = new HashSet<UUID>();
    for (ServerPlayer player : crew) crewIds.add(player.getUUID());
    for (Entity entity : cargo)
      if (entity instanceof Leashable leashed && leashed.getLeashHolder() instanceof Entity holder
          && (cargoIds.contains(holder.getUUID()) || crewIds.contains(holder.getUUID()))) {
        leashes.put(entity.getUUID(), holder.getUUID());
        leashed.removeLeash();
      }
    var mounts = new LinkedHashMap<ServerPlayer, UUID>();
    for (ServerPlayer player : crew)
      if (player.getVehicle() != null && cargoIds.contains(player.getVehicle().getUUID())) {
        mounts.put(player, player.getVehicle().getUUID());
        player.stopRiding();
      }
    for (Entity entity : roots) {
      var moved =
          entity.teleport(
              new TeleportTransition(
                  target,
                  entity.position().add(delta.getX(), delta.getY(), delta.getZ()),
                  entity.getDeltaMovement(),
                  entity.getYRot(),
                  entity.getXRot(),
                  TeleportTransition.DO_NOTHING));
      if (moved == null) throw new IllegalStateException("Could not transfer passenger entity");
    }
    for (ServerPlayer player : crew)
      player.teleport(
          new TeleportTransition(
              target,
              player.position().add(delta.getX(), delta.getY(), delta.getZ()),
              Vec3.ZERO,
              player.getYRot(),
              player.getXRot(),
              TeleportTransition.PLACE_PORTAL_TICKET));
    for (var mount : mounts.entrySet()) {
      var vehicle = target.getEntity(mount.getValue());
      if (vehicle != null) mount.getKey().startRiding(vehicle, true, true);
    }
    for (var leash : leashes.entrySet()) {
      var holder = target.getEntity(leash.getValue());
      if (target.getEntity(leash.getKey()) instanceof Leashable leashed && holder != null)
        leashed.setLeashedTo(holder, true);
    }
    checkpoint(file, journal, "PASSENGERS_MOVED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    store.save();
    server.saveEverything(true, true, true);
    checkpoint(file, journal, "COMMITTED");
    return new Result(Outcome.COMPLETED, "Arrived");
  }

  /** A journaled entity and every rider saved inside it. */
  private static Set<UUID> journaled(CompoundTag entry) {
    var ids = new HashSet<UUID>();
    ids.add(UUID.fromString(entry.getStringOr("id", "")));
    for (Tag rider : entry.getListOrEmpty("riders"))
      ids.add(UUID.fromString(rider.asString().orElse("")));
    return ids;
  }

  private static void checkpoint(Path file, CompoundTag journal, String stage) {
    journal.putString("stage", stage);
    ShipStore.write(file, journal);
    failureInjector.accept(stage);
  }

  private static void safeOfflineArrival(
      ServerLevel source, ShipScan.Result scan, BlockPos base, CompoundTag member) {
    var feet =
        BlockPos.containing(
            base.getX() + member.getDoubleOr("x", 0),
            base.getY() + member.getDoubleOr("y", 0),
            base.getZ() + member.getDoubleOr("z", 0));
    if (scan.pressure().breathable(feet)
        && scan.pressure().breathable(feet.above())
        && scan.blocks().contains(feet.below())
        && source.getBlockState(feet.below()).isCollisionShapeFullBlock(source, feet.below()))
      return;
    // An offline passenger's old room may be in the abandoned component. Reconnect in the
    // validated cabin, never at an offset that has become empty space in the departing ship.
    var safe = scan.boarding().subtract(base);
    member.putDouble("x", safe.getX() + .5);
    member.putDouble("y", safe.getY());
    member.putDouble("z", safe.getZ() + .5);
  }

  static void place(ServerLevel level, StructureTemplate template, BlockPos min) {
    ShipPlacement.place(level, template, min);
  }

  static void clear(ServerLevel level, BlockPos min, BlockPos max) {
    ShipPlacement.clear(level, min, max);
  }

  public static void recover(MinecraftServer server) {
    var store = ShipStore.get(server);
    try (var files = Files.list(store.directory.resolve("transfers"))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".nbt")).sorted().toList()) {
        var journal = ShipStore.read(file);
        if (journal.getIntOr("version", 0) != 1)
          throw new IllegalStateException("Unsupported transfer journal");
        if (journal.getStringOr("stage", "").equals("COMMITTED")
            || journal.getStringOr("stage", "").equals("ROLLED_BACK")) continue;
        var source = ShipStore.level(server, journal.getStringOr("source", ""));
        var target = ShipStore.level(server, journal.getStringOr("target", ""));
        BlockPos min = ShipStore.pos(journal, "min"),
            max = ShipStore.pos(journal, "max"),
            delta = ShipStore.pos(journal, "delta");
        store.replace(
            journal.getCompoundOrEmpty("before").copy(), ShipRepository.Mutation.RECOVERY);
        for (Tag tag : journal.getListOrEmpty("entities"))
          for (UUID moved : journaled((CompoundTag) tag))
            authorityStore(
                store,
                moved,
                source.dimension().identifier().toString(),
                target.dimension().identifier().toString(),
                min.offset(delta),
                max.offset(delta));
        clear(target, min.offset(delta), max.offset(delta));
        var template = new StructureTemplate();
        template.load(
            source.registryAccess().lookupOrThrow(Registries.BLOCK),
            journal.getCompoundOrEmpty("template"));
        place(source, template, min);
        for (Tag tag : journal.getListOrEmpty("entities")) {
          var entry = (CompoundTag) tag;
          UUID id = UUID.fromString(entry.getStringOr("id", ""));
          var ids = journaled(entry);
          for (var old : ShipEntities.inBounds(source, new ShipScan.Bounds(min, max)))
            if (ids.contains(old.getUUID())) old.discard();
          for (var old :
              ShipEntities.inBounds(
                  target, new ShipScan.Bounds(min.offset(delta), max.offset(delta))))
            if (ids.contains(old.getUUID())) old.discard();
          var entity =
              EntityType.loadEntityRecursive(
                  TagValueInput.create(
                      ProblemReporter.DISCARDING,
                      source.registryAccess(),
                      entry.getCompoundOrEmpty("data")),
                  source,
                  EntitySpawnReason.LOAD,
                  e -> e);
          if (entity == null) throw new IllegalStateException("Could not restore passenger " + id);
          source.addFreshEntityWithPassengers(entity);
          if (entity.isRemoved() || source.getEntity(entity.getUUID()) != entity)
            throw new IllegalStateException("Could not establish authoritative passenger " + id);
        }
        for (Tag tag : journal.getListOrEmpty("players")) {
          var p = (CompoundTag) tag;
          targetStore(
              store,
              UUID.fromString(p.getStringOr("id", "")),
              source.dimension().identifier().toString(),
              p.getDoubleOr("x", 0),
              p.getDoubleOr("y", 0),
              p.getDoubleOr("z", 0),
              p.getFloatOr("yaw", 0),
              p.getFloatOr("pitch", 0));
        }
        store.save();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) resolveArrival(player);
        server.saveEverything(true, true, true);
        journal.putString("stage", "ROLLED_BACK");
        ShipStore.write(file, journal);
      }
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot recover transfer journal", error);
    }
  }

  public static void resolveArrival(ServerPlayer player) {
    var store = ShipStore.get(player.level().getServer());
    for (Tag item : store.list("targets")) {
      var target = (CompoundTag) item;
      if (!target.getStringOr("id", "").equals(player.getUUID().toString())) continue;
      var level = ShipStore.level(player.level().getServer(), target.getStringOr("dimension", ""));
      player.teleport(
          new TeleportTransition(
              level,
              new Vec3(
                  target.getDoubleOr("x", 0),
                  target.getDoubleOr("y", 0),
                  target.getDoubleOr("z", 0)),
              Vec3.ZERO,
              target.getFloatOr("yaw", 0),
              target.getFloatOr("pitch", 0),
              TeleportTransition.PLACE_PORTAL_TICKET));
      clearTarget(player);
      break;
    }
  }

  private static void clearTarget(ServerPlayer player) {
    var store = ShipStore.get(player.level().getServer());
    store
        .list("targets")
        .removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(player.getUUID().toString()));
    store.save();
  }

  private static void targetStore(
      ShipStore store,
      UUID id,
      String dimension,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {
    var targets = store.list("targets");
    targets.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id.toString()));
    var target = new CompoundTag();
    target.putString("id", id.toString());
    target.putString("dimension", dimension);
    target.putDouble("x", x);
    target.putDouble("y", y);
    target.putDouble("z", z);
    target.putFloat("yaw", yaw);
    target.putFloat("pitch", pitch);
    targets.add(target);
  }

  private static void authorityStore(
      ShipStore store, UUID id, String dimension, String obsolete, BlockPos min, BlockPos max) {
    var root = new CompoundTag();
    root.put("entityAuthority", store.list("entityAuthority"));
    authority(root, id, dimension, obsolete, min, max);
  }

  private static void authority(
      CompoundTag root, UUID id, String dimension, String obsolete, BlockPos min, BlockPos max) {
    if (!root.contains("entityAuthority")) root.put("entityAuthority", new ListTag());
    var list = root.getListOrEmpty("entityAuthority");
    list.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id.toString()));
    var tag = new CompoundTag();
    tag.putString("id", id.toString());
    tag.putString("dimension", dimension);
    tag.putString("obsoleteDimension", obsolete);
    tag.putLong("min", min.asLong());
    tag.putLong("max", max.asLong());
    list.add(tag);
  }

  private TravelService() {}
}
