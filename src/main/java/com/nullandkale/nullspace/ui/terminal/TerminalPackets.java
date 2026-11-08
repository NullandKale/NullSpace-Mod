package com.nullandkale.nullspace.ui.terminal;

import com.nullandkale.nullspace.ui.terminal.ComputerTerminal.Action;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What terminal screens send and receive: opening, updates, requests, acknowledgements and closing.
 */
public final class TerminalPackets
{

    public record Destination(String id, String name, String state, String detail)
    {
        public Destination(String id, String name, String state)
        {
            this(id, name, state, "");
        }
    }

    /** Session-scoped live data can never open a closed terminal. */
    public record Refresh(Open view) implements CustomPacketPayload
    {
        public static final Type<Refresh> TYPE = new Type<>(Identifier.parse("nullspace:terminal_refresh"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Refresh> CODEC = Open.CODEC.map(Refresh::new, Refresh::view);

        public Type<Refresh> type()
        {
            return TYPE;
        }
    }

    public record Ack(UUID session) implements CustomPacketPayload
    {
        public static final Type<Ack> TYPE = new Type<>(Identifier.parse("nullspace:terminal_ack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ack> CODEC = StreamCodec.of((b, p) -> b.writeUUID(p.session), b -> new Ack(b.readUUID()));

        public Type<Ack> type()
        {
            return TYPE;
        }
    }

    /** Readings never open a screen: a late update for a closed session is discarded. */
    public record Update(UUID session, String message, int revision) implements CustomPacketPayload
    {
        public Update
        {
            if (message.length() > 2048)
                message = message.substring(0, 2048);
        }

        public static final Type<Update> TYPE = new Type<>(Identifier.parse("nullspace:terminal_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUUID(p.session);
            b.writeUtf(p.message, 2048);
            b.writeInt(p.revision);
        }, b -> new Update(b.readUUID(), b.readUtf(2048), b.readInt()));

        @Override
        public Type<Update> type()
        {
            return TYPE;
        }
    }

    public record Open(BlockPos position, String message, String mode, String value, UUID session, List<Destination> destinations) implements CustomPacketPayload
    {
        public Open(BlockPos p, String message)
        {
            this(p, message, "pad", "");
        }

        public Open(BlockPos p, String message, String mode)
        {
            this(p, message, mode, "");
        }

        public Open(BlockPos p, String message, String mode, String value)
        {
            this(p, message, mode, value, ComputerTerminal.NONE, List.of());
        }

        public static final Type<Open> TYPE = new Type<>(Identifier.parse("nullspace:terminal"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeBlockPos(p.position);
            b.writeUtf(p.message, 2048);
            b.writeUtf(p.mode, 32);
            b.writeUtf(p.value, 32);
            b.writeUUID(p.session);
            b.writeVarInt(p.destinations.size());
            for (var d : p.destinations)
            {
                b.writeUtf(d.id, 36);
                b.writeUtf(d.name, 160);
                b.writeUtf(d.state, 16);
                b.writeUtf(d.detail, 256);
            }
        }, b ->
        {
            var pos = b.readBlockPos();
            var message = b.readUtf(2048);
            var mode = b.readUtf(32);
            var value = b.readUtf(32);
            var id = b.readUUID();
            int count = b.readVarInt();
            if (count < 0 || count > 256)
                throw new IllegalArgumentException("Invalid terminal catalog size");
            var destinations = new ArrayList<Destination>();
            for (int i = 0; i < count; i++)
                destinations.add(new Destination(b.readUtf(36), b.readUtf(160), b.readUtf(16), b.readUtf(256)));
            return new Open(pos, message, mode, value, id, List.copyOf(destinations));
        });

        @Override
        public Type<Open> type()
        {
            return TYPE;
        }
    }

    public record Request(UUID session, Action action, String argument, String destinationId, int revision) implements CustomPacketPayload
    {
        public Request(UUID session, Action action, String argument, String destinationId)
        {
            this(session, action, argument, destinationId, -1);
        }

        public static final Type<Request> TYPE = new Type<>(Identifier.parse("nullspace:terminal_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUUID(p.session);
            b.writeEnum(p.action);
            b.writeUtf(p.argument, 32);
            b.writeUtf(p.destinationId, 36);
            b.writeInt(p.revision);
        }, b -> new Request(b.readUUID(), b.readEnum(Action.class), b.readUtf(32), b.readUtf(36), b.readInt()));

        @Override
        public Type<Request> type()
        {
            return TYPE;
        }
    }

    public record Close(UUID session) implements CustomPacketPayload
    {
        public static final Type<Close> TYPE = new Type<>(Identifier.parse("nullspace:terminal_close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC = StreamCodec.of((b, p) -> b.writeUUID(p.session), b -> new Close(b.readUUID()));

        @Override
        public Type<Close> type()
        {
            return TYPE;
        }
    }

    public record Rejected(UUID session, String reason) implements CustomPacketPayload
    {
        public static final Type<Rejected> TYPE = new Type<>(Identifier.parse("nullspace:terminal_rejected"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Rejected> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUUID(p.session);
            b.writeUtf(p.reason, 256);
        }, b -> new Rejected(b.readUUID(), b.readUtf(256)));

        @Override
        public Type<Rejected> type()
        {
            return TYPE;
        }
    }

    public record Authorized(BlockPos position, String mode, String argument)
    {}

    private TerminalPackets()
    {
    }
}
