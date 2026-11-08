package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.crew.ShipRespawns;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.ControllerChecks;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.Jumps;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.ownership.CrewRelease;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.BlockSupport;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.LooseBlocks;
import com.nullandkale.nullspace.gameplay.ship.structure.Occupants;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;
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

  private static Result blocked(ServerLevel level, BlockPos at, String advice) {
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
    if (detaching && LooseBlocks.approved(source, controller).isEmpty())
      return Result.rejected("Loose blocks changed; review and confirm them again");
    if (TransferLocks.areaBusy(
        source, inspected.scan().bounds(), current, recall ? actor.getUUID() : null))
      return Result.rejected("This ship's area is reserved by another travel or recall");
    ComputerService.flush(source.getServer());
    if (inspected.vessel() != null) {
      if (!recall
          && !detaching
          && ShipSystems.enabled(inspected.vessel())
          && ShipSystems.power(source, inspected.vessel(), PowerBus.System.ENGINES) == 0)
        return Result.rejected("Propulsion lost power before transfer");
      ShipSystems.cancelTransit(source, inspected.vessel());
    }
    var store = WorldStore.get(server);
    var scan = inspected.scan();
    var sourceBase = inspected.base();
    boolean leavingPieces = !recall && !LooseBlocks.approved(source, controller).isEmpty();
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
          && !PlanetSurfaces.surfaceOf(server, LocalSpaces.address(vessel), landing.dimension()))
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
                && !HullScan.cover(target.getBlockState(new BlockPos(x, y, z))))
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
        while (slot <= 7000 && !TransferLocks.spaceSlotFree(server, slot)) slot++;
        if (slot > 7000) return Result.rejected("No free physical ship regions remain");
        targetBase = new BlockPos(slot * SPACE_SPACING, 96, 0);
      }
    }
    BlockPos delta = targetBase.subtract(sourceBase),
        targetMin = scan.bounds().min().offset(delta),
        targetMax = scan.bounds().max().offset(delta);
    if (TransferLocks.areaBusy(
        target,
        new HullScan.Bounds(targetMin, targetMax),
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
      if (!target.getBlockState(pos).isAir() && !HullScan.cover(target.getBlockState(pos)))
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
    if (!HullEntities.loaded(target, new HullScan.Bounds(targetMin, targetMax)))
      return Result.waiting();
    var blockers = HullEntities.inBounds(target, new HullScan.Bounds(targetMin, targetMax));
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
    if (!HullEntities.loaded(source, scan.bounds())) return Result.waiting();
    var occupants = Occupants.inspect(source, scan, recall);
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
        FuelSystem.travelCost(source, fuelShip, FuelSystem.transfer(scan.blocks().size()));
    if (!recall && !detaching && !FuelSystem.spend(source, fuelShip, fuelCost))
      return Result.rejected(
          "Not enough fuel for travel: needs "
              + FuelSystem.display(fuelCost)
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
    if (target.dimension().equals(SPACE)) SceneCatalog.forShip(server, vessel);
    CompoundTag journal = new CompoundTag();
    journal.putInt("version", 1);
    journal.putString("id", UUID.randomUUID().toString());
    journal.putString("source", source.dimension().identifier().toString());
    journal.putString("target", target.dimension().identifier().toString());
    journal.putLong("min", min.asLong());
    journal.putLong("max", max.asLong());
    journal.putLong("delta", delta.asLong());
    journal.put("template", template.save(new CompoundTag()));
    ShipRespawns.captureSurface(source, id, sourceBase, scan.blocks());
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
      String address = PlanetSurfaces.address(source);
      if (address != null) next.putString("localSpace", address);
    }
    if (target.dimension().equals(SPACE) && !next.contains("simX")) {
      Jumps.randomizeArrival(server, next);
      var arrival = Jumps.arrivalOrigin(server, next);
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
    CrewRelease.authorize(next, actor);
    // Recall lands an empty ship on a planet pad; storing moves one into space instead.
    if (recall && !target.dimension().equals(SPACE)) {
      String region = PlanetSurfaces.address(target);
      if (region != null) next.putString("localSpace", region);
      Piloting.stop(next);
      for (String field : List.of("simX", "simY", "simZ")) next.remove(field);
    }
    ListTag members = new ListTag();
    if (vessel != null)
      for (Tag t : vessel.getListOrEmpty("crew")) {
        var member = ((CompoundTag) t).copy();
        UUID uuid = UUID.fromString(member.getStringOr("id", ""));
        if (server.getPlayerList().getPlayer(uuid) == null) {
          if (leavingPieces) TransferRecovery.safeOfflineArrival(source, scan, sourceBase, member);
          members.add(member);
          WorldStore.target(
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
      WorldStore.target(
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
        if (leavingPieces) TransferRecovery.safeOfflineArrival(source, scan, sourceBase, member);
        members.add(member);
        WorldStore.target(
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
      TransferRecovery.authority(
          after,
          entity.getUUID(),
          target.dimension().identifier().toString(),
          source.dimension().identifier().toString(),
          min,
          max);
    journal.put("after", after);
    Path file =
        store.directory.resolve("transfers").resolve(journal.getStringOr("id", "") + ".nbt");
    TransferRecovery.checkpoint(file, journal, "PREPARED");
    // Force close any menus retaining references to source containers. Only real container
    // menus need closing: a terminal screen (for example My ships beside the pad) holds no
    // ship container, and closing it mid-session left players stuck (playtest 2).
    for (ServerPlayer player : source.players())
      if (scan.bounds().box().inflate(8).contains(player.position())
          && player.containerMenu != player.inventoryMenu) player.closeContainer();
    var destinationSnapshot =
        BlockSupport.destinationTemplate(
            BlockSupport.connectedTemplate(
                journal.getCompoundOrEmpty("template"), min, scan.blocks()),
            scan.blocks(),
            delta);
    var destinationTemplate = new StructureTemplate();
    destinationTemplate.load(
        target.registryAccess().lookupOrThrow(Registries.BLOCK), destinationSnapshot);
    // Snow or grass on the pad gives way to the landing ship instead of ending up inside it.
    for (var at : BlockPos.betweenClosed(targetMin, targetMax))
      if (HullScan.cover(target.getBlockState(at)))
        target.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
    TransferRecovery.place(target, destinationTemplate, targetMin);
    var area = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(min, max);
    target.getBlockTicks().copyAreaFrom(source.getBlockTicks(), area, delta);
    target.getFluidTicks().copyAreaFrom(source.getFluidTicks(), area, delta);
    TransferRecovery.checkpoint(file, journal, "DESTINATION_STAGED");
    HullScan.clearCover(source, scan.blocks());
    TransferReplay.clear(source, scan.blocks());
    TransferRecovery.checkpoint(file, journal, "SOURCE_CLEARED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    // Leashes held by something that travels too are detached quietly and reattached after.
    var leashes = new LinkedHashMap<UUID, UUID>();
    var crewIds = new HashSet<UUID>();
    for (ServerPlayer player : crew) crewIds.add(player.getUUID());
    for (Entity entity : cargo)
      if (entity instanceof Leashable leashed
          && leashed.getLeashHolder() instanceof Entity holder
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
    TransferRecovery.checkpoint(file, journal, "PASSENGERS_MOVED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    store.save();
    server.saveEverything(true, true, true);
    TransferRecovery.checkpoint(file, journal, "COMMITTED");
    return new Result(Outcome.COMPLETED, "Arrived");
  }

  private TravelService() {}
}
