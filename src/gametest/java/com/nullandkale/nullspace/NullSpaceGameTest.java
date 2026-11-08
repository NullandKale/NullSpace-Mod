package com.nullandkale.nullspace;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class NullSpaceGameTest
{
    @GameTest
    public void modLoadsAndWorldTicks(GameTestHelper context)
    {
        if (!FabricLoader.getInstance().isModLoaded(NullSpace.MOD_ID))
        {
            throw new IllegalStateException("NullSpace was not loaded into the test server");
        }
        context.setBlock(1, 1, 1, Blocks.STONE);
        context.runAfterDelay(1, () ->
        {
            context.assertBlockPresent(Blocks.STONE, 1, 1, 1);
            context.succeed();
        });
    }
}
