package com.nullandkale.nullspace.render.sound;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.gameplay.sound.SoundPackets;
import com.nullandkale.nullspace.model.sound.SoundCues;
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
 * The client's synth voices: plays what the server sends, updates loops in place, fades out what it stops, and keeps at most {@link #VOICES} playing (the
 * quietest give way). A loop whose stream ran dry in a client hitch is started again on the same voice. The cue file reloads with the resources (F3+T), so a
 * cue can be tuned without restarting.
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

        Layered(boolean relative, SoundSource source)
        {
            this.relative = relative;
            this.source = source;
        }
    }

    private static final Map<Long, Layered> PLAYING = new HashMap<>();
    private static int ticks;
    private static double longest;

    /** The longest any voice has played, in seconds: it grows only while the sound engine reads the voice's samples, so tests can tell sound is really made. */
    public static double longest()
    {
        return longest;
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

    static void play(SoundPackets.Play p)
    {
        float loudness = Math.max(0, Math.min(1, p.level() * SynthVoice.muffledGain(p.muffle())));
        var existing = PLAYING.get(p.id());
        if (existing != null)
        {
            existing.loudness = loudness;
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
        var layers = p.patch() != null ? List.of(new SoundCues.Layer(0, p.patch())) : SoundCues.get(p.cue());
        if (layers.isEmpty() || !makeRoom(layers.size(), loudness))
            return;
        var layered = new Layered(p.relative(), p.relative() ? SoundSource.AMBIENT : SoundSource.BLOCKS);
        layered.loudness = loudness;
        for (var layer : layers)
        {
            var voice = new SynthVoice(layer.patch(), ThreadLocalRandom.current().nextLong());
            voice.controls(p.pitch(), p.brightness(), p.rate(), p.muffle());
            var sound = new SynthSound(voice, layered.source, p.relative(), p.x(), p.y(), p.z(), loudness, Math.round(layer.delay() * 20));
            layered.sounds.add(sound);
            layered.started.add(ticks + Math.round(layer.delay() * 20));
            Minecraft.getInstance().getSoundManager().play(sound);
        }
        PLAYING.put(p.id(), layered);
    }

    /** Frees voices for {@code count} more of loudness {@code loudness}: the quietest playing sounds give way, unless the new one is quieter than all of them. */
    private static boolean makeRoom(int count, float loudness)
    {
        while (playing() + count > VOICES)
        {
            var quietest = PLAYING.entrySet().stream().filter(e -> e.getValue().sounds.stream().anyMatch(s -> !s.voice.finished())).min((a, b) -> Float.compare(a.getValue().loudness, b.getValue().loudness));
            if (quietest.isEmpty() || quietest.get().getValue().loudness > loudness)
                return false;
            end(quietest.get().getValue());
            PLAYING.remove(quietest.get().getKey());
        }
        return true;
    }

    static void stop(long id)
    {
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
        var manager = Minecraft.getInstance().getSoundManager();
        for (var it = PLAYING.entrySet().iterator(); it.hasNext();)
        {
            var layered = it.next().getValue();
            for (var sound : layered.sounds)
                longest = Math.max(longest, sound.voice.time());
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
    }

    private ClientSounds()
    {
    }
}
