package com.nullandkale.nullspace.worldgen.condition;

import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.EncounterKind;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.*;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Feature-level checks of a found wreck, from the player's side: can you walk from the dock to loot, and do the rooms the
 * wreck left whole keep their air while the torn ones vent. The class-level tests check the pass's parts; these check its promises.
 */
public final class DerelictScenarioGameTest
{
    private record Vessel(int kind, long seed, StationBlueprint.Plan whole, StationPlan layout, int dz)
    {}

    private static List<Vessel> vessels(int perKind, long salt)
    {
        var out = new ArrayList<Vessel>();
        var random = new Random(salt);
        for (var kind : List.of(EncounterKind.DAMAGED_FREIGHTER, EncounterKind.SALVAGE_SHIP, EncounterKind.ABANDONED_STATION))
            for (int i = 0; i < perKind; i++)
            {
                long seed = random.nextLong();
                var whole = EncounterSites.plan(kind.id, seed);
                if (kind == EncounterKind.ABANDONED_STATION)
                    out.add(new Vessel(kind.id, seed, whole, StationPlanner.generate(EncounterStations.encounter(seed)), 0));
                else
                    out.add(new Vessel(kind.id, seed, whole, ShipBlueprints.layout(ShipBlueprints.get(EncounterSites.design(kind.id, seed))), ShipPlanner.RAMP));
            }
        return out;
    }

    // ---- 1. A wreck is playable: a walker from the dock reaches loot ----

    private static BlockState at(Map<BlockPos, BlockState> blocks, BlockPos p)
    {
        var s = blocks.get(p);
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    /**
     * A cell a body walks through: nothing solid in it. A door always counts: a closed one opens on its plate, and a jammed one
     * (its plates taken) is broken with a pickaxe, which the design allows. Rubble, fallen roof and holes in a deck are not walked through.
     */
    private static boolean passable(Map<BlockPos, BlockState> blocks, BlockPos p)
    {
        var s = at(blocks, p);
        if (s.getBlock() instanceof DoorBlock)
            return true;
        return s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    private static boolean standable(Map<BlockPos, BlockState> blocks, BlockPos p)
    {
        var here = at(blocks, p);
        // Standing on a stair block: the body is the two cells above it.
        if (here.getBlock() instanceof StairBlock)
            return passable(blocks, p.above()) && passable(blocks, p.above(2));
        return passable(blocks, p) && passable(blocks, p.above()) && !passable(blocks, p.below());
    }

    /** Every cell a walker can reach from {@code start}: steps of one up, drops of up to three, doors that plates open. */
    private static Set<BlockPos> reach(Map<BlockPos, BlockState> blocks, BlockPos start)
    {
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        if (standable(blocks, start))
        {
            seen.add(start);
            queue.add(start);
        }
        while (!queue.isEmpty() && seen.size() < 600_000)
        {
            var p = queue.poll();
            for (var d : Direction.Plane.HORIZONTAL)
            {
                var n = p.relative(d);
                for (int dy : new int[]{1, 0, -1, -2, -3})
                {
                    var cand = n.above(dy);
                    if (seen.contains(cand) || !standable(blocks, cand))
                        continue;
                    // The way through: a step up needs headroom over the start; a stair is walked onto; anything else needs the column beside open.
                    boolean clear;
                    if (dy > 0)
                        clear = passable(blocks, p.above(2));
                    else if (at(blocks, cand).getBlock() instanceof StairBlock)
                        clear = true;
                    else
                        clear = passable(blocks, n) && passable(blocks, n.above());
                    if (!clear)
                        continue;
                    seen.add(cand);
                    queue.add(cand);
                    break;
                }
            }
        }
        return seen;
    }

    private static BlockPos dockStart(StationBlueprint.Plan plan)
    {
        var dock = plan.docks().getFirst();
        var facing = at(plan.blocks(), dock).getValue(HorizontalDirectionalBlock.FACING);
        return dock.above().relative(facing.getOpposite());
    }

    private static boolean reachesAChest(Map<BlockPos, BlockState> blocks, Set<BlockPos> walked, List<BlockPos> chests)
    {
        for (var chest : chests)
            for (var d : Direction.Plane.HORIZONTAL)
                for (int dy = 0; dy >= -1; dy--)
                    if (walked.contains(chest.relative(d).above(dy)))
                        return true;
        return false;
    }

    @GameTest(maxTicks = 400)
    public void fromTheDockAWalkerAlwaysReachesLoot(GameTestHelper ctx)
    {
        int vesselsChecked = 0, reached = 0;
        for (var v : vessels(12, 0x5A1C0DEL))
        {
            var chests = v.whole().points("roomloot").stream().map(p -> p.pos()).toList();
            // The walker itself is checked on the whole vessel first: if it cannot find loot there, it is the walker that is wrong.
            var whole = reach(v.whole().blocks(), dockStart(v.whole()));
            ctx.assertTrue(reachesAChest(v.whole().blocks(), whole, chests), "kind " + v.kind() + " seed " + v.seed() + ": the walker finds loot in the whole vessel (" + whole.size() + " cells)");
            for (int danger = 0; danger <= 3; danger++)
            {
                var wreck = DerelictDamage.apply(v.layout(), v.dz(), v.whole(), v.seed(), danger).plan();
                var walked = reach(wreck.blocks(), dockStart(wreck));
                vesselsChecked++;
                boolean ok = reachesAChest(wreck.blocks(), walked, chests);
                reached += ok ? 1 : 0;
                ctx.assertTrue(ok, "kind " + v.kind() + " seed " + v.seed() + " danger " + danger + ": a walker from the dock reaches no loot chest (" + walked.size() + " cells walked)");
            }
        }
        ctx.assertTrue(vesselsChecked == reached && vesselsChecked >= 100, "Every wreck is playable: " + reached + " of " + vesselsChecked);
        ctx.succeed();
    }

    // ---- 2. The bubbles: torn rooms vent, whole rooms keep their air ----

    private static Set<BlockPos> airCells(ServerLevel level, StationPlan.Room room, int dz, BlockPos base)
    {
        var i = room.interior().shift(0, dz);
        var out = new HashSet<BlockPos>();
        for (int x = i.x0(); x <= i.x1(); x++)
            for (int z = i.z0(); z <= i.z1(); z++)
            {
                var p = base.offset(x, i.y0(), z);
                if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir())
                    out.add(p);
            }
        return out;
    }

    private static void place(ServerLevel level, BlockPos base, StationBlueprint.Plan plan)
    {
        plan.blocks().forEach((at, state) -> level.setBlock(base.offset(at), state, 18));
    }

    private static void clear(ServerLevel level, BlockPos base, StationBlueprint.Plan plan)
    {
        plan.blocks().keySet().forEach(at -> level.setBlock(base.offset(at), Blocks.AIR.defaultBlockState(), 18));
    }

    private static HullScan.Bounds bounds(BlockPos base, StationBlueprint.Plan plan)
    {
        int x = plan.blocks().keySet().stream().mapToInt(BlockPos::getX).max().orElse(0), y = plan.blocks().keySet().stream().mapToInt(BlockPos::getY).max().orElse(0), z = plan.blocks().keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0);
        return new HullScan.Bounds(base, base.offset(x, y, z));
    }

    @GameTest(environment = "nullspace:derelict_scenarios", maxTicks = 2000)
    public void roomsTheWreckLeftWholeKeepTheirAirAndTornRoomsVent(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var level = server.getLevel(TravelService.SPACE);
        int slot = 0;
        int tornSeen = 0;
        // A breach wreck of each kind, at danger 3, forced to tell the breach story so there is always a hull to tear.
        for (var v : vessels(1, 0xB0B1E5L))
        {
            var base = new BlockPos(520_000 + slot * 4_000, 96, 520_000);
            slot++;
            var whole = v.whole();
            var wreck = DerelictDamage.apply(v.layout(), v.dz(), whole, v.seed(), 3, DerelictHistory.BREACH);
            var report = wreck.report();
            var dockRooms = new HashSet<String>();
            for (var berth : v.layout().berths())
                dockRooms.add(berth.dock());
            // The whole vessel first: this is what each room's air was before the wreck, the bubble to keep.
            place(level, base, whole);
            var before = new HullScan.LivePressure(level, bounds(base, whole));
            var sealedBefore = new HashMap<String, Boolean>();
            for (var room : v.layout().rooms())
            {
                var cells = airCells(level, room, v.dz(), base);
                sealedBefore.put(room.id(), !cells.isEmpty() && cells.stream().allMatch(before::breathable));
            }
            clear(level, base, whole);
            place(level, base, wreck.plan());
            var after = new HullScan.LivePressure(level, bounds(base, wreck.plan()));
            String label = "kind " + v.kind() + " seed " + v.seed();
            boolean anyTorn = false;
            for (var room : v.layout().rooms())
            {
                var cells = airCells(level, room, v.dz(), base);
                if (cells.isEmpty())
                    continue;
                boolean intact = !report.damaged().contains(room.id());
                long breathing = cells.stream().filter(after::breathable).count();
                if (intact && sealedBefore.get(room.id()))
                    ctx.assertTrue(breathing == cells.size(), label + ": the whole room " + room.id() + " (" + room.use() + ") keeps its air, " + breathing + " of " + cells.size() + " cells");
                if (!intact && breathing == 0)
                    anyTorn = true;
            }
            ctx.assertTrue(dockRooms.stream().noneMatch(id -> report.damaged().contains(id)), label + ": the dock room is never damaged");
            if (report.breaches() > 0)
            {
                ctx.assertTrue(anyTorn, label + ": " + report.breaches() + " breach(es) tore the hull, so some damaged room has no air");
                tornSeen++;
            }
            clear(level, base, wreck.plan());
        }
        ctx.assertTrue(tornSeen >= 2, "The scenario saw torn rooms vent: " + tornSeen);
        ctx.succeed();
    }
}
