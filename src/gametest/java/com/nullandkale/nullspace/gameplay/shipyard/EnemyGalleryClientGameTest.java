package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.GameType;

/**
 * Visual review of {@code /nullspace shipyard enemies}: the hall, the first and second half of the rows from the west end, and
 * a close view of the creeper, cave spider, slime and blaze rows. It fails only if the hall cannot be built or drawn.
 */
public final class EnemyGalleryClientGameTest implements FabricClientGameTest
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
            long[] origin = new long[3];
            world.getServer().runOnServer(server ->
            {
                var at = EnemyGallery.build(server);
                origin[0] = at.getX();
                origin[1] = at.getY();
                origin[2] = at.getZ();
            });
            String[] names = {"hall-first-rows", "hall-last-rows", "creeper-spiders", "slime-blaze"};
            // Spectator views from the west end: yaw -90 looks east along the rows. Rows are 5 blocks apart from z+5.
            double[][] stands = {{2.5, 1.8, 5 + 2.5 * 5}, {2.5, 1.8, 5 + 8.5 * 5}, {2.5, 1.8, 5 + 6.5 * 5 + .5}, {2.5, 1.8, 5 + 10.5 * 5 + .5}};
            for (int i = 0; i < stands.length; i++)
            {
                double[] stand = stands[i];
                world.getServer().runOnServer(server ->
                {
                    var p = world.getConnection().getServerPlayer();
                    p.setGameMode(GameType.SPECTATOR);
                    p.teleportTo(server.overworld(), origin[0] + stand[0], origin[1] + stand[1], origin[2] + stand[2], Set.of(), -90, 8, true);
                });
                world.getConnection().waitForChunksRender();
                c.waitTicks(30);
                c.takeScreenshot("nullspace-enemy-gallery-" + names[i]);
            }
            // The vanilla creatures keep their vanilla look: a creeper, spider, slime and blaze beside the hall's first row.
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                var types = java.util.List.<net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>of(net.minecraft.world.entity.EntityTypes.CREEPER, net.minecraft.world.entity.EntityTypes.SPIDER, net.minecraft.world.entity.EntityTypes.SLIME, net.minecraft.world.entity.EntityTypes.BLAZE);
                for (int i = 0; i < types.size(); i++)
                {
                    var mob = types.get(i).create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    mob.snapTo(origin[0] + 8.5 + i * 2.5, origin[1] + 1, origin[2] + 1.5, 0, 0);
                    mob.setNoAi(true);
                    mob.setPermanentlyInvulnerable(true);
                    mob.setPersistenceRequired();
                    level.addFreshEntity(mob);
                }
                var p = world.getConnection().getServerPlayer();
                p.teleportTo(level, origin[0] + 12.5, origin[1] + 1.8, origin[2] + 7.5, Set.of(), 180, 8, true);
            });
            world.getConnection().waitForChunksRender();
            c.waitTicks(30);
            c.takeScreenshot("nullspace-enemy-gallery-vanilla-comparison");
            ClientTestEvidence.passed(getClass());
        }
    }
}
