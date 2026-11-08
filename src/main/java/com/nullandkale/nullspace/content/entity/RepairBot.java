package com.nullandkale.nullspace.content.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The one new mob: a small hovering maintenance drone that keeps a derelict's crew going and is
 * always hostile to players there. It flies but cannot pass through walls, and needs no air.
 */
public class RepairBot extends Monster
{
    public RepairBot(EntityType<? extends RepairBot> type, Level level)
    {
        super(type, level);
        this.moveControl = new FlyingMoveControl<>(this, 20, true);
        this.setNoGravity(true);
        this.xpReward = 5;
    }

    public static AttributeSupplier.Builder attributes()
    {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 16).add(Attributes.FLYING_SPEED, 0.45).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.ATTACK_DAMAGE, 3).add(Attributes.FOLLOW_RANGE, 24);
    }

    @Override
    protected PathNavigation createNavigation(Level level)
    {
        var navigation = new FlyingPathNavigation(this, level);
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        return navigation;
    }

    @Override
    protected void registerGoals()
    {
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomFlyingGoal(this, 0.8));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    /** Every 2 seconds it patches itself and nearby wounded crew, 2 health each. */
    @Override
    protected void customServerAiStep(ServerLevel level)
    {
        super.customServerAiStep(level);
        if (this.tickCount % 40 != 0)
            return;
        if (this.getHealth() < this.getMaxHealth())
            this.heal(2);
        for (var crew : level.getEntitiesOfClass(Monster.class, this.getBoundingBox().inflate(6), m -> m != this && m.isAlive() && m.getHealth() < m.getMaxHealth()))
            crew.heal(2);
    }
}
