package com.nullandkale.nullspace.gameplay.crew;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.worldgen.sites.TerraHarbor;
import java.util.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Respawn identity is (vessel, bed placement), never a reusable space allocation. */
public final class ShipRespawns {
  private static String key(String ship, BlockPos local) {
    return ship + "@" + local.asLong();
  }

  public static boolean bind(ServerPlayer player, BlockPos at) {
    var level = player.level();
    var store = WorldStore.get(level.getServer());
    var ship = store.at(level, at);
    var state = level.getBlockState(at);
    if (ship == null || !(state.getBlock() instanceof AbstractBedBlock)) return false;
    if (!StationService.allowed(player, ship)) {
      player.sendSystemMessage(
          Component.literal("This station does not allow you to set a bed here"));
      return true;
    }
    if (TravelService.busy(player)) return true;
    var head =
        state.getValue(AbstractBedBlock.PART) == BedPart.HEAD
            ? at
            : at.relative(state.getValue(AbstractBedBlock.FACING));
    var local = head.subtract(WorldStore.pos(ship, "base"));
    String identity = key(ship.getStringOr("id", ""), local);
    var placements = store.section("bedPlacements");
    if (!placements.contains(identity))
      placements.putString(identity, UUID.randomUUID().toString());
    var binding = new CompoundTag();
    binding.putString("ship", ship.getStringOr("id", ""));
    binding.putLong("bed", local.asLong());
    binding.putString("placement", placements.getStringOr(identity, ""));
    binding.putFloat("yaw", player.getYRot());
    store.section("vesselBeds").put(player.getUUID().toString(), binding);
    store.save();
    player.sendSystemMessage(Component.literal("Respawn aboard this ship"));
    return true;
  }

  public static void changed(ServerLevel level, BlockPos at, boolean replaced) {
    if (!replaced || TravelService.worldMutation()) return;
    var store = WorldStore.get(level.getServer());
    var ship = store.at(level, at);
    if (ship == null) return;
    // Either half changing invalidates the placement, including break-and-replace in one tick.
    for (var p : List.of(at, at.north(), at.south(), at.east(), at.west()))
      store
          .section("bedPlacements")
          .remove(key(ship.getStringOr("id", ""), p.subtract(WorldStore.pos(ship, "base"))));
    store.requestSave();
  }

  public static void surfaceSpawnChanged(ServerPlayer player, ServerPlayer.RespawnConfig config) {
    if (config == null) return;
    var level = player.level().getServer().getLevel(config.respawnData().dimension());
    if (level != null
        && WorldStore.get(level.getServer()).at(level, config.respawnData().pos()) == null) {
      WorldStore.get(level.getServer()).section("vesselBeds").remove(player.getUUID().toString());
      WorldStore.get(level.getServer()).requestSave();
    }
  }

  public static void captureSurface(
      ServerLevel level, String ship, BlockPos base, Set<BlockPos> blocks) {
    var store = WorldStore.get(level.getServer());
    for (var player : level.getServer().getPlayerList().getPlayers()) {
      var config = player.getRespawnConfig();
      if (config == null
          || !config.respawnData().dimension().equals(level.dimension())
          || !blocks.contains(config.respawnData().pos())) continue;
      var at = config.respawnData().pos();
      if (!(level.getBlockState(at).getBlock() instanceof AbstractBedBlock)) continue;
      var local = at.subtract(base);
      String id = key(ship, local);
      var placements = store.section("bedPlacements");
      if (!placements.contains(id)) placements.putString(id, UUID.randomUUID().toString());
      var binding = new CompoundTag();
      binding.putString("ship", ship);
      binding.putLong("bed", local.asLong());
      binding.putString("placement", placements.getStringOr(id, ""));
      binding.putFloat("yaw", config.respawnData().yaw());
      store.section("vesselBeds").put(player.getUUID().toString(), binding);
    }
  }

  public static TeleportTransition resolve(
      ServerPlayer player, TeleportTransition.PostTeleportTransition post) {
    var server = player.level().getServer();
    var store = WorldStore.get(server);
    var binding = store.section("vesselBeds").getCompoundOrEmpty(player.getUUID().toString());
    if (binding.isEmpty()) return null; // Ordinary Minecraft players retain vanilla respawn.
    var ship = store.repository().find(binding.getStringOr("ship", ""));
    String reason = "Your ship bed is missing or unsafe";
    if (ship == null) reason = "Your ship is no longer available";
    else if (ship.getBooleanOr("stored", false)) reason = "Your ship is in storage";
    else if (TravelService.inTransit(server, ship)) reason = "Your ship is between locations";
    else if (!StationService.allowed(player, ship))
      reason = "You no longer have access to your ship";
    if (ship != null
        && !ship.getBooleanOr("stored", false)
        && !TravelService.inTransit(server, ship)
        && StationService.allowed(player, ship)) {
      var level = WorldStore.level(server, ship.getStringOr("dimension", ""));
      var local = WorldStore.pos(binding, "bed");
      var at = WorldStore.pos(ship, "base").offset(local);
      level.getChunk(at);
      var state = level.getBlockState(at);
      if (binding
              .getStringOr("placement", "")
              .equals(
                  store
                      .section("bedPlacements")
                      .getStringOr(key(ship.getStringOr("id", ""), local), "missing"))
          && state.getBlock() instanceof AbstractBedBlock
          && state.getValue(AbstractBedBlock.PART) == BedPart.HEAD
          && ShipSystems.connected(level, ship).contains(at)) {
        var spot =
            AbstractBedBlock.findStandUpPosition(
                EntityTypes.PLAYER,
                level,
                at,
                state.getValue(AbstractBedBlock.FACING),
                binding.getFloatOr("yaw", 0));
        if (spot.isPresent()
            && (PlanetSurfaces.breathable(level)
                || ShipSystems.breathable(
                    level, ship, BlockPos.containing(spot.get().add(0, 1.6, 0))))) {
          var member = new CompoundTag();
          member.putString("id", player.getUUID().toString());
          var base = WorldStore.pos(ship, "base");
          member.putDouble("x", spot.get().x - base.getX());
          member.putDouble("y", spot.get().y - base.getY());
          member.putDouble("z", spot.get().z - base.getZ());
          var crew = ship.getListOrEmpty("crew");
          crew.removeIf(
              t -> ((CompoundTag) t).getStringOr("id", "").equals(player.getUUID().toString()));
          crew.add(member);
          ship.put("crew", crew);
          store.requestSave();
          return new TeleportTransition(
              level, spot.get(), Vec3.ZERO, binding.getFloatOr("yaw", 0), 0, post);
        }
      }
    }
    var spot = TerraHarbor.arrival(server);
    player.sendSystemMessage(Component.literal(reason + ". Respawning at Terra Harbor."));
    return new TeleportTransition(server.overworld(), spot, Vec3.ZERO, 0, 0, post);
  }

  /**
   * Ship beds only replace vanilla where the world can never let you sleep (space has no day/night
   * cycle and would destroy a bed). Elsewhere vanilla sleeps and sets the spawn; launch then
   * converts that spawn into a ship-relative one (captureSurface).
   */
  static boolean replacesVanilla(net.minecraft.world.level.Level level, BlockPos at) {
    var state = level.getBlockState(at);
    if (!(state.getBlock() instanceof AbstractBedBlock bed)) return false;
    var rule = bed.getBedRule(level, at);
    return rule.destroyOnUse()
        || rule.canSleep() == net.minecraft.world.attribute.BedRule.Rule.NEVER;
  }

  public static void initialize() {
    UseBlockCallback.EVENT.register(
        (player, level, hand, hit) ->
            player instanceof ServerPlayer p
                    && !p.isShiftKeyDown()
                    && replacesVanilla(level, hit.getBlockPos())
                    && bind(p, hit.getBlockPos())
                ? InteractionResult.SUCCESS
                : InteractionResult.PASS);
  }

  private ShipRespawns() {}
}
