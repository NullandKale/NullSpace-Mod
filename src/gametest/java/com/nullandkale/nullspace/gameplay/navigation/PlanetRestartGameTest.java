package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;

/** Opt-in fresh-process persistence check driven by planet-restart-test.ps1. */
public final class PlanetRestartGameTest
{
    @GameTest(maxTicks = 1200)
    public void restart(GameTestHelper ctx) throws Exception
    {
        String mode = System.getProperty("nullspace.planet.restart.mode", "");
        if (mode.isEmpty())
        {
            ctx.succeed();
            return;
        }
        var server = ctx.getLevel().getServer();
        var store = WorldStore.get(server);
        var bodies = StarSystems.galaxy(server).systems().stream().flatMap(s -> s.bodies().stream()).filter(b -> b.landable() && b.surface().equals(GalaxyPlan.planetDimension(b.id()))).limit(2).toList();
        ctx.assertTrue(bodies.size() == 2, "Two independent generated surfaces exist");
        var marker = new BlockPos(48, 200, 48);
        if (mode.equals("prepare"))
        {
            var manifest = new ListTag();
            for (int i = 0; i < bodies.size(); i++)
            {
                var body = bodies.get(i);
                PlanetSurfaces.ensureLanding(server, body.id());
                var level = server.getLevel(PlanetSurfaces.dimension(server, body.id()));
                level.getChunk(marker);
                level.setBlock(marker, (i == 0 ? Blocks.GOLD_BLOCK : Blocks.DIAMOND_BLOCK).defaultBlockState(), 18);
                var pad = PadRegistry.read(server).stream().filter(p -> p.dimension().equals(body.surface())).findFirst().orElseThrow();
                var entry = new CompoundTag();
                entry.putString("body", body.id());
                entry.putString("surface", body.surface());
                entry.putLong("seed", level.getSeed());
                entry.putString("pad", pad.id());
                entry.putString("biome", level.getBiome(marker).unwrapKey().orElseThrow().identifier().toString());
                manifest.add(entry);
            }
            store.document("planetRestartProbe", manifest);
            server.saveEverything(true, true, true);
            Runtime.getRuntime().halt(86);
        }
        else if (mode.equals("verify"))
        {
            var manifest = store.list("planetRestartProbe");
            ctx.assertTrue(manifest.size() == 2, "The prior process saved both surface identities");
            for (int i = 0; i < manifest.size(); i++)
            {
                var entry = (CompoundTag) manifest.get(i);
                var key = PlanetSurfaces.dimension(server, entry.getStringOr("body", ""));
                ctx.assertTrue(key.identifier().toString().equals(entry.getStringOr("surface", "")), "Restart preserves the saved dimension identity");
                var level = server.getLevel(key);
                ctx.assertTrue(level != null && level.getSeed() == entry.getLongOr("seed", 0), "The level and its individual terrain seed are recreated before recovery");
                ctx.assertTrue(level.getBlockState(marker).is(i == 0 ? Blocks.GOLD_BLOCK : Blocks.DIAMOND_BLOCK), "Identical coordinates retain different builds on independent worlds");
                ctx.assertTrue(level.getBiome(marker).unwrapKey().orElseThrow().identifier().toString().equals(entry.getStringOr("biome", "")), "Terrain biome survives restart");
                PlanetSurfaces.ensureLanding(server, entry.getStringOr("body", ""));
                ctx.assertTrue(PadRegistry.read(server).stream().anyMatch(p -> p.id().equals(entry.getStringOr("pad", "")) && p.dimension().equals(key.identifier().toString())), "Landing registration is retained without rebuilding");
                ctx.assertTrue(level.getBlockState(marker).is(i == 0 ? Blocks.GOLD_BLOCK : Blocks.DIAMOND_BLOCK), "Revisiting preserves player construction");
            }
            Files.writeString(Path.of(System.getProperty("nullspace.planet.restart.result")), "passed");
            ctx.succeed();
        }
        else
            throw new IllegalArgumentException("Unknown planet restart phase");
    }
}
