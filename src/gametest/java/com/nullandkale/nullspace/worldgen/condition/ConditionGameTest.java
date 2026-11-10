package com.nullandkale.nullspace.worldgen.condition;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.blueprint.GenerationPalette;
import com.nullandkale.nullspace.worldgen.encounters.SitePoints;
import com.nullandkale.nullspace.worldgen.piece.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/** G4: assemblies become plans with loot spots, wear scales with danger, and sites stock from points. */
public final class ConditionGameTest
{
    private static Assembler.Assembly build(long seed)
    {
        var build = Assembler.assemble(AssemblerGameTest.kit(true), AssemblerGameTest.scout(new Vec3i(32, 32, 32)), seed);
        if (!build.problems().isEmpty())
            throw new IllegalStateException(build.problems().toString());
        return build;
    }

    @GameTest
    public void assembliesBecomePlansAndWearScalesWithDanger(GameTestHelper ctx)
    {
        var plan = Assembler.plan(build(4));
        ctx.assertTrue(plan.blocks().get(plan.core()).is(ModBlocks.COMPUTER), "The plan's core is the ship's computer");
        ctx.assertTrue(!plan.points("loot").isEmpty() && plan.points("loot").stream().allMatch(p -> plan.blocks().get(p.pos()).is(Blocks.BARREL)), "Every loot point becomes a barrel");
        ctx.assertTrue(Condition.apply(plan, 0, 1).plan().blocks().equals(plan.blocks()), "Danger 0 leaves the build intact");
        int lightsBefore = (int) plan.blocks().values().stream().filter(s -> s.is(Blocks.SEA_LANTERN)).count();
        int scorchedTotal = 0, darkTotal = 0;
        for (long seed = 1; seed <= 6; seed++)
        {
            var result = Condition.apply(plan, 3, seed);
            var after = result.plan().blocks();
            ctx.assertTrue(result.report().breaches() >= 3 && result.report().removed() > 0, "Danger 3 breaches the hull: " + result.report());
            scorchedTotal += result.report().scorched();
            darkTotal += result.report().darkened();
            plan.blocks().forEach((at, state) ->
            {
                if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("nullspace"))
                    ctx.assertTrue(after.get(at).equals(state), "Machines are never damaged: " + state + " at " + at);
            });
            ctx.assertTrue(plan.points("loot").stream().allMatch(p -> after.get(p.pos()).is(Blocks.BARREL)), "Loot spots survive");
            ctx.assertTrue(GenerationPalette.violations(after).isEmpty(), "Damage never adds valuable blocks");
            ctx.assertTrue(Condition.apply(plan, 3, seed).plan().blocks().equals(after), "The same seed gives the same damage");
            var light = Condition.apply(plan, 1, seed).report();
            ctx.assertTrue(light.breaches() == 1, "Danger 1 makes one breach: " + light);
        }
        ctx.assertTrue(scorchedTotal > 0, "Hull around breaches is scorched");
        ctx.assertTrue(lightsBefore == 0 || darkTotal > 0, "Some lights go out at danger 3");
        ctx.succeed();
    }

    @GameTest
    public void highDangerLeavesOutAnOptionalEndPiece(GameTestHelper ctx)
    {
        var archetype = AssemblerGameTest.scout(new Vec3i(32, 32, 32));
        int omitted = 0;
        for (long seed = 0; seed < 12; seed++)
        {
            var build = build(seed);
            var damaged = Condition.omitSection(build, archetype, 3, seed);
            ctx.assertTrue(Condition.omitSection(build, archetype, 0, seed) == build && Condition.omitSection(build, archetype, 1, seed) == build, "Low danger keeps every piece");
            if (damaged.placed().size() == build.placed().size())
            {
                ctx.assertTrue(build.placed().stream().noneMatch(p -> p.role().equals("wing")), "A build with a wing always loses one at danger 3");
                continue;
            }
            omitted++;
            var gone = build.placed().stream().filter(p -> !damaged.placed().contains(p) && !p.role().equals("cap")).toList();
            ctx.assertTrue(gone.size() == 1 && gone.getFirst().role().equals("wing"), "Only an optional end piece is left out: " + gone);
            ctx.assertTrue(gone.getFirst().cells().stream().allMatch(c -> damaged.blocks().get(c).isAir()), "Its blocks are cleared");
            ctx.assertTrue(Assembler.missingMachines(Archetype.Kind.SHIP, damaged.blocks()).isEmpty(), "Required machines are never in a left-out piece");
        }
        ctx.assertTrue(omitted > 0, "Some seeds have a wing to lose");
        ctx.succeed();
    }

    @GameTest
    public void sitesStockTheirMarkedLootSpots(GameTestHelper ctx)
    {
        var plan = Assembler.plan(build(2));
        var base = ctx.absolutePos(new BlockPos(1, 1, 1));
        var level = ctx.getLevel();
        plan.blocks().forEach((at, state) -> level.setBlock(base.offset(at), state, 18));
        var site = new CompoundTag();
        SitePoints.write(site, plan.points());
        var points = SitePoints.read(site);
        ctx.assertTrue(points.equals(plan.points()), "Points survive being saved with the site");
        int stocked = SitePoints.stock(level, base, points, "treasure_2", 7, "Test supplies");
        ctx.assertTrue(stocked == plan.points("loot").size(), "Every loot point's container is stocked: " + stocked);
        for (var loot : plan.points("loot"))
            ctx.assertTrue(level.getBlockEntity(base.offset(loot.pos())) instanceof RandomizableContainerBlockEntity c && c.getLootTable() != null, "The barrel at " + loot.pos() + " has a loot table");
        for (var at : plan.blocks().keySet())
            level.setBlock(base.offset(at), Blocks.AIR.defaultBlockState(), 18);
        ctx.succeed();
    }
}
