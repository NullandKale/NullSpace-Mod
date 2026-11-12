package com.nullandkale.nullspace.worldgen.sites;

import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

import com.mojang.blaze3d.platform.InputConstants;

/** Review the real raised foundation, then walk its complete descent without jumping. */
public final class TerraLandingClientGameTest implements FabricClientGameTest
{
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                TerrainPadFixture.build(level);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.CREATIVE);
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                p.teleportTo(level, TerrainPadFixture.X - 25.5, 96, TerrainPadFixture.Z - 35.5, Set.of(), 0, 0, true);
            });
            world.getConnection().waitForChunksRender();
            c.waitTicks(10);
            c.getInput().lookAt(new BlockPos(TerrainPadFixture.X + 12, 73, TerrainPadFixture.Z - 6));
            c.waitTicks(8);
            c.takeScreenshot("nullspace-raised-pad-grounded-stairs");
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.SURVIVAL);
                p.teleportTo(server.overworld(), TerrainPadFixture.X + 16.5, TerrainPadFixture.TOP + 1, TerrainPadFixture.Z + 1.5, Set.of(), 180, 0, true);
            });
            c.waitTicks(10);
            c.getInput().lookAt(180, 0);
            c.getInput().holdKey(InputConstants.KEY_W);
            try
            {
                c.waitFor(mc -> mc.player != null && mc.player.getZ() < TerrainPadFixture.Z - 23 && mc.player.getY() <= TerrainPadFixture.GROUND + 1.1, 300);
            }
            finally
            {
                c.getInput().releaseKey(InputConstants.KEY_W);
            }
            c.waitTicks(5);
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                if (!p.onGround() || p.getHealth() < 19)
                    throw new AssertionError("The pad descent must be walkable without a fall");
            });
            c.takeScreenshot("nullspace-pad-stairs-walked-to-ground");
            ClientTestEvidence.passed(getClass());
        }
    }
}
