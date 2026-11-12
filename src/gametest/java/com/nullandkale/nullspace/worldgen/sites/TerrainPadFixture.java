package com.nullandkale.nullspace.worldgen.sites;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

final class TerrainPadFixture
{
    static final int X = 420000, Z = 420000, TOP = 80, GROUND = 60;

    static void build(ServerLevel level)
    {
        for (int x = -4; x <= 36; x++)
            for (int z = -70; z <= 36; z++)
            {
                int height = z < 0 ? GROUND : GROUND + Math.max(0, x) / 4;
                for (int y = GROUND; y <= height; y++)
                    level.setBlock(new BlockPos(X + x, y, Z + z), Blocks.STONE.defaultBlockState(), 18);
            }
        TerraLandingSite.buildPlatform(level, X, Z, TOP);
    }

    private TerrainPadFixture()
    {
    }
}
