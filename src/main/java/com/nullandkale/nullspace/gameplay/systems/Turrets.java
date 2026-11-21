package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.defence.TurretBlockEntity;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.model.power.PowerBus;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

/**
 * What the turrets of a ship or station do each tick (chunk A3 of {@code docs/design/rewards-work-plan.md}). A turret works while
 * its vessel's controls have power, and each shot costs {@link #SHOT_FUEL} of the generators' fuel. It shoots the nearest hostile
 * mob inside the vessel in line of sight, within {@link #RANGE} blocks, never a player, a pet or a villager, as a player would: the
 * damage comes from a fake player, so the salvage and experience of {@code AirlessCrews} are earned. It gathers the items and
 * experience that fall within {@link #REACH} blocks. A <b>hostile</b> turret (a security turret of a generated site) shoots
 * players instead, and nothing else.
 */
public final class Turrets
{
    public static final int RANGE = 16, REACH = 3, SCAN = 10, COOLDOWN = 20;

    /** Damage of a shot, in half-hearts. */
    public static final float DAMAGE = 4.0F;

    /** Internal fuel units per shot: 2 percent of a coal (one coal is 12,800). */
    public static final int SHOT_FUEL = 256;

    static void tick(ServerLevel level, CompoundTag ship, ShipRuntime r)
    {
        if (r.turrets.isEmpty() || level.getGameTime() % SCAN != 0 || grant(r, PowerBus.System.CONTROLS) < 1)
            return;
        var bounds = WorldStore.bounds(ship);
        for (var pos : r.turrets)
            if (level.getBlockEntity(pos) instanceof TurretBlockEntity turret && turret.enabled())
                work(level, ship, turret, pos, bounds);
    }

    private static void work(ServerLevel level, CompoundTag ship, TurretBlockEntity turret, BlockPos pos, HullScan.Bounds bounds)
    {
        gather(level, turret, pos);
        boolean hostile = turret.hostile() && com.nullandkale.nullspace.worldgen.encounters.SiteDefences.guarded(ship);
        var target = target(level, hostile, pos, bounds);
        if (target != null)
            turret.aim(Vec3.atCenterOf(pos).add(0, 0.7, 0), target.getEyePosition());
        if (level.getGameTime() - turret.lastShot() < COOLDOWN || target == null || !FuelSystem.spend(level, ship, SHOT_FUEL))
            return;
        turret.shot(level.getGameTime());
        var from = Vec3.atCenterOf(pos).add(0, 0.7, 0);
        var to = target.getEyePosition();
        var source = hostile ? level.damageSources().generic() : level.damageSources().playerAttack(FakePlayer.get(level));
        target.hurtServer(level, source, DAMAGE);
        var step = to.subtract(from).scale(1 / 6.0);
        for (int i = 1; i <= 6; i++)
            level.sendParticles(ParticleTypes.CRIT, from.x + step.x * i, from.y + step.y * i, from.z + step.z * i, 1, 0, 0, 0, 0);
        com.nullandkale.nullspace.gameplay.sound.ShipSounds.play(level, from, "turret.fire");
    }

    /** The nearest living thing this turret may shoot, or null. */
    public static LivingEntity target(ServerLevel level, TurretBlockEntity turret, BlockPos pos, HullScan.Bounds bounds)
    {
        return target(level, turret.hostile(), pos, bounds);
    }

    /** The nearest living thing a turret of this kind may shoot: players for a hostile one, hostile mobs for any other. */
    public static LivingEntity target(ServerLevel level, boolean hostile, BlockPos pos, HullScan.Bounds bounds)
    {
        var from = Vec3.atCenterOf(pos).add(0, 0.7, 0);
        var box = new AABB(pos).inflate(RANGE);
        LivingEntity best = null;
        double distance = Double.MAX_VALUE;
        java.util.List<? extends LivingEntity> candidates = hostile ? level.getEntitiesOfClass(Player.class, box, p -> !p.isCreative() && !p.isSpectator()) : level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy);
        for (var candidate : candidates)
        {
            double d = candidate.distanceToSqr(from);
            if (!candidate.isAlive() || candidate.isInvulnerable() || d > RANGE * RANGE || d >= distance || !bounds.contains(candidate.blockPosition()))
                continue;
            if (level.clip(new ClipContext(from, candidate.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType() != HitResult.Type.MISS)
                continue;
            best = candidate;
            distance = d;
        }
        return best;
    }

    /** Takes the items and the experience that lie within reach into the turret. */
    private static void gather(ServerLevel level, TurretBlockEntity turret, BlockPos pos)
    {
        var box = new AABB(pos).inflate(REACH);
        for (var item : level.getEntitiesOfClass(ItemEntity.class, box))
        {
            var rest = turret.insert(item.getItem());
            if (rest.isEmpty())
                item.discard();
            else
                item.setItem(rest);
        }
        for (var orb : level.getEntitiesOfClass(ExperienceOrb.class, box))
        {
            turret.addExperience(orb.getValue());
            orb.discard();
        }
    }

    private Turrets()
    {
    }
}
