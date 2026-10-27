package com.nullandkale.nullspace.ship;

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

public final class SpaceSurvival {
  public static final int TETHER_RANGE = 72;
  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, Integer>> AIR =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, Integer> air(net.minecraft.server.MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(AIR, HashMap::new);
  }

  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, Vec3>> LAST =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, Vec3> last(net.minecraft.server.MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(LAST, HashMap::new);
  }

  public static boolean suited(ServerPlayer p) {
    return p.getItemBySlot(EquipmentSlot.HEAD).is(ShipBlocks.HELMET)
        && p.getItemBySlot(EquipmentSlot.CHEST).is(ShipBlocks.CHEST)
        && p.getItemBySlot(EquipmentSlot.LEGS).is(ShipBlocks.LEGS)
        && p.getItemBySlot(EquipmentSlot.FEET).is(ShipBlocks.BOOTS);
  }

  private static void surfaceAir(ServerPlayer player, ShipStore store, CompoundTag savedAir) {
    if (!player.isAlive()
        || TravelService.busy(player)
        || player.isCreative()
        || player.isSpectator()) return;
    var ship = store.at(player.level(), player.blockPosition());
    boolean cabin = false, source = false;
    if (ship != null) {
      if (com.nullandkale.nullspace.systems.ShipSystems.enabled(ship)) {
        cabin =
            com.nullandkale.nullspace.systems.ShipSystems.breathable(
                player.level(), ship, BlockPos.containing(player.getEyePosition()));
        source =
            !cabin
                && suited(player)
                && com.nullandkale.nullspace.systems.ShipSystems.tether(player.level(), ship);
      } else {
        var pressure = new ShipScan.LivePressure(player.level(), ShipStore.bounds(ship));
        cabin = pressure.breathable(BlockPos.containing(player.getEyePosition()));
      }
    }
    int air =
        air(player.level().getServer())
            .getOrDefault(
                player.getUUID(),
                savedAir.getIntOr(player.getUUID().toString(), player.getAirSupply()));
    air = cabin || source ? Math.min(player.getMaxAirSupply(), air + 4) : air - 1;
    if (air <= -20) {
      player.hurtServer(player.level(), player.damageSources().drown(), 4);
      air = 0;
    }
    air(player.level().getServer()).put(player.getUUID(), air);
    player.setAirSupply(Math.max(-20, air));
    savedAir.putInt(player.getUUID().toString(), air);
    if (player.level().getServer().getTickCount() % 20 == 0)
      player.sendOverlayMessage(
          net.minecraft.network.chat.Component.literal(
              (cabin ? "CABIN AIR" : source ? "SUIT / TETHER AIR" : "AIRLESS SURFACE")
                  + " | Oxygen "
                  + Math.max(0, air)));
  }

  public static void initialize() {
    com.nullandkale.nullspace.server.ServerContext.tick(
        com.nullandkale.nullspace.server.ServerContext.Phase.SURVIVAL,
        server -> {
          Map<String, ShipScan.LivePressure> rooms = new HashMap<>();
          var store = ShipStore.get(server);

          var savedAir = store.section("air");
          for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(TravelService.SPACE)) {
              if (com.nullandkale.nullspace.navigation.PlanetSurfaces.vacuum(player.level())) {
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
            BlockPos anchor = ShipStore.pos(vessel, "controller");
            Vec3 center = Vec3.atCenterOf(anchor);
            if (player.position().distanceToSqr(center) > TETHER_RANGE * TETHER_RANGE) {
              var anchorState = player.level().getBlockState(anchor);
              var boarding =
                  ShipBlocks.navigation(anchorState)
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
            boolean source = false, cabin;
            if (com.nullandkale.nullspace.systems.ShipSystems.enabled(vessel)) {
              cabin =
                  com.nullandkale.nullspace.systems.ShipSystems.breathable(
                      player.level(), vessel, BlockPos.containing(player.getEyePosition()));
              source =
                  !cabin
                      && suited(player)
                      && com.nullandkale.nullspace.systems.ShipSystems.tether(
                          player.level(), vessel);
            } else {
              String id = vessel.getStringOr("id", "");
              var selected = vessel;
              var pressure =
                  rooms.computeIfAbsent(
                      id,
                      key -> new ShipScan.LivePressure(player.level(), ShipStore.bounds(selected)));
              var state = player.level().getBlockState(anchor);
              if (ShipBlocks.navigation(state))
                source =
                    pressure.breathable(
                        anchor.relative(
                            state.getValue(
                                net.minecraft.world.level.block.HorizontalDirectionalBlock
                                    .FACING)));
              cabin = pressure.breathable(BlockPos.containing(player.getEyePosition()));
            }
            boolean protection = suited(player) && source;
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
              if (!cabin && !protection) {
                int air =
                    air(player.level().getServer())
                            .getOrDefault(
                                player.getUUID(),
                                savedAir.getIntOr(
                                    player.getUUID().toString(), player.getAirSupply()))
                        - 1;
                air(player.level().getServer()).put(player.getUUID(), air);
                player.setAirSupply(Math.max(-20, air));
                if (air <= -20) {
                  player.hurtServer(player.level(), player.damageSources().drown(), 4);
                  air(player.level().getServer()).put(player.getUUID(), 0);
                }
              } else if (!player.isUnderWater()) {
                int air =
                    Math.min(
                        player.getMaxAirSupply(),
                        air(player.level().getServer())
                                .getOrDefault(
                                    player.getUUID(),
                                    savedAir.getIntOr(
                                        player.getUUID().toString(), player.getAirSupply()))
                            + 4);
                air(player.level().getServer()).put(player.getUUID(), air);
                player.setAirSupply(air);
              } else {
                // Vanilla owns underwater air. Keep the vacuum ledger synchronized so
                // leaving water into a breach cannot restore a stale dry-cabin reserve.
                air(player.level().getServer()).put(player.getUUID(), player.getAirSupply());
              }
            }
            savedAir.putInt(
                player.getUUID().toString(),
                air(player.level().getServer())
                    .getOrDefault(player.getUUID(), player.getAirSupply()));
            if (server.getTickCount() % 20 == 0) {
              player.sendOverlayMessage(
                  net.minecraft.network.chat.Component.literal(
                      (cabin
                              ? "CABIN SEALED"
                              : protection ? "SUIT / TETHER AIR" : "VACUUM - NO AIR")
                          + " | Tether "
                          + (int) player.position().distanceTo(center)
                          + "/72 | Air "
                          + Math.max(
                              0,
                              air(player.level().getServer())
                                  .getOrDefault(player.getUUID(), 300))));
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
              var base = ShipStore.pos(vessel, "base");
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
              var vessel = ShipStore.get(p.level().getServer()).at(p.level(), hit.getBlockPos());
              if (vessel == null
                  || (player.getItemInHand(hand).getItem()
                          instanceof net.minecraft.world.item.BlockItem
                      && !ShipStore.bounds(vessel)
                          .contains(hit.getBlockPos().relative(hit.getDirection()))))
                return InteractionResult.FAIL;
            }
          }
          return InteractionResult.PASS;
        });
    AttackBlockCallback.EVENT.register(
        (player, world, hand, pos, face) -> {
          if (player instanceof ServerPlayer p
              && (TravelService.busy(p)
                  || TravelService.locked(p.level(), pos)
                  || (world.dimension().equals(TravelService.SPACE)
                      && ShipStore.get(p.level().getServer()).at(p.level(), pos) == null)))
            return InteractionResult.FAIL;
          return InteractionResult.PASS;
        });
    PlayerBlockBreakEvents.BEFORE.register(
        (world, player, pos, state, be) ->
            !(player instanceof ServerPlayer p)
                || (!TravelService.busy(p)
                    && !TravelService.locked(p.level(), pos)
                    && (!world.dimension().equals(TravelService.SPACE)
                        || ShipStore.get(p.level().getServer()).at(p.level(), pos) != null)));
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
        (handler, server) -> ShipStore.get(server).save());
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> ShipStore.get(server).save());
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          com.nullandkale.nullspace.server.ServerContext.get(server).remove(AIR);
          com.nullandkale.nullspace.server.ServerContext.get(server).remove(LAST);
        });
    ServerLivingEntityEvents.AFTER_DEATH.register(
        (entity, damage) -> {
          if (entity instanceof ServerPlayer p) {
            var store = ShipStore.get(p.level().getServer());
            for (Tag tag : store.ships())
              ((CompoundTag) tag)
                  .getListOrEmpty("crew")
                  .removeIf(
                      t -> ((CompoundTag) t).getStringOr("id", "").equals(p.getUUID().toString()));
            store
                .list("targets")
                .removeIf(
                    t -> ((CompoundTag) t).getStringOr("id", "").equals(p.getUUID().toString()));
            store.section("air").remove(p.getUUID().toString());
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
          var store = ShipStore.get(level.getServer());
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
                  new ShipScan.Bounds(ShipStore.pos(entry, "min"), ShipStore.pos(entry, "max"));
              if (bounds.box().inflate(2).contains(entity.position())) entity.discard();
            }
          }
        });
  }

  private SpaceSurvival() {}
}
