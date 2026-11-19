package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Opt-in diagnostic: builds a standard ring and large arms hab and logs where the time goes (see {@code HAB GALLERY} in the log): planning, building the blocks,
 * loading the chunks, filling the ground, digging the rooms and placing the blocks. It passes when the gallery finishes; the numbers are for
 * the person tuning it.
 */
public final class HabGalleryTimingGameTest
{
    @GameTest(maxTicks = 600000)
    public void oneHabOfEachSizeIsBuiltAndTheTimeEachPhaseTookIsLogged(GameTestHelper ctx)
    {
        if (!Boolean.getBoolean("nullspace.perf.habGallery"))
        {
            ctx.succeed();
            return;
        }
        var server = ctx.getLevel().getServer();
        var job = HabGallery.start(server, StationSpec.Size.STANDARD, List.of(new HabGallery.Entry(Maker.HALCYON, HabPlanner.Form.RING, StationSpec.Size.STANDARD), new HabGallery.Entry(Maker.RIVET, HabPlanner.Form.ARMS, StationSpec.Size.LARGE)), false);
        var tick = new Runnable[1];
        long[] started = {System.nanoTime()};
        tick[0] = () ->
        {
            long begun = System.nanoTime();
            boolean finished = job.step(HabGallery.PER_TICK);
            com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB GALLERY tick {}: {} in {} ms", ctx.getTick(), job.progress(), (System.nanoTime() - begun) / 1_000_000);
            if (!finished)
            {
                ctx.runAfterDelay(1, () -> tick[0].run());
                return;
            }
            com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB GALLERY done in {} ms", (System.nanoTime() - started[0]) / 1_000_000);
            ctx.succeed();
        };
        ctx.runAfterDelay(1, tick[0]);
    }
}
