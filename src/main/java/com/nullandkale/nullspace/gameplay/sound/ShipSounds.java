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
     * hears it); {@code listeners} null means everyone in range who can hear it.
     */
    public record Loop(String key, String cue, SynthPatch patch, ServerLevel level, Vec3 at, Collection<ServerPlayer> listeners, float volume, float pitch, float brightness, float rate)
    {
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

    /** Plays a cue once at a place, for everyone near enough who can hear it. */
    public static void play(ServerLevel level, Vec3 at, String cue)
    {
        play(level, at, cue, null, 1, 1);
    }

    /** Plays a cue, or a patch of its own, once at a place. */
    public static void play(ServerLevel level, Vec3 at, String cue, SynthPatch patch, float volume, float pitch)
    {
        long id = ThreadLocalRandom.current().nextLong();
        for (var p : level.players())
        {
            if (p.distanceToSqr(at) > RANGE * RANGE)
                continue;
            float muffle = Hearing.muffle(level, p.getEyePosition(), at);
            if (muffle != Hearing.SILENT)
                send(p, new SoundPackets.Play(id, cue, patch, false, at.x, at.y, at.z, volume, pitch, 0, 1, muffle));
        }
    }

    /** Plays a cue once at the listener for everyone aboard a ship: the jolt into FTL, the arrival chime. */
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

    /** Plays a cue once at one player. */
    public static void player(ServerPlayer p, String cue)
    {
        send(p, new SoundPackets.Play(ThreadLocalRandom.current().nextLong(), cue, null, true, 0, 0, 0, 1, 1, 0, 1, 0));
    }

    private static void send(ServerPlayer p, SoundPackets.Play packet)
    {
        var log = ServerContext.get(p.level().getServer()).service(RECENT, java.util.ArrayDeque::new);
        synchronized (log)
        {
            log.addLast(p.getPlainTextName() + " " + (packet.patch() == null ? packet.cue() : "patch") + " muffle " + Math.round(packet.muffle() * 100) / 100f);
            while (log.size() > 64)
                log.removeFirst();
        }
        if (ServerPlayNetworking.canSend(p, SoundPackets.Play.TYPE))
            ServerPlayNetworking.send(p, packet);
    }

    /** The last sounds the server decided to send, newest last ({@code player cue muffle m}), for diagnosing what someone heard. */
    private static final ServerContext.Key<java.util.ArrayDeque<String>> RECENT = new ServerContext.Key<>();

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
                float muffle = 0;
                if (loop.at() != null)
                {
                    if (p.level() != loop.level() || p.distanceToSqr(loop.at()) > RANGE * RANGE)
                        continue;
                    muffle = Hearing.muffle(loop.level(), p.getEyePosition(), loop.at());
                    if (muffle == Hearing.SILENT)
                        continue;
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
