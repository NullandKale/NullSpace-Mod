package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.worldgen.shipgen.StationGenerator;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;

public final class StationGalleryGameTest
{
    @GameTest(maxTicks = 1200)
    public void everyPieceStandsWithAWalkInOpeningAndTheStationsAreBuilt(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var origin = StationGallery.build(server);
        int built = 0;
        for (int windows = 1; windows <= 2; windows++)
        {
            var pieces = StationGallery.row(windows);
            for (int column = 0; column < pieces.size(); column++)
            {
                var at = origin.offset(StationGallery.MARGIN + column * StationGallery.PITCH, -1, StationGallery.MARGIN + (windows - 1) * StationGallery.PITCH);
                var door = at.offset(StationGenerator.SLOTS[0], 2, 15);
                ctx.assertTrue(level.getBlockState(door).isAir() && level.getBlockState(door.above()).isAir(), "Opening: " + pieces.get(column).id());
                // The hall inside is open at standing height and bright enough to see.
                var inside = at.offset(StationGenerator.SLOTS[0], 2, 13);
                ctx.assertTrue(level.getBlockState(inside).isAir(), "Walk-in cell is open: " + pieces.get(column).id());
                built++;
            }
        }
        ctx.assertTrue(built == 20, "All twenty pieces are built: " + built);
        ctx.succeed();
    }
}
