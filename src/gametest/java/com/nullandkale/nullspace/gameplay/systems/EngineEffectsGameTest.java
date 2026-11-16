package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.gameplay.start.SpaceStart;
import com.nullandkale.nullspace.gameplay.start.WorldStartSettings;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;

/** A thrusting ship's engines show an exhaust outside the hull; an idle ship's show none. Own environment: it uses a space slot. */
public final class EngineEffectsGameTest
{
    @GameTest(environment = "nullspace:engine_effects", maxTicks = 200)
    public void thrustingEnginesHaveExhaustsOutsideTheHullAndIdleOnesNone(GameTestHelper ctx)
    {
        var player = ctx.makeMockServerPlayerInLevel();
        var ship = SpaceStart.start(player, new WorldStartSettings(true, "rivet_bolt"));
        var space = player.level();
        ship.putInt("thrustDemand", 0);
        ctx.assertTrue(EngineEffects.exhausts(space, ship).isEmpty(), "An idle ship has no exhaust");
        ship.putInt("thrustDemand", 6);
        var exhausts = EngineEffects.exhausts(space, ship);
        ctx.assertTrue(!exhausts.isEmpty(), "A thrusting ship's engines have exhausts");
        for (var exhaust : exhausts)
            ctx.assertTrue(space.getBlockState(BlockPos.containing(exhaust.origin())).isAir(), "Each exhaust fires into open space, not into the hull: " + exhaust.origin());
        ctx.assertTrue(exhausts.stream().allMatch(e -> e.tier() == 0), "Plain engines are tier 0");
        // Upgrades change the exhaust's tier, and so its colour.
        for (var at : ShipSystems.connected(space, ship))
            if (space.getBlockState(at).is(ModBlocks.ENGINE) && space.getBlockEntity(at) instanceof MachineBlockEntity machine)
            {
                machine.setItem(0, new ItemStack(ModBlocks.EXOTIC_UPGRADE));
                machine.setItem(1, new ItemStack(ModBlocks.ADVANCED_UPGRADE));
                machine.setItem(2, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
            }
        var upgraded = EngineEffects.exhausts(space, ship);
        ctx.assertTrue(!upgraded.isEmpty() && upgraded.stream().allMatch(e -> e.tier() == 3), "Fully upgraded engines are tier 3: " + upgraded);
        ship.putInt("thrustDemand", 0);
        ctx.assertTrue(EngineEffects.exhausts(space, ship).isEmpty(), "When thrust stops, the exhaust stops");
        StationGameTest.cleanup(space, ship);
        ctx.succeed();
    }
}
