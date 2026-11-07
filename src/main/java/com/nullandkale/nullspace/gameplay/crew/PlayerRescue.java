package com.nullandkale.nullspace.gameplay.crew;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.worldgen.sites.TerraHarbor;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Rescue owns a player-location intent, never a second copy of their inventory. */
public final class PlayerRescue {
  private static final ServerContext.Key<Map<UUID, Integer>> PROTECTION = new ServerContext.Key<>();

  public record Request(boolean cancel) implements CustomPacketPayload {
    public static final Type<Request> TYPE = new Type<>(Identifier.parse("nullspace:rescue"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
        StreamCodec.of((b, p) -> b.writeBoolean(p.cancel), b -> new Request(b.readBoolean()));

    public Type<Request> type() {
      return TYPE;
    }
  }

  public static String request(ServerPlayer player, boolean cancel) {
    var store = WorldStore.get(player.level().getServer());
    String id = player.getUUID().toString();
    if (cancel) {
      if (!store.section("rescues").contains(id)) return "No rescue is pending";
      store.section("rescues").remove(id);
      store.save();
      return "Rescue cancelled";
    }
    if (!player.isAlive()) return "Respawn before calling rescue";
    if (store.section("rescues").contains(id)) return "Rescue is already on its way";
    var intent = new CompoundTag();
    intent.putInt("remaining", 60);
    store.section("rescues").put(id, intent);
    store.save();
    return "Rescue takes you to Terra Harbor. You keep what you are carrying. Your ship and its"
        + " cargo stay here.";
  }

  public static boolean protectedPlayer(ServerPlayer player) {
    var until = ServerContext.get(player.level().getServer()).existing(PROTECTION);
    return until != null
        && until.getOrDefault(player.getUUID(), 0) > player.level().getServer().getTickCount();
  }

  public static void arrive(ServerPlayer player) {
    var server = player.level().getServer();
    var at = TerraHarbor.arrival(server);
    var store = WorldStore.get(server);
    for (var tag : store.ships())
      ((CompoundTag) tag)
          .getListOrEmpty("crew")
          .removeIf(
              t -> ((CompoundTag) t).getStringOr("id", "").equals(player.getUUID().toString()));
    store
        .list("targets")
        .removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(player.getUUID().toString()));
    // Remove stale crew/arrival anchors before teleport. A retry can only move the same player.
    store.save();
    player.stopRiding();
    player.closeContainer();
    player.teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), 0, 0, true);
    player.setDeltaMovement(Vec3.ZERO);
    player.fallDistance = 0;
    player.setAirSupply(player.getMaxAirSupply());
    if (!player.isCreative() && !player.isSpectator()) {
      player.getAbilities().mayfly = false;
      player.getAbilities().flying = false;
      player.onUpdateAbilities();
    }
    ServerContext.get(server)
        .service(PROTECTION, HashMap::new)
        .put(player.getUUID(), server.getTickCount() + 200);
  }

  public static void initialize() {
    PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Request.TYPE,
        (packet, context) ->
            context
                .server()
                .execute(
                    () ->
                        context
                            .player()
                            .sendSystemMessage(
                                Component.literal(request(context.player(), packet.cancel())))));
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) ->
            dispatcher.register(
                Commands.literal("nullspace")
                    .then(
                        Commands.literal("rescue")
                            .executes(
                                c -> {
                                  var p = c.getSource().getPlayerOrException();
                                  p.sendSystemMessage(Component.literal(request(p, false)));
                                  return 1;
                                })
                            .then(
                                Commands.literal("cancel")
                                    .executes(
                                        c -> {
                                          var p = c.getSource().getPlayerOrException();
                                          p.sendSystemMessage(Component.literal(request(p, true)));
                                          return 1;
                                        })))));
    ServerContext.tick(
        ServerContext.Phase.SESSIONS,
        server -> {
          var store = WorldStore.get(server);
          var rescues = store.section("rescues");
          for (var id : List.copyOf(rescues.keySet())) {
            var p = server.getPlayerList().getPlayer(UUID.fromString(id));
            if (p == null)
              continue; // Persist across reconnect; do not create another player/inventory.
            if (!p.isAlive()) {
              rescues.remove(id);
              store.requestSave();
              continue;
            }
            var intent = rescues.getCompoundOrEmpty(id);
            int remaining = intent.getIntOr("remaining", 0);
            if (remaining > 0) {
              intent.putInt("remaining", remaining - 1);
              if (remaining % 20 == 0)
                p.sendOverlayMessage(Component.literal("Rescue: " + (remaining / 20) + "..."));
              continue;
            }
            if (TravelService.busy(p))
              continue; // Never split an in-progress boarding/physical transfer.
            try {
              arrive(p);
              rescues.remove(id);
              store.save();
              p.sendSystemMessage(
                  Component.literal(
                      "Arrived at Terra Harbor. Your ship and cargo remain where you left them."));
            } catch (RuntimeException error) {
              intent.putInt("remaining", 100);
              NullSpace.LOGGER.warn("Rescue arrival delayed for {}: {}", id, error.getMessage());
            }
          }
        });
  }

  private PlayerRescue() {}
}
