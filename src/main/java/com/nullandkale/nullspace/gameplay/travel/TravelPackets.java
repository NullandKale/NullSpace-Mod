package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.gameplay.docking.Boarding;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class TravelPackets {
  public enum Kind {
    TRANSFER,
    JUMP,
    BOARD
  }

  public record Progress(UUID id, String message, String dimension, boolean done, Kind kind)
      implements CustomPacketPayload {
    public Progress(UUID id, String message, String dimension, boolean done) {
      this(id, message, dimension, done, Kind.TRANSFER);
    }

    public static final Type<Progress> TYPE = new Type<>(Identifier.parse("nullspace:travel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Progress> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.id);
              b.writeUtf(p.message, 256);
              b.writeUtf(p.dimension, 256);
              b.writeBoolean(p.done);
              b.writeEnum(p.kind);
            },
            b ->
                new Progress(
                    b.readUUID(),
                    b.readUtf(256),
                    b.readUtf(256),
                    b.readBoolean(),
                    b.readEnum(Kind.class)));

    @Override
    public Type<Progress> type() {
      return TYPE;
    }
  }

  public record Ready(UUID id, boolean arrived) implements CustomPacketPayload {
    public static final Type<Ready> TYPE = new Type<>(Identifier.parse("nullspace:travel_ready"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Ready> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.id);
              b.writeBoolean(p.arrived);
            },
            b -> new Ready(b.readUUID(), b.readBoolean()));

    @Override
    public Type<Ready> type() {
      return TYPE;
    }
  }

  public static void initialize() {
    PayloadTypeRegistry.clientboundPlay().register(Progress.TYPE, Progress.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(Ready.TYPE, Ready.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Ready.TYPE,
        (packet, context) ->
            context
                .server()
                .execute(
                    () -> {
                      if (Boarding.ready(context.player(), packet)) return;
                      if (!NavigationService.ready(context.player(), packet))
                        TravelService.ready(context.player(), packet);
                    }));
  }

  private TravelPackets() {}
}
