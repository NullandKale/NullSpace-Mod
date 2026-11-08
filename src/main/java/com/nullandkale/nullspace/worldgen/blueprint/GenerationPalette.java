package com.nullandkale.nullspace.worldgen.blueprint;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Generated ships, stations and sites are built from cheap blocks, so tearing a wreck apart never
 * pays out valuable materials. Storage blocks of metals, gems and fuels are never allowed;
 * functional pieces such as iron doors are fine because a site only has a few.
 */
public final class GenerationPalette
{
    private static final Set<Block> BANNED = Set.of(Blocks.IRON_BLOCK, Blocks.RAW_IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.COAL_BLOCK, Blocks.AMETHYST_BLOCK, Blocks.ANCIENT_DEBRIS, Blocks.RAW_COPPER_BLOCK);

    /** True if a generated build may contain this block. */
    public static boolean allowed(BlockState state)
    {
        if (BANNED.contains(state.getBlock()))
            return false;
        // Every copper storage block, cut or oxidised or waxed, is still copper.
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return !(id.contains("copper") && (id.endsWith("copper") || id.contains("cut_copper") || id.contains("copper_block") || id.startsWith("chiseled_copper")));
    }

    /** The positions in a generated build that break the palette, for tests and authoring. */
    public static List<BlockPos> violations(Map<BlockPos, BlockState> blocks)
    {
        return blocks.entrySet().stream().filter(e -> !allowed(e.getValue())).map(Map.Entry::getKey).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
    }

    private GenerationPalette()
    {
    }
}
