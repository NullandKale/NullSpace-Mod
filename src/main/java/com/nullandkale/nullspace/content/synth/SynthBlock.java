package com.nullandkale.nullspace.content.synth;

import com.nullandkale.nullspace.content.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

/** The Synth block: right-click for its screen; a redstone pulse plays what the screen has set (see {@link Synths}). */
public final class SynthBlock extends ModBlocks.Machine implements EntityBlock
{
    public SynthBlock(Properties props)
    {
        super(props);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new SynthBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit)
    {
        if (player instanceof ServerPlayer sp)
            Synths.open(sp, pos, Synths.silence(sp.level(), pos));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, Orientation orientation, boolean moved)
    {
        super.neighborChanged(state, level, pos, neighbor, orientation, moved);
        if (level instanceof ServerLevel server && server.getBlockEntity(pos) instanceof SynthBlockEntity)
            Synths.redstone(server, pos, server.hasNeighborSignal(pos));
    }
}
