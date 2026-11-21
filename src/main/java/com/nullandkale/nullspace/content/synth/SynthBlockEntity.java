package com.nullandkale.nullspace.content.synth;

import com.nullandkale.nullspace.model.sound.SynthPatch;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a Synth block keeps: its patch, whether its loop is playing, and the redstone signal it last saw. It can instead play one of the game's cues as the
 * game plays it ({@link Cue}, set by the sound gallery); changing its patch ends that.
 */
public final class SynthBlockEntity extends BlockEntity
{
    /** A game cue with its live controls fixed: loudness, pitch, brightness, pace, and the least it is muffled. */
    public record Cue(String name, float volume, float pitch, float brightness, float rate, float muffle)
    {
        public Cue(String name)
        {
            this(name, 1, 1, 0, 1, 0);
        }
    }

    private SynthPatch patch = SynthPatch.DEFAULT;
    private Cue cue;
    private boolean playing, powered;

    public SynthBlockEntity(BlockPos pos, BlockState state)
    {
        super(Synths.ENTITY, pos, state);
    }

    public SynthPatch patch()
    {
        return patch;
    }

    void patch(SynthPatch next)
    {
        patch = next;
        cue = null;
        if (!patch.loop())
            playing = false;
        setChanged();
    }

    /** The game cue this block plays in place of its patch, or null. */
    public Cue cue()
    {
        return cue;
    }

    /** Plays a game cue: the patch becomes the cue's first layer, for the screen and as a start for tuning. */
    void cue(Cue next, SynthPatch first)
    {
        patch(first);
        cue = next;
        setChanged();
    }

    public boolean playing()
    {
        return playing;
    }

    void playing(boolean value)
    {
        playing = value;
        setChanged();
    }

    boolean powered()
    {
        return powered;
    }

    void powered(boolean value)
    {
        powered = value;
        setChanged();
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        try
        {
            patch = SynthPatch.parse(input.getStringOr("Patch", SynthPatch.DEFAULT.text()));
        }
        catch (IllegalArgumentException damaged)
        {
            patch = SynthPatch.DEFAULT;
        }
        playing = input.getBooleanOr("Playing", false) && patch.loop();
        cue = input.read("Cue", CUE_CODEC).orElse(null);
        powered = input.getBooleanOr("Powered", false);
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.putString("Patch", patch.text());
        output.putBoolean("Playing", playing);
        output.putBoolean("Powered", powered);
        if (cue != null)
            output.store("Cue", CUE_CODEC, cue);
    }

    private static final com.mojang.serialization.Codec<Cue> CUE_CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(com.mojang.serialization.Codec.STRING.fieldOf("name").forGetter(Cue::name), com.mojang.serialization.Codec.FLOAT.fieldOf("volume").forGetter(Cue::volume), com.mojang.serialization.Codec.FLOAT.fieldOf("pitch").forGetter(Cue::pitch), com.mojang.serialization.Codec.FLOAT.fieldOf("brightness").forGetter(Cue::brightness), com.mojang.serialization.Codec.FLOAT.fieldOf("rate").forGetter(Cue::rate), com.mojang.serialization.Codec.FLOAT.fieldOf("muffle").forGetter(Cue::muffle)).apply(i, Cue::new));
}
