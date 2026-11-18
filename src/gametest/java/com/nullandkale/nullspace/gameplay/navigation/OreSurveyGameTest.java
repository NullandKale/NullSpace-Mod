package com.nullandkale.nullspace.gameplay.navigation;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** What the Scanner's tiers find in the ground: kinds, richness, the way to the nearest vein, and ancient debris only at the top. */
public final class OreSurveyGameTest
{
    @GameTest(environment = "nullspace:space_boss", maxTicks = 300)
    public void eachScannerTierFindsMoreOreFartherOffAndOnlyTheTopTierFindsDebris(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var center = new BlockPos(850000, 80, 850000);
        for (int cx = -5; cx <= 5; cx++)
            for (int cz = -5; cz <= 5; cz++)
                level.setChunkForced((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, true);
        try
        {
            for (int cx = -5; cx <= 5; cx++)
                for (int cz = -5; cz <= 5; cz++)
                    level.getChunk((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, ChunkStatus.FULL, true);
            for (int x = -70; x <= 70; x++)
                for (int z = -70; z <= 70; z++)
                    for (int y = -8; y <= 8; y++)
                        level.setBlock(center.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(center.offset(5, 0, 0), Blocks.IRON_ORE.defaultBlockState(), 2);
            level.setBlock(center.offset(0, -6, 0), Blocks.DIAMOND_ORE.defaultBlockState(), 2);
            level.setBlock(center.offset(0, 0, -20), Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), 2);
            level.setBlock(center.offset(40, 0, 0), Blocks.COPPER_ORE.defaultBlockState(), 2);
            level.setBlock(center.offset(0, 2, 20), Blocks.ANCIENT_DEBRIS.defaultBlockState(), 2);
            var none = OreSurvey.report(level, center, 0, 0);
            ctx.assertTrue(none.size() == 1 && none.getFirst().contains("cannot find ore"), "The stock Scanner finds nothing: " + none);
            var one = String.join("\n", OreSurvey.report(level, center, 1, 0));
            ctx.assertTrue(one.contains("iron: 1 block") && one.contains("diamond: 1 block") && one.contains("coal: 1 block"), "Tier I lists the kinds within 32: " + one);
            ctx.assertTrue(!one.contains("copper") && !one.contains("debris") && !one.contains("nearest") && !one.contains("Richness"), "Tier I does not reach the copper 40 blocks away and finds no debris, no directions: " + one);
            var two = String.join("\n", OreSurvey.report(level, center, 2, 3));
            ctx.assertTrue(two.contains("Richness 3") && two.contains("ore x3.0") && two.contains("48 blocks nearer the surface"), "Tier II gives the richness: " + two);
            ctx.assertTrue(two.contains("iron: 1 block, nearest 5 blocks east") && two.contains("diamond: 1 block, nearest 6 blocks below"), "Tier II gives the way to the nearest vein: " + two);
            ctx.assertTrue(!two.contains("debris") && two.contains("copper: 1 block, nearest 40 blocks east"), "Tier II looks 48 blocks, reaches the copper and does not find debris: " + two);
            var three = String.join("\n", OreSurvey.report(level, center, 3, 0));
            ctx.assertTrue(three.contains("ancient debris: 1 block") && three.contains("copper: 1 block, nearest 40 blocks east"), "Tier III looks 64 blocks and finds debris: " + three);
            var creative = String.join("\n", OreSurvey.report(level, center, 4, 0));
            ctx.assertTrue(creative.contains("ancient debris"), "The creative tier looks as far as tier III: " + creative);
            ctx.succeed();
        }
        finally
        {
            for (int cx = -5; cx <= 5; cx++)
                for (int cz = -5; cz <= 5; cz++)
                    level.setChunkForced((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, false);
        }
    }
}
