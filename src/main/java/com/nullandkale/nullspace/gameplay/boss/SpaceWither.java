package com.nullandkale.nullspace.gameplay.boss;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;

/** A white wither. It fights as the vanilla one does, and when it dies the nest starts counting the day to the next one. */
public final class SpaceWither extends WitherBoss
{
    private boolean died;

    public SpaceWither(EntityType<? extends WitherBoss> type, Level level)
    {
        super(type, level);
    }

    /** The vanilla wither's nether star is not what this one gives: it gives the black hole, which stays as long as the star would. */
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer)
    {
        var blackHole = spawnAtLocation(level, SpaceBoss.BLACK_HOLE);
        if (blackHole != null)
            blackHole.setExtendedLifetime();
    }

    @Override
    public void die(DamageSource source)
    {
        super.die(source);
        // Health is already zero when this is called, so the first call is the death; a second must not restart the day.
        if (!died && level() instanceof ServerLevel server)
        {
            died = true;
            SpaceNests.killed(server.getServer());
        }
    }
}
