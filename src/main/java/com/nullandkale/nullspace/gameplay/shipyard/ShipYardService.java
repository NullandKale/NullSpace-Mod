package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ownership.CrewRelease;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRefresh;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;

/** Server-only delivery authority. A saved intent makes interrupted construction resumable. */
public final class ShipYardService {
  public enum DeliveryStage {
    INTENT_SAVED,
    HULL_PLACED,
    SHIP_SAVED,
    WORLD_SAVED,
    ORDER_CLEARED
  }

  /** Durability checkpoints for the opt-in, fresh-process crash harness. */
  public static Consumer<DeliveryStage> deliveryObserver = stage -> {};

  public static IntConsumer placementObserver = count -> {};
  private static final String ORDERS = "shipyardOrders";
  private static final String LOADING =
      "The delivery area's creatures and items are still loading. Wait a moment, then call the ship"
          + " again.";
  private static final ServerContext.Key<Integer> RECOVERY_RETRIES = new ServerContext.Key<>();

  private static final class AreaLoading extends IllegalArgumentException {
    AreaLoading() {
      super(LOADING);
    }
  }

  public static void initialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          recover(server);
          if (!WorldStore.get(server).list(ORDERS).isEmpty())
            ServerContext.get(server).service(RECOVERY_RETRIES, () -> 3);
        });
    ServerContext.tick(
        ServerContext.Phase.SESSIONS,
        server -> {
          if (server.getTickCount() % 20 != 0) return;
          var context = ServerContext.get(server);
          var retries = context.remove(RECOVERY_RETRIES);
          if (retries == null || retries <= 0) return;
          recover(server);
          if (retries > 1 && !WorldStore.get(server).list(ORDERS).isEmpty())
            context.service(RECOVERY_RETRIES, () -> retries - 1);
        });
  }

  private static PadRegistry.Pad pad(ServerLevel level, BlockPos beacon) {
    if (level.dimension().equals(TravelService.SPACE))
      throw new IllegalArgumentException(
          "Call ships at a surface landing pad, outside the space dimension.");
    if (!level.hasChunkAt(beacon) || !level.getBlockState(beacon).is(ModBlocks.SHIP_BEACON))
      throw new IllegalArgumentException(
          "The ship beacon is missing. Place it on the pad's cobblestone ring.");
    if (TransferLocks.locked(level, beacon))
      throw new IllegalArgumentException(
          "Wait for the ship already using this pad to finish travelling.");
    PadRefresh.near(level, beacon);
    var scanned = PadComputers.inspect(level, beacon);
    if (!scanned.valid()) throw new IllegalArgumentException(scanned.message());
    var result =
        PadRepository.get(level.getServer())
            .dimension(level.dimension().identifier().toString())
            .stream()
            .filter(p -> p.bounds().equals(scanned.pad()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Register this pad at its pad computer first. Keep the beacon on another"
                            + " part of the ring."));
    PadRegistry.validate(level, result);
    var b = result.bounds();
    if (TransferLocks.areaBusy(
        level,
        new HullScan.Bounds(
            new BlockPos(b.minX(), b.y() + 1, b.minZ()),
            new BlockPos(b.maxX(), b.y() + 32, b.maxZ()))))
      throw new IllegalArgumentException("This pad is reserved by a ship's travel or recall");
    return result;
  }

  public record Preview(String message, int readiness) {}

  public static Preview preview(ServerPlayer player, BlockPos beacon, String selected) {
    var preset =
        ShipBlueprints.get(selected.isEmpty() ? ShipBlueprints.PRESETS.getFirst().id() : selected);
    try {
      var pad = pad(player.level(), beacon);
      boolean resume = false;
      for (var tag : WorldStore.get(player.level().getServer()).list(ORDERS)) {
        var order = (CompoundTag) tag;
        if (!order.getStringOr("pad", "").equals(pad.id())) continue;
        if (!order.getStringOr("preset", "").equals(preset.id()))
          return new Preview(
              "Select "
                  + ShipBlueprints.get(order.getStringOr("preset", "")).name()
                  + " to resume the pending delivery.",
              0);
        resume = true;
      }
      var origin =
          ShipBlueprints.origin(
              preset, pad.bounds(), PadRegistry.buildHeight(player.level(), pad.bounds()));
      String problem = spaceProblem(player.level(), pad, preset, origin, resume, player);
      if (!problem.isEmpty()) return new Preview(problem, 0);
      return new Preview(
          (resume ? "Ready to resume" : "Ready to deliver")
              + " at "
              + pad.name()
              + ". Free.\n"
              + preset.description()
              + "\nIncludes fuel, controller and a compact door + docking base.",
          resume ? 2 : 1);
    } catch (IllegalArgumentException invalid) {
      return new Preview(invalid.getMessage(), 0);
    }
  }

  public static void open(ServerPlayer player, BlockPos beacon, String selected, String notice) {
    var preset =
        ShipBlueprints.get(selected.isEmpty() ? ShipBlueprints.PRESETS.getFirst().id() : selected);
    var preview = preview(player, beacon, preset.id());
    String message = (notice.isEmpty() ? "" : notice + "\n") + preview.message();
    var issued = ComputerTerminal.issue(player, beacon, message, "shipyard", preset.id());
    var rows =
        issued.destinations().stream()
            .map(
                d ->
                    new TerminalPackets.Destination(
                        d.id(),
                        d.name(),
                        d.id().equals(preset.id())
                            ? Integer.toString(preview.readiness())
                            : "FREE"))
            .toList();
    if (player.containerMenu != player.inventoryMenu) player.closeContainer();
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
        player,
        new TerminalPackets.Open(
            issued.position(),
            issued.message(),
            issued.mode(),
            issued.value(),
            issued.session(),
            rows));
  }

  public static void action(ServerPlayer player, BlockPos beacon, String preset, boolean purchase) {
    String notice = "";
    if (purchase) {
      var delivered = deliver(player, beacon, preset);
      CrewRelease.authorize(delivered, player);
      WorldStore.get(player.level().getServer()).save();
      notice =
          "DELIVERED: "
              + ShipBlueprints.get(preset).name()
              + ". Walk in, close the door, inspect and launch at the ship controller.";
    }
    open(player, beacon, preset, notice);
  }

  public static CompoundTag deliver(ServerPlayer player, BlockPos beacon, String presetId) {
    if (!player.isAlive()
        || player.distanceToSqr(beacon.getX() + .5, beacon.getY() + .5, beacon.getZ() + .5) > 64
        || !player.mayInteract(player.level(), beacon)
        || TransferLocks.busy(player))
      throw new IllegalArgumentException(
          "Move within 8 blocks of the beacon and wait for travel to finish.");
    var level = player.level();
    var selected = pad(level, beacon);
    var preset = ShipBlueprints.get(presetId);
    var origin =
        ShipBlueprints.origin(
            preset, selected.bounds(), PadRegistry.buildHeight(level, selected.bounds()));
    var store = WorldStore.get(level.getServer());
    for (var tag : store.list(ORDERS)) {
      var pending = (CompoundTag) tag;
      if (!pending.getStringOr("pad", "").equals(selected.id())) continue;
      if (!pending.getStringOr("preset", "").equals(preset.id()))
        throw new IllegalArgumentException(
            "This pad has a pending "
                + ShipBlueprints.get(pending.getStringOr("preset", "")).name()
                + " delivery. Select that ship and call it again to resume.");
      checkSpace(level, selected, preset, origin, true, player);
      return complete(level.getServer(), pending);
    }
    if (store.list(ORDERS).size() >= 64)
      throw new IllegalArgumentException(
          "Too many deliveries are waiting. Finish an interrupted delivery first.");
    var order = new CompoundTag();
    order.putInt("version", 1);
    order.putString("id", UUID.randomUUID().toString());
    order.putString("dimension", selected.dimension());
    order.putString("pad", selected.id());
    order.putString("preset", preset.id());
    order.putLong("beacon", beacon.asLong());
    order.putLong("origin", origin.asLong());
    order.putLong(
        "padFloor",
        new BlockPos(selected.bounds().minX(), selected.bounds().y(), selected.bounds().minZ())
            .asLong());
    order.putInt("padWidth", selected.bounds().width());
    order.putInt("padDepth", selected.bounds().depth());
    checkSpace(level, selected, preset, origin, false, player);
    // Preview is read-only. Retire empty allocations only together with a durable delivery intent,
    // so the new hull receives fresh identity/resources and interrupted delivery remains resumable.
    var retired = emptyAssignments(level, origin, preset);
    var previousShips = retired.isEmpty() ? null : store.ships().copy();
    if (!retired.isEmpty()) {
      var remaining = store.ships().copy();
      remaining.removeIf(tag -> retired.contains(((CompoundTag) tag).getStringOr("id", "")));
      store.document("ships", remaining);
    }
    store.list(ORDERS).add(order);
    try {
      store.save();
    } catch (RuntimeException failure) {
      store.list(ORDERS).remove(order);
      if (previousShips != null) store.document("ships", previousShips);
      store.requestSave();
      throw failure;
    }
    deliveryObserver.accept(DeliveryStage.INTENT_SAVED);
    return complete(level.getServer(), order);
  }

  private static void checkSpace(
      ServerLevel level,
      PadRegistry.Pad pad,
      ShipBlueprints.Preset preset,
      BlockPos origin,
      boolean resume,
      ServerPlayer buyer) {
    String problem = spaceProblem(level, pad, preset, origin, resume, buyer);
    if (problem.equals(LOADING)) throw new AreaLoading();
    if (!problem.isEmpty()) throw new IllegalArgumentException(problem);
  }

  private static String spaceProblem(
      ServerLevel level,
      PadRegistry.Pad pad,
      ShipBlueprints.Preset preset,
      BlockPos origin,
      boolean resume,
      ServerPlayer buyer) {
    var plan = ShipBlueprints.blocks(preset);
    var b = pad.bounds();
    if (origin.getY() < level.getMinY()
        || origin.getY() + preset.height() - 1 > level.getMaxY()
        || !level.getWorldBorder().isWithinBounds(origin)
        || !level
            .getWorldBorder()
            .isWithinBounds(origin.offset(preset.width() - 1, 0, preset.depth() - 1)))
      return "This delivery would cross the world's height or border. Choose another pad.";
    for (int x = b.minX(); x <= b.maxX(); x++)
      for (int z = b.minZ(); z <= b.maxZ(); z++) {
        if (!level.hasChunkAt(new BlockPos(x, b.y(), z)))
          return "Part of the pad is still loading. Move closer and try again.";
        for (int y = b.y() + 1; y <= level.getMaxY(); y++) {
          var at = new BlockPos(x, y, z);
          if (y <= b.y() + 32 && TransferLocks.locked(level, at))
            return "A ship is travelling through this pad. Wait for it to finish.";
          var current = level.getBlockState(at);
          if (!current.isAir() && (!resume || !current.equals(plan.get(at.subtract(origin)))))
            return "Clear the pad and the sky above it first. "
                + ShipMessages.block(level, at)
                + " is in the delivery area at "
                + x
                + ", "
                + y
                + ", "
                + z
                + ". Existing blocks are kept.";
          if (buyer != null
              && plan.containsKey(at.subtract(origin))
              && !buyer.mayInteract(level, at))
            return "You cannot build in part of this pad. Choose a pad you can use.";
        }
      }
    var bounds =
        new HullScan.Bounds(
            origin, origin.offset(preset.width() - 1, preset.height() - 1, preset.depth() - 1));
    for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
      for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
        level
            .getChunkSource()
            .addTicketWithRadius(
                net.minecraft.server.level.TicketType.PORTAL,
                new net.minecraft.world.level.ChunkPos(x, z),
                2);
    if (!HullEntities.loaded(level, bounds)) return LOADING;
    var volume = bounds.box();
    var entities = HullEntities.inBounds(level, bounds);
    if (!entities.isEmpty())
      return "Move "
          + ShipMessages.entity(entities.getFirst())
          + " out of the delivery area before calling a ship. Stand beside the ring.";
    for (var tag : WorldStore.get(level.getServer()).ships()) {
      var ship = (CompoundTag) tag;
      if (!ship.getStringOr("dimension", "").equals(pad.dimension())
          || !WorldStore.bounds(ship).box().intersects(volume)) continue;
      if ((!resume
          || !ship.getStringOr("shipyardOrder", "")
              .equals(activeOrder(level.getServer(), pad.id())))) {
        String problem = assignmentProblem(level, ship);
        if (!problem.isEmpty()) return problem;
      }
    }
    return "";
  }

  private static String assignmentProblem(ServerLevel level, CompoundTag ship) {
    String assigned = "This pad is assigned to another ship. Launch it or completely dismantle it.";
    if (ship.getStringOr("kind", "").equals("station")
        || ship.contains("dock")
        || !ship.getCompoundOrEmpty("dockLinks").isEmpty()
        || ship.contains("stationBuild")) return assigned;
    for (var tag : ship.getListOrEmpty("crew")) {
      String id = ((CompoundTag) tag).getStringOr("id", "");
      if (level.getServer().getPlayerList().getPlayers().stream()
          .noneMatch(p -> p.getUUID().toString().equals(id)))
        return "The previous ship still has offline crew assigned. Have them log in before"
            + " replacing it.";
    }
    var bounds = WorldStore.bounds(ship);
    if (bounds.max().getX() - bounds.min().getX() < 0
        || bounds.max().getX() - bounds.min().getX() > 31
        || bounds.max().getZ() - bounds.min().getZ() < 0
        || bounds.max().getZ() - bounds.min().getZ() > 31
        || bounds.max().getY() < bounds.min().getY()) return assigned;
    for (var pos : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
      if (!level.hasChunkAt(pos)) return LOADING;
      if (!level.getBlockState(pos).isAir())
        return "The previous ship still has "
            + ShipMessages.block(level, pos)
            + " at "
            + pos.toShortString()
            + ". Clear its whole area before replacing it.";
    }
    for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
      for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
        level
            .getChunkSource()
            .addTicketWithRadius(
                net.minecraft.server.level.TicketType.PORTAL,
                new net.minecraft.world.level.ChunkPos(x, z),
                2);
    if (!HullEntities.loaded(level, bounds)) return LOADING;
    var occupants = HullEntities.inBounds(level, bounds);
    if (!occupants.isEmpty())
      return "Move "
          + ShipMessages.entity(occupants.getFirst())
          + " out of the previous ship's area before calling another ship.";
    return "";
  }

  private static Set<String> emptyAssignments(
      ServerLevel level, BlockPos origin, ShipBlueprints.Preset preset) {
    var volume =
        new HullScan.Bounds(
                origin, origin.offset(preset.width() - 1, preset.height() - 1, preset.depth() - 1))
            .box();
    var ids = new HashSet<String>();
    for (var tag : WorldStore.get(level.getServer()).ships()) {
      var ship = (CompoundTag) tag;
      if (ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString())
          && WorldStore.bounds(ship).box().intersects(volume)
          && assignmentProblem(level, ship).isEmpty()) ids.add(ship.getStringOr("id", ""));
    }
    return ids;
  }

  private static String activeOrder(MinecraftServer server, String pad) {
    for (var tag : WorldStore.get(server).list(ORDERS)) {
      var order = (CompoundTag) tag;
      if (order.getStringOr("pad", "").equals(pad)) return order.getStringOr("id", "");
    }
    return "";
  }

  private static CompoundTag complete(MinecraftServer server, CompoundTag order) {
    if (order.getIntOr("version", 0) != 1)
      throw new IllegalArgumentException(
          "This delivery needs a newer blueprint version. Existing blocks were preserved.");
    var level = WorldStore.level(server, order.getStringOr("dimension", ""));
    var beacon = WorldStore.pos(order, "beacon");
    var registered = PadRepository.get(server).id(order.getStringOr("pad", ""));
    if (registered == null)
      throw new IllegalArgumentException(
          "Restore the registered delivery pad before resuming this order.");
    // Recovery loads only the original registered footprint, bounded by the 32-block pad limit.
    var b = registered.bounds();
    for (int x = (b.minX() - 1) >> 4; x <= (b.maxX() + 1) >> 4; x++)
      for (int z = (b.minZ() - 1) >> 4; z <= (b.maxZ() + 1) >> 4; z++) level.getChunk(x, z);
    var selected = pad(level, beacon);
    if (!selected.id().equals(registered.id()))
      throw new IllegalArgumentException(
          "The beacon now touches a different pad. Restore its original ring.");
    var current = selected.bounds();
    if (!current.equals(b)
        || !new BlockPos(current.minX(), current.y(), current.minZ())
            .equals(WorldStore.pos(order, "padFloor"))
        || current.width() != order.getIntOr("padWidth", 0)
        || current.depth() != order.getIntOr("padDepth", 0))
      throw new IllegalArgumentException(
          "The delivery pad changed. Restore its original ring before resuming this order.");
    var preset = ShipBlueprints.get(order.getStringOr("preset", ""));
    var origin = ShipBlueprints.origin(preset, b, PadRegistry.buildHeight(level, b));
    if (!origin.equals(WorldStore.pos(order, "origin")))
      throw new IllegalArgumentException(
          "This pad changed during delivery. Restore the original ring to resume.");
    checkSpace(level, selected, preset, origin, true, null);
    int count = 0;
    for (var entry : ShipBlueprints.blocks(preset).entrySet()) {
      var at = origin.offset(entry.getKey());
      if (level.getBlockState(at).isAir() && !level.setBlock(at, entry.getValue(), 18))
        throw new IllegalStateException("Could not place ship block at " + at);
      placementObserver.accept(++count);
    }
    deliveryObserver.accept(DeliveryStage.HULL_PLACED);
    for (var entry : ShipBlueprints.blocks(preset).entrySet())
      if (entry.getValue().is(ModBlocks.GENERATOR))
        FuelSystem.provision(level, origin.offset(entry.getKey()), 64);
    var helm = origin.offset(preset.helm());
    HullScan.inspect(level, helm, b, true);
    var ship = ShipTopology.resolve(level, helm);
    ship.putString("shipyardOrder", order.getStringOr("id", ""));
    ship.putString("blueprint", preset.id());
    ship.putString("name", preset.name());
    boolean initializeSystems = !ShipSystems.enabled(ship);
    SystemsUpgrade.upgrade(level, ship);
    if (initializeSystems)
      ship.getCompoundOrEmpty("systems")
          .putIntArray("requests", new int[] {1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
    var store = WorldStore.get(server);
    store.save();
    deliveryObserver.accept(DeliveryStage.SHIP_SAVED);
    server.saveEverything(true, true, true);
    deliveryObserver.accept(DeliveryStage.WORLD_SAVED);
    store.list(ORDERS).remove(order);
    try {
      store.save();
    } catch (RuntimeException failure) {
      store.list(ORDERS).add(order);
      store.requestSave();
      throw failure;
    }
    deliveryObserver.accept(DeliveryStage.ORDER_CLEARED);
    return ship;
  }

  public static void recover(MinecraftServer server) {
    var orders = new ArrayList<Tag>(WorldStore.get(server).list(ORDERS));
    for (var tag : orders) {
      try {
        complete(server, (CompoundTag) tag);
      } catch (AreaLoading loading) {
        NullSpace.LOGGER.debug("Waiting for shipyard entity data before resuming delivery");
      } catch (Exception failure) {
        NullSpace.LOGGER.error(
            "Shipyard delivery remains pending; existing blocks preserved. Restore the original pad"
                + " and select the same ship at its beacon to resume: {}",
            tag,
            failure);
      }
    }
  }

  private ShipYardService() {}
}
