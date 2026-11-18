package com.nullandkale.nullspace.content;

import com.nullandkale.nullspace.gameplay.navigation.PlanetDimensions;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** The green gem's fluid, its geode, and how far a world's ore richness follows its distance from Terra. */
public final class SpaceMaterialsGameTest
{
    @GameTest(environment = "nullspace:space_materials", maxTicks = 200)
    public void theGreenFluidHurtsAnythingInItAndNeverMakesANewSource(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var at = ctx.absolutePos(new BlockPos(2, 2, 2));
        for (int x = -4; x <= 8; x++)
            for (int z = -4; z <= 8; z++)
            {
                level.setBlock(at.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 4; y++)
                    level.setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        var source = SpaceMaterials.GREEN_FLUID_BLOCK.defaultBlockState();
        level.setBlock(at, source, 3);
        level.setBlock(at.offset(2, 0, 0), source, 3);
        var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        pig.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        pig.setNoAi(true);
        pig.setPersistenceRequired();
        level.addFreshEntity(pig);
        ctx.assertTrue(level.getFluidState(at).getType() == SpaceMaterials.GREEN_FLUID, "The block holds the still fluid");
        ctx.runAfterDelay(80, () ->
        {
            ctx.assertTrue(pig.getHealth() < pig.getMaxHealth(), "A pig standing in it is hurt: " + pig.getHealth() + " of " + pig.getMaxHealth());
            var gap = at.offset(1, 0, 0);
            ctx.assertTrue(!level.getFluidState(gap).isEmpty(), "It flows into the gap between the two sources");
            ctx.assertTrue(!level.getFluidState(gap).isSource(), "Two sources beside a gap do not make a third, as water would");
            ctx.assertTrue(level.getFluidState(gap).getType() == SpaceMaterials.FLOWING_GREEN_FLUID, "What flows is the flowing fluid");
            ctx.assertTrue(level.getFluidState(gap).is(net.minecraft.tags.FluidTags.WATER), "It swims like water");
            pig.discard();
            ctx.succeed();
        });
    }

    @GameTest(environment = "nullspace:space_materials", maxTicks = 300)
    public void everyGreenGeodeIsSmallFilledWithFluidAndHoldsAtLeastNineGems(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var center = new BlockPos(840000, 80, 840000);
        for (int cx = -3; cx <= 3; cx++)
            for (int cz = -3; cz <= 3; cz++)
                level.setChunkForced((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, true);
        try
        {
            for (int cx = -3; cx <= 3; cx++)
                for (int cz = -3; cz <= 3; cz++)
                    level.getChunk((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, ChunkStatus.FULL, true);
            for (int x = -40; x <= 40; x++)
                for (int z = -40; z <= 40; z++)
                    for (int y = -16; y <= 16; y++)
                        level.setBlock(center.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            var feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getValue(Identifier.parse("nullspace:green_geode"));
            ctx.assertTrue(feature != null, "The green geode feature is registered");
            var report = new ArrayList<String>();
            int placed = 0;
            int least = Integer.MAX_VALUE, most = 0, widest = 0;
            for (int i = 0; i < 16; i++)
            {
                var origin = center.offset(-30 + (i % 4) * 20, 0, -30 + (i / 4) * 20);
                if (!feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(1000 + i), origin))
                    continue;
                placed++;
                int clusters = 0, fluid = 0, minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                for (int x = -9; x <= 9; x++)
                    for (int y = -9; y <= 9; y++)
                        for (int z = -9; z <= 9; z++)
                        {
                            var p = origin.offset(x, y, z);
                            var state = level.getBlockState(p);
                            if (state.is(SpaceMaterials.GREEN_GEM_CLUSTER))
                                clusters++;
                            if (!level.getFluidState(p).isEmpty() && level.getFluidState(p).getType() == SpaceMaterials.GREEN_FLUID)
                                fluid++;
                            if (!state.is(Blocks.STONE) && !state.isAir() && level.getFluidState(p).isEmpty())
                            {
                                minX = Math.min(minX, x);
                                maxX = Math.max(maxX, x);
                            }
                        }
                int gems = clusters * SpaceMaterials.CLUSTER_GEMS;
                least = Math.min(least, gems);
                most = Math.max(most, gems);
                widest = Math.max(widest, maxX - minX + 1);
                report.add(i + ": " + clusters + " clusters (" + gems + " gems), " + fluid + " fluid, " + (maxX - minX + 1) + " wide");
                ctx.assertTrue(fluid >= 1, "Geode " + i + " is filled with the fluid: " + report.getLast());
            }
            ctx.assertTrue(placed >= 12, "Most placements take: " + placed + " of 16");
            ctx.assertTrue(least >= 9, "Every geode holds at least nine gems (a repair bay): " + report);
            ctx.assertTrue(widest <= 13, "A geode is small, no more than thirteen blocks across: " + widest + " " + report);
            ctx.succeed();
        }
        finally
        {
            for (int cx = -3; cx <= 3; cx++)
                for (int cz = -3; cz <= 3; cz++)
                    level.setChunkForced((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, false);
        }
    }

    @GameTest(environment = "nullspace:space_materials", maxTicks = 100)
    public void everyCreatedPlanetKnowsItsDistanceBandAndSolIsBandZero(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        int checked = 0, far = 0;
        var seen = new ArrayList<String>();
        for (var system : StarSystems.galaxy(server).systems())
            for (var body : system.bodies())
            {
                if (!body.landable())
                    continue;
                ServerLevel level = server.getLevel(PlanetSurfaces.dimension(server, body.id()));
                if (level == null)
                    continue;
                int band = PlanetDimensions.band(level);
                int expected = Math.clamp(StarSystems.danger(server, body.id()), 0, 3);
                seen.add(body.id() + "=" + band);
                if (level.dimension().identifier().getNamespace().equals("nullspace") && !body.surface().equals(GalaxyPlan.planetDimension(body.id())))
                    continue;
                ctx.assertTrue(band == expected || band == 0, body.id() + " is band " + band + " but its danger is " + expected);
                checked++;
                if (band > 0)
                    far++;
            }
        ctx.assertTrue(checked > 0, "Some planets exist: " + seen);
        ctx.assertTrue(PlanetDimensions.band(server.overworld()) == 0, "Terra is band 0");
        ctx.assertTrue(far > 0 || seen.size() < 3, "A planet outside Sol has a nonzero band: " + seen);
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:space_materials", maxTicks = 100)
    public void theGreenGemsCraftBothWaysAndAClusterDropsGems(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var recipes = level.getServer().getRecipeManager();
        var names = new ArrayList<String>();
        recipes.getRecipes().forEach(holder -> names.add(holder.id().identifier().toString()));
        ctx.assertTrue(names.contains("nullspace:green_gem_block") && names.contains("nullspace:green_gem_from_block"), "Nine gems make the block and the block makes nine again");
        ctx.assertTrue(names.contains("nullspace:repair_bay") && names.contains("nullspace:salvage_turret"), "The two machines have recipes");
        var at = ctx.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(at, SpaceMaterials.GREEN_GEM_CLUSTER.defaultBlockState(), 3);
        var drops = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(at), level, at, null, null, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
        int gems = drops.stream().filter(s -> s.is(SpaceMaterials.GREEN_GEM)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
        ctx.assertTrue(gems >= SpaceMaterials.CLUSTER_GEMS, "A cluster drops its gems: " + drops);
        ctx.succeed();
    }
}
