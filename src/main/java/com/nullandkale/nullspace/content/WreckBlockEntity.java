package com.nullandkale.nullspace.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds nothing; it exists so a wreck can tick on the client. Vanilla's random block animation reaches a given block about
 * once a second, which is too rare for smoke, so each wreck smokes, sparks and smoulders every tick it is in view.
 */
public final class WreckBlockEntity extends BlockEntity
{
    public WreckBlockEntity(BlockPos pos, BlockState state)
    {
        super(BrokenMachines.TYPE, pos, state);
    }

    static void clientTick(Level level, BlockPos pos, BlockState state, WreckBlockEntity wreck)
    {
        var random = level.getRandom();
        double x = pos.getX() + .2 + random.nextDouble() * .6, z = pos.getZ() + .2 + random.nextDouble() * .6;
        if (random.nextInt(3) == 0)
            level.addParticle(ParticleTypes.LARGE_SMOKE, x, pos.getY() + 1.02, z, 0, .04 + random.nextDouble() * .03, 0);
        if (random.nextInt(12) == 0)
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, pos.getY() + random.nextDouble(), z, (random.nextDouble() - .5) * .3, .1, (random.nextDouble() - .5) * .3);
        if (random.nextInt(25) == 0)
            level.addParticle(ParticleTypes.FLAME, x, pos.getY() + 1.0, z, 0, .02, 0);
        if (random.nextInt(80) == 0)
            level.playLocalSound(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, .6f, .8f + random.nextFloat() * .4f, false);
    }
}
