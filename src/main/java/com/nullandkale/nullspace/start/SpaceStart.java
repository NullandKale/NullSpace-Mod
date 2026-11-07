package com.nullandkale.nullspace.start;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.shipyard.ShipBlueprints;
import com.nullandkale.nullspace.systems.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.storage.LevelResource;

/** New arrivals are commissioned after login registration, never inside the JOIN callback. */
public final class SpaceStart {
  private static final ServerContext.Key<WorldStartSettings> SETTINGS = new ServerContext.Key<>();
  private static final ServerContext.Key<Map<UUID, Integer>> ARRIVALS = new ServerContext.Key<>();
  private static final String SUPPLIES = "nullspace_start_supplies";

  public static void initialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          var file =
              server.isDedicatedServer()
                  ? server.getServerDirectory().resolve("server.properties")
                  : server.getWorldPath(LevelResource.ROOT).resolve(WorldStartSettings.FILE);
          try {
            ServerContext.get(server).put(SETTINGS, WorldStartSettings.read(file));
          } catch (IOException | IllegalArgumentException failure) {
            // Invalid configuration must not silently start players with a different ship.
            throw new IllegalStateException(
                "Cannot read NullSpace start settings from " + file, failure);
          }
        });
    ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> {
          var player = handler.player;
          var settings = settings(server);
          if (!settings.space()) return;
          if (!firstArrival(player)) return;
          ServerContext.get(server).service(ARRIVALS, HashMap::new).put(player.getUUID(), 0);
        });
    ServerContext.tick(
        ServerContext.Phase.SESSIONS,
        server -> {
          var waiting = ServerContext.get(server).existing(ARRIVALS);
          if (waiting == null) return;
          for (var id : List.copyOf(waiting.keySet())) {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) {
              waiting.remove(id);
              continue;
            }
            if (waiting.get(id) > server.getTickCount()) continue;
            try {
              start(player, settings(server));
              waiting.remove(id);
            } catch (RuntimeException failure) {
              waiting.put(id, server.getTickCount() + 1200);
              NullSpace.LOGGER.error(
                  "Space start failed for {}; the saved starting ship will be retried",
                  id,
                  failure);
              player.sendSystemMessage(
                  Component.literal(
                      "Your starting ship could not finish setting up. The server will retry. See"
                          + " the server log for details."));
            }
          }
        });
  }

  public static WorldStartSettings settings(MinecraftServer server) {
    var settings = ServerContext.get(server).existing(SETTINGS);
    return settings == null ? WorldStartSettings.DEFAULT : settings;
  }

  public static boolean firstArrival(ServerPlayer player) {
    var server = player.level().getServer();
    var previous =
        ShipStore.get(server)
            .section("spaceStarts")
            .getCompoundOrEmpty(player.getUUID().toString());
    if (previous.getBooleanOr("completed", false)) return false;
    if (!previous.isEmpty()) return true; // A saved construction intent can resume after login.
    // Use the native layout, including its backup. Keep pre-migration layouts protected too.
    for (var data :
        List.of(
            server.getWorldPath(LevelResource.PLAYER_DATA_DIR),
            server.getWorldPath(LevelResource.PLAYER_OLD_DATA_DIR),
            server.getWorldPath(LevelResource.ROOT).resolve("playerdata")))
      if (Files.exists(data.resolve(player.getUUID() + ".dat"))
          || Files.exists(data.resolve(player.getUUID() + ".dat_old"))) return false;
    return true;
  }

  /** Stable random choice per new player and world; only landable planets, never moons. */
  public static String planet(MinecraftServer server, UUID player) {
    // P9: start in the home system, over a planet you can land on.
    var planets =
        com.nullandkale.nullspace.navigation.StarSystems.galaxy(server)
            .place(com.nullandkale.nullspace.navigation.GalaxyPlan.HOME) instanceof
                com.nullandkale.nullspace.navigation.GalaxyPlan.StarSystem sol
            ? sol.planets().stream()
                .filter(com.nullandkale.nullspace.navigation.GalaxyPlan.Body::landable)
                .toList()
            : List.<com.nullandkale.nullspace.navigation.GalaxyPlan.Body>of();
    if (planets.isEmpty()) throw new IllegalStateException("No starting planets are available");
    var random =
        new Random(
            server.overworld().getSeed()
                ^ player.getMostSignificantBits()
                ^ Long.rotateLeft(player.getLeastSignificantBits(), 17));
    return planets.get(random.nextInt(planets.size())).id();
  }

  public static CompoundTag start(ServerPlayer player, WorldStartSettings settings) {
    if (!settings.space()) return null;
    var server = player.level().getServer();
    var store = ShipStore.get(server);
    var starts = store.section("spaceStarts");
    String key = player.getUUID().toString();
    var record = starts.getCompoundOrEmpty(key);
    if (record.getBooleanOr("completed", false))
      return store.repository().find(record.getStringOr("ship", ""));
    var level = server.getLevel(TravelService.SPACE);
    if (level == null) throw new IllegalStateException("The space dimension is unavailable");
    if (record.isEmpty()) {
      record.putString("ship", UUID.randomUUID().toString());
      record.putString("preset", settings.ship());
      record.putString("planet", planet(server, player.getUUID()));
      starts.put(key, record);
      store.save();
    }
    PlanetSurfaces.ensureLanding(server, record.getStringOr("planet", LocalSpaces.HOME));
    var preset = ShipBlueprints.get(record.getStringOr("preset", "sparrow"));
    var ship = store.repository().find(record.getStringOr("ship", ""));
    if (ship == null) {
      BlockPos base = null;
      for (int slot = 0; slot <= 7000 && base == null; slot++) {
        if (!TravelService.spaceSlotFree(server, slot)) continue;
        var candidate = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
        for (int x = 0; x < 32; x += 16)
          for (int z = 0; z < 32; z += 16) level.getChunk(candidate.offset(x, 0, z));
        boolean clear = true;
        for (var at : BlockPos.betweenClosed(candidate, candidate.offset(31, 31, 31)))
          if (!level.getBlockState(at).isAir()) {
            clear = false;
            break;
          }
        if (clear
            && level
                .getEntities(
                    null, new ShipScan.Bounds(candidate, candidate.offset(31, 31, 31)).box())
                .isEmpty()) base = candidate;
      }
      if (base == null) throw new IllegalStateException("No clear starting ship region remains");
      ship = new CompoundTag();
      ship.putString("id", record.getStringOr("ship", ""));
      ship.putString("dimension", TravelService.SPACE.identifier().toString());
      ship.putLong("base", base.asLong());
      ship.putLong("controller", base.offset(preset.helm()).asLong());
      ship.putInt("width", 32);
      ship.putInt("height", 32);
      ship.putInt("depth", 32);
      ship.putString("blueprint", preset.id());
      ship.putString("name", preset.name());
      ship.putString("owner", key);
      ship.putString("localSpace", record.getStringOr("planet", LocalSpaces.HOME));
      ship.put("crew", new ListTag());
      var arrival = NavigationService.arrivalOrigin(server, ship);
      ship.putDouble("simX", arrival.x);
      ship.putDouble("simY", arrival.y);
      ship.putDouble("simZ", arrival.z);
      store.ships().add(ship);
      store.save(); // Reserve identity and storage before placing the first block.
    }
    var base = ShipStore.pos(ship, "base");
    for (int x = 0; x < 32; x += 16)
      for (int z = 0; z < 32; z += 16) level.getChunk(base.offset(x, 0, z));
    var plan = ShipBlueprints.blocks(preset);
    for (var entry : plan.entrySet()) {
      var at = base.offset(entry.getKey());
      var existing = level.getBlockState(at);
      if (!existing.isAir() && !existing.equals(entry.getValue()))
        throw new IllegalStateException(
            "Starting ship construction is obstructed at " + at.toShortString());
      if (existing.isAir() && !level.setBlock(at, entry.getValue(), 18))
        throw new IllegalStateException(
            "Cannot place starting ship block at " + at.toShortString());
    }
    for (var entry : plan.entrySet())
      if (entry.getValue().is(ShipBlocks.GENERATOR))
        FuelSystem.provision(level, base.offset(entry.getKey()), 32);
    ShipSystems.upgrade(level, ship);
    ship.getCompoundOrEmpty("systems")
        .putIntArray("requests", new int[] {1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
    var controller = ShipStore.pos(ship, "controller");
    var facing = level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING);
    var at = controller.relative(facing);
    if (!ShipSystems.breathable(level, ship, at)
        || !ShipSystems.breathable(level, ship, at.above()))
      throw new IllegalStateException("Starting ship does not have a breathable cabin");
    ShipRecall.authorize(ship, player);
    var member = new CompoundTag();
    member.putString("id", key);
    member.putDouble("x", at.getX() - base.getX() + .5);
    member.putDouble("y", at.getY() - base.getY());
    member.putDouble("z", at.getZ() - base.getZ() + .5);
    var crew = ship.getListOrEmpty("crew");
    crew.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(key));
    crew.add(member);
    ship.put("crew", crew);
    store.save();
    server.saveEverything(true, true, true);
    player.teleportTo(
        level,
        at.getX() + .5,
        at.getY(),
        at.getZ() + .5,
        Set.of(),
        facing.getOpposite().toYRot(),
        0,
        true);
    if (!player.entityTags().contains(SUPPLIES)) {
      player.getInventory().add(new ItemStack(Items.BREAD, 16));
      player.getInventory().add(new ItemStack(Items.COAL, 16));
      player.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
      player.getInventory().add(new ItemStack(Items.IRON_AXE));
      player.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
      player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ShipBlocks.HELMET));
      player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ShipBlocks.CHEST));
      player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ShipBlocks.LEGS));
      player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ShipBlocks.BOOTS));
      com.nullandkale.nullspace.guide.FieldGuide.supply(player);
      player.addTag(SUPPLIES);
    }
    player.setAirSupply(player.getMaxAirSupply());
    server.getPlayerList().saveAll();
    record.putBoolean("completed", true);
    store.save();
    player.sendSystemMessage(
        Component.literal(
            "Welcome aboard "
                + preset.name()
                + ". You are above "
                + LocalSpaces.get(server, LocalSpaces.address(ship)).name()
                + ". Use the Ship Controller to explore. Fuel is in the generator; food is in your"
                + " inventory."));
    return ship;
  }

  private SpaceStart() {}
}
