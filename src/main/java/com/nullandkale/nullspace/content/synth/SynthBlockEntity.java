package com.nullandkale.nullspace.content.synth;

import com.nullandkale.nullspace.model.sound.SynthPatch;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** What a Synth block keeps: its patch, whether its loop is playing, and the redstone signal it last saw. */
public final class SynthBlockEntity extends BlockEntity
{
    private SynthPatch patch = SynthPatch.DEFAULT;
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
        if (!patch.loop())
            playing = false;
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
        powered = input.getBooleanOr("Powered", false);
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.putString("Patch", patch.text());
        output.putBoolean("Playing", playing);
        output.putBoolean("Powered", powered);
    }
}
