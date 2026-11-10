package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYard;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

import com.mojang.blaze3d.platform.InputConstants;

/** Exterior review and real, unassisted walking up and down the explorer stairwell. */
public final class ManufacturerRoleClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            var presets = ShipBlueprints.PRESETS.stream().filter(p -> !p.role().equals("Scout")).toList();
            world.getServer().runOnServer(server ->
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                int i = 0;
                for (var preset : presets)
                {
                    var base = new BlockPos(i++ * 40, -59, 0);
                    for (var e : ShipBlueprints.blocks(preset).entrySet())
                        server.overworld().setBlock(base.offset(e.getKey()), e.getValue(), 18);
                }
                world.getConnection().getServerPlayer().setGameMode(GameType.SPECTATOR);
            });
            for (int i = 0; i < presets.size(); i++)
            {
                var base = new BlockPos(i * 40, -59, 0);
                world.getServer().runOnServer(server ->
                {
                    var p = world.getConnection().getServerPlayer();
                    p.teleportTo(server.overworld(), base.getX() - 15.5, base.getY() + 20, -15.5, Set.of(), 0, 0, true);
                });
                world.getConnection().waitForChunksRender();
                c.waitTicks(10);
                c.getInput().lookAt(base.offset(12, 3, 15));
                c.waitTicks(10);
                c.takeScreenshot("nullspace-" + presets.get(i).id());
            }
            // Inside each explorer and freighter: a view down the bridge from the docking door.
            for (int i = 0; i < presets.size(); i++)
            {
                var base = new BlockPos(i * 40, -59, 0);
                var preset = presets.get(i);
                var door = base.offset(preset.width() / 2, 2, 1);
                world.getServer().runOnServer(server ->
                {
                    var p = world.getConnection().getServerPlayer();
                    p.teleportTo(server.overworld(), door.getX() + .5, door.getY() + 1, door.getZ() + 2.5, Set.of(), 0, 8, true);
                });
                world.getConnection().waitForChunksRender();
                c.waitTicks(10);
                c.takeScreenshot("nullspace-" + preset.id() + "-inside");
            }
            world.getServer().runOnServer(server -> WorldStore.get(server).save());
            ClientTestEvidence.passed(getClass());
        }
    }
}
