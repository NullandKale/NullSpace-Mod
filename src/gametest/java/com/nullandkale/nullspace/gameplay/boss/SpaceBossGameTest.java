package com.nullandkale.nullspace.gameplay.boss;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.crew.LifeSupport;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;

import java.util.ArrayList;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** The space wither, the black hole it drops, its nest and the beacon helmet. */
@SuppressWarnings("removal")
public final class SpaceBossGameTest
{
    @GameTest(environment = "nullspace:space_boss", maxTicks = 200)
    public void aSpaceWitherIsAWitherThatDropsABlackHoleAndNotANetherStarAndStartsTheDay(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var at = ctx.absolutePos(new BlockPos(2, 4, 2));
        var wither = SpaceBoss.SPACE_WITHER.create(level, EntitySpawnReason.COMMAND);
        ctx.assertTrue(wither instanceof WitherBoss, "A space wither is a wither");
        wither.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        wither.setNoAi(true);
        level.addFreshEntity(wither);
        var before = WorldStore.get(server).section("spaceNest").getLongOr("lastKill", Long.MIN_VALUE);
        wither.hurtServer(level, level.damageSources().genericKill(), 10000F);
        ctx.assertTrue(!wither.isAlive() || wither.isDeadOrDying(), "It dies");
        ctx.runAfterDelay(20, () ->
        {
            var drops = new ArrayList<ItemStack>();
            for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(8)))
                drops.add(item.getItem());
            ctx.assertTrue(drops.stream().anyMatch(s -> s.is(SpaceBoss.BLACK_HOLE)), "It drops a black hole: " + drops);
            ctx.assertTrue(drops.stream().noneMatch(s -> s.is(Items.NETHER_STAR)), "It does not drop a nether star: " + drops);
            long kill = WorldStore.get(server).section("spaceNest").getLongOr("lastKill", Long.MIN_VALUE);
            ctx.assertTrue(kill != before && Math.abs(kill - server.overworld().getGameTime()) < 100, "The kill starts the day to the next boss: " + kill);
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:space_boss", maxTicks = 100)
    public void theNestIsAtTheChartsOneOddity(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var zone = SpaceNests.zone(server);
        var oddities = StarSystems.galaxy(server).features().stream().filter(f -> f.type() == GalaxyPlan.FeatureType.PULSAR || f.type() == GalaxyPlan.FeatureType.BLACK_HOLE).toList();
        ctx.assertTrue(oddities.size() == 1, "The chart has exactly one pulsar or black hole: " + oddities.size());
        ctx.assertTrue(oddities.getFirst().id().equals(zone), "The nest is in its zone: " + zone);
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:space_boss", maxTicks = 100)
    public void theBeaconHelmetGivesABeaconsEffectsCyclesThemAndKeepsItsWearerBreathing(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var player = ctx.makeMockServerPlayerInLevel();
        var helmet = new ItemStack(SpaceBoss.BEACON_HELMET);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
        var held = new ItemStack(SpaceBoss.BEACON_HELMET);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModBlocks.CHEST));
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModBlocks.LEGS));
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModBlocks.BOOTS));
        ctx.assertTrue(LifeSupport.suited(player), "The beacon helmet is a suit helmet: with the rest of the suit its wearer is suited");
        SpaceBoss.renew(player);
        var speed = player.getEffect(MobEffects.SPEED);
        ctx.assertTrue(speed != null && speed.getAmplifier() == 1 && player.getEffect(MobEffects.REGENERATION) == null, "The first setting is Speed II");
        ctx.assertTrue(SpaceBoss.describe(0).equals("Speed II") && SpaceBoss.describe(5).equals("Speed I with Regeneration"), "The settings are named");
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        for (int i = 0; i < 5; i++)
            held.getItem().use(level, player, InteractionHand.MAIN_HAND);
        ctx.assertTrue(SpaceBoss.setting(held) == 5, "Sneak-using it five times reaches the fifth setting: " + SpaceBoss.setting(held));
        player.setItemSlot(EquipmentSlot.HEAD, held.copy());
        player.removeAllEffects();
        SpaceBoss.renew(player);
        var slow = player.getEffect(MobEffects.SPEED);
        ctx.assertTrue(slow != null && slow.getAmplifier() == 0 && player.getEffect(MobEffects.REGENERATION) != null, "Setting five is Speed I with Regeneration");
        for (int i = 0; i < 5; i++)
            held.getItem().use(level, player, InteractionHand.MAIN_HAND);
        ctx.assertTrue(SpaceBoss.setting(held) == 0, "The settings wrap around ten");
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.removeAllEffects();
        SpaceBoss.renew(player);
        ctx.assertTrue(player.getEffect(MobEffects.SPEED) == null, "Without the helmet there are no effects");
        var recipes = new ArrayList<String>();
        level.getServer().getRecipeManager().getRecipes().forEach(holder -> recipes.add(holder.id().identifier().toString()));
        ctx.assertTrue(recipes.contains("nullspace:beacon_helmet"), "The helmet has a recipe");
        ctx.succeed();
    }
}
