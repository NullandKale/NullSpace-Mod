package com.nullandkale.nullspace.render.sound;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.gameplay.sound.SoundPackets;
import com.nullandkale.nullspace.model.sound.SoundCues;
import com.nullandkale.nullspace.model.sound.SynthPatch;
import com.nullandkale.nullspace.model.sound.SynthVoice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * The client's synth voices: plays what the server sends, updates loops in place, fades out what it stops, and keeps at most {@link #VOICES} playing. When there
 * are more, the ones quietest where the player stands (their loudness and their distance) give way. A loop that gives way, or that found no voice free, waits
 * rather than being lost: the server sends a loop again only when it changes, so a forgotten loop would come back only when the player happened to move. Twice
 * a second the waiting loops are checked again, and one that has become clearly louder than a playing sound takes its voice. A loop whose stream ran dry in a
 * client hitch is started again on the same voice. The cue file reloads with the resources (F3+T), so a cue can be tuned without restarting.
 */
public final class ClientSounds
{
    public static final int VOICES = 8;

    private static final class Layered
    {
        final List<SynthSound> sounds = new ArrayList<>();
        final List<Integer> started = new ArrayList<>();
        final boolean relative;
        final SoundSource source;
        float loudness;
        int restarts;
        /** The latest the server sent for it, to wait with if it gives way. */
        SoundPackets.Play last;

        Layered(boolean relative, SoundSource source)
        {
            this.relative = relative;
            this.source = source;
        }
    }

    private static final Map<Long, Layered> PLAYING = new HashMap<>();
    /** Loops the server is playing that have no voice now, with the latest it sent for each. */
    private static final Map<Long, SoundPackets.Play> WAITING = new HashMap<>();
    /** How much louder a waiting loop must be than a playing sound to take its voice, so two sounds of about the same loudness do not trade places. */
    private static final float TAKE_OVER = 1.5f;
    /** How far a sound in the world carries, in blocks: its loudness falls to nothing there (the sound event's attenuation distance). */
    private static final double RANGE = 40;
    /** The latest sound started under each name (a cue, or "patch" for a Synth's own), kept after it ends, for tests. */
    private static final Map<String, Layered> LATEST = new HashMap<>();
    private static int ticks;

    /**
     * How long the latest sound started under a name (a cue, or {@code patch} for a Synth's own patch) has played, in seconds, 0 if none: a voice's clock moves
     * only while the sound engine reads its samples, so tests can tell sound is really made.
     */
    public static double heard(String name)
    {
        var layered = LATEST.get(name);
        return layered == null ? 0 : layered.sounds.stream().mapToDouble(s -> s.voice.time()).max().orElse(0);
    }

    /** The latest sound started under a name, as an opaque token, or null: a test compares tokens to tell that a new one started. */
    public static Object latest(String name)
    {
        return LATEST.get(name);
    }

    /**
     * How loud the latest sound under a name is where the player stands now (its loudness, falling with distance), 0 if none: a test tells by it that a sound
     * plays near the player, not somewhere they have since left.
     */
    public static double heardHere(String name)
    {
        var layered = LATEST.get(name);
        return layered == null ? 0 : heardAt(layered);
    }

    public static void initialize()
    {
        ClientPlayNetworking.registerGlobalReceiver(SoundPackets.Play.TYPE, (packet, context) -> context.client().execute(() -> play(packet)));
        ClientPlayNetworking.registerGlobalReceiver(SoundPackets.Stop.TYPE, (packet, context) -> context.client().execute(() -> stop(packet.id())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientSounds::clear));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES).registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener()
        {
            @Override
            public Identifier getFabricId()
            {
                return Identifier.parse("nullspace:synth_cues");
            }

            @Override
            public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager)
            {
                manager.getResource(Identifier.parse("nullspace:synth/cues.json")).ifPresent(resource ->
                {
                    try (var in = resource.open())
                    {
                        SoundCues.replace(SoundCues.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                    }
                    catch (Exception unreadable)
                    {
                        NullSpace.LOGGER.warn("Could not read the synth cues; keeping the ones loaded before", unreadable);
                    }
                });
            }
        });
    }

    /** How many voices are playing now, for tests. */
    public static int playing()
    {
        return PLAYING.values().stream().mapToInt(l -> (int) l.sounds.stream().filter(s -> !s.voice.finished()).count()).sum();
    }

    /** The cues playing now, for tests: true when some voice of {@code id} is still sounding. */
    public static boolean playing(long id)
    {
        var layered = PLAYING.get(id);
        return layered != null && layered.sounds.stream().anyMatch(s -> !s.voice.finished());
    }

    /** The cues started most recently, newest last, for tests. */
    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();

    public static List<String> recent()
    {
        return List.copyOf(RECENT);
    }

    static void play(SoundPackets.Play p)
    {
        float loudness = loudness(p.level(), p.muffle());
        var existing = PLAYING.get(p.id());
        if (existing != null)
        {
            existing.loudness = loudness;
            existing.last = p;
            for (var sound : existing.sounds)
            {
                if (p.patch() != null)
                    sound.voice.patch(p.patch());
                sound.voice.controls(p.pitch(), p.brightness(), p.rate(), p.muffle());
                sound.loudness(loudness);
                if (!existing.relative)
                    sound.place(p.x(), p.y(), p.z());
            }
            return;
        }
        if (WAITING.containsKey(p.id()))
        {
            WAITING.put(p.id(), p);
            return;
        }
        if (!start(p) && loops(p))
            WAITING.put(p.id(), p);
    }

    private static boolean loops(SoundPackets.Play p)
    {
        return p.patch() != null ? p.patch().loop() : SoundCues.get(p.cue()).stream().anyMatch(layer -> layer.patch().loop());
    }

    /** How loud a sound is where the player stands: its loudness, falling to nothing at the edge of its range as Minecraft's own falloff does. */
    private static float heardAt(boolean relative, float loudness, double x, double y, double z)
    {
        var player = Minecraft.getInstance().player;
        if (relative || player == null)
            return loudness;
        return (float) (loudness * Math.max(0, 1 - player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(x, y, z)) / RANGE));
    }

    private static float heardAt(Layered layered)
    {
        var first = layered.sounds.getFirst();
        return heardAt(layered.relative, layered.loudness, first.getX(), first.getY(), first.getZ());
    }

    private static float heardAt(SoundPackets.Play p)
    {
        return heardAt(p.relative(), loudness(p.level(), p.muffle()), p.x(), p.y(), p.z());
    }

    private static float loudness(float level, float muffle)
    {
        return Math.max(0, Math.min(1, level * SynthVoice.muffledGain(muffle)));
    }

    /** Starts what the server sent, making room for it; false when there is no room for something this quiet. */
    private static boolean start(SoundPackets.Play p)
    {
        long id = p.id();
        String cue = p.cue();
        SynthPatch patch = p.patch();
        boolean relative = p.relative();
        var source = relative ? SoundSource.AMBIENT : SoundSource.BLOCKS;
        double x = p.x(), y = p.y(), z = p.z();
        float pitch = p.pitch(), brightness = p.brightness(), rate = p.rate(), muffle = p.muffle();
        float loudness = loudness(p.level(), muffle);
        var layers = patch != null ? List.of(new SoundCues.Layer(0, patch)) : SoundCues.get(cue);
        if (layers.isEmpty() || !makeRoom(layers.size(), heardAt(p)))
            return false;
        RECENT.addLast(patch != null ? "patch" : cue);
        while (RECENT.size() > 32)
            RECENT.removeFirst();
        var layered = new Layered(relative, source);
        layered.loudness = loudness;
        layered.last = p;
        for (var layer : layers)
        {
            var voice = new SynthVoice(layer.patch(), ThreadLocalRandom.current().nextLong());
            voice.controls(pitch, brightness, rate, muffle);
            var sound = new SynthSound(voice, layered.source, relative, x, y, z, loudness, Math.round(layer.delay() * 20));
            layered.sounds.add(sound);
            layered.started.add(ticks + Math.round(layer.delay() * 20));
            Minecraft.getInstance().getSoundManager().play(sound);
        }
        PLAYING.put(id, layered);
        LATEST.put(patch != null ? "patch" : cue, layered);
        return true;
    }

    /**
     * Frees voices for {@code count} more heard at {@code heard} where the player stands: the quietest playing sounds give way, unless the new one is quieter than
     * all of them. A loop that gives way waits for a voice.
     */
    private static boolean makeRoom(int count, float heard)
    {
        while (playing() + count > VOICES)
        {
            var quietest = PLAYING.entrySet().stream().filter(e -> e.getValue().sounds.stream().anyMatch(s -> !s.voice.finished())).min((a, b) -> Float.compare(heardAt(a.getValue()), heardAt(b.getValue())));
            if (quietest.isEmpty() || heardAt(quietest.get().getValue()) > heard)
                return false;
            var given = quietest.get().getValue();
            end(given);
            PLAYING.remove(quietest.get().getKey());
            if (given.last != null && loops(given.last))
                WAITING.put(quietest.get().getKey(), given.last);
        }
        return true;
    }

    /** Gives a voice to each waiting loop that has room, or that is now clearly louder where the player stands than a playing sound, loudest first. */
    private static void admit()
    {
        var order = WAITING.values().stream().sorted((a, b) -> Float.compare(heardAt(b), heardAt(a))).toList();
        for (var p : order)
        {
            float heard = heardAt(p);
            if (heard <= 0)
                continue;
            int voices = p.patch() != null ? 1 : SoundCues.get(p.cue()).size();
            if (playing() + voices > VOICES)
            {
                var quietest = PLAYING.values().stream().filter(l -> l.sounds.stream().anyMatch(s -> !s.voice.finished())).mapToDouble(ClientSounds::heardAt).min().orElse(Double.MAX_VALUE);
                if (heard < quietest * TAKE_OVER)
                    continue;
            }
            WAITING.remove(p.id());
            if (!start(p))
                WAITING.put(p.id(), p);
        }
    }

    static void stop(long id)
    {
        WAITING.remove(id);
        var layered = PLAYING.get(id);
        if (layered != null)
            layered.sounds.forEach(s -> s.voice.release());
    }

    private static void end(Layered layered)
    {
        var manager = Minecraft.getInstance().getSoundManager();
        for (var sound : layered.sounds)
        {
            sound.voice.release();
            manager.stop(sound);
        }
    }

    static void tick()
    {
        ticks++;
        if (ticks % 10 == 0 && !WAITING.isEmpty() && Minecraft.getInstance().level != null)
            admit();
        var manager = Minecraft.getInstance().getSoundManager();
        for (var it = PLAYING.entrySet().iterator(); it.hasNext();)
        {
            var layered = it.next().getValue();
            if (layered.sounds.stream().allMatch(s -> s.voice.finished()))
            {
                it.remove();
                continue;
            }
            // A stream that ran dry in a hitch ends its sound; the voice is still playing, so start it again (a few times at most).
            for (int i = 0; i < layered.sounds.size(); i++)
            {
                var sound = layered.sounds.get(i);
                if (sound.voice.finished() || ticks < layered.started.get(i) + 10 || manager.isActive(sound) || layered.restarts >= 5)
                    continue;
                layered.restarts++;
                var again = new SynthSound(sound.voice, layered.source, layered.relative, sound.getX(), sound.getY(), sound.getZ(), layered.loudness, 0);
                layered.sounds.set(i, again);
                layered.started.set(i, ticks);
                manager.play(again);
            }
        }
    }

    static void clear()
    {
        PLAYING.values().forEach(ClientSounds::end);
        PLAYING.clear();
        WAITING.clear();
        RECENT.clear();
        LATEST.clear();
    }

    private ClientSounds()
    {
    }
}
