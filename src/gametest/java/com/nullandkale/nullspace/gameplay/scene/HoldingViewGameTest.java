package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.gameplay.travel.TravelService;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;

/** {@code /nullspace debug holding} takes an operator to the holding dimension as a spectator, and running it again puts them back as they were. */
public final class HoldingViewGameTest
{
    @GameTest
    public void theHoldingViewTakesAnOperatorToSpaceAndBack(GameTestHelper ctx)
    {
        var player = ctx.makeMockServerPlayerInLevel();
        var server = ctx.getLevel().getServer();
        try
        {
            player.setGameMode(GameType.SURVIVAL);
            var home = player.level().dimension();
            var at = player.position();
            String on = HoldingView.toggle(player);
            ctx.assertTrue(on.startsWith("Holding view on") && player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR && player.level().dimension().equals(TravelService.SPACE), "The view starts as a spectator in space: " + on + " " + player.level().dimension());
            String off = HoldingView.toggle(player);
            ctx.assertTrue(off.startsWith("Holding view off") && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && player.level().dimension().equals(home) && player.position().distanceTo(at) < 1e-6, "Running it again returns the player as they were: " + off + " " + player.level().dimension() + " " + player.position());
        }
        finally
        {
            server.getPlayerList().remove(player);
        }
        ctx.succeed();
    }
}
