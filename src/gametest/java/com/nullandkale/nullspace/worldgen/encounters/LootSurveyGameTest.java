package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.model.galaxy.EncounterKind;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import com.google.gson.*;

/**
 * Not a regression test: a survey. It builds many random encounters of every kind, stocks them with the game's own
 * decision ({@link EncounterSites#assignments}), rolls every real loot table, and writes everything the player can take
 * (chest contents, item frames, armor stands, animals and every placed block) to a JSON file that
 * {@code scripts/art/loot_survey.py} turns into distribution tables.
 *
 * <p>Run it with {@code Run-FocusedTests.ps1 -Classes ...LootSurveyGameTest -JvmProperties -Dnullspace.survey.sites=40
 * -Dnullspace.survey.out=path}; it is deliberately absent from the sweep's class list.
 */
public final class LootSurveyGameTest
{
    private static final int[] DANGERS = {0, 1, 2, 3};

    @GameTest(maxTicks = 6000)
    public void surveyEncounterLoot(GameTestHelper ctx)
    {
        // A tool, not a check: the sweep runs it as an empty pass unless a survey is asked for.
        if (System.getProperty("nullspace.survey.sites") == null)
        {
            ctx.succeed();
            return;
        }
        int sites = Integer.getInteger("nullspace.survey.sites", 20);
        var out = Path.of(System.getProperty("nullspace.survey.out", "build/loot-survey.json"));
        var level = ctx.getLevel();
        var chestAt = ctx.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), 3);
        var random = new Random(0x10075E11L);
        var root = new JsonObject();
        var list = new JsonArray();
        for (var kind : EncounterKind.values())
            for (int i = 0; i < sites; i++)
            {
                long seed = random.nextLong();
                var plan = EncounterSites.plan(kind.id, seed);
                var site = new JsonObject();
                site.addProperty("kind", kind.id);
                site.addProperty("label", kind.label);
                site.addProperty("seed", seed);
                String design = EncounterSites.design(kind.id, seed);
                site.addProperty("design", design);
                site.add("blocks", blocks(plan));
                if (kind == EncounterKind.ABANDONED_STATION)
                {
                    // The planner's own room list: how many rooms the station has, of what size and use, against how many hold a loot chest.
                    var stationPlan = com.nullandkale.nullspace.worldgen.stations.StationPlanner.generate(com.nullandkale.nullspace.worldgen.stations.EncounterStations.encounter(seed));
                    site.addProperty("stationSize", stationPlan.spec().size().name());
                    var uses = new JsonObject();
                    int rooms = 0;
                    for (var room : stationPlan.rooms())
                    {
                        uses.addProperty(room.use().name(), uses.has(room.use().name()) ? uses.get(room.use().name()).getAsInt() + 1 : 1);
                        rooms++;
                    }
                    site.addProperty("rooms", rooms);
                    site.add("roomUses", uses);
                }
                var frames = new JsonObject();
                int armored = 0, suits = 0;
                var animals = new JsonObject();
                for (var point : plan.points())
                {
                    var args = point.argument().split(" ");
                    switch (point.name())
                    {
                        case "frame" -> frames.addProperty(args[0], frames.has(args[0]) ? frames.get(args[0]).getAsInt() + 1 : 1);
                        case "stand" ->
                        {
                            boolean empty = args.length > 1 && args[1].equals("empty");
                            if (!empty && args.length > 1 && args[1].equals("armored"))
                                armored++;
                            else if (!empty)
                                suits++;
                        }
                        case "animal" -> animals.addProperty(args[0], animals.has(args[0]) ? animals.get(args[0]).getAsInt() + 1 : 1);
                        default ->
                            {
                            }
                    }
                }
                site.add("frames", frames);
                site.addProperty("suits", suits);
                site.addProperty("armoredSuits", armored);
                site.add("animals", animals);
                var byDanger = new JsonObject();
                for (int danger : DANGERS)
                {
                    // Dangerous zones' treasure tables; black holes and pulsars use the singularity table at danger 3.
                    String treasure = "treasure_" + danger;
                    byDanger.add(Integer.toString(danger), rolled(ctx, chestAt, EncounterSites.assignments(kind.id, design, BlockPos.ZERO, plan.points(), treasure, danger, seed, "Survey")));
                }
                byDanger.add("S", rolled(ctx, chestAt, EncounterSites.assignments(kind.id, design, BlockPos.ZERO, plan.points(), "treasure_singularity", 3, seed, "Survey")));
                site.add("loot", byDanger);
                list.add(site);
            }
        root.addProperty("sitesPerKind", sites);
        root.add("sites", list);
        try
        {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, new GsonBuilder().create().toJson(root));
        }
        catch (java.io.IOException failure)
        {
            throw new IllegalStateException(failure);
        }
        ctx.succeed();
    }

    private static JsonObject blocks(com.nullandkale.nullspace.worldgen.stations.StationBlueprint.Plan plan)
    {
        var counts = new TreeMap<String, Integer>();
        for (var state : plan.blocks().values())
            if (!state.isAir())
                counts.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
        var json = new JsonObject();
        counts.forEach(json::addProperty);
        return json;
    }

    /** Rolls each assignment's table in a real chest; one entry per container. */
    private static JsonArray rolled(GameTestHelper ctx, BlockPos chestAt, List<SiteLoot.Assignment> assignments)
    {
        var chests = new JsonArray();
        for (var a : assignments)
        {
            SiteLoot.assign(ctx.getLevel(), chestAt, a.table(), a.seed(), a.name());
            var chest = (ChestBlockEntity) ctx.getLevel().getBlockEntity(chestAt);
            chest.unpackLootTable(null);
            var items = new TreeMap<String, Integer>();
            for (int slot = 0; slot < chest.getContainerSize(); slot++)
            {
                var stack = chest.getItem(slot);
                if (!stack.isEmpty())
                    items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
            var entry = new JsonObject();
            entry.addProperty("table", a.table());
            var contents = new JsonObject();
            items.forEach(contents::addProperty);
            entry.add("items", contents);
            chests.add(entry);
        }
        return chests;
    }
}
