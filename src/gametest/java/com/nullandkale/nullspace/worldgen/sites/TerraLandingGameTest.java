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
    @GameTest(environment = "nullspace:ship_control", maxTicks = 200)
    public void raisedPadStairsReachTerrainAndFoundationsLeaveNoOpenCavity(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        TerrainPadFixture.build(level);
        for (int x = -1; x <= 32; x++)
            for (int z = -1; z <= 32; z++)
                for (int y = TerrainPadFixture.GROUND; y < TerrainPadFixture.TOP; y++)
                    ctx.assertTrue(!level.getBlockState(new BlockPos(TerrainPadFixture.X + x, y, TerrainPadFixture.Z + z)).isAir(), "No open spawning cavity below the raised platform");
        for (int step = 1; step <= 20; step++)
        {
            var at = new BlockPos(TerrainPadFixture.X + 16, TerrainPadFixture.TOP - step + 1, TerrainPadFixture.Z - 1 - step);
            ctx.assertTrue(level.getBlockState(at).is(Blocks.COBBLESTONE_STAIRS), "Stairs continue past the former nine-step limit");
            ctx.assertTrue(!level.getBlockState(at.below()).isAir(), "Each stair has a foundation");
        }
        ctx.succeed();
    }

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
