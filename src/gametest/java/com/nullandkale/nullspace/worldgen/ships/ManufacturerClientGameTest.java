package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.worldgen.stations.ManufacturerStation;

import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

/**
 * Real rendered review gallery, including each silhouette and the station's three interior wings.
 */
public final class ManufacturerClientGameTest implements FabricClientGameTest
{
    private static void view(ClientGameTestContext c, TestSingleplayerContext world, BlockPos at, BlockPos target, String screenshot)
    {
        world.getServer().runOnServer(server ->
        {
            var p = world.getConnection().getServerPlayer();
            p.teleportTo(server.overworld(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), 0, 0, true);
        });
        world.getConnection().waitForChunksRender();
        c.waitTicks(10);
        c.getInput().lookAt(target);
        c.waitTicks(10);
        c.takeScreenshot(screenshot);
    }

    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            var presets = ShipBlueprints.PRESETS.stream().filter(p -> p.role().equals("Scout")).toList();
            world.getServer().runOnServer(server ->
            {
                var level = server.overworld();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                int i = 0;
                for (var preset : presets)
                {
                    var base = new BlockPos(i++ * 40, -59, 0);
                    for (var e : ShipBlueprints.blocks(preset).entrySet())
                        level.setBlock(base.offset(e.getKey()), e.getValue(), 18);
                }
                var station = new BlockPos(120, -59, 0);
                for (var e : ManufacturerStation.build().blocks().entrySet())
                    level.setBlock(station.offset(e.getKey()), e.getValue(), 18);
                ManufacturerStation.label(level, station);
                world.getConnection().getServerPlayer().setGameMode(GameType.SPECTATOR);
            });
            for (int i = 0; i < presets.size(); i++)
            {
                var p = presets.get(i);
                var base = new BlockPos(i * 40, -59, 0);
                view(c, world, base.offset(-12, 16, -12), base.offset(p.width() / 2, 1, p.depth() / 2), "nullspace-" + p.id());
            }
            var base = new BlockPos(120, -59, 0);
            view(c, world, base.offset(-13, 24, -14), base.offset(15, 3, 15), "nullspace-manufacturer-station");
            view(c, world, base.offset(8, 3, 15), base.offset(3, 4, 17), "nullspace-rivet-workshop");
            view(c, world, base.offset(23, 3, 15), base.offset(29, 4, 15), "nullspace-halcyon-observation");
            view(c, world, base.offset(15, 3, 28), base.offset(13, 4, 24), "nullspace-bastion-operations");
            ClientTestEvidence.passed(getClass());
        }
    }
}
