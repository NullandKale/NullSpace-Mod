package com.nullandkale.nullspace.render.sound;

import com.nullandkale.nullspace.model.sound.SynthVoice;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * One playing synth voice as a Minecraft sound: the sound event {@code nullspace:synth} points at Fabric's empty stream, and this hands the engine the voice's
 * own stream instead. Its position follows its source every tick, and its volume glides to each new loudness over about a fifth of a second, so a door shutting
 * or a step into the next room fades the sound rather than switching it.
 */
final class SynthSound extends AbstractTickableSoundInstance
{
    static final SoundEvent EVENT = SoundEvent.createVariableRangeEvent(Identifier.parse("nullspace:synth"));
    /** How far the volume moves toward its latest each tick: about 0.2 s to settle, as the voice's own live values do. */
    private static final float GLIDE = (float) (1 - Math.exp(-1 / 4.0));

    final SynthVoice voice;
    private volatile float target;

    SynthSound(SynthVoice voice, SoundSource source, boolean relative, double x, double y, double z, float volume, int delayTicks)
    {
        super(EVENT, source, SoundInstance.createUnseededRandom());
        this.voice = voice;
        this.relative = relative;
        this.attenuation = relative ? Attenuation.NONE : Attenuation.LINEAR;
        this.x = x;
        this.y = y;
        this.z = z;
        this.delay = delayTicks;
        this.looping = false;
        this.volume = volume;
        this.target = volume;
    }

    void place(double x, double y, double z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    void loudness(float volume)
    {
        target = Math.max(0, Math.min(1, volume));
    }

    @Override
    public void tick()
    {
        volume += (target - volume) * GLIDE;
        if (Math.abs(target - volume) < .002f)
            volume = target;
        if (voice.finished())
            stop();
    }

    @Override
    public boolean canStartSilent()
    {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly)
    {
        return CompletableFuture.completedFuture(new SynthStream(voice));
    }
}
