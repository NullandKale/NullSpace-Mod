package com.nullandkale.nullspace.ship;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;

/** P4: the player's oxygen meter drifts toward the local air and runs out in about 20 seconds. */
public final class OxygenGameTest {
  @GameTest(environment = "nullspace:ship_recall", maxTicks = 200)
  public void theMeterDriftsTenAPointSecondDownAndTwentyUp(GameTestHelper ctx) {
    var player = ShipRecallGameTest.player(ctx, 221);
    var saved = new CompoundTag();
    int meter = 1000;
    for (int tick = 0; tick < 100; tick++) meter = SpaceSurvival.breathe(player, 50, saved);
    ctx.assertTrue(meter == 500, "Down 10 points a second: 100 to 50 in 5 s, got " + meter / 10);
    for (int tick = 0; tick < 25; tick++) meter = SpaceSurvival.breathe(player, 100, saved);
    ctx.assertTrue(meter == 750, "Up 20 points a second, got " + meter / 10);
    for (int tick = 0; tick < 400; tick++)
      meter = SpaceSurvival.breathe(player, Math.max(0, SpaceSurvival.SUIT_TETHER_FLOOR), saved);
    ctx.assertTrue(
        meter == SpaceSurvival.SUIT_TETHER_FLOOR * 10,
        "Suited and tethered in vacuum the meter rests at 20, got " + meter / 10);
    ctx.assertTrue(
        player.getAirSupply() == player.getMaxAirSupply() / 5, "Air bubbles show the meter");
    ctx.getLevel().getServer().getPlayerList().remove(player);
    ctx.succeed();
  }

  private static void suit(net.minecraft.server.level.ServerPlayer player) {
    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(ShipBlocks.HELMET));
    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(ShipBlocks.CHEST));
    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(ShipBlocks.LEGS));
    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new net.minecraft.world.item.ItemStack(ShipBlocks.BOOTS));
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 200)
  public void aCanisterGivesASuitedPlayerTwoMinutesOfAirAndRefillsAtAPoweredAirSupply(
      GameTestHelper ctx) throws Exception {
    var f = new ShipRecallGameTest.Fixture(ctx, 223);
    var player = f.player;
    var canister = new net.minecraft.world.item.ItemStack(ShipBlocks.AIR_CANISTER);
    player.getInventory().add(canister);
    ctx.assertTrue(
        SpaceSurvival.suitTarget(player, 0, false) == 0,
        "Without the full suit a canister cannot be used");
    suit(player);
    ctx.assertTrue(
        SpaceSurvival.suitTarget(player, 80, false) == 80 && canister.getDamageValue() == 0,
        "Breathable air is used first; the canister is not drawn");
    int full = 0;
    for (int tick = 0; tick < SpaceSurvival.CANISTER_TICKS; tick++)
      if (SpaceSurvival.suitTarget(player, 0, false) == 100) full++;
    ctx.assertTrue(
        full == SpaceSurvival.CANISTER_TICKS,
        "A full canister gives exactly 2 minutes of full air, gave " + full / 20 + " s");
    ctx.assertTrue(
        SpaceSurvival.suitTarget(player, 0, true) == SpaceSurvival.SUIT_TETHER_FLOOR,
        "With the canister empty, the tether floor remains");
    var supply =
        com.nullandkale.nullspace.systems.ShipSystems.connected(f.space, f.ship()).stream()
            .filter(p -> f.space.getBlockState(p).is(ShipBlocks.AIR_SUPPLY))
            .findFirst()
            .orElseThrow();
    player.teleportTo(f.space, supply.getX() + .5, supply.getY(), supply.getZ() + 1.5, java.util.Set.of(), 0, 0, true);
    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, canister);
    SpaceSurvival.fillCanister(player, net.minecraft.world.InteractionHand.MAIN_HAND, supply);
    ctx.assertTrue(canister.getDamageValue() == 0, "A powered Air Supply refills the canister");
    f.close();
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 700)
  public void withoutAirOrASuitAPlayerDiesInAboutTwentySeconds(GameTestHelper ctx) {
    var player = ShipRecallGameTest.player(ctx, 222);
    player.setHealth(player.getMaxHealth());
    var saved = new CompoundTag();
    int[] ticks = {0};
    ctx.onEachTick(
        () -> {
          if (!player.isAlive()) {
            ctx.assertTrue(
                ticks[0] >= 360 && ticks[0] <= 460,
                "Death in about 20 s with no air and no suit, took " + ticks[0] / 20.0 + " s");
            ctx.getLevel().getServer().getPlayerList().remove(player);
            ctx.succeed();
            return;
          }
          ticks[0]++;
          int meter = SpaceSurvival.breathe(player, 0, saved);
          if (ticks[0] == 200)
            ctx.assertTrue(meter == 0, "The meter reaches 0 after about 10 s, got " + meter / 10);
          if (ticks[0] > 600) ctx.fail("Still alive after 30 s without air");
        });
  }
}
