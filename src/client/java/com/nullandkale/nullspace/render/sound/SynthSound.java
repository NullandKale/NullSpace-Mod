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
 * own stream instead. Its volume and position change every tick, so a sound that is muffled by a closing door, or that follows a moving source, changes at once.
 */
final class SynthSound extends AbstractTickableSoundInstance
{
    static final SoundEvent EVENT = SoundEvent.createVariableRangeEvent(Identifier.parse("nullspace:synth"));

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
