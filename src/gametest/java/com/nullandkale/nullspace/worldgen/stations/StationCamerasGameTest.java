package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

public final class StationCamerasGameTest
{
    @GameTest(maxTicks = 200)
    public void reachableBerthsHaveOutwardCamerasAndEveryCameraIsMountedInsideTheStation(GameTestHelper ctx)
    {
        for (var maker : Maker.values())
            for (var size : StationSpec.Size.values())
                for (var purpose : new StationSpec.Purpose[]{StationSpec.Purpose.HARBOR, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY})
                {
                    var spec = new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, 11);
                    var plan = StationPlanner.generate(spec);
                    var blueprint = StationStructure.build(plan);
                    var cameras = blueprint.blocks().entrySet().stream().filter(e -> e.getValue().is(ModBlocks.CAMERA)).toList();
                    long reachable = plan.berths().stream().filter(b -> inside(b.port().above(2).relative(b.facing()))).count();
                    ctx.assertTrue(cameras.size() >= reachable, spec.description() + ": a camera over each of " + reachable + " berths inside the station's box, found " + cameras.size());
                    for (var berth : plan.berths())
                    {
                        var over = berth.port().above(2).relative(berth.facing());
                        if (!inside(over))
                            continue;
                        ctx.assertTrue(blueprint.blocks().get(over) != null && blueprint.blocks().get(over).is(ModBlocks.CAMERA) && blueprint.blocks().get(over).getValue(HorizontalDirectionalBlock.FACING) == berth.facing(), spec.description() + ": the camera over " + berth.port() + " looks out of the port");
                    }
                    ctx.assertTrue(cameras.stream().allMatch(c -> inside(c.getKey())), spec.description() + ": every camera is inside the 128 by 32 by 128 box a station is built in");
                    for (var camera : cameras)
                    {
                        var behind = camera.getKey().relative(camera.getValue().getValue(HorizontalDirectionalBlock.FACING).getOpposite());
                        ctx.assertTrue(blueprint.blocks().containsKey(behind) && !blueprint.blocks().get(behind).isAir(), spec.description() + ": the camera at " + camera.getKey() + " is mounted on something: " + behind);
                    }
                }
        ctx.succeed();
    }

    private static boolean inside(BlockPos at)
    {
        return at.getX() >= 0 && at.getZ() >= 0 && at.getY() >= 0 && at.getX() < 128 && at.getZ() < 128 && at.getY() < 32;
    }
}
