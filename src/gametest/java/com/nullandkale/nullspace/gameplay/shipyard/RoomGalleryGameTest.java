package com.nullandkale.nullspace.gameplay.shipyard;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class RoomGalleryGameTest
{
    @GameTest(maxTicks = 1200)
    public void everyRoomKitIsBuiltWithAnOpenDoorway(GameTestHelper ctx) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var level = server.overworld();
        var origin = RoomGallery.build(server);
        int built = 0;
        for (int row = 0; row < RoomGallery.USES.size(); row++)
            for (int column = 0; column < RoomGallery.COLUMNS.size(); column++)
            {
                var tier = com.nullandkale.nullspace.worldgen.shipgen.Spec.Tier.values()[RoomGallery.COLUMNS.get(column)[0]];
                var ship = com.nullandkale.nullspace.worldgen.shipgen.ShipGenerator.room(RoomGallery.USES.get(row), tier, RoomGallery.COLUMNS.get(column)[1]);
                if (ship == null)
                    continue;
                built++;
                var at = origin.offset(RoomGallery.MARGIN + column * RoomGallery.PITCH_X, 1, RoomGallery.MARGIN + row * RoomGallery.PITCH_Z);
                // The doorway on the north face is open at head height on the centre line.
                for (var e : ship.voxels().entrySet())
                {
                    var state = level.getBlockState(at.offset(e.getKey()));
                    if (state.is(net.minecraft.world.level.block.Blocks.CHEST) && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
                    {
                        var partner = level.getBlockState(at.offset(e.getKey()).relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)));
                        ctx.assertTrue(partner.is(net.minecraft.world.level.block.Blocks.CHEST) && partner.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) && partner.getValue(net.minecraft.world.level.block.ChestBlock.FACING) == state.getValue(net.minecraft.world.level.block.ChestBlock.FACING), "Double chest pairs up in " + RoomGallery.USES.get(row) + " " + tier + " at " + e.getKey());
                    }
                }
                var door = at.offset(ship.width() / 2, 2, 0);
                ctx.assertTrue(level.getBlockState(door).isAir() && level.getBlockState(door.above()).isAir(), "Doorway open: " + RoomGallery.USES.get(row) + " " + tier);
            }
        ctx.assertTrue(built > 30, "Most kits fit most sizes: " + built);
        ctx.succeed();
    }
}
