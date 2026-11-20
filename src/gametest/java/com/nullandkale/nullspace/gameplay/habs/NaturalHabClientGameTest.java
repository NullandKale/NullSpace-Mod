package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ClientWaits;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.HabStructure;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;

/** Real exploration triggers placement; no gallery or direct population entrypoint is called. */
public final class NaturalHabClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        String previous = System.getProperty("nullspace.habs.enabled");
        System.setProperty("nullspace.habs.enabled", "false");
        try (var world = c.worldBuilder().setUseConsistentSettings(false).adjustSettings(settings -> settings.setSeed("79419843")).create())
        {
            ClientWaits.chunksRendered(world.getConnection());
            var found = new CompoundTag[1];
            var surface = new ServerLevel[1];
            world.getServer().runOnServer(server ->
            {
                var player = world.getConnection().getServerPlayer();
                surface[0] = server.getLevel(PlanetSurfaces.dimension(server, "sol_luna"));
                int cell = 20;
                while (HabSites.site(surface[0].getSeed(), cell, 20).spec().size() != StationSpec.Size.COMPACT)
                    cell++;
                var site = HabSites.site(surface[0].getSeed(), cell, 20);
                player.setGameMode(GameType.CREATIVE);
                player.setNoGravity(true);
                player.teleportTo(surface[0], site.x() + HabSites.SPACING, 180, site.z() + HabSites.SPACING, Set.of(), 0, 0, true);
                System.setProperty("nullspace.habs.enabled", "true");
            });
            for (int i = 0; i < 1000 && found[0] == null; i++)
            {
                c.waitTicks(20);
                world.getServer().runOnServer(server ->
                {
                    var records = WorldStore.get(server).section(NaturalHabs.SECTION);
                    for (var key : records.keySet())
                    {
                        var record = records.getCompoundOrEmpty(key);
                        if (record.getStringOr("dimension", "").equals(surface[0].dimension().identifier().toString()) && record.getStringOr("state", "").equals("built") && !BuildJournal.pending(server, record.getStringOr("id", "")))
                        {
                            found[0] = record.copy();
                            break;
                        }
                    }
                    if (found[0] == null)
                    {
                        var player = world.getConnection().getServerPlayer();
                        int cx = Math.floorDiv(player.blockPosition().getX(), HabSites.SPACING), cz = Math.floorDiv(player.blockPosition().getZ(), HabSites.SPACING);
                        boolean available = false;
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dz = -1; dz <= 1; dz++)
                            {
                                var site = HabSites.site(surface[0].getSeed(), cx + dx, cz + dz);
                                double distance = Math.hypot(site.x() - player.getX(), site.z() - player.getZ());
                                if (distance < 384 || distance > 1280)
                                    continue;
                                String key = surface[0].dimension().identifier() + "/" + (cx + dx) + "/" + (cz + dz);
                                if (!records.contains(key) || !records.getCompoundOrEmpty(key).getStringOr("state", "").equals("skipped"))
                                    available = true;
                            }
                        if (!available)
                            throw new AssertionError("Exploration exhausted every nearby hab candidate: " + records);
                    }
                });
            }
            if (found[0] == null)
                throw new AssertionError("Exploring untouched planet terrain must spawn a hab without any operator command");
            System.setProperty("nullspace.habs.enabled", "false");
            var build = HabStructure.build(HabPlanner.generate(StationSpec.read(found[0].getCompoundOrEmpty("spec"))));
            var origin = WorldStore.pos(found[0], "origin");
            var door = origin.offset(build.entrance());
            shoot(c, world, surface[0], door.relative(build.outside(), 24).above(7), build.outside().getOpposite().toYRot(), 18, "nullspace-natural-hab-surface");
            shoot(c, world, surface[0], door.relative(build.outside().getOpposite(), 2), build.outside().getOpposite().toYRot(), 15, "nullspace-natural-hab-entry");
            var room = build.hab().groveRoom();
            var interior = room.interior();
            var middle = origin.offset((interior.x0() + interior.x1()) / 2, room.standY(), (interior.z0() + interior.z1()) / 2);
            shoot(c, world, surface[0], middle.offset(0, 0, -7), 0, -12, "nullspace-natural-hab-grove");
        }
        finally
        {
            if (previous == null)
                System.clearProperty("nullspace.habs.enabled");
            else
                System.setProperty("nullspace.habs.enabled", previous);
        }
        ClientTestEvidence.passed(getClass());
    }

    private static void shoot(ClientGameTestContext c, net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world, ServerLevel level, BlockPos at, float yaw, float pitch, String name)
    {
        world.getServer().runOnServer(server ->
        {
            var player = world.getConnection().getServerPlayer();
            player.teleportTo(level, at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), yaw, pitch, true);
        });
        c.waitTicks(100);
        ClientWaits.chunksRendered(world.getConnection());
        c.takeScreenshot(name);
    }
}
