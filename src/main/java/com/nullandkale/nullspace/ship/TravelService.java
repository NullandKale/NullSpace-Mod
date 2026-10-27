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

  public static boolean spaceSlotFree(MinecraftServer server, int slot) {
    if (slot < 0 || slot > 7000) return false;
    var base = new BlockPos(slot * SPACE_SPACING, 96, 0);
    var area =
        new net.minecraft.world.phys.AABB(
            base.getX() - 128, -32, -128, base.getX() + 160, 256, 160);
    return ShipStore.get(server).ships().stream()
        .map(t -> (CompoundTag) t)
        .noneMatch(
            ship ->
                ship.getStringOr("dimension", "").equals(SPACE.identifier().toString())
                    && area.intersects(ShipStore.bounds(ship).box().inflate(128)));
  }

  public static final ResourceKey<Level> SPACE =
      ResourceKey.create(Registries.DIMENSION, Identifier.parse("nullspace:space"));
  private static final com.nullandkale.nullspace.server.ServerContext.Key<Pending> PENDING =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();
  private static final com.nullandkale.nullspace.server.ServerContext.Key<Set<UUID>> ARRIVALS =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  public static boolean worldMutation() {
    return ShipPlacement.worldMutation();
  }

  public static Consumer<String> failureInjector = stage -> {};

  public static final class SimulatedCrash extends Error {}

  private static final class DestinationLoading extends RuntimeException {}

  private static final class Pending {
    final UUID id = UUID.randomUUID();
    final UUID actor;
    final BlockPos computer;
    final String destination;
    final Map<UUID, Vec3> crew = new HashMap<>();
    final Set<UUID> ready = new HashSet<>(), arrived = new HashSet<>();
    int ticks;
    boolean moved;
    String targetDimension = "";
    ShipScan.Bounds locked;

    Pending(ServerPlayer actor, BlockPos pos, String destination) {
      this.actor = actor.getUUID();
      this.computer = pos;
      this.destination = destination;
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
    var p = com.nullandkale.nullspace.server.ServerContext.get(level.getServer()).existing(PENDING);
    return com.nullandkale.nullspace.navigation.NavigationService.locked(level, pos)
        || p != null
            && p.moved
            && p.locked != null
            && p.targetDimension.equals(level.dimension().identifier().toString())
            && p.locked.contains(pos);
  }

  public static boolean active(MinecraftServer server) {
    return (com.nullandkale.nullspace.server.ServerContext.get(server).existing(PENDING) != null);
  }

  public static boolean physicalBusy(ServerPlayer player) {
    var p =
        com.nullandkale.nullspace.server.ServerContext.get(player.level().getServer())
            .existing(PENDING);
    return p != null && p.crew.containsKey(player.getUUID());
  }

  public static boolean busy(ServerPlayer player) {
    var p =
        com.nullandkale.nullspace.server.ServerContext.get(player.level().getServer())
            .existing(PENDING);
    return com.nullandkale.nullspace.navigation.NavigationService.busy(player)
        || p != null && p.crew.containsKey(player.getUUID());
  }

  public static void ready(ServerPlayer player, TravelPackets.Ready packet) {
    var pending =
        com.nullandkale.nullspace.server.ServerContext.get(player.level().getServer())
            .existing(PENDING);
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
    var server = player.level().getServer();
    if ((com.nullandkale.nullspace.server.ServerContext.get(server).existing(PENDING) != null)
        || com.nullandkale.nullspace.navigation.NavigationService.pending(server))
      throw new IllegalArgumentException("Another ship transfer is in progress; try again shortly");

    if (player.level().dimension().equals(SPACE) && !destination.isBlank()) {
      var pad =
          PadRegistry.read(server).stream()
              .filter(
                  p ->
                      destination.startsWith("id:")
                          ? p.id().equals(destination.substring(3))
                          : p.name().equalsIgnoreCase(destination.strip()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Unknown landing pad"));
      var expected =
          com.nullandkale.nullspace.navigation.PlanetSurfaces.dimension(
              com.nullandkale.nullspace.navigation.LocalSpaces.address(inspection.vessel()));
      if (!pad.dimension().equals(expected.identifier().toString()))
        throw new IllegalArgumentException(
            "Jump to "
                + (pad.dimension().equals("minecraft:overworld")
                    ? "Sol / Terra"
                    : "the destination planet")
                + " before landing at this pad");
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
    Pending pending = new Pending(player, controller, destination);
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
    com.nullandkale.nullspace.server.ServerContext.get(server).put(PENDING, pending);
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
    Pending pending = com.nullandkale.nullspace.server.ServerContext.get(server).existing(PENDING);
    if (pending == null) return;
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
        var inspected = ShipService.inspect(actor.level(), pending.computer);
        if (!inspected.scan().pressure().breathable(BlockPos.containing(actor.getEyePosition())))
          throw new IllegalArgumentException("Pilot left the cabin");
        transfer(actor, pending.computer, pending.destination, inspected);
        pending.moved = true;
        pending.ticks = 0;
        pending.targetDimension = actor.level().dimension().identifier().toString();
        var vessel = ShipStore.get(server).at(actor.level(), actor.blockPosition());
        pending.locked = vessel == null ? null : ShipStore.bounds(vessel);
        broadcast(server, pending, "Boarding", pending.targetDimension, false);
      } catch (DestinationLoading loading) {
        // Chunk and entity IO complete between ticks, while passengers stay behind the loading
        // screen.
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
    com.nullandkale.nullspace.server.ServerContext.get(server).remove(PENDING);
    var actor = server.getPlayerList().getPlayer(pending.actor);
    if (actor != null)
      actor.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
  }

  /**
   * Mutations occur in one server task: machines, mobs and menus cannot tick between journal
   * stages.
   */
  public static void transfer(
      ServerPlayer actor,
      BlockPos controller,
      String destination,
      ShipService.Inspection inspected) {
    com.nullandkale.nullspace.computer.ComputerService.flush(actor.level().getServer());
    if (inspected.vessel() != null) {
      if (com.nullandkale.nullspace.systems.ShipSystems.enabled(inspected.vessel())
          && com.nullandkale.nullspace.systems.ShipSystems.power(
                  actor.level(),
                  inspected.vessel(),
                  com.nullandkale.nullspace.systems.PowerBus.System.ENGINES)
              == 0) throw new IllegalArgumentException("Propulsion lost power before transfer");
      com.nullandkale.nullspace.systems.ShipSystems.cancelTransit(
          actor.level(), inspected.vessel());
    }
    var source = actor.level();
    var server = source.getServer();
    var store = ShipStore.get(server);
    var scan = inspected.scan();
    var sourceBase = inspected.base();
    ServerLevel target;
    BlockPos targetBase;
    PadRegistry.Pad landing = null;
    var vessel = inspected.vessel();
    String id = vessel == null ? UUID.randomUUID().toString() : vessel.getStringOr("id", "");
    if (source.dimension().equals(SPACE)) {
      landing =
          PadRegistry.read(server).stream()
              .filter(
                  p ->
                      destination.startsWith("id:")
                          ? p.id().equals(destination.substring(3))
                          : p.name().equalsIgnoreCase(destination.strip()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Unknown landing pad; use destinations to list names"));
      if (!landing
          .dimension()
          .equals(
              com.nullandkale.nullspace.navigation.PlanetSurfaces.dimension(
                      com.nullandkale.nullspace.navigation.LocalSpaces.address(vessel))
                  .identifier()
                  .toString()))
        throw new IllegalArgumentException(
            "Jump to the destination planet before landing at this pad");
      target = ShipStore.level(server, landing.dimension());
      PadRegistry.validate(target, landing);
      var b = landing.bounds();
      targetBase = new BlockPos(b.minX(), b.y() + 1, b.minZ());
      if (scan.bounds().max().getX() - sourceBase.getX() >= b.width()
          || scan.bounds().max().getZ() - sourceBase.getZ() >= b.depth())
        throw new IllegalArgumentException("Ship does not fit this pad");
      for (int x = b.minX(); x <= b.maxX(); x++)
        for (int z = b.minZ(); z <= b.maxZ(); z++)
          for (int y = b.y() + 1; y <= target.getMaxY(); y++)
            if (!target.getBlockState(new BlockPos(x, y, z)).isAir())
              throw ShipMessages.blockProblem(
                  target,
                  new BlockPos(x, y, z),
                  "The landing column is obstructed. Clear this block above the destination pad,"
                      + " then try landing again.");
    } else {
      target = server.getLevel(SPACE);
      if (target == null)
        throw new IllegalArgumentException(
            "Space dimension is not loaded; restart with NullSpace enabled");
      int slot = 0;
      while (slot <= 7000 && !spaceSlotFree(server, slot)) slot++;
      if (slot > 7000) throw new IllegalArgumentException("No free physical ship regions remain");
      targetBase = new BlockPos(slot * SPACE_SPACING, 96, 0);
    }
    BlockPos delta = targetBase.subtract(sourceBase),
        targetMin = scan.bounds().min().offset(delta),
        targetMax = scan.bounds().max().offset(delta);
    if (targetMin.getY() < target.getMinY()
        || targetMax.getY() > target.getMaxY()
        || !target.getWorldBorder().isWithinBounds(targetMin)
        || !target.getWorldBorder().isWithinBounds(targetMax))
      throw new IllegalArgumentException("Destination exceeds world limits");
    for (BlockPos pos : BlockPos.betweenClosed(targetMin, targetMax)) {
      target.getChunk(pos);
      if (!target.getBlockState(pos).isAir())
        throw ShipMessages.blockProblem(
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
    try {
      ShipEntities.requireLoaded(target, new ShipScan.Bounds(targetMin, targetMax));
    } catch (IllegalArgumentException notLoaded) {
      throw new DestinationLoading();
    }
    var blockers = ShipEntities.inBounds(target, new ShipScan.Bounds(targetMin, targetMax));
    if (!blockers.isEmpty()) {
      var blocker = blockers.getFirst();
      NullSpace.LOGGER.info(
          "Ship destination occupied: {} {} at {} in {}",
          blocker.getType(),
          blocker.getUUID(),
          blocker.position(),
          target.dimension().identifier());
      throw ShipMessages.entityProblem(
          blocker,
          "The destination is occupied. Move this occupant clear or choose another landing pad.");
    }
    ShipEntities.requireLoaded(source, scan.bounds());
    var occupants = ShipOccupants.inspect(source, scan);
    var cargo = occupants.cargo();
    var crew = occupants.crew();
    var current = com.nullandkale.nullspace.server.ServerContext.get(server).existing(PENDING);
    if (current != null
        && !crew.stream()
            .map(Entity::getUUID)
            .collect(java.util.stream.Collectors.toSet())
            .equals(
                current.crew.keySet().stream()
                    .filter(uuid -> server.getPlayerList().getPlayer(uuid) != null)
                    .collect(java.util.stream.Collectors.toSet())))
      throw new IllegalArgumentException("Passenger list changed; inspect and launch again");
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
    journal.put("before", store.snapshot());
    ListTag entities = new ListTag();
    for (Entity entity : cargo) {
      var output =
          TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess());
      if (!entity.save(output))
        throw ShipMessages.entityProblem(
            entity,
            "This passenger cannot be saved for travel. Move it off the ship and try again.");
      var entry = new CompoundTag();
      entry.putString("id", entity.getUUID().toString());
      entry.put("data", output.buildResult());
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
    var next = vessel == null ? new CompoundTag() : vessel.copy();
    next.putString("id", id);
    next.putString("dimension", target.dimension().identifier().toString());
    next.putInt("width", landing == null ? 32 : landing.bounds().width());
    next.putInt("depth", landing == null ? 32 : landing.bounds().depth());
    next.putInt("height", landing == null ? 32 : PadRegistry.buildHeight(target, landing.bounds()));
    next.putLong("base", targetBase.asLong());
    if (!source.dimension().equals(SPACE)) {
      String address = com.nullandkale.nullspace.navigation.PlanetSurfaces.address(source);
      if (address != null) next.putString("localSpace", address);
    }
    if (target.dimension().equals(SPACE) && !next.contains("simX")) {
      var arrival =
          com.nullandkale.nullspace.navigation.NavigationService.arrivalOrigin(server, next);
      next.putDouble("simX", arrival.x);
      next.putDouble("simY", arrival.y);
      next.putDouble("simZ", arrival.z);
    }
    next.putLong("controller", controller.offset(delta).asLong());
    if (inspected.pad() != null) {
      next.putString("home", inspected.pad().name());
      next.putString("homeId", inspected.pad().id());
    }
    ListTag members = new ListTag();
    if (vessel != null)
      for (Tag t : vessel.getListOrEmpty("crew")) {
        var member = ((CompoundTag) t).copy();
        UUID uuid = UUID.fromString(member.getStringOr("id", ""));
        if (server.getPlayerList().getPlayer(uuid) == null) {
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
        members.add(member);
        ShipStore.target(
            after,
            uuid,
            target.dimension().identifier().toString(),
            position.x + delta.getX(),
            position.y + delta.getY(),
            position.z + delta.getZ(),
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
    // Force close any menus retaining references to source containers.
    for (ServerPlayer player : source.players())
      if (scan.bounds().box().inflate(8).contains(player.position())) player.closeContainer();
    var destinationSnapshot =
        ShipBlockSupport.destinationTemplate(
            journal.getCompoundOrEmpty("template"), scan.blocks(), delta);
    var destinationTemplate = new StructureTemplate();
    destinationTemplate.load(
        target.registryAccess().lookupOrThrow(Registries.BLOCK), destinationSnapshot);
    place(target, destinationTemplate, targetMin);
    checkpoint(file, journal, "DESTINATION_STAGED");
    clear(source, min, max);
    checkpoint(file, journal, "SOURCE_CLEARED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    for (Entity entity : cargo) {
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
    checkpoint(file, journal, "PASSENGERS_MOVED");
    store.replace(after, ShipRepository.Mutation.TRANSFER);
    store.save();
    server.saveEverything(true, true, true);
    checkpoint(file, journal, "COMMITTED");
  }

  private static void checkpoint(Path file, CompoundTag journal, String stage) {
    journal.putString("stage", stage);
    ShipStore.write(file, journal);
    failureInjector.accept(stage);
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
          authorityStore(
              store,
              UUID.fromString(((CompoundTag) tag).getStringOr("id", "")),
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
          for (var old : ShipEntities.inBounds(source, new ShipScan.Bounds(min, max)))
            if (old.getUUID().equals(id)) old.discard();
          for (var old :
              ShipEntities.inBounds(
                  target, new ShipScan.Bounds(min.offset(delta), max.offset(delta))))
            if (old.getUUID().equals(id)) old.discard();
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
          if (!source.addFreshEntity(entity))
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
