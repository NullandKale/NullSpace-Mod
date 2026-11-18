package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;

import java.util.*;
import java.util.concurrent.Executor;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.*;

/** Recreates the saved planet levels before recovery and before players receive their dimension list. */
public final class PlanetDimensions
{
    private static final ServerContext.Key<Map<ResourceKey<Level>, Long>> SEEDS = new ServerContext.Key<>();

    /**
     * The danger band (0 to 3) of each planet surface by dimension id, set when the level is created. World generation reads it from
     * its own threads, so it is a plain concurrent map and never asks the galaxy ({@link com.nullandkale.nullspace.worldgen.OreRichness}).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> BANDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** How far out a surface lies: 0 at Sol (and for anything not a created planet), 3 beyond 65 light-years. */
    public static int band(Level level)
    {
        return BANDS.getOrDefault(level.dimension().identifier().toString(), 0);
    }

    public static Long seed(ServerLevel level)
    {
        var seeds = ServerContext.get(level.getServer()).existing(SEEDS);
        return seeds == null ? null : seeds.get(level.dimension());
    }

    public static void create(MinecraftServer server, Executor executor, LevelStorageSource.LevelStorageAccess storage, Map<ResourceKey<Level>, ServerLevel> levels)
    {
        var seeds = ServerContext.get(server).service(SEEDS, HashMap::new);
        var stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        for (var system : StarSystems.galaxy(server).systems())
            for (var body : system.bodies())
            {
                if (!body.landable())
                    continue;
                var key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(body.surface()));
                if (levels.containsKey(key))
                    continue; // Existing level generators and seeds remain unchanged.
                if (!body.surface().equals(GalaxyPlan.planetDimension(body.id())))
                    throw new IllegalStateException("Missing saved planet dimension " + body.surface());
                var type = body.type() == GalaxyPlan.BodyType.TOXIC ? GalaxyPlan.BodyType.BARREN : body.type();
                var prototype = stems.getValue(ResourceKey.create(Registries.LEVEL_STEM, Identifier.fromNamespaceAndPath("nullspace", GalaxyPlan.surfaceSlot(type, 0))));
                if (prototype == null)
                    throw new IllegalStateException("Missing planet terrain template for " + body.type());
                // Populate this before construction: ChunkMap asks getSeed while ServerLevel is being built.
                seeds.put(key, body.seed());
                BANDS.put(key.identifier().toString(), Math.clamp(StarSystems.danger(server, body.id()), 0, 3));
                var data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
                var level = new ServerLevel(server, executor, storage, data, key, new LevelStem(prototype.type(), prototype.generator()), false, BiomeManager.obfuscateSeed(body.seed()), List.of(), false);
                level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
                server.getPlayerList().addWorldborderListener(level);
                levels.put(key, level);
            }
    }

    private PlanetDimensions()
    {
    }
}
