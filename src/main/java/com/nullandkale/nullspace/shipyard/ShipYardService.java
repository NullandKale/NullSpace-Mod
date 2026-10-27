package com.nullandkale.nullspace.shipyard;

import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
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
  private static final com.nullandkale.nullspace.server.ServerContext.Key<Integer>
      RECOVERY_RETRIES = new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static final class AreaLoading extends IllegalArgumentException {
    AreaLoading() {
      super(
          "The delivery area's creatures and items are still loading. Wait a moment, then call the"
              + " ship again.");
    }
  }

  public static void initialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          recover(server);
          if (!ShipStore.get(server).list(ORDERS).isEmpty())
            com.nullandkale.nullspace.server.ServerContext.get(server)
                .service(RECOVERY_RETRIES, () -> 3);
        });
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.SESSIONS,
        server -> {
          if (server.getTickCount() % 20 != 0) return;
          var context = com.nullandkale.nullspace.server.ServerContext.get(server);
          var retries = context.remove(RECOVERY_RETRIES);
          if (retries == null || retries <= 0) return;
          recover(server);
          if (retries > 1 && !ShipStore.get(server).list(ORDERS).isEmpty())
            context.service(RECOVERY_RETRIES, () -> retries - 1);
        });
  }

  private static PadRegistry.Pad pad(ServerLevel level, BlockPos beacon) {
    if (level.dimension().equals(TravelService.SPACE))
      throw new IllegalArgumentException(
          "Call ships at a surface landing pad, outside the space dimension.");
    if (!level.hasChunkAt(beacon) || !level.getBlockState(beacon).is(ShipBlocks.SHIP_BEACON))
      throw new IllegalArgumentException(
          "The ship beacon is missing. Place it on the pad's cobblestone ring.");
    if (TravelService.active(level.getServer()))
      throw new IllegalArgumentException(
          "Wait for the current ship transfer to finish before calling another ship.");
    if (TravelService.locked(level, beacon))
      throw new IllegalArgumentException(
          "Wait for the ship already using this pad to finish travelling.");
    PadRefreshService.near(level, beacon);
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
    return result;
  }

  public static void open(ServerPlayer player, BlockPos beacon, String selected, String notice) {
    var preset =
        ShipBlueprints.get(selected.isEmpty() ? ShipBlueprints.PRESETS.getFirst().id() : selected);
    String status;
    try {
      var pad = pad(player.level(), beacon);
      var b = pad.bounds();
      status =
          "Pad: "
              + pad.name()
              + " | "
              + b.width()
              + " x "
              + b.depth()
              + " | height "
              + PadRegistry.buildHeight(player.level(), b);
    } catch (IllegalArgumentException invalid) {
      status = invalid.getMessage();
    }
    status +=
        "\n"
            + preset.name()
            + " | "
            + preset.size()
            + " | "
            + preset.decks()
            + " deck(s) | FREE"
            + "\n"
            + preset.description()
            + "\nHelm, power, battery, air, doors, terminal, camera + scanners."
            + "\n"
            + (notice.isEmpty()
                ? "Select a ship for details, then Call selected. No money is charged."
                : notice);
    ComputerTerminal.send(player, beacon, status, "shipyard", preset.id());
  }

  public static void action(ServerPlayer player, BlockPos beacon, String preset, boolean purchase) {
    String notice = "";
    if (purchase) {
      deliver(player, beacon, preset);
      notice =
          "DELIVERED: "
              + ShipBlueprints.get(preset).name()
              + ". Walk in, close the door, inspect and launch at the helm.";
    }
    open(player, beacon, preset, notice);
  }

  public static CompoundTag deliver(ServerPlayer player, BlockPos beacon, String presetId) {
    if (!player.isAlive()
        || player.distanceToSqr(beacon.getX() + .5, beacon.getY() + .5, beacon.getZ() + .5) > 64
        || !player.mayInteract(player.level(), beacon)
        || TravelService.busy(player))
      throw new IllegalArgumentException(
          "Move within 8 blocks of the beacon and wait for travel to finish.");
    var level = player.level();
    var selected = pad(level, beacon);
    var preset = ShipBlueprints.get(presetId);
    var origin =
        ShipBlueprints.origin(
            preset, selected.bounds(), PadRegistry.buildHeight(level, selected.bounds()));
    var store = ShipStore.get(level.getServer());
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
    store.list(ORDERS).add(order);
    try {
      store.save();
    } catch (RuntimeException failure) {
      store.list(ORDERS).remove(order);
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
    var plan = ShipBlueprints.blocks(preset);
    var b = pad.bounds();
    if (origin.getY() < level.getMinY()
        || origin.getY() + preset.height() - 1 > level.getMaxY()
        || !level.getWorldBorder().isWithinBounds(origin)
        || !level
            .getWorldBorder()
            .isWithinBounds(origin.offset(preset.width() - 1, 0, preset.depth() - 1)))
      throw new IllegalArgumentException(
          "This delivery would cross the world's height or border. Choose another pad.");
    for (int x = b.minX(); x <= b.maxX(); x++)
      for (int z = b.minZ(); z <= b.maxZ(); z++) {
        if (!level.hasChunkAt(new BlockPos(x, b.y(), z)))
          throw new IllegalArgumentException(
              "Part of the pad is still loading. Move closer and try again.");
        for (int y = b.y() + 1; y <= level.getMaxY(); y++) {
          var at = new BlockPos(x, y, z);
          if (y <= b.y() + 32 && TravelService.locked(level, at))
            throw new IllegalArgumentException(
                "A ship is travelling through this pad. Wait for it to finish.");
          var current = level.getBlockState(at);
          if (!current.isAir() && (!resume || !current.equals(plan.get(at.subtract(origin)))))
            throw new IllegalArgumentException(
                "Clear the pad and the sky above it first. "
                    + ShipMessages.block(level, at)
                    + " is in the delivery area at "
                    + x
                    + ", "
                    + y
                    + ", "
                    + z
                    + ". Existing blocks are kept.");
          if (buyer != null
              && plan.containsKey(at.subtract(origin))
              && !buyer.mayInteract(level, at))
            throw new IllegalArgumentException(
                "You cannot build in part of this pad. Choose a pad you can use.");
        }
      }
    var bounds =
        new ShipScan.Bounds(
            origin, origin.offset(preset.width() - 1, preset.height() - 1, preset.depth() - 1));
    for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
      for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
        level
            .getChunkSource()
            .addTicketWithRadius(
                net.minecraft.server.level.TicketType.PORTAL,
                new net.minecraft.world.level.ChunkPos(x, z),
                2);
    try {
      ShipEntities.requireLoaded(level, bounds);
    } catch (IllegalArgumentException loading) {
      throw new AreaLoading();
    }
    var volume = bounds.box();
    var entities = ShipEntities.inBounds(level, bounds);
    if (!entities.isEmpty())
      throw new IllegalArgumentException(
          "Move "
              + ShipMessages.entity(entities.getFirst())
              + " out of the delivery area before calling a ship. Stand beside the ring.");
    for (var tag : ShipStore.get(level.getServer()).ships()) {
      var ship = (CompoundTag) tag;
      if (!ship.getStringOr("dimension", "").equals(pad.dimension())
          || !ShipStore.bounds(ship).box().intersects(volume)) continue;
      if (!resume
          || !ship.getStringOr("shipyardOrder", "")
              .equals(activeOrder(level.getServer(), pad.id())))
        throw new IllegalArgumentException(
            "This pad is assigned to another ship. Launch that ship or choose an empty pad.");
    }
  }

  private static String activeOrder(MinecraftServer server, String pad) {
    for (var tag : ShipStore.get(server).list(ORDERS)) {
      var order = (CompoundTag) tag;
      if (order.getStringOr("pad", "").equals(pad)) return order.getStringOr("id", "");
    }
    return "";
  }

  private static CompoundTag complete(MinecraftServer server, CompoundTag order) {
    if (order.getIntOr("version", 0) != 1)
      throw new IllegalArgumentException(
          "This delivery needs a newer blueprint version. Existing blocks were preserved.");
    var level = ShipStore.level(server, order.getStringOr("dimension", ""));
    var beacon = ShipStore.pos(order, "beacon");
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
            .equals(ShipStore.pos(order, "padFloor"))
        || current.width() != order.getIntOr("padWidth", 0)
        || current.depth() != order.getIntOr("padDepth", 0))
      throw new IllegalArgumentException(
          "The delivery pad changed. Restore its original ring before resuming this order.");
    var preset = ShipBlueprints.get(order.getStringOr("preset", ""));
    var origin = ShipBlueprints.origin(preset, b, PadRegistry.buildHeight(level, b));
    if (!origin.equals(ShipStore.pos(order, "origin")))
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
    var helm = origin.offset(preset.helm());
    ShipScan.inspect(level, helm, b, true);
    var ship = ShipTopology.resolve(level, helm);
    ship.putString("shipyardOrder", order.getStringOr("id", ""));
    ship.putString("blueprint", preset.id());
    ship.putString("name", preset.name());
    ShipSystems.upgrade(level, ship);
    var store = ShipStore.get(server);
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
    var orders = new ArrayList<Tag>(ShipStore.get(server).list(ORDERS));
    for (var tag : orders) {
      try {
        complete(server, (CompoundTag) tag);
      } catch (AreaLoading loading) {
        com.nullandkale.nullspace.NullSpace.LOGGER.debug(
            "Waiting for shipyard entity data before resuming delivery");
      } catch (Exception failure) {
        com.nullandkale.nullspace.NullSpace.LOGGER.error(
            "Shipyard delivery remains pending; existing blocks preserved. Restore the original pad"
                + " and select the same ship at its beacon to resume: {}",
            tag,
            failure);
      }
    }
  }

  private ShipYardService() {}
}
