package com.nullandkale.nullspace.gameplay.travel;

import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * A pressure plate or button that a lock holds down comes up once the lock ends. A ship's area refuses block changes while it jumps, moves or docks, and
 * a plate's own tick reschedules only while something presses it, so a plate stepped off during a jump stayed down for good, with its door open (0.9.0).
 */
public final class LockedPlatesGameTest
{
    @GameTest(maxTicks = 200)
    public void aPlateAndAButtonHeldDownByALockComeUpAfterIt(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var plate = ctx.absolutePos(new BlockPos(1, 1, 1));
        var button = ctx.absolutePos(new BlockPos(3, 1, 1));
        level.setBlock(plate.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(button.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(plate, Blocks.STONE_PRESSURE_PLATE.defaultBlockState().setValue(PressurePlateBlock.POWERED, true), 3);
        level.setBlock(button, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR).setValue(ButtonBlock.POWERED, true), 3);
        // As a press leaves them: each comes up on its own tick once nothing holds it down.
        level.scheduleTick(plate, Blocks.STONE_PRESSURE_PLATE, 20);
        level.scheduleTick(button, Blocks.STONE_BUTTON, 20);
        // The same lock a ship's move leaves on its area until it is resolved, over just these two blocks.
        String dimension = level.dimension().identifier().toString(), id = "locked-plates-" + UUID.randomUUID();
        UnresolvedJournals.put(server, new UnresolvedJournals.Entry(id, 0, "MOVE", "locked-plates-test", List.of(), dimension, dimension, plate.offset(-1, -1, -1), button.offset(1, 1, 1), BlockPos.ZERO, UnresolvedJournals.State.SET_ASIDE, "test lock", server.getTickCount(), null));
        ctx.runAfterDelay(40, () ->
        {
            boolean held = powered(level, plate) && powered(level, button);
            UnresolvedJournals.remove(server, id);
            ctx.assertTrue(held, "The lock holds the plate and the button down past their own release tick");
            ctx.runAfterDelay(30, () ->
            {
                ctx.assertTrue(!powered(level, plate), "The plate comes up once the lock ends: " + level.getBlockState(plate));
                ctx.assertTrue(!powered(level, button), "The button comes up once the lock ends: " + level.getBlockState(button));
                ctx.succeed();
            });
        });
    }

    private static boolean powered(ServerLevel level, BlockPos at)
    {
        return level.getBlockState(at).getSignal(level, at, Direction.UP) > 0;
    }
}
