package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /nullspace debug holding}: an operator sees the space dimension as it really is. Every ship and station is a block area there, 4,096 blocks apart
 * along X, and the client normally hides everything outside the player's own ship and draws the sky and other ships as a picture over it. This turns the
 * operator into a spectator, takes them to space if they are elsewhere, and tells their client to show every loaded block and entity with no sky. Running it
 * again puts them back where they were, in their old game mode.
 */
public final class HoldingView
{
    /** Tells the client to show the holding dimension's real blocks (true) or the normal view (false). */
    public record Raw(boolean on) implements CustomPacketPayload
    {
        public static final Type<Raw> TYPE = new Type<>(Identifier.parse("nullspace:holding_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Raw> CODEC = StreamCodec.of((b, p) -> b.writeBoolean(p.on), b -> new Raw(b.readBoolean()));

        @Override
        public Type<Raw> type()
        {
            return TYPE;
        }
    }

    /** Where an operator was before looking, to go back to. */
    private record Saved(GameType mode, ResourceKey<Level> dimension, Vec3 at, float yaw, float pitch)
    {}

    private static final ServerContext.Key<Map<UUID, Saved>> SAVED = new ServerContext.Key<>();

    public static void initialize()
    {
        PayloadTypeRegistry.clientboundPlay().register(Raw.TYPE, Raw.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerContext.get(server).service(SAVED, HashMap::new).remove(handler.player.getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(Commands.literal("nullspace").then(Commands.literal("debug").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.literal("holding").executes(c ->
        {
            var player = c.getSource().getPlayerOrException();
            c.getSource().sendSuccess(() -> Component.literal(toggle(player)), false);
            return 1;
        })))));
    }

    /** Starts or ends the view for {@code player}, and says what happened. */
    public static String toggle(ServerPlayer player)
    {
        var server = player.level().getServer();
        var saved = ServerContext.get(server).service(SAVED, HashMap::new);
        var before = saved.remove(player.getUUID());
        if (before != null)
        {
            if (ServerPlayNetworking.canSend(player, Raw.TYPE))
                ServerPlayNetworking.send(player, new Raw(false));
            var level = server.getLevel(before.dimension());
            if (level != null)
                player.teleport(new TeleportTransition(level, before.at(), Vec3.ZERO, before.yaw(), before.pitch(), TeleportTransition.DO_NOTHING));
            player.setGameMode(before.mode());
            return "Holding view off: back where you were.";
        }
        var space = server.getLevel(TravelService.SPACE);
        if (space == null)
            return "The space dimension is not loaded.";
        saved.put(player.getUUID(), new Saved(player.gameMode.getGameModeForPlayer(), player.level().dimension(), player.position(), player.getYRot(), player.getXRot()));
        player.setGameMode(GameType.SPECTATOR);
        if (!player.level().dimension().equals(TravelService.SPACE))
        {
            // Above the first ship or station in space, looking down at it; the first slot if there is none.
            var at = new Vec3(16, 140, 16);
            for (var tag : WorldStore.get(server).ships())
            {
                var ship = (CompoundTag) tag;
                if (ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()))
                {
                    var base = WorldStore.pos(ship, "base");
                    at = new Vec3(base.getX() + ship.getIntOr("width", 32) / 2.0, base.getY() + 48, base.getZ() + ship.getIntOr("depth", 32) / 2.0);
                    break;
                }
            }
            player.teleport(new TeleportTransition(space, at, Vec3.ZERO, 0, 60, TeleportTransition.DO_NOTHING));
        }
        if (ServerPlayNetworking.canSend(player, Raw.TYPE))
            ServerPlayNetworking.send(player, new Raw(true));
        return "Holding view on: you are a spectator seeing the space dimension's real blocks, with no sky or ship pictures. Ship areas are 4096 blocks apart along X, at Y 96. Run /nullspace debug holding again to go back.";
    }

    private HoldingView()
    {
    }
}
