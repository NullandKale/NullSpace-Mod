package com.nullandkale.nullspace.render.sound;

import com.nullandkale.nullspace.model.sound.SynthVoice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;

import org.lwjgl.BufferUtils;

/**
 * A {@link SynthVoice} as a stream for Minecraft's sound engine. The engine asks for a second of audio at a time and keeps four queued, which would put a live
 * sound seconds behind the game; this answers each request with a tenth of a second, so about 0.4 s is queued: enough to ride out a client hitch, short enough
 * that a hiss following the air feels live. When the voice has finished it answers nothing, the queued audio drains and the sound ends.
 */
final class SynthStream implements AudioStream
{
    private static final AudioFormat FORMAT = new AudioFormat(SynthVoice.RATE, 16, 1, true, false);
    private static final int CHUNK = SynthVoice.RATE / 10;

    private final SynthVoice voice;
    private final short[] samples = new short[CHUNK];

    SynthStream(SynthVoice voice)
    {
        this.voice = voice;
    }

    @Override
    public AudioFormat getFormat()
    {
        return FORMAT;
    }

    @Override
    public ByteBuffer read(int size)
    {
        int count = voice.render(samples, Math.min(CHUNK, Math.max(1, size / 2)));
        if (count <= 0)
            return null;
        var buffer = BufferUtils.createByteBuffer(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++)
            buffer.putShort(samples[i]);
        buffer.flip();
        return buffer;
    }

    /** The engine also closes a stream that ran dry in a client hitch; the voice keeps playing and {@link ClientSounds} starts it on a new stream. */
    @Override
    public void close()
    {
    }
}
