package com.nullandkale.nullspace.worldgen.blueprint;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.model.galaxy.JourneyPlan;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.ManufacturerStation;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;

/** P2: every generated ship, station and encounter has settled facing and connections. */
public final class BlueprintOrientationGameTest
{
    @GameTest(environment = "nullspace:ship_recall", maxTicks = 40)
    public void everyGeneratedBlueprintFacesItsRoomAndConnectsItsPanes(GameTestHelper ctx)
    {
        var plans = new LinkedHashMap<String, Map<BlockPos, BlockState>>();
        for (var preset : ShipBlueprints.PRESETS)
            plans.put(preset.id(), ShipBlueprints.blocks(preset));
        plans.put("waypoint", StationBlueprint.build(StationLayout.waypoint()).blocks());
        for (long seed = 1; seed <= 6; seed++)
            plans.put("station-" + seed, StationBlueprint.build(StationLayout.generate(seed)).blocks());
        plans.put("manufacturer-station", ManufacturerStation.build().blocks());
        for (int kind = 0; kind < JourneyPlan.KINDS; kind++)
            for (long seed = 0; seed < 6; seed++)
                plans.put("encounter-" + kind + "-" + seed, EncounterSites.plan(kind, seed).blocks());
        for (var preset : ShipBlueprints.PRESETS)
        {
            var raw = new LinkedHashMap<>(ShipBlueprints.rawBlocks(preset));
            var fixed = BlueprintOrientation.fix(raw);
            if (fixed.total() > 0)
                NullSpace.LOGGER.info("Blueprint {} settled: {}", preset.id(), fixed);
        }
        var unsettled = new ArrayList<String>();
        for (var plan : plans.entrySet())
        {
            var copy = new LinkedHashMap<>(plan.getValue());
            var again = BlueprintOrientation.fix(copy);
            if (again.total() > 0)
                unsettled.add(plan.getKey() + " " + again);
        }
        ctx.assertTrue(unsettled.isEmpty(), "Generated blueprints must already be settled (facing, beds, connections): " + unsettled);
        ctx.succeed();
    }
}
