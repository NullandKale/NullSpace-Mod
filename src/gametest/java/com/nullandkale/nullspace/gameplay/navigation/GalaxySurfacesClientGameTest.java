package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.testing.ClientTestEvidence;

import java.util.*;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

/** P9: stand on each planet type's surface and look around, for visual review of the terrain. */
public final class GalaxySurfacesClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            var bodies = world.getServer().computeOnServer(server ->
            {
                var examples = new EnumMap<GalaxyPlan.BodyType, GalaxyPlan.Body>(GalaxyPlan.BodyType.class);
                StarSystems.galaxy(server).systems().stream().flatMap(s -> s.bodies().stream()).filter(b -> b.landable() && b.surface().equals(GalaxyPlan.planetDimension(b.id()))).forEach(b -> examples.putIfAbsent(b.type(), b));
                if (examples.isEmpty())
                    throw new AssertionError("No dedicated planet dimensions were created");
                return List.copyOf(examples.values());
            });
            for (var body : bodies)
            {
                world.getServer().runOnServer(server ->
                {
                    var key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(body.surface()));
                    var level = server.getLevel(key);
                    if (level == null)
                        throw new AssertionError("Missing surface " + body.surface());
                    if (level.getSeed() != body.seed())
                        throw new AssertionError("Incorrect planet seed");
                    var chunk = level.getChunk(4, 4);
                    int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8) + 2;
                    var p = world.getConnection().getServerPlayer();
                    p.setGameMode(GameType.SPECTATOR);
                    p.teleportTo(level, 72.5, y + 6, 72.5, Set.of(), 225, 20, true);
                });
                world.getConnection().waitForChunksRender();
                c.waitTicks(30);
                c.takeScreenshot("nullspace-surface-" + body.type().name().toLowerCase(Locale.ROOT));
            }
            ClientTestEvidence.passed(getClass());
        }
    }
}
