package com.nullandkale.nullspace.gameplay.crew;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.docking.DockingTunnel;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;
import java.util.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

public final class LifeSupport {
  public static final int TETHER_RANGE = 72;

  public static double tetherDistance(CompoundTag vessel, Vec3 position) {
    if (!StationService.station(vessel))
      return position.distanceTo(Vec3.atCenterOf(WorldStore.pos(vessel, "controller")));
    var bounds = WorldStore.bounds(vessel).box();
    return position.distanceTo(
        new Vec3(
            Math.clamp(position.x, bounds.minX, bounds.maxX),
            Math.clamp(position.y, bounds.minY, bounds.maxY),
            Math.clamp(position.z, bounds.minZ, bounds.maxZ)));
  }

  private static final ServerContext.Key<Map<UUID, Integer>> AIR = new ServerContext.Key<>();

  private static Map<UUID, Integer> air(net.minecraft.server.MinecraftServer server) {
    return ServerContext.get(server).service(AIR, HashMap::new);
  }

  private static final ServerContext.Key<Map<UUID, Vec3>> LAST = new ServerContext.Key<>();

  private static Map<UUID, Vec3> last(net.minecraft.server.MinecraftServer server) {
    return ServerContext.get(server).service(LAST, HashMap::new);
  }

  public static boolean suited(ServerPlayer p) {
    // Plain and armored suit pieces protect the same way; any mix counts as a full suit.
    return piece(p, EquipmentSlot.HEAD, ModBlocks.HELMET, ModBlocks.ARMORED_HELMET)
        && piece(p, EquipmentSlot.CHEST, ModBlocks.CHEST, ModBlocks.ARMORED_CHEST)
        && piece(p, EquipmentSlot.LEGS, ModBlocks.LEGS, ModBlocks.ARMORED_LEGS)
        && piece(p, EquipmentSlot.FEET, ModBlocks.BOOTS, ModBlocks.ARMORED_BOOTS);
  }

  private static boolean piece(
      ServerPlayer p,
      EquipmentSlot slot,
      net.minecraft.world.item.Item plain,
      net.minecraft.world.item.Item armored) {
    var stack = p.getItemBySlot(slot);
    return stack.is(plain) || stack.is(armored);
  }

  /** Suited and tethered, the meter never falls below this (player's spec). */
  public static final int SUIT_TETHER_FLOOR = 20;

  /** Ticks of full suit air in one Air Canister (2 minutes). */
  public static final int CANISTER_TICKS = 2 * 60 * 20;

  /** Uses one tick from the emptiest canister that still holds air; false if none do. */
  static boolean drawCanister(ServerPlayer player) {
    var inventory = player.getInventory();
    net.minecraft.world.item.ItemStack best = null;
    for (int i = 0; i < inventory.getContainerSize(); i++) {
      var stack = inventory.getItem(i);
      if (stack.is(ModBlocks.AIR_CANISTER)
          && stack.getDamageValue() < stack.getMaxDamage()
          && (best == null || stack.getDamageValue() > best.getDamageValue())) best = stack;
    }
    if (best == null) return false;
    best.setDamageValue(best.getDamageValue() + 1);
    return true;
  }

  /** Air left in all carried canisters, as a percentage of one full canister. */
  static int canisterPercent(ServerPlayer player) {
    long left = 0;
    var inventory = player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++) {
      var stack = inventory.getItem(i);
      if (stack.is(ModBlocks.AIR_CANISTER)) left += stack.getMaxDamage() - stack.getDamageValue();
    }
    return (int) (left * 100 / CANISTER_TICKS);
  }

  /**
   * What the meter drifts toward: breathable air where the player stands; otherwise, with the full
   * suit, a carried canister (full air) or the ship tether (the 20 floor); otherwise the local air.
   */
  static int suitTarget(ServerPlayer player, int local, boolean tethered) {
    if (local >= SpatialAir.percent(Atmosphere.BREATHABLE) || !suited(player)) return local;
    if (drawCanister(player)) return 100;
    return tethered ? Math.max(local, SUIT_TETHER_FLOOR) : local;
  }

  /** Refills a held canister from a powered Air Supply. */
  public static net.minecraft.world.InteractionResult fillCanister(
      ServerPlayer player, net.minecraft.world.InteractionHand hand, BlockPos at) {
    var stack = player.getItemInHand(hand);
    if (!stack.is(ModBlocks.AIR_CANISTER)
        || !player.level().getBlockState(at).is(ModBlocks.AIR_SUPPLY))
      return net.minecraft.world.InteractionResult.PASS;
    var ship = WorldStore.get(player.level().getServer()).at(player.level(), at);
    boolean powered =
        ship != null
            && ShipSystems.enabled(ship)
            && ShipSystems.active(ship, at)
            && ShipSystems.power(player.level(), ship, PowerBus.System.AIR) > 0;
    if (!powered) {
      player.sendOverlayMessage(
          net.minecraft.network.chat.Component.literal(
              "This Air Supply needs power to fill a canister"));
      return net.minecraft.world.InteractionResult.SUCCESS;
    }
    stack.setDamageValue(0);
    player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Air canister filled"));
    return net.minecraft.world.InteractionResult.SUCCESS;
  }

  /**
   * The oxygen meter, 0-100 (stored in tenths). It drifts toward the air where the player's head
   * is: 10 points a second down, 20 up. Vanilla air bubbles show it; at 0 the player drowns at 2
   * health a second, so an unsuited player in vacuum lasts about 20 seconds in total.
   */
  static int breathe(ServerPlayer player, int target, CompoundTag saved) {
    var meters = air(player.level().getServer());
    String key = player.getUUID().toString();
    int meter = meters.getOrDefault(player.getUUID(), saved.getIntOr(key, 1000));
    int goal = Math.clamp(target, 0, 100) * 10;
    meter = goal < meter ? meter - Math.min(5, meter - goal) : meter + Math.min(10, goal - meter);
    meters.put(player.getUUID(), meter);
    saved.putInt(key, meter);
    if (player.isUnderWater()) return meter; // vanilla owns air bubbles underwater
    player.setAirSupply(meter * player.getMaxAirSupply() / 1000);
    if (meter == 0 && player.level().getServer().getTickCount() % 20 == 0)
      player.hurtServer(player.level(), player.damageSources().drown(), 2);
    return meter;
  }

  /** Oxygen at the player's head inside this vessel. Air comes only from working ship systems. */
  private static int localOxygen(ServerPlayer player, CompoundTag ship) {
    if (ship == null || !ShipSystems.enabled(ship)) return 0;
    return ShipSystems.oxygen(player.level(), ship, BlockPos.containing(player.getEyePosition()));
  }

  private static void surfaceAir(ServerPlayer player, WorldStore store, CompoundTag savedAir) {
    if (!player.isAlive()
        || TravelService.busy(player)
        || player.isCreative()
        || player.isSpectator()) return;
    var ship = store.at(player.level(), player.blockPosition());
    boolean cabin = false, source = false;
    // A ship whose systems are not set up (no generator and Air Supply) has no air to give.
    if (ship != null && ShipSystems.enabled(ship)) {
      cabin =
          ShipSystems.breathable(
              player.level(), ship, BlockPos.containing(player.getEyePosition()));
      source = !cabin && suited(player) && ShipSystems.tether(player.level(), ship);
    }
    int local = localOxygen(player, ship);
    int meter = breathe(player, suitTarget(player, local, source), savedAir);
    if (player.level().getServer().getTickCount() % 20 == 0)
      player.sendOverlayMessage(
          net.minecraft.network.chat.Component.literal(
              (cabin ? "CABIN AIR" : source ? "SUIT / TETHER AIR" : "AIRLESS SURFACE")
                  + " | Oxygen "
                  + meter / 10
                  + (suited(player) && !cabin
                      ? " | Canister " + canisterPercent(player) + "%"
                      : "")));
  }

  public static void initialize() {
    net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(
        (player, level, hand, hit) ->
            player instanceof ServerPlayer p
                ? fillCanister(p, hand, hit.getBlockPos())
                : net.minecraft.world.InteractionResult.PASS);
    ServerContext.tick(
        ServerContext.Phase.SURVIVAL,
        server -> {
          var store = WorldStore.get(server);

          var savedAir = store.section("oxygen");
          for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(TravelService.SPACE)) {
              if (PlanetSurfaces.vacuum(player.level())) {
                surfaceAir(player, store, savedAir);
                continue;
              }
              if (air(player.level().getServer()).remove(player.getUUID()) != null
                  && !player.isCreative()
                  && !player.isSpectator()) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
              }
              last(player.level().getServer()).remove(player.getUUID());
              continue;
            }
            if (!player.isAlive()) continue;
            if (TravelService.busy(player)) continue;
            CompoundTag vessel = null;
            for (Tag tag : store.ships()) {
              var candidate = (CompoundTag) tag;
              if (!candidate
                  .getStringOr("dimension", "")
                  .equals(TravelService.SPACE.identifier().toString())) continue;
              for (Tag member : candidate.getListOrEmpty("crew"))
                if (((CompoundTag) member)
                    .getStringOr("id", "")
                    .equals(player.getUUID().toString())) vessel = candidate;
            }
            if (vessel == null) vessel = store.at(player.level(), player.blockPosition());
            if (vessel == null) {
              // Rescue an unassociated player to a planet, never generate terrain in space.
              var overworld = server.overworld();
              var spawn = overworld.getRespawnData().pos();
              player.teleportTo(
                  overworld,
                  spawn.getX() + .5,
                  spawn.getY() + 1,
                  spawn.getZ() + .5,
                  Set.of(),
                  0,
                  0,
                  true);
              continue;
            }
            BlockPos anchor = WorldStore.pos(vessel, "controller");
            if (tetherDistance(vessel, player.position()) > TETHER_RANGE) {
              var anchorState = player.level().getBlockState(anchor);
              var boarding =
                  ModBlocks.navigation(anchorState)
                      ? anchor.relative(
                          anchorState.getValue(
                              net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING))
                      : anchor.above();
              Vec3 last =
                  last(player.level().getServer())
                      .getOrDefault(
                          player.getUUID(),
                          new Vec3(boarding.getX() + .5, boarding.getY(), boarding.getZ() + .5));
              player.teleportTo(last.x, last.y, last.z);
              player.setDeltaMovement(Vec3.ZERO);
              player.sendOverlayMessage(
                  net.minecraft.network.chat.Component.literal("Tether limit: 72 blocks"));
            } else last(player.level().getServer()).put(player.getUUID(), player.position());
            boolean source = false, cabin = false;
            // A ship whose systems are not set up has no air: suits are needed until it does.
            if (ShipSystems.enabled(vessel)) {
              cabin =
                  ShipSystems.breathable(
                      player.level(), vessel, BlockPos.containing(player.getEyePosition()));
              source = !cabin && suited(player) && ShipSystems.tether(player.level(), vessel);
            }
            boolean construction = DockingService.constructionAir(player, vessel);
            boolean protection = suited(player) && source || construction;
            if (!player.isSpectator()) {
              boolean vacuumFlight = (player.isCreative() || suited(player)) && !cabin;
              // Vanilla lands the client on contact with a floor. Respect that state
              // instead of forcing flight back on and sending corrections every tick.
              boolean flight = vacuumFlight && !player.onGround();
              boolean allowed = player.isCreative() || vacuumFlight;
              if (player.getAbilities().mayfly != allowed
                  || player.getAbilities().flying != flight) {
                player.getAbilities().mayfly = allowed;
                player.getAbilities().flying = flight;
                player.onUpdateAbilities();
              }
            }
            if (!player.isCreative() && !player.isSpectator()) {
              int local = localOxygen(player, vessel);
              breathe(player, construction ? 100 : suitTarget(player, local, source), savedAir);
            }
            if (server.getTickCount() % 20 == 0) {
              player.sendOverlayMessage(
                  net.minecraft.network.chat.Component.literal(
                      (cabin
                              ? "CABIN SEALED"
                              : protection ? "SUIT / TETHER AIR" : "VACUUM - NO AIR")
                          + " | Tether "
                          + (int) tetherDistance(vessel, player.position())
                          + "/72 | Oxygen "
                          + air(player.level().getServer()).getOrDefault(player.getUUID(), 1000)
                              / 10));
              // Record offline crew offsets without replacing or refilling their inventories or
              // air.
              var members = vessel.getListOrEmpty("crew");
              CompoundTag member = null;
              for (Tag tag : members)
                if (((CompoundTag) tag).getStringOr("id", "").equals(player.getUUID().toString()))
                  member = (CompoundTag) tag;
              if (member == null) {
                member = new CompoundTag();
                member.putString("id", player.getUUID().toString());
                members.add(member);
              }
              var base = WorldStore.pos(vessel, "base");
              member.putDouble("x", player.getX() - base.getX());
              member.putDouble("y", player.getY() - base.getY());
              member.putDouble("z", player.getZ() - base.getZ());
              member.putFloat("yaw", player.getYRot());
              member.putFloat("pitch", player.getXRot());
            }
          }
          // ShipSystems owns the periodic metadata save; avoid a second gzip/fsync
          // of the same ship/air state on the same tick. Disconnect/stop still flush.
        });
    UseBlockCallback.EVENT.register(
        (player, world, hand, hit) -> {
          if (player instanceof ServerPlayer p) {
            if (TravelService.busy(p) || TravelService.locked(p.level(), hit.getBlockPos()))
              return InteractionResult.FAIL;
            if (world.dimension().equals(TravelService.SPACE)) {
              if (player.getItemInHand(hand).getItem()
                  instanceof net.minecraft.world.item.BucketItem) return InteractionResult.FAIL;
              var vessel = WorldStore.get(p.level().getServer()).at(p.level(), hit.getBlockPos());
              if (vessel == null
                  || (player.getItemInHand(hand).getItem()
                          instanceof net.minecraft.world.item.BlockItem
                      && !WorldStore.bounds(vessel)
                          .contains(hit.getBlockPos().relative(hit.getDirection()))))
                return InteractionResult.FAIL;
            }
          }
          if (player instanceof ServerPlayer p
              && DockingService.useCompactDoor(p, hit.getBlockPos()))
            return InteractionResult.SUCCESS;
          return InteractionResult.PASS;
        });
    AttackBlockCallback.EVENT.register(
        (player, world, hand, pos, face) -> {
          if (player instanceof ServerPlayer p
              && (DockingTunnel.protectedAt(p.level(), pos)
                  || TravelService.busy(p)
                  || TravelService.locked(p.level(), pos)
                  || (world.dimension().equals(TravelService.SPACE)
                      && WorldStore.get(p.level().getServer()).at(p.level(), pos) == null)))
            return InteractionResult.FAIL;
          return InteractionResult.PASS;
        });
    PlayerBlockBreakEvents.BEFORE.register(
        (world, player, pos, state, be) ->
            !(player instanceof ServerPlayer p)
                || (!DockingTunnel.protectedAt(p.level(), pos)
                    && !TravelService.busy(p)
                    && !TravelService.locked(p.level(), pos)
                    && (!world.dimension().equals(TravelService.SPACE)
                        || WorldStore.get(p.level().getServer()).at(p.level(), pos) != null)));
    UseItemCallback.EVENT.register(
        (player, world, hand) ->
            player instanceof ServerPlayer p && TravelService.busy(p)
                ? InteractionResult.FAIL
                : InteractionResult.PASS);
    UseEntityCallback.EVENT.register(
        (player, world, hand, entity, hit) ->
            player instanceof ServerPlayer p
                    && (TravelService.busy(p)
                        || TravelService.locked(p.level(), entity.blockPosition()))
                ? InteractionResult.FAIL
                : InteractionResult.PASS);
    AttackEntityCallback.EVENT.register(
        (player, world, hand, entity, hit) ->
            player instanceof ServerPlayer p
                    && (TravelService.busy(p)
                        || TravelService.locked(p.level(), entity.blockPosition()))
                ? InteractionResult.FAIL
                : InteractionResult.PASS);
    net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> WorldStore.get(server).save());
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> WorldStore.get(server).save());
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          ServerContext.get(server).remove(AIR);
          ServerContext.get(server).remove(LAST);
        });
    ServerLivingEntityEvents.AFTER_DEATH.register(
        (entity, damage) -> {
          if (entity instanceof ServerPlayer p) {
            ShipStorage.rememberDeath(p);
            var store = WorldStore.get(p.level().getServer());
            for (Tag tag : store.ships())
              ((CompoundTag) tag)
                  .getListOrEmpty("crew")
                  .removeIf(
                      t -> ((CompoundTag) t).getStringOr("id", "").equals(p.getUUID().toString()));
            store
                .list("targets")
                .removeIf(
                    t -> ((CompoundTag) t).getStringOr("id", "").equals(p.getUUID().toString()));
            store.section("oxygen").remove(p.getUUID().toString());
            air(p.level().getServer()).remove(p.getUUID());
            last(p.level().getServer()).remove(p.getUUID());
            store.save();
          }
        });
    net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
        (oldPlayer, player, alive) -> {
          if (oldPlayer.level().dimension().equals(TravelService.SPACE)
              && !player.isCreative()
              && !player.isSpectator()) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
          }
        });
    ServerEntityEvents.ENTITY_LOAD.register(
        (entity, level) -> {
          if (entity instanceof ServerPlayer) return;
          var store = WorldStore.get(level.getServer());
          for (Tag item : store.list("entityAuthority")) {
            var entry = (CompoundTag) item;
            if (entry.getStringOr("id", "").equals(entity.getUUID().toString())
                && !entry
                    .getStringOr("dimension", "")
                    .equals(level.dimension().identifier().toString())
                && entry
                    .getStringOr("obsoleteDimension", "")
                    .equals(level.dimension().identifier().toString())) {
              var bounds =
                  new HullScan.Bounds(WorldStore.pos(entry, "min"), WorldStore.pos(entry, "max"));
              if (bounds.box().inflate(2).contains(entity.position())) entity.discard();
            }
          }
        });
  }

  private LifeSupport() {}
}
