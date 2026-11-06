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
