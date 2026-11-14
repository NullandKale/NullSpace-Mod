package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationBuilds;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.EncounterKind;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.worldgen.stations.EncounterStations;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;

import com.google.gson.*;

/**
 * Not a regression test: counts what a built, stocked abandoned station really holds. Where {@link LootSurveyGameTest}
 * rolls the plan's marked chests, this queues real stations through the construction queue with the same metadata a
 * creative-spawned station gets, waits for each to be ready and counts every container that holds a loot table, every
 * item frame and every armor stand, then writes {@code nullspace.survey.out} as JSON.
 */
public final class LiveStationSurveyGameTest
{
    @GameTest(environment = "nullspace:station_building", maxTicks = 200000)
    public void liveStationLoot(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        int count = Integer.getInteger("nullspace.survey.sites", 20);
        var out = Path.of(System.getProperty("nullspace.survey.out", "build/loot-live.json"));
        var random = new Random(0xC0FFEEL);
        // The most dangerous zone there is, so the tables are the ones a danger 3 station rolls.
        String address = "sol_luna";
        for (var f : StarSystems.galaxy(server).features())
            if (StarSystems.danger(server, f.id()) == 3 && f.type() != GalaxyPlan.FeatureType.BLACK_HOLE && f.type() != GalaxyPlan.FeatureType.PULSAR)
            {
                address = f.id();
                break;
            }
        final String zone = address;
        int zoneDanger = StarSystems.danger(server, zone);
        var results = new JsonArray();
        var index = new int[1];
        var tries = new int[1];
        var current = new CompoundTag[1];
        var meta = new JsonObject[1];
        var poll = new Runnable[1];
        poll[0] = () ->
        {
            var level = server.getLevel(TravelService.SPACE);
            if (current[0] == null)
            {
                if (index[0] >= count)
                {
                    try
                    {
                        var root = new JsonObject();
                        root.addProperty("zone", zone);
                        root.addProperty("zoneDanger", zoneDanger);
                        root.add("stations", results);
                        Files.createDirectories(out.toAbsolutePath().getParent());
                        Files.writeString(out, new GsonBuilder().create().toJson(root));
                    }
                    catch (java.io.IOException failure)
                    {
                        throw new IllegalStateException(failure);
                    }
                    ctx.succeed();
                    return;
                }
                long seed = random.nextLong();
                var spec = EncounterStations.encounter(seed);
                var blueprint = EncounterSites.plan(EncounterKind.ABANDONED_STATION.id, seed);
                var metadata = new CompoundTag();
                metadata.putBoolean("worldGenerated", true);
                metadata.putBoolean("salvage", true);
                metadata.putInt("encounterKind", EncounterKind.ABANDONED_STATION.id);
                metadata.putLong("encounterSeed", seed);
                metadata.putInt("encounterDanger", zoneDanger);
                metadata.putString("encounterDesign", "");
                metadata.putBoolean("generatedRooms", true);
                metadata.put("stationSpec", spec.save());
                current[0] = StationBuilds.queueAmbient(server, UUID.randomUUID().toString(), "Survey station", zone, new Vec3(0, 0, 600), blueprint, metadata);
                if (current[0] == null)
                    ctx.assertTrue(false, "No storage for survey station " + index[0]);
                meta[0] = new JsonObject();
                meta[0].addProperty("seed", seed);
                meta[0].addProperty("size", spec.size().name());
                meta[0].addProperty("planLootChests", blueprint.points().stream().filter(p -> p.name().equals("roomloot")).count());
                meta[0].addProperty("planFrames", blueprint.points().stream().filter(p -> p.name().equals("frame")).count());
                tries[0] = 0;
                ctx.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            var id = current[0].getStringOr("id", "");
            var station = WorldStore.get(server).repository().find(id);
            if (station == null || !StationAccess.ready(station) || BuildJournal.pending(server, id))
            {
                if (++tries[0] > 4000)
                    ctx.assertTrue(false, "Survey station " + index[0] + " was never ready");
                ctx.runAfterDelay(10, () -> poll[0].run());
                return;
            }
            int containers = 0, withLoot = 0, nonEmpty = 0;
            var tables = new TreeMap<String, Integer>();
            for (var at : BlockPos.betweenClosed(WorldStore.bounds(station).min(), WorldStore.bounds(station).max()))
                if (level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity container)
                {
                    containers++;
                    var table = container.getLootTable();
                    if (table != null)
                    {
                        withLoot++;
                        tables.merge(table.identifier().getPath(), 1, Integer::sum);
                    }
                    container.unpackLootTable(null);
                    if (!container.isEmpty())
                        nonEmpty++;
                }
            var box = WorldStore.bounds(station).box().inflate(4);
            meta[0].addProperty("containers", containers);
            meta[0].addProperty("containersWithLootTable", withLoot);
            meta[0].addProperty("containersHoldingItems", nonEmpty);
            meta[0].addProperty("frames", level.getEntitiesOfClass(ItemFrame.class, box).size());
            meta[0].addProperty("stands", level.getEntitiesOfClass(ArmorStand.class, box).size());
            var tableJson = new JsonObject();
            tables.forEach(tableJson::addProperty);
            meta[0].add("tables", tableJson);
            results.add(meta[0]);
            StationGameTest.cleanup(level, station);
            current[0] = null;
            index[0]++;
            ctx.runAfterDelay(5, () -> poll[0].run());
        };
        ctx.runAfterDelay(5, () -> poll[0].run());
    }
}
