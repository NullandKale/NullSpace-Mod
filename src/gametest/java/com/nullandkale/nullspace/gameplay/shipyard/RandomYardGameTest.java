package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.CabinPaths;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.worldgen.stations.ShipStructure;

import java.util.Random;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

public final class RandomYardGameTest
{
    @GameTest(maxTicks = 1200)
    public void everyGalleryShipIsSealedAndWalkableFromTheGroundUpItsRamp(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var gallery = RandomYard.build(server, 7);
        var random = new Random(7);
        for (int index = 0; index < RandomYard.ROWS * RandomYard.COLUMNS; index++)
        {
            var spec = RandomYard.spec(index, random);
            var ship = ShipStructure.build(spec);
            var base = gallery.pad(index);
            var pad = new PadScanner.Pad(base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31);
            var at = new BlockPos(pad.minX() + (32 - ship.width()) / 2, pad.y() + 1, pad.minZ() + (32 - ship.depth()) / 2);
            long built = ship.blueprint().blocks().values().stream().filter(b -> b.is(com.nullandkale.nullspace.content.ModBlocks.ENGINE)).count();
            long placed = ship.blueprint().blocks().keySet().stream().filter(k -> level.getBlockState(at.offset(k)).is(com.nullandkale.nullspace.content.ModBlocks.ENGINE)).count();
            ctx.assertTrue(built >= 2 && placed == built, "Engines were built and placed: " + built + " built, " + placed + " standing on " + spec);
            var scan = HullScan.inspect(level, at.offset(ship.helm()), pad, true);
            ctx.assertTrue(scan.pressure().breathable(scan.boarding()), "Sealed and breathable: " + spec);
            var dock = ship.blueprint().docks().getFirst().above();
            for (int step = 0; step < dock.getY(); step++)
            {
                var stair = at.offset(dock.getX(), step, dock.getZ() - dock.getY() + step);
                ctx.assertTrue(level.getBlockState(stair).getBlock() instanceof net.minecraft.world.level.block.StairBlock, "Ramp step " + step + " is a stair: " + spec);
                ctx.assertTrue(level.getBlockState(stair.above()).isAir() && level.getBlockState(stair.above(2)).isAir(), "Ramp step " + step + " has head room: " + spec);
            }
            // Room doors are closed (and sealed, or the scan above would fail); a person opens them on the way.
            for (var block : scan.blocks())
                if (level.getBlockState(block).getBlock() instanceof net.minecraft.world.level.block.DoorBlock && level.getBlockState(block).getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER && !block.equals(at.offset(dock)))
                    com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors.setOpen(level, block, true);
            // The ship is a working vessel: powered doors and controls, a usable docking port, and engines with a clear exhaust.
            var record = com.nullandkale.nullspace.core.save.WorldStore.get(server).at(level, at.offset(ship.helm()));
            ctx.assertTrue(record != null, "The gallery registered the ship: " + spec);
            ctx.assertTrue(com.nullandkale.nullspace.gameplay.systems.ShipSystems.power(level, record, com.nullandkale.nullspace.model.power.PowerBus.System.CONTROLS) > 0, "Controls have power: " + spec);
            ctx.assertTrue(com.nullandkale.nullspace.gameplay.systems.ShipSystems.power(level, record, com.nullandkale.nullspace.model.power.PowerBus.System.DOORS) > 0, "Doors have power: " + spec);
            ctx.assertTrue(!com.nullandkale.nullspace.gameplay.docking.DockingPorts.ports(level, record).isEmpty(), "The docking port is usable: " + spec);
            int engines = 0;
            for (var entry : ship.blueprint().blocks().entrySet())
                if (entry.getValue().is(com.nullandkale.nullspace.content.ModBlocks.ENGINE))
                {
                    engines++;
                    var exhaust = at.offset(entry.getKey()).relative(entry.getValue().getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING));
                    ctx.assertTrue(level.getBlockState(exhaust).isAir(), "An engine has a clear exhaust: " + spec);
                }
            ctx.assertTrue(engines >= 2, "A ship has at least two engines: " + spec);
            ctx.assertTrue(CabinPaths.reaches(level, scan.boarding(), at.offset(ship.helm()).north(), scan.pressure()), "Boarding point walks to the bridge: " + spec);
        }
        ctx.succeed();
    }
}
