package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.model.sound.SynthVoice;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's own sounds (blocks, mobs, doors, explosions) follow the {@link Hearing} rules as far as its sound engine allows: a sound that is not heard is not
 * sent, and a muffled one is sent quieter. Only an explosion can also be made darker, by a muffled recording of it ({@code nullspace:muffled_explosion}); an
 * unheard explosion still throws its particles and its push. Sounds a client makes for itself (the player's own steps and blocks, a furnace's crackle) never
 * pass through the server and are left alone. The server's sound broadcast and explosion packets are routed here by mixins.
 */
public final class VanillaHearing
{
    /** An explosion heard through a hull or a door: the same recording, quieter and lower. */
    public static final Holder<SoundEvent> MUFFLED_EXPLOSION = Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse("nullspace:muffled_explosion")));

    /** Whether a packet broadcast around a place is a sound this class decides on. */
    public static boolean applies(Packet<?> packet)
    {
        return packet instanceof ClientboundSoundPacket || packet instanceof ClientboundSoundEntityPacket;
    }

    /**
     * Sends a broadcast sound to the players near it, each as they hear it; true when it was handled here (the caller sends nothing more). It keeps the
     * broadcast's own rule for who is near: the same world, inside the radius, not the player who made the sound.
     */
    public static boolean broadcast(Iterable<ServerPlayer> players, ServerLevel level, Player except, double x, double y, double z, double radius, Packet<?> packet)
    {
        if (level == null || !applies(packet))
            return false;
        for (var p : players)
        {
            if (p == except || p.level() != level)
                continue;
            double dx = x - p.getX(), dy = y - p.getY(), dz = z - p.getZ();
            if (dx * dx + dy * dy + dz * dz >= radius * radius)
                continue;
            var heard = heard(level, p, packet);
            if (heard != null)
                p.connection.send(heard);
        }
        return true;
    }

    /** The packet one listener gets for a sound: unchanged when clear, quieter when muffled, or null when not heard. Other packets pass unchanged. */
    public static Packet<?> heard(ServerLevel level, ServerPlayer listener, Packet<?> packet)
    {
        if (packet instanceof ClientboundSoundPacket sound)
        {
            float muffle = Hearing.muffle(level, listener.getEyePosition(), new Vec3(sound.getX(), sound.getY(), sound.getZ()), false);
            if (muffle == 0)
                return sound;
            if (muffle == Hearing.SILENT)
                return null;
            return new ClientboundSoundPacket(sound.getSound(), sound.getSource(), sound.getX(), sound.getY(), sound.getZ(), sound.getVolume() * SynthVoice.muffledGain(muffle), sound.getPitch(), sound.getSeed());
        }
        if (packet instanceof ClientboundSoundEntityPacket sound)
        {
            var entity = level.getEntity(sound.getId());
            if (entity == null)
                return sound;
            float muffle = Hearing.muffle(level, listener.getEyePosition(), entity.position(), false);
            if (muffle == 0)
                return sound;
            if (muffle == Hearing.SILENT)
                return null;
            return new ClientboundSoundEntityPacket(sound.getSound(), sound.getSource(), entity, sound.getVolume() * SynthVoice.muffledGain(muffle), sound.getPitch(), sound.getSeed());
        }
        return packet;
    }

    /** The explosion packet one listener gets: its sound unchanged, muffled, or left out; its particles and push always arrive. */
    public static Packet<?> explosion(ServerLevel level, ServerPlayer listener, Packet<?> packet)
    {
        if (!(packet instanceof ClientboundExplodePacket blast) || !blast.playSound())
            return packet;
        float muffle = Hearing.muffle(level, listener.getEyePosition(), blast.center(), false);
        if (muffle == 0)
            return blast;
        return new ClientboundExplodePacket(blast.center(), blast.radius(), blast.blockCount(), blast.playerKnockback(), blast.explosionParticle(), muffle == Hearing.SILENT ? blast.explosionSound() : MUFFLED_EXPLOSION, blast.blockParticles(), muffle != Hearing.SILENT);
    }

    private VanillaHearing()
    {
    }
}
