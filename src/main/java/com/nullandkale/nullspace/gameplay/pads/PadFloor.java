package com.nullandkale.nullspace.gameplay.pads;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a landing pad's floor may be made of. Vanilla grass and dirt, and anything a mod or a data pack adds to
 * {@code nullspace:pad_floor}: another mod's grass block spreading over a pad, or a biome whose surface is not vanilla
 * grass, must not make a pad unusable. The tag lists dirt and the standard {@code minecraft:grass_blocks} tag, which mods
 * already use for their own grass.
 */
public final class PadFloor
{
    private static final TagKey<Block> FLOOR = TagKey.create(Registries.BLOCK, Identifier.parse("nullspace:pad_floor"));
    private static final TagKey<Block> GRASS = TagKey.create(Registries.BLOCK, Identifier.parse("minecraft:grass_blocks"));

    public static boolean is(BlockState state)
    {
        // The vanilla blocks stay listed in code, so a pad never depends on a tag loading.
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(FLOOR);
    }

    /** The scanner's view of a floor block, or null when the block is not pad floor. */
    public static PadScanner.Cell cell(BlockState state)
    {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(GRASS))
            return PadScanner.Cell.GRASS;
        return is(state) ? PadScanner.Cell.DIRT : null;
    }

    private PadFloor()
    {
    }
}
