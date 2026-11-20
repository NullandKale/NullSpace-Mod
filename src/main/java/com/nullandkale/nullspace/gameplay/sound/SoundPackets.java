package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.model.sound.SynthPatch;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** What the server tells a client to play: a cue by name (or a patch of its own, for the Synth block), where, how loud and how muffled. */
public final class SoundPackets
{
    /**
     * Starts a sound, or, for an id already playing, updates it: a loop keeps its id while its loudness, pitch, position or muffling change, and a new patch
     * replaces the old one in place. {@code patch} is null for a named cue. {@code relative} sounds play at the listener, not in the world.
     */
    public record Play(long id, String cue, SynthPatch patch, boolean relative, double x, double y, double z, float level, float pitch, float brightness, float rate, float muffle) implements CustomPacketPayload
    {

        public static final Type<Play> TYPE = new Type<>(Identifier.parse("nullspace:sound_play"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Play> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeLong(p.id);
            b.writeUtf(p.cue, 64);
            b.writeBoolean(p.patch != null);
            if (p.patch != null)
                p.patch.write(b);
            b.writeBoolean(p.relative);
            b.writeDouble(p.x);
            b.writeDouble(p.y);
            b.writeDouble(p.z);
            for (float value : new float[]{p.level, p.pitch, p.brightness, p.rate, p.muffle})
                b.writeFloat(value);
        }, b -> new Play(b.readLong(), b.readUtf(64), b.readBoolean() ? SynthPatch.read(b) : null, b.readBoolean(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat()));

        @Override
        public Type<Play> type()
        {
            return TYPE;
        }
    }

    /** Fades a playing sound out over its release. */
    public record Stop(long id) implements CustomPacketPayload
    {
        public static final Type<Stop> TYPE = new Type<>(Identifier.parse("nullspace:sound_stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC = StreamCodec.of((b, p) -> b.writeLong(p.id), b -> new Stop(b.readLong()));

        @Override
        public Type<Stop> type()
        {
            return TYPE;
        }
    }

    public static void initialize()
    {
        PayloadTypeRegistry.clientboundPlay().register(Play.TYPE, Play.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Stop.TYPE, Stop.CODEC);
    }

    private SoundPackets()
    {
    }
}
