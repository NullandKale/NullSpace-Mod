package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.model.sound.SynthPatch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The server's side of sound: it decides who hears what ({@link Hearing}) and tells each client which cue to play, where and how muffled. One-shots are sent
 * once. Loops (an air hiss, the FTL drone, an alarm) are asked for again from their producers twice a second; each player is sent only what changed, so a loop
 * that a player walks away from, or that stops, fades out, and one whose loudness or muffling changes is updated in place.
 */
public final class ShipSounds
{
    /** How far a sound in the world carries, in blocks. */
    public static final double RANGE = 40;

    /**
     * A loop a producer wants playing now. {@code at} is its place in {@code level}, or null for a sound at the listener ({@code listeners} must then name who
     * hears it); {@code listeners} null means everyone in range who can hear it. {@code muffle} is the least it is muffled, whatever lies between (the sound
     * gallery's stands play a cue as if through a door or a wall).
     */
    public record Loop(String key, String cue, SynthPatch patch, ServerLevel level, Vec3 at, Collection<ServerPlayer> listeners, float volume, float pitch, float brightness, float rate, float muffle)
    {
        public Loop(String key, String cue, SynthPatch patch, ServerLevel level, Vec3 at, Collection<ServerPlayer> listeners, float volume, float pitch, float brightness, float rate)
        {
            this(key, cue, patch, level, at, listeners, volume, pitch, brightness, rate, 0);
        }

        public Loop(String key, String cue, ServerLevel level, Vec3 at, Collection<ServerPlayer> listeners, float volume)
        {
            this(key, cue, null, level, at, listeners, volume, 1, 0, 1);
        }
    }

    public interface Producer
    {
        void loops(MinecraftServer server, Consumer<Loop> out);
    }

    private record Sent(String cue, String patch, int volume, int pitch, int brightness, int rate, int muffle, int x, int y, int z)
    {}

    private static final List<Producer> PRODUCERS = new CopyOnWriteArrayList<>();
    private static final ServerContext.Key<Map<UUID, Map<String, Sent>>> SENT = new ServerContext.Key<>();

    public static void initialize()
    {
        SoundPackets.initialize();
        ServerContext.tick(ServerContext.Phase.SCENES, ShipSounds::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> sent(server).remove(handler.player.getUUID()));
    }

    /** Adds a source of loops, asked twice a second for the loops it wants playing. */
    public static void producer(Producer producer)
    {
        PRODUCERS.add(producer);
    }

    /**
     * Plays one of Minecraft's sounds once at a place, as the game plays it, quieter by {@code muffle} as Minecraft's sounds are heard through doors and walls
     * ({@link VanillaHearing}). The sound gallery uses it for the sounds the game borrows from Minecraft. Who it reaches is logged in {@link #recent}.
     */
    public static void recording(ServerLevel level, Vec3 at, net.minecraft.resources.Identifier sound, float volume, float pitch, float muffle)
    {
        float loudness = volume * (muffle > 0 ? com.nullandkale.nullspace.model.sound.SynthVoice.muffledGain(muffle) : 1);
        level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvent.createVariableRangeEvent(sound), net.minecraft.sounds.SoundSource.BLOCKS, loudness, pitch);
        // A sound carries 16 blocks for each unit of loudness above 1.
        double range = 16 * Math.max(1, loudness);
        for (var p : level.players())
            if (p.position().distanceTo(at) < range)
                log(level.getServer(), p.getPlainTextName() + " " + sound + " muffle " + Math.round(muffle * 100) / 100f);
    }

    /** Plays a cue once at a place, for everyone near enough who can hear it. */
    public static void play(ServerLevel level, Vec3 at, String cue)
    {
        play(level, at, cue, null, 1, 1);
    }

    /** Plays a cue, or a patch of its own, once at a place. */
    public static void play(ServerLevel level, Vec3 at, String cue, SynthPatch patch, float volume, float pitch)
    {
        play(level, at, cue, patch, volume, pitch, 0, 1, 0);
    }

    /** As {@link #play(ServerLevel, Vec3, String, SynthPatch, float, float)}, with the live controls, and muffled at least by {@code least}. */
    public static void play(ServerLevel level, Vec3 at, String cue, SynthPatch patch, float volume, float pitch, float brightness, float rate, float least)
    {
        long id = ThreadLocalRandom.current().nextLong();
        for (var p : level.players())
        {
            if (p.distanceToSqr(at) > RANGE * RANGE)
                continue;
            float muffle = Hearing.muffle(level, p.getEyePosition(), at);
            if (muffle != Hearing.SILENT)
                send(p, new SoundPackets.Play(id, cue, patch, false, at.x, at.y, at.z, volume, pitch, brightness, rate, Math.max(muffle, least)));
        }
    }

    /** Plays a cue once at the listener for everyone aboard a ship: the jolt into FTL. */
    public static void crew(MinecraftServer server, CompoundTag ship, String cue)
    {
        long id = ThreadLocalRandom.current().nextLong();
        String shipId = ship.getStringOr("id", "");
        for (var p : server.getPlayerList().getPlayers())
        {
            var aboard = SceneService.observer(p);
            if (aboard != null && aboard.getStringOr("id", "").equals(shipId))
                send(p, new SoundPackets.Play(id, cue, null, true, 0, 0, 0, 1, 1, 0, 1, 0));
        }
    }

    /**
     * Plays a cue once where something struck a hull, for everyone aboard it. The hull itself carries the blow, so it is heard even in vacuum: through the
     * rooms between, and at worst as through a wall.
     */
    public static void hull(ServerLevel level, CompoundTag ship, Vec3 at, String cue)
    {
        long id = ThreadLocalRandom.current().nextLong();
        String shipId = ship.getStringOr("id", "");
        var store = com.nullandkale.nullspace.core.save.WorldStore.get(level.getServer());
        float wall = Hearing.rooms(com.nullandkale.nullspace.gameplay.systems.RoomAcoustics.WALL);
        for (var p : level.players())
        {
            var aboard = store.at(level, net.minecraft.core.BlockPos.containing(p.getEyePosition()));
            if (aboard == null || !aboard.getStringOr("id", "").equals(shipId))
                continue;
            float muffle = Hearing.muffle(level, p.getEyePosition(), at);
            if (muffle == Hearing.SILENT || muffle > wall)
                muffle = wall;
            send(p, new SoundPackets.Play(id, cue, null, false, at.x, at.y, at.z, 1, 1, 0, 1, muffle));
        }
    }

    /** Plays a cue once at one player. */
    public static void player(ServerPlayer p, String cue)
    {
        send(p, new SoundPackets.Play(ThreadLocalRandom.current().nextLong(), cue, null, true, 0, 0, 0, 1, 1, 0, 1, 0));
    }

    private static void send(ServerPlayer p, SoundPackets.Play packet)
    {
        log(p.level().getServer(), p.getPlainTextName() + " " + (packet.patch() == null ? packet.cue() : "patch") + " muffle " + Math.round(packet.muffle() * 100) / 100f);
        if (ServerPlayNetworking.canSend(p, SoundPackets.Play.TYPE))
            ServerPlayNetworking.send(p, packet);
    }

    /** The last sounds the server decided to send, newest last ({@code player cue muffle m}), for diagnosing what someone heard. */
    private static final ServerContext.Key<java.util.ArrayDeque<String>> RECENT = new ServerContext.Key<>();

    private static void log(MinecraftServer server, String line)
    {
        var log = ServerContext.get(server).service(RECENT, java.util.ArrayDeque::new);
        synchronized (log)
        {
            log.addLast(line);
            while (log.size() > 64)
                log.removeFirst();
        }
    }

    public static List<String> recent(MinecraftServer server)
    {
        var log = ServerContext.get(server).service(RECENT, java.util.ArrayDeque::new);
        synchronized (log)
        {
            return List.copyOf(log);
        }
    }

    private static Map<UUID, Map<String, Sent>> sent(MinecraftServer server)
    {
        return ServerContext.get(server).service(SENT, HashMap::new);
    }

    /** The id a loop's key always has, so each update reaches the same playing sound. */
    public static long id(String key)
    {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
    }

    static void tick(MinecraftServer server)
    {
        if (server.getTickCount() % 10 != 0)
            return;
        var wanted = new ArrayList<Loop>();
        for (var producer : PRODUCERS)
            producer.loops(server, wanted::add);
        var state = sent(server);
        for (var p : server.getPlayerList().getPlayers())
        {
            var now = new HashMap<String, Sent>();
            var before = state.getOrDefault(p.getUUID(), Map.of());
            for (var loop : wanted)
            {
                if (loop.listeners() != null && !loop.listeners().contains(p))
                    continue;
                float muffle = loop.muffle();
                if (loop.at() != null)
                {
                    if (p.level() != loop.level() || p.distanceToSqr(loop.at()) > RANGE * RANGE)
                        continue;
                    float heard = Hearing.muffle(loop.level(), p.getEyePosition(), loop.at());
                    if (heard == Hearing.SILENT)
                        continue;
                    muffle = Math.max(muffle, heard);
                }
                var at = loop.at() == null ? Vec3.ZERO : loop.at();
                var sent = new Sent(loop.cue(), loop.patch() == null ? "" : loop.patch().text(), Math.round(loop.volume() * 20), Math.round(loop.pitch() * 50), Math.round(loop.brightness() * 20), Math.round(loop.rate() * 20), Math.round(muffle * 20), (int) Math.floor(at.x * 2), (int) Math.floor(at.y * 2), (int) Math.floor(at.z * 2));
                now.put(loop.key(), sent);
                if (!sent.equals(before.get(loop.key())))
                    send(p, new SoundPackets.Play(id(loop.key()), loop.cue(), loop.patch(), loop.at() == null, at.x, at.y, at.z, loop.volume(), loop.pitch(), loop.brightness(), loop.rate(), muffle));
            }
            for (var key : before.keySet())
                if (!now.containsKey(key) && ServerPlayNetworking.canSend(p, SoundPackets.Stop.TYPE))
                    ServerPlayNetworking.send(p, new SoundPackets.Stop(id(key)));
            if (now.isEmpty())
                state.remove(p.getUUID());
            else
                state.put(p.getUUID(), now);
        }
    }

    /** The loops a player is being sent now, by key, for tests. */
    public static java.util.Set<String> playing(ServerPlayer p)
    {
        return sent(p.level().getServer()).getOrDefault(p.getUUID(), Map.of()).keySet();
    }

    private ShipSounds()
    {
    }
}
