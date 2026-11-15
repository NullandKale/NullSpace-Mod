package com.nullandkale.nullspace.gameplay.shipyard;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

public final class EnemyGalleryGameTest
{
    @GameTest(maxTicks = 600)
    public void theEnemyGalleryStandsEveryCreatureAtEveryDangerStillAndUnhurtable(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var origin = EnemyGallery.build(server);
        var box = new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 60, origin.getY() + EnemyGallery.HEIGHT, origin.getZ() + 70);
        var mobs = level.getEntitiesOfClass(Mob.class, box);
        ctx.assertTrue(mobs.size() == EnemyGallery.count(), "The gallery holds " + EnemyGallery.count() + " creatures, found " + mobs.size());
        ctx.assertTrue(mobs.stream().allMatch(m -> m.isNoAi() && m.isInvulnerable() && m.isPersistenceRequired() && m.getCustomName() == null), "Every creature stands still, cannot be hurt, stays, and has no name tag");
        ctx.assertTrue(mobs.stream().map(m -> m.getType()).distinct().count() >= 10, "At least ten different kinds");
        // Rebuilding replaces the hall: the count does not grow.
        EnemyGallery.build(server);
        ctx.assertTrue(level.getEntitiesOfClass(Mob.class, box).size() == EnemyGallery.count(), "A second build replaces the first");
        level.getEntitiesOfClass(Mob.class, box).forEach(Mob::discard);
        ctx.succeed();
    }
}
