package com.nullandkale.nullspace.gameplay.start;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Covers a new player's screen while their start is built. A new player logs in at the world's spawn like anyone else, and the ship is built and the
 * player moved into it a tick or more later; without a cover they saw the ordinary spawn first and were then pulled away, which was confusing (0.9.0).
 */
public final class StartPackets
{
    /** {@code done} false: show or update the cover with {@code message}. True: the player stands where they start; reveal once it is drawn. */
    public record Preparing(boolean done, String message) implements CustomPacketPayload
    {
        public static final Type<Preparing> TYPE = new Type<>(Identifier.parse("nullspace:start_preparing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Preparing> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeBoolean(p.done);
            b.writeUtf(p.message, 256);
        }, b -> new Preparing(b.readBoolean(), b.readUtf(256)));

        @Override
        public Type<Preparing> type()
        {
            return TYPE;
        }
    }

    static void register()
    {
        PayloadTypeRegistry.clientboundPlay().register(Preparing.TYPE, Preparing.CODEC);
    }

    static boolean canSend(ServerPlayer player)
    {
        return ServerPlayNetworking.canSend(player, Preparing.TYPE);
    }

    static void send(ServerPlayer player, boolean done, String message)
    {
        if (canSend(player))
            ServerPlayNetworking.send(player, new Preparing(done, message));
    }

    private StartPackets()
    {
    }
}
