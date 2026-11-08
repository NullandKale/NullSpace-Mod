package com.nullandkale.nullspace.worldgen.sites;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.travel.ConcurrentShipActionsGameTest;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class TerraLandingGameTest
{
    @GameTest(environment = "nullspace:ship_control", maxTicks = 180)
    public void freshWorldHasAReadyTerraPadAndAnOrbitingShipCanActuallyLandThere(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var terra = server.overworld();
        var pad = PadRegistry.read(server).stream().filter(p -> p.dimension().equals("minecraft:overworld") && p.name().startsWith("Terra Landing")).findFirst().orElseThrow(() -> new AssertionError("Fresh worlds must create Terra's landing site before login"));
        PadRegistry.validate(terra, pad);
        var b = pad.bounds();
        for (var p : BlockPos.betweenClosed(new BlockPos(b.minX(), b.y() + 1, b.minZ()), new BlockPos(b.maxX(), terra.getMaxY(), b.maxZ())))
            ctx.assertTrue(terra.getBlockState(p).isAir(), "Terra's entire landing column must be clear");
        var corner = new BlockPos(b.minX() - 1, b.y(), b.minZ() - 1);
        terra.setBlock(corner, Blocks.AIR.defaultBlockState(), 18);
        int before = PadRegistry.read(server).size();
        PlanetSurfaces.ensureLanding(server, LocalSpaces.HOME);
        ctx.assertTrue(terra.getBlockState(corner).isAir() && PadRegistry.read(server).size() == before, "A later visit cannot rebuild player edits or create another Terra pad");
        terra.setBlock(corner, Blocks.COBBLESTONE.defaultBlockState(), 18);
        var client = ConcurrentShipActionsGameTest.client(ctx, 92);
        var f = client.fixture();
        client.board();
        var controller = WorldStore.pos(f.ship(), "controller");
        int[] ticks = {0};
        ctx.onEachTick(() ->
        {
            if (++ticks[0] < 20)
                return;
            var result = TravelService.transfer(f.player, controller, "id:" + pad.id(), ShipService.preflight(f.space, controller));
            if (result.outcome() == TravelService.Outcome.WAITING)
                return;
            ctx.assertTrue(result.outcome() == TravelService.Outcome.COMPLETED, result.message());
            ctx.assertTrue(f.player.level() == terra && WorldStore.pos(f.ship(), "base").equals(new BlockPos(b.minX(), b.y() + 1, b.minZ())), "The real ship, identity and passenger arrive on Terra");
            client.close();
            ctx.succeed();
        });
    }
}
