package com.nullandkale.nullspace.gameplay.travel;

import static com.nullandkale.nullspace.gameplay.travel.TravelService.SPACE;
import static com.nullandkale.nullspace.gameplay.travel.TravelService.SPACE_SPACING;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.crew.ShipRespawns;
import com.nullandkale.nullspace.gameplay.navigation.Jumps;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.ownership.CrewRelease;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.BlockSupport;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.LooseBlocks;
import com.nullandkale.nullspace.gameplay.ship.structure.Occupants;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Outcome;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Pending;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Result;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.power.PowerBus;
import java.nio.file.Path;
import java.util.*;
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

/**
 * One physical ship move (launch, landing, detaching loose blocks, recall or storage), run as a
 * sequence of phases. Checks come first and may stop the move. From the PREPARED checkpoint on, the
 * transfer journal records each stage so a crash can be replayed or rolled back (TransferRecovery).
 */
final class ShipTransfer {
  // What was asked for
  private final ServerPlayer actor;
  private final ServerLevel source;
  private final BlockPos controller;
  private final String destination;
  private final ShipService.Inspection inspected;
  private final boolean recall;
  private final MinecraftServer server;

  // Source ship
  private Pending current;
  private boolean detaching, leavingPieces;
  private WorldStore store;
  private HullScan.Result scan;
  private BlockPos sourceBase, min, max;
  private CompoundTag vessel;
  private String id;

  // Destination
  private ServerLevel target;
  private BlockPos targetBase, delta, targetMin, targetMax;
  private PadRegistry.Pad landing;

  // What travels
  private List<Entity> cargo;
  private List<ServerPlayer> crew;
  private final Set<UUID> cargoIds = new HashSet<>();
  private final List<Entity> roots = new ArrayList<>();

  // Journal
  private CompoundTag journal, after;
  private ListTag ships;
  private Path file;

  ShipTransfer(
      ServerPlayer actor,
      ServerLevel source,
      BlockPos controller,
      String destination,
      ShipService.Inspection inspected,
      boolean recall) {
    this.actor = actor;
    this.source = source;
    this.controller = controller;
    this.destination = destination;
    this.inspected = inspected;
    this.recall = recall;
    this.server = source.getServer();
  }

  Result run() {
    Result stop;
    if ((stop = checkSource()) != null) return stop;
    if ((stop = chooseDestination()) != null) return stop;
    if ((stop = checkDestination()) != null) return stop;
    if ((stop = checkPassengersAndFuel()) != null) return stop;
    captureHull();
    if ((stop = saveCargo()) != null) return stop;
    buildAfterState();
    TransferRecovery.checkpoint(file, journal, "PREPARED");
    stageDestination();
    TransferRecovery.checkpoint(file, journal, "DESTINATION_STAGED");
    HullScan.clearCover(source, scan.blocks());
    TransferReplay.clear(source, scan.blocks());
    TransferRecovery.checkpoint(file, journal, "SOURCE_CLEARED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    movePassengers();
    TransferRecovery.checkpoint(file, journal, "PASSENGERS_MOVED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    store.save();
    server.saveEverything(true, true, true);
    TransferRecovery.checkpoint(file, journal, "COMMITTED");
    return new Result(Outcome.COMPLETED, "Arrived");
  }

  /** The request still matches, nothing else holds the area, and engines still have power. */
  private Result checkSource() {
    current =
        recall
            ? null
            : TravelService.pending(server).values().stream()
                .filter(
                    p ->
                        p.actor.equals(actor.getUUID())
                            && p.computer.equals(controller)
                            && p.destination.equals(destination)
                            && p.sourceDimension.equals(source.dimension().identifier().toString()))
                .findFirst()
                .orElse(null);
    detaching = current != null && current.destination.equals(TravelService.DETACH);
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
    store = WorldStore.get(server);
    scan = inspected.scan();
    sourceBase = inspected.base();
    leavingPieces = !recall && !LooseBlocks.approved(source, controller).isEmpty();
    vessel = inspected.vessel();
    id = vessel == null ? UUID.randomUUID().toString() : vessel.getStringOr("id", "");
    return null;
  }

  /** A clear, registered landing pad below, or a free storage slot in space. */
  private Result chooseDestination() {
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
              return TravelService.blocked(
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
    return null;
  }

  /** The destination is inside the world, unreserved, loaded and empty of blocks and entities. */
  private Result checkDestination() {
    delta = targetBase.subtract(sourceBase);
    targetMin = scan.bounds().min().offset(delta);
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
        return TravelService.blocked(
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
    return null;
  }

  /** Crew still match the loading screen, recall moves no one, and launch fuel is paid. */
  private Result checkPassengersAndFuel() {
    if (!HullEntities.loaded(source, scan.bounds())) return Result.waiting();
    var occupants = Occupants.inspect(source, scan, recall);
    cargo = occupants.cargo();
    crew = occupants.crew();
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
    return null;
  }

  /** Copies the hull's blocks into the journal, with the save state before the move. */
  private void captureHull() {
    StructureTemplate template = new StructureTemplate();
    min = scan.bounds().min();
    max = scan.bounds().max();
    template.fillFromWorld(
        source,
        min,
        new Vec3i(
            max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1),
        false,
        List.of());
    if (target.dimension().equals(SPACE)) SceneCatalog.forShip(server, vessel);
    journal = new CompoundTag();
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
  }

  /**
   * Saves every non-player passenger into the journal. Roots are entities not riding other cargo;
   * riders move and are journaled with their mount.
   */
  private Result saveCargo() {
    ListTag entities = new ListTag();
    for (Entity entity : cargo) cargoIds.add(entity.getUUID());
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
    return null;
  }

  /** The save state after the move: the moved ship's record, its crew and any left debris. */
  private void buildAfterState() {
    after = store.snapshot();
    ships = after.getListOrEmpty("ships");
    if (!after.contains("ships")) {
      ships = new ListTag();
      after.put("ships", ships);
    }
    ships.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
    if (detaching) ships.add(debris());
    var next = movedShip();
    var passengers = new ListTag();
    var members = crewRecords(passengers);
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
    file = store.directory.resolve("transfers").resolve(journal.getStringOr("id", "") + ".nbt");
  }

  /**
   * Loose blocks left behind become their own record. Independent physical allocation and scene
   * actor: the debris cannot follow the ship through later local flight or an FTL region change. It
   * keeps its original inventories.
   */
  private CompoundTag debris() {
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
    return debris;
  }

  /** The ship's record at its destination: place, size, region, scene position and home pad. */
  private CompoundTag movedShip() {
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
    return next;
  }

  /**
   * Crew records for the moved ship, with where each player arrives. Online crew travel now;
   * offline crew (and passengers who disconnected after agreeing to the loading screen, including
   * on the first launch) arrive when they next log in. Fills {@code passengers} with where each
   * travelling player started, for rollback.
   */
  private ListTag crewRecords(ListTag passengers) {
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
    return members;
  }

  /** Places the hull's blocks and scheduled ticks at the destination. */
  private void stageDestination() {
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
  }

  /**
   * Teleports cargo and crew with the hull. Leashes and mounts between travellers are detached
   * quietly first and restored after, so nothing is dropped or left behind.
   */
  private void movePassengers() {
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
  }
}
