package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;

public final class ShipGalleryGameTest
{
    @GameTest(maxTicks = 240)
    public void galleryShipsFailuresBlanksAndEdits(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var gallery = ShipGallery.ensure(server);
        var ironBase = gallery.base(8);
        for (int x = ironBase.getX() >> 4; x <= (ironBase.getX() + 11) >> 4; x++)
            for (int z = ironBase.getZ() >> 4; z <= (ironBase.getZ() + 11) >> 4; z++)
                level.setChunkForced(x, z, true);
        for (var plot : ShipGallery.PLOTS)
        {
            var base = gallery.base(plot.number());
            var pad = PadRegistry.read(server).stream().filter(p -> p.computer().equals(base.offset(-1, 1, 5))).findFirst().orElseThrow();
            PadRegistry.validate(level, pad);
            ctx.assertTrue(level.getBlockEntity(base.offset(1, 1, -3)) instanceof SignBlockEntity, "Every plot needs an outside label");
            if (plot.blank())
            {
                ctx.assertTrue(PadRegistry.inspectState(server, pad).state() == PadRegistry.State.READY, "Blank pad must be empty and ready: " + plot.number());
                continue;
            }
            String failure = "";
            try
            {
                ShipService.inspect(level, gallery.controller(plot.number()));
            }
            catch (IllegalArgumentException error)
            {
                failure = error.getMessage();
            }
            ctx.assertTrue(plot.ready() ? failure.isEmpty() : failure.toLowerCase(java.util.Locale.ROOT).contains(plot.expected()), "Unexpected ship validation at plot " + plot.number() + ": " + failure);
            if (!plot.ready())
            {
                var repair = base.offset(plot.number() == 11 ? 1 : plot.number() == 10 ? 2 : 5, plot.number() == 12 ? 36 : plot.number() == 9 ? 5 : 2, plot.number() == 9 ? 7 : 6);
                var original = level.getBlockState(repair);
                var replacement = plot.number() == 9 ? Blocks.STONE.defaultBlockState() : plot.number() == 10 ? ModBlocks.ENGINE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST) : Blocks.AIR.defaultBlockState();
                try
                {
                    level.setBlock(repair, replacement, 18);
                    ShipService.inspect(level, gallery.controller(plot.number()));
                }
                finally
                {
                    level.setBlock(repair, original, 18);
                }
            }
            // Entry stairs are captured, inside the footprint, and one ordinary step above the plaza.
            ctx.assertTrue(level.getBlockState(base.offset(5, 1, 2)).is(Blocks.STONE_STAIRS), "Ships must be walk-in builds");
            ctx.assertTrue(level.getBlockState(base.offset(5, 2, 3)).getBlock() instanceof DoorBlock, "Entry door must meet the stairs");
        }
        // A redstone-operated outer iron door must actually open and then close again.
        var iron = gallery.base(8);
        var button = iron.offset(4, 3, 2);
        var state = level.getBlockState(button);
        ((ButtonBlock) state.getBlock()).press(state, level, button, null);
        ctx.assertTrue(level.getBlockState(iron.offset(5, 2, 3)).getValue(DoorBlock.OPEN), "Exterior button must operate the iron door");
        // The airlock must protect the controller's room while its exterior door is open.
        var airlock = gallery.base(4);
        var outer = airlock.offset(5, 2, 3);
        ((DoorBlock) level.getBlockState(outer).getBlock()).setOpen(null, level, level.getBlockState(outer), outer, true);
        var pressure = new HullScan.LivePressure(level, new HullScan.Bounds(airlock.above(), airlock.offset(11, 32, 11)));
        ctx.assertTrue(!pressure.breathable(airlock.offset(5, 3, 4)) && pressure.breathable(airlock.offset(7, 3, 7)), "Gallery airlock must isolate its real cabin");
        ((DoorBlock) level.getBlockState(outer).getBlock()).setOpen(null, level, level.getBlockState(outer), outer, false);
        ctx.runAfterDelay(35, () ->
        {
            ctx.assertTrue(!level.getBlockState(iron.offset(5, 2, 3)).getValue(DoorBlock.OPEN), "Iron entry must automatically close after its button releases");
            ShipService.inspect(level, gallery.controller(8));
            var edited = gallery.base(1).offset(3, 3, 9);
            var original = level.getBlockState(edited);
            var missing = gallery.controller(2);
            var controller = level.getBlockState(missing);
            var blank = gallery.base(13).above();
            var before = level.getBlockState(blank);
            var supplies = (net.minecraft.world.Container) level.getBlockEntity(gallery.origin().offset(18, 1, 4));
            var item = supplies.removeItemNoUpdate(0);
            try
            {
                level.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
                level.setBlock(missing, Blocks.AIR.defaultBlockState(), 18);
                level.setBlock(blank, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
                ctx.assertTrue(ShipGallery.ensure(server).equals(gallery), "Repeated generation must reuse the gallery");
                ctx.assertTrue(level.getBlockState(edited).is(Blocks.GOLD_BLOCK) && level.getBlockState(missing).isAir() && level.getBlockState(blank).is(Blocks.DIAMOND_BLOCK), "Player edits, departed controllers, and custom blank-pad builds must stay intact");
                ctx.assertTrue(supplies.getItem(0).isEmpty(), "Returning to the lab must not refill player-edited barrels");
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
            finally
            {
                level.setBlock(edited, original, 18);
                level.setBlock(missing, controller, 18);
                level.setBlock(blank, before, 18);
                supplies.setItem(0, item);
            }
            ctx.succeed();
        });
    }
}
