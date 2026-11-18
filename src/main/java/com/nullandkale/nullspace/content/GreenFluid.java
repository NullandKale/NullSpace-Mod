package com.nullandkale.nullspace.content;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * The green fluid inside the green geodes: it flows and spreads like water, but it hurts. It is a water-tagged fluid (see
 * {@code data/minecraft/tags/fluid/water.json}) so that it swims like water, and it never makes new sources, so a pool
 * left alone stays the size it was. Standing in it costs {@link #DAMAGE} every {@link #INTERVAL} ticks, and it does not set fire.
 */
public abstract class GreenFluid extends FlowingFluid
{
    /** Half-hearts of damage each time it hurts, and how many ticks apart (one heart every half second). */
    public static final float DAMAGE = 2.0F;

    public static final int INTERVAL = 10;

    @Override
    public Fluid getFlowing()
    {
        return SpaceMaterials.FLOWING_GREEN_FLUID;
    }

    @Override
    public Fluid getSource()
    {
        return SpaceMaterials.GREEN_FLUID;
    }

    @Override
    public Item getBucket()
    {
        return SpaceMaterials.GREEN_FLUID_BUCKET;
    }

    @Override
    protected boolean canConvertToSource(ServerLevel level)
    {
        return false;
    }

    @Override
    protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state)
    {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        Block.dropResources(state, level, pos, blockEntity);
    }

    @Override
    protected void entityInside(Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier)
    {
        if (level instanceof ServerLevel server && entity instanceof LivingEntity living && living.isAlive() && living.tickCount % INTERVAL == 0)
            living.hurtServer(server, server.damageSources().generic(), DAMAGE);
    }

    @Override
    public int getSlopeFindDistance(LevelReader level)
    {
        return 4;
    }

    @Override
    public BlockState createLegacyBlock(FluidState fluidState)
    {
        return SpaceMaterials.GREEN_FLUID_BLOCK.defaultBlockState().setValue(LiquidBlock.LEVEL, getLegacyLevel(fluidState));
    }

    @Override
    public boolean isSame(Fluid other)
    {
        return other == SpaceMaterials.GREEN_FLUID || other == SpaceMaterials.FLOWING_GREEN_FLUID;
    }

    @Override
    public int getDropOff(LevelReader level)
    {
        return 1;
    }

    @Override
    public int getTickDelay(LevelReader level)
    {
        return 5;
    }

    @Override
    public boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid other, Direction direction)
    {
        return direction == Direction.DOWN && !other.is(FluidTags.WATER);
    }

    @Override
    protected float getExplosionResistance()
    {
        return 100.0F;
    }

    @Override
    public Optional<SoundEvent> getPickupSound()
    {
        return Optional.of(SoundEvents.BUCKET_FILL);
    }

    public static final class Flowing extends GreenFluid
    {
        @Override
        protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder)
        {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getAmount(FluidState fluidState)
        {
            return fluidState.getValue(LEVEL);
        }

        @Override
        public boolean isSource(FluidState fluidState)
        {
            return false;
        }
    }

    public static final class Source extends GreenFluid
    {
        @Override
        public int getAmount(FluidState fluidState)
        {
            return 8;
        }

        @Override
        public boolean isSource(FluidState fluidState)
        {
            return true;
        }
    }
}
