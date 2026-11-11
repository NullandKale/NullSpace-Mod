package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferRecovery;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.ShipTestFixture;

import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class HullCarveGameTest
{
    @GameTest(environment = "nullspace:hull_carve", maxTicks = 300)
    public void cornerCarvingsKeepTouchingBlocksAndTravelWhileAirGapsStayDisconnected(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var base = new BlockPos(10368, 96, 10368);
        for (int x = base.getX() >> 4; x <= (base.getX() + 31) >> 4; x++)
            for (int z = base.getZ() >> 4; z <= (base.getZ() + 31) >> 4; z++)
                level.setChunkForced(x, z, true);
        var helm = ShipTestFixture.build(level, base);
        PadComputers.register(level, base.offset(-1, 1, 3), new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7), "Carved hull", false);
        for (var at : BlockPos.betweenClosed(base.offset(6, 6, 6), base.offset(7, 7, 7)))
            level.setBlock(at, Blocks.STONE.defaultBlockState(), 18);
        ShipService.inspect(level, helm);
        var corner = base.offset(7, 7, 7);
        for (var at : BlockPos.betweenClosed(base.offset(6, 6, 6), corner))
            if (!at.equals(base.offset(6, 6, 6)) && !at.equals(corner))
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
        var scan = ShipService.inspect(level, helm);
        ctx.assertTrue(scan.scan().blocks().contains(corner), "Carving face connections does not detach a block still touching the hull's corner");
        var loose = base.offset(0, 10, 7);
        level.setBlock(loose, Blocks.STONE.defaultBlockState(), 18);
        boolean rejected = false;
        try
        {
            ShipService.inspect(level, helm);
        }
        catch (HullScan.Failure failure)
        {
            rejected = failure.getMessage().contains("disconnected");
        }
        level.setBlock(loose, Blocks.AIR.defaultBlockState(), 18);
        ctx.assertTrue(rejected, "A piece separated from the hull by air is still refused");
        var store = WorldStore.get(server);
        var ship = ShipTopology.resolve(level, helm);
        SystemsUpgrade.upgrade(level, ship);
        ctx.assertTrue(ShipSystems.connected(level, ship).contains(corner), "Equipment discovery uses the same hull connections");
        String id = ship.getStringOr("id", "");
        var sourceBase = WorldStore.pos(ship, "base");
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(level, base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5, Set.of(), 0, 0, true);
        boolean[] completed = {false};
        ctx.onEachTick(() ->
        {
            if (completed[0])
                return;
            var current = store.repository().find(id);
            if (!current.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()))
            {
                try
                {
                    var result = TravelService.transfer(player, helm, "", ShipService.preflight(level, helm));
                    if (result.outcome() != TravelService.Outcome.COMPLETED)
                    {
                        ctx.assertTrue(result.message().contains("loading"), "The carved ship can launch: " + result.message());
                        return;
                    }
                }
                catch (IllegalArgumentException loading)
                {
                    ctx.assertTrue(loading.getMessage().contains("loading"), "Only entity loading may delay the move: " + loading.getMessage());
                    return;
                }
            }
            completed[0] = true;
            current = store.repository().find(id);
            var destination = server.getLevel(TravelService.SPACE);
            var movedCorner = WorldStore.pos(current, "base").offset(corner.subtract(sourceBase));
            ctx.assertTrue(destination.getBlockState(movedCorner).is(Blocks.STONE) && level.getBlockState(corner).isAir(), "The touching corner moves exactly once with the actual hull");
            server.getPlayerList().remove(player);
            var bounds = WorldStore.bounds(current);
            TransferRecovery.clear(destination, bounds.min(), bounds.max());
            store.ships().remove(current);
            ctx.succeed();
        });
    }
}
