package com.nullandkale.nullspace.gameplay.scene;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

/** What a scene view sends between server and client: definitions, frames and keep-alives. */
public final class ScenePackets {

  public record Definition(CompoundTag data) implements CustomPacketPayload {
    public static final Type<Definition> TYPE =
        new Type<>(Identifier.parse("nullspace:space_definition"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Definition> CODEC =
        StreamCodec.of((b, p) -> b.writeNbt(p.data), b -> new Definition(b.readNbt()));

    @Override
    public Type<Definition> type() {
      return TYPE;
    }
  }

  public record Frame(UUID lease, int revision, CompoundTag scene) implements CustomPacketPayload {
    public static final Type<Frame> TYPE = new Type<>(Identifier.parse("nullspace:scene_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Frame> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.lease);
              b.writeInt(p.revision);
              b.writeNbt(p.scene);
            },
            b -> {
              var lease = b.readUUID();
              int revision = b.readInt();
              if (!(b.readNbt(NbtAccounter.create(16L * 1024 * 1024)) instanceof CompoundTag scene))
                throw new IllegalArgumentException("Invalid scene frame");
              return new Frame(lease, revision, scene);
            });

    @Override
    public Type<Frame> type() {
      return TYPE;
    }
  }

  public record KeepAlive(UUID lease, boolean close, int selection) implements CustomPacketPayload {
    public static final Type<KeepAlive> TYPE =
        new Type<>(Identifier.parse("nullspace:scene_lease"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KeepAlive> CODEC =
        StreamCodec.of(
            (b, p) -> {
              b.writeUUID(p.lease);
              b.writeBoolean(p.close);
              b.writeVarInt(p.selection);
            },
            b -> new KeepAlive(b.readUUID(), b.readBoolean(), b.readVarInt()));

    @Override
    public Type<KeepAlive> type() {
      return TYPE;
    }
  }

  private ScenePackets() {}
}
