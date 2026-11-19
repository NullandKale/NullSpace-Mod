package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.HabStructure;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;

public final class NaturalHabGameTest
{
    @GameTest(environment = "nullspace:natural_habs", maxTicks = 600000)
    public void terrainTicksBuildARealDungeonOnceAndPreserveLootedBlocksAfterReload(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(PlanetSurfaces.dimension(server, "sol_luna"));
        int[] cell = {40};
        boolean[] held = {false};
        Runnable[] poll = {null};
        poll[0] = () ->
        {
            while (HabSites.site(level.getSeed(), cell[0], 40).spec().size() != StationSpec.Size.COMPACT)
                cell[0]++;
            ctx.assertTrue(cell[0] < 160, "A dry, untouched compact site is available on Luna");
            var record = NaturalHabs.consider(level, cell[0], 40);
            String state = record.getStringOr("state", "");
            if (state.equals("skipped"))
            {
                cell[0]++;
                ctx.runAfterDelay(1, () -> poll[0].run());
                return;
            }
            if (!state.equals("built") || BuildJournal.pending(server, record.getStringOr("id", "")))
            {
                if (state.equals("building"))
                    ctx.assertTrue(TransferLocks.locked(level, WorldStore.pos(record, "min")), "An incomplete site stays protected across chunk batches");
                ctx.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            var vessel = WorldStore.get(server).repository().find(record.getStringOr("id", ""));
            ctx.assertTrue(vessel != null && vessel.getBooleanOr("hab", false) && vessel.getBooleanOr("worldGenerated", false), "Terrain ticks register the hab as a real station vessel");
            var bounds = WorldStore.bounds(vessel);
            if (!held[0])
            {
                force(level, bounds, true);
                held[0] = true;
                ctx.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            if (!com.nullandkale.nullspace.gameplay.ship.structure.HullEntities.loaded(level, bounds))
            {
                ctx.runAfterDelay(1, () -> poll[0].run());
                return;
            }
            var origin = WorldStore.pos(record, "origin");
            var build = HabStructure.build(HabPlanner.generate(StationSpec.read(record.getCompoundOrEmpty("spec"))));
            var core = WorldStore.pos(vessel, "controller");
            ctx.assertTrue(level.getBlockState(core).is(ModBlocks.STATION_CORE), "The dungeon has a functional Station Core");
            var door = origin.offset(build.entrance());
            ctx.assertTrue(level.getBlockState(door).is(Blocks.IRON_DOOR), "The entrance stands at the actual surface");
            ctx.assertTrue(!level.getBlockState(door.relative(build.outside()).below()).isAir(), "Players have ground outside the entrance");
            var entry = build.hab().entry().outer();
            for (var at : build.plan().blocks().keySet())
            {
                if (at.getX() >= entry.x0() - 2 && at.getX() <= entry.x1() + 2 && at.getZ() >= entry.z0() - 2 && at.getZ() <= entry.z1() + 2)
                    continue;
                var absolute = origin.offset(at);
                int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, absolute.getX(), absolute.getZ()) - 1;
                ctx.assertTrue(absolute.getY() < surface, "The dungeon's rooms stay below the terrain at " + absolute + " (" + build.plan().blocks().get(at) + "), surface " + surface + ", above " + level.getBlockState(absolute.above()));
            }
            var tree = origin.offset(build.plan().points("tree").getFirst().pos());
            ctx.assertTrue(level.getBlockEntity(tree) instanceof com.nullandkale.nullspace.gameplay.trees.TreeBlockEntity, "The deepest floor holds its golden tree");
            var inventories = build.plan().blocks().keySet().stream().map(origin::offset).filter(p -> level.getBlockEntity(p) instanceof Container).toList();
            ctx.assertTrue(inventories.stream().anyMatch(p -> !((Container) level.getBlockEntity(p)).isEmpty()), "Generated dungeon containers hold real loot");
            ctx.assertTrue(com.nullandkale.nullspace.gameplay.ship.structure.HullEntities.inBounds(level, WorldStore.bounds(vessel)).stream().anyMatch(e -> e instanceof net.minecraft.world.entity.Mob), "The dungeon has its lost crew, including its inactive chunks");
            inventories.forEach(p ->
            {
                var inventory = (Container) level.getBlockEntity(p);
                // Opening a chest rolls its deferred loot before the player removes it.
                inventory.isEmpty();
                inventory.clearContent();
                inventory.setChanged();
            });
            ctx.assertTrue(!TransferLocks.locked(level, core), "Completed and durably saved habs are editable");
            level.setBlock(core, Blocks.AIR.defaultBlockState(), 18);
            WorldStore.get(server).save();
            WorldStore.forget(server);
            NaturalHabs.consider(level, cell[0], 40);
            ctx.runAfterDelay(45, () ->
            {
                try
                {
                    ctx.assertTrue(level.getBlockState(core).isAir(), "Reload and rediscovery never rebuild a stripped core");
                    ctx.assertTrue(inventories.stream().allMatch(p -> ((Container) level.getBlockEntity(p)).isEmpty()), "Loot stays depleted after rediscovery");
                    ctx.assertTrue(WorldStore.get(server).ships().stream().filter(t -> ((net.minecraft.nbt.CompoundTag) t).getStringOr("id", "").equals(record.getStringOr("id", ""))).count() == 1, "The same hab is never registered twice");
                    ctx.succeed();
                }
                finally
                {
                    force(level, bounds, false);
                }
            });
        };
        ctx.runAfterDelay(1, () -> poll[0].run());
    }

    @GameTest(environment = "nullspace:natural_habs_occupied", maxTicks = 600000)
    public void previouslyInhabitedTerrainIsNeverReplaced(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        ServerLevel level = server.getLevel(PlanetSurfaces.dimension(server, "sol_luna"));
        int selected = 90;
        while (HabSites.site(level.getSeed(), selected, 90).spec().size() != StationSpec.Size.COMPACT)
            selected++;
        final int cell = selected;
        var site = HabSites.site(level.getSeed(), cell, 90);
        var plan = HabStructure.build(HabPlanner.generate(site.spec()));
        var edit = plan.plan().blocks().keySet().iterator().next();
        var marker = new BlockPos(site.x() - plan.hab().plan().width() / 2 + edit.getX(), 80, site.z() - plan.hab().plan().depth() / 2 + edit.getZ());
        for (int attempt = 0; attempt < HabSites.ATTEMPTS; attempt++)
        {
            var alternative = HabSites.site(level.getSeed(), cell, 90, attempt);
            int x = alternative.x() - plan.hab().plan().width() / 2 + edit.getX();
            int z = alternative.z() - plan.hab().plan().depth() / 2 + edit.getZ();
            level.getChunk(x >> 4, z >> 4).setInhabitedTime(1);
        }
        level.setBlock(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        NaturalHabs.consider(level, cell, 90);
        Runnable[] poll = {null};
        poll[0] = () ->
        {
            var record = NaturalHabs.consider(level, cell, 90);
            if (!record.getStringOr("state", "").equals("skipped"))
            {
                ctx.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            ctx.assertTrue(level.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "The existing player block survives");
            ctx.assertTrue(!record.contains("next"), "No construction batch starts in inhabited terrain");
            ctx.succeed();
        };
        ctx.runAfterDelay(5, () -> poll[0].run());
    }

    private static void force(ServerLevel level, com.nullandkale.nullspace.gameplay.ship.structure.HullScan.Bounds bounds, boolean on)
    {
        for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
            for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
            {
                level.setChunkForced(x, z, on);
                if (on)
                    level.getChunk(x, z);
            }
    }
}
