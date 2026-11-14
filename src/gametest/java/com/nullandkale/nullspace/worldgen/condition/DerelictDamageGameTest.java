package com.nullandkale.nullspace.worldgen.condition;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.model.galaxy.EncounterKind;
import com.nullandkale.nullspace.worldgen.blueprint.GenerationPalette;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.*;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** The derelict damage pass on real generated ships and stations: what it spares, how much it wrecks and what each history does. */
public final class DerelictDamageGameTest
{
    private static final int SEEDS = 24;
    private static final double[] SHARE = {1 / 3.0, 1 / 2.0, 2 / 3.0, 4 / 5.0};

    /** One found vessel: the whole plan, its layout and the shift between them. */
    private record Vessel(int kind, long seed, StationBlueprint.Plan whole, StationPlan layout, int dz)
    {}

    private static List<Vessel> vessels(int perKind)
    {
        var out = new ArrayList<Vessel>();
        var random = new Random(0xDA3A6E5L);
        for (var kind : EncounterKind.values())
        {
            if (kind == EncounterKind.GROWING_STATION || kind == EncounterKind.DRIFTING_CARGO)
                continue;
            for (int i = 0; i < perKind; i++)
            {
                long seed = random.nextLong();
                var whole = EncounterSites.plan(kind.id, seed);
                if (kind == EncounterKind.ABANDONED_STATION)
                    out.add(new Vessel(kind.id, seed, whole, StationPlanner.generate(EncounterStations.encounter(seed)), 0));
                else
                    out.add(new Vessel(kind.id, seed, whole, ShipBlueprints.layout(ShipBlueprints.get(EncounterSites.design(kind.id, seed))), ShipPlanner.RAMP));
            }
        }
        return out;
    }

    private static DerelictDamage.Result wreck(Vessel v, int danger)
    {
        return DerelictDamage.apply(v.layout(), v.dz(), v.whole(), v.seed(), danger);
    }

    private static BlockState at(Map<BlockPos, BlockState> blocks, BlockPos p)
    {
        var s = blocks.get(p);
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    private static int extent(Map<BlockPos, BlockState> blocks, boolean x)
    {
        return blocks.keySet().stream().mapToInt(p -> x ? p.getX() : p.getZ()).max().orElse(0);
    }

    @GameTest(maxTicks = 200)
    public void theDockTheControllerAndTheLootChestsAreNeverTouched(GameTestHelper ctx)
    {
        for (var v : vessels(SEEDS / 2))
            for (int danger = 0; danger <= 3; danger++)
            {
                var after = wreck(v, danger).plan();
                String label = "kind " + v.kind() + " seed " + v.seed() + " danger " + danger;
                ctx.assertTrue(after.core().equals(v.whole().core()) && at(after.blocks(), after.core()).getBlock() == at(v.whole().blocks(), v.whole().core()).getBlock(), label + ": the controller survives");
                ctx.assertTrue(after.docks().equals(v.whole().docks()), label + ": the docks are listed as before");
                for (var dock : v.whole().docks())
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++)
                            for (int dy = -1; dy <= 3; dy++)
                            {
                                var p = dock.offset(dx, dy, dz);
                                ctx.assertTrue(at(after.blocks(), p).getBlock() == at(v.whole().blocks(), p).getBlock(), label + ": the port at " + dock + " is intact at " + p);
                            }
                for (var point : v.whole().points("roomloot"))
                    ctx.assertTrue(at(after.blocks(), point.pos()).getBlock() == at(v.whole().blocks(), point.pos()).getBlock(), label + ": the loot chest " + point.argument() + " is kept");
                ctx.assertTrue(after.points("roomloot").equals(v.whole().points("roomloot")), label + ": every loot point is kept");
                ctx.assertTrue(extent(after.blocks(), true) == extent(v.whole().blocks(), true) && extent(after.blocks(), false) == extent(v.whole().blocks(), false), label + ": the vessel keeps its extent");
                ctx.assertTrue(GenerationPalette.violations(after.blocks()).isEmpty(), label + ": a wreck is still built of cheap blocks");
            }
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void dangerSetsTheShareOfRoomsThatAreDamagedAndTheWreckGrowsWithIt(GameTestHelper ctx)
    {
        var all = vessels(SEEDS);
        var removed = new double[4];
        for (var v : all)
        {
            int eligible = (int) v.layout().rooms().stream().filter(r -> r.use() != Use.DOCK && r.use() != Use.ARRIVAL && v.layout().berths().stream().noneMatch(b -> b.dock().equals(r.id()))).count();
            for (int danger = 0; danger <= 3; danger++)
            {
                var report = wreck(v, danger).report();
                int expected = Math.min(eligible, Math.max(1, (int) Math.round(SHARE[danger] * v.layout().rooms().size())));
                ctx.assertTrue(report.damaged().size() == expected, "kind " + v.kind() + " seed " + v.seed() + " danger " + danger + " damages " + report.damaged().size() + " rooms, expected " + expected);
                removed[danger] += report.removed();
            }
        }
        for (int danger = 1; danger <= 3; danger++)
            ctx.assertTrue(removed[danger] > removed[danger - 1], "More of the vessel is destroyed at danger " + danger + ": " + Arrays.toString(removed));
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aPowerFailureLeavesNoGeneratorOrBatteryAndDarkRooms(GameTestHelper ctx)
    {
        int seen = 0;
        for (var v : vessels(SEEDS))
        {
            if (DerelictHistory.of(v.seed(), 3) != DerelictHistory.POWER_FAILURE)
                continue;
            var after = wreck(v, 3).plan();
            long before = v.whole().blocks().values().stream().filter(s -> s.is(ModBlocks.GENERATOR) || s.is(ModBlocks.BATTERY)).count();
            long left = after.blocks().values().stream().filter(s -> s.is(ModBlocks.GENERATOR) || s.is(ModBlocks.BATTERY)).count();
            ctx.assertTrue(before > 0 && left == 0, "kind " + v.kind() + " seed " + v.seed() + ": " + before + " power blocks before, " + left + " after");
            long lightsBefore = v.whole().blocks().values().stream().filter(s -> s.is(Blocks.WALL_TORCH) || s.is(Blocks.TORCH) || s.is(Blocks.LANTERN)).count();
            long lightsAfter = after.blocks().values().stream().filter(s -> s.is(Blocks.WALL_TORCH) || s.is(Blocks.TORCH) || s.is(Blocks.LANTERN)).count();
            ctx.assertTrue(lightsAfter < lightsBefore, "kind " + v.kind() + " seed " + v.seed() + ": lights go out, " + lightsBefore + " to " + lightsAfter);
            seen++;
        }
        ctx.assertTrue(seen >= 3, "The survey covers power failures: " + seen);
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aBreachTearsTheHullOpenAndEvacuationDoesNot(GameTestHelper ctx)
    {
        int breached = 0, wounds = 0, quiet = 0;
        for (var v : vessels(SEEDS))
        {
            var rough = wreck(v, 3);
            if (rough.report().history() == DerelictHistory.BREACH)
            {
                wounds++;
                breached += rough.report().breaches() > 0 ? 1 : 0;
                ctx.assertTrue(rough.report().removed() > 0, "kind " + v.kind() + " seed " + v.seed() + ": a breach removes blocks");
            }
            var calm = wreck(v, 1);
            if (calm.report().history() == DerelictHistory.EVACUATED || calm.report().history() == DerelictHistory.INTERRUPTED_WORK)
            {
                quiet++;
                ctx.assertTrue(calm.report().breaches() == 0, "kind " + v.kind() + " seed " + v.seed() + ": nothing tears the hull at danger 1");
            }
        }
        ctx.assertTrue(wounds >= 3 && breached * 5 >= wounds * 4, "Breaches open the hull: " + breached + " of " + wounds);
        ctx.assertTrue(quiet >= 3, "The survey covers calm wrecks: " + quiet);
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aRoomThatIsNotDamagedKeepsItsFurnishings(GameTestHelper ctx)
    {
        for (var v : vessels(SEEDS / 2))
        {
            var result = wreck(v, 1);
            if (result.report().history() == DerelictHistory.POWER_FAILURE)
                continue;
            var after = result.plan();
            var dockRooms = new HashSet<String>();
            for (var berth : v.layout().berths())
                dockRooms.add(berth.dock());
            for (var room : v.layout().rooms())
            {
                if (result.report().damaged().contains(room.id()) || dockRooms.contains(room.id()))
                    continue;
                var i = room.interior().shift(0, v.dz());
                for (int x = i.x0(); x <= i.x1(); x++)
                    for (int z = i.z0(); z <= i.z1(); z++)
                        for (int y = i.y0(); y <= i.y1(); y++)
                        {
                            var p = new BlockPos(x, y, z);
                            var was = at(v.whole().blocks(), p);
                            // A jammed door takes its plates; nothing else in a whole room changes.
                            if (was.getBlock() instanceof BasePressurePlateBlock || was.getBlock() instanceof DoorBlock)
                                continue;
                            ctx.assertTrue(at(after.blocks(), p).equals(was), "kind " + v.kind() + " seed " + v.seed() + ": whole room " + room.id() + " lost " + was.getBlock() + " at " + p);
                        }
            }
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void framesStandsAndAnimalsFollowTheWreck(GameTestHelper ctx)
    {
        int emptied = 0;
        for (var v : vessels(SEEDS))
            for (int danger = 0; danger <= 3; danger++)
            {
                var after = wreck(v, danger).plan();
                for (var point : after.points())
                {
                    var args = point.argument().split(" ");
                    switch (point.name())
                    {
                        case "frame" ->
                        {
                            var face = net.minecraft.core.Direction.byName(args[1]);
                            ctx.assertTrue(!at(after.blocks(), point.pos().relative(face.getOpposite())).isAir(), "A frame still hangs on a wall at " + point.pos());
                            if (args[0].equals("minecraft:air"))
                                emptied++;
                        }
                        case "stand", "animal" -> ctx.assertTrue(!at(after.blocks(), point.pos().below()).isAir(), point.name() + " still has a floor at " + point.pos());
                        default ->
                            {
                            }
                    }
                }
                ctx.assertTrue(after.points().size() <= v.whole().points().size(), "A wreck never gains entities");
            }
        ctx.assertTrue(emptied > 0, "Some frames are stripped empty");
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aChosenHistoryOverridesTheDrawnOne(GameTestHelper ctx)
    {
        for (var v : vessels(4))
            for (var history : DerelictHistory.values())
            {
                var result = DerelictDamage.apply(v.layout(), v.dz(), v.whole(), v.seed(), 0, history);
                ctx.assertTrue(result.report().history() == history, "kind " + v.kind() + " seed " + v.seed() + ": the wreck tells " + history + ", not " + result.report().history());
                if (history == DerelictHistory.POWER_FAILURE)
                    ctx.assertTrue(result.plan().blocks().values().stream().noneMatch(s -> s.is(ModBlocks.GENERATOR) || s.is(ModBlocks.BATTERY)), "A chosen power failure strips the power plant even at danger 0");
            }
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void theSameSeedAndDangerAlwaysGiveTheSameWreck(GameTestHelper ctx)
    {
        for (var v : vessels(4))
        {
            var a = wreck(v, 3).plan();
            var b = wreck(v, 3).plan();
            ctx.assertTrue(a.blocks().equals(b.blocks()) && a.points().equals(b.points()), "kind " + v.kind() + " seed " + v.seed() + " wrecks the same way twice");
            ctx.assertTrue(!wreck(v, 0).plan().blocks().equals(a.blocks()), "danger changes the wreck");
        }
        ctx.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aFoundVesselIsBuiltWreckedAndAWholeOneIsNot(GameTestHelper ctx)
    {
        for (var v : vessels(4))
        {
            var found = EncounterSites.plan(v.kind(), v.seed(), 3);
            ctx.assertTrue(!found.blocks().equals(v.whole().blocks()), "kind " + v.kind() + ": a found vessel at danger 3 is wrecked");
            ctx.assertTrue(EncounterSites.plan(v.kind(), v.seed(), -1).blocks().equals(v.whole().blocks()), "kind " + v.kind() + ": no danger gives the whole vessel");
        }
        for (int kind : new int[]{EncounterKind.GROWING_STATION.id, EncounterKind.DRIFTING_CARGO.id})
            ctx.assertTrue(EncounterSites.plan(kind, 5, 3).blocks().equals(EncounterSites.plan(kind, 5).blocks()), "The growing station and the cargo pod are not wrecked");
        ctx.succeed();
    }
}
