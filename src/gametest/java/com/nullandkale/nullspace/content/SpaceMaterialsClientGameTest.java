package com.nullandkale.nullspace.content;

import com.nullandkale.nullspace.gameplay.navigation.OreSurvey;
import com.nullandkale.nullspace.gameplay.shipyard.MaterialsGallery;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Pictures of the space materials in the materials gallery ({@code /nullspace shipyard materials}): the green geode cut open, the fluid
 * with the gem blocks and clusters, the turret and repair bay with the new items on frames, the white space wither, and a Scanner's ore survey.
 */
public final class SpaceMaterialsClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            var origin = new BlockPos[1];
            world.getServer().runOnServer(server ->
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
                origin[0] = MaterialsGallery.build(server);
            });
            int z = MaterialsGallery.AISLE - 3;
            shoot(c, world, origin[0], 11.5, 6.0, z - 2, 180, -24, "nullspace-green-geode-cut-open");
            shoot(c, world, origin[0], MaterialsGallery.PITCH + 14.5, 2.0, z - 2, 180, 14, "nullspace-green-fluid-and-gems");
            shoot(c, world, origin[0], MaterialsGallery.PITCH * 2 + 11.5, 2.6, z - 5, 180, 24, "nullspace-turret-and-repair-bay");
            shoot(c, world, origin[0], MaterialsGallery.PITCH * 3 + 11.5, 2.5, z - 2, 180, -4, "nullspace-white-space-wither");
            shoot(c, world, origin[0], MaterialsGallery.PITCH * 4 + 11.5, 2.5, z + 1, 180, -4, "nullspace-golden-apple-trees");
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var level = (ServerLevel) p.level();
                var at = origin[0].offset(MaterialsGallery.PITCH * 3 + 2, 1, 6);
                var ores = new net.minecraft.world.level.block.Block[]{Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.ANCIENT_DEBRIS};
                for (int i = 0; i < ores.length; i++)
                    level.setBlock(at.offset(i, i % 3, 0), ores[i].defaultBlockState(), 2);
                for (var line : OreSurvey.report(level, at, 3, 2))
                    p.sendSystemMessage(Component.literal(line));
            });
            shoot(c, world, origin[0], MaterialsGallery.PITCH * 3 + 6.5, 2.5, 14.5, 180, 10, "nullspace-scanner-ore-survey");
        }
        ClientTestEvidence.passed(getClass());
    }

    /** Puts the player at a spot relative to the gallery's corner, looking the given way, lets the picture settle, and takes it. */
    private static void shoot(ClientGameTestContext c, TestSingleplayerContext world, BlockPos origin, double x, double y, double z, float yaw, float pitch, String name)
    {
        world.getServer().runOnServer(server ->
        {
            var p = world.getConnection().getServerPlayer();
            p.setNoGravity(true);
            p.teleportTo((ServerLevel) p.level(), origin.getX() + x, origin.getY() + y, origin.getZ() + z, Set.of(), yaw, pitch, true);
        });
        c.waitTicks(160);
        world.getConnection().waitForChunksRender();
        c.takeScreenshot(name);
    }
}
