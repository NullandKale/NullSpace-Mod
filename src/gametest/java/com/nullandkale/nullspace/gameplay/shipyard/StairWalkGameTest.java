package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.worldgen.shipgen.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public final class StairWalkGameTest
{
    @GameTest(maxTicks = 600)
    public void aPlayerWalksUpEveryFlightWithoutJumping(GameTestHelper ctx)
    {
        var level = ctx.getLevel().getServer().overworld();
        int tested = 0;
        for (var spec : new Spec[]{new Spec(Spec.Maker.BASTION, Spec.Shape.SPINE, Spec.Tier.MEDIUM, Spec.Role.SCOUT, 1), new Spec(Spec.Maker.HALCYON, Spec.Shape.SPINE, Spec.Tier.LARGE, Spec.Role.SCOUT, 2)})
        {
            var ship = ShipGenerator.build(spec);
            var at = new BlockPos(3000 + tested * 64, 150, 3000);
            for (int dx = -2; dx < 40; dx++)
                for (int dy = -1; dy < 30; dy++)
                    for (int dz = -2; dz < 40; dz++)
                        level.setBlock(at.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
            ShipBlocks.blocks(ship).forEach((pos, state) -> level.setBlock(at.offset(pos), state, 18));
            BlockPos foot = null;
            for (var e : ship.voxels().entrySet())
                if (e.getValue().kind() == Kind.STAIR && e.getValue().facing() == Direction.NORTH && e.getKey().getY() >= 1 && ship.kind(e.getKey().south()) != Kind.STAIR && (foot == null || e.getKey().getY() < foot.getY()))
                    foot = e.getKey();
            if (foot == null)
                continue;
            tested++;
            var player = ctx.makeMockServerPlayerInLevel();
            // Stand on the landing in front of the lowest stair and walk straight up the lane.
            var start = at.offset(foot).south();
            player.teleportTo(level, start.getX() + 0.5, start.getY(), start.getZ() + 0.5, java.util.Set.of(), 180, 0, true);
            double laneX = start.getX() + 0.5;
            var trace = new StringBuilder("foot " + foot + " laneX " + laneX + " at " + at + " p0 " + player.position() + "; ");
            for (int tick = 0; tick < 200; tick++)
            {
                player.move(MoverType.SELF, new Vec3(Math.clamp(laneX - player.getX(), -0.05, 0.05), -0.08, -0.2));
                if (tick % 10 == 0)
                    trace.append(String.format("(%.2f,%.2f,%.2f) ", player.getX(), player.getY(), player.getZ()));
            }
            for (int k = 0; k < 6; k++)
                trace.append(" | z-").append(k).append(": ").append(level.getBlockState(at.offset(foot).north(k)).getBlock().getDescriptionId()).append("/").append(level.getBlockState(at.offset(foot).north(k).above()).getBlock().getDescriptionId());
            // The upper deck stands one pitch above the lower.
            ctx.assertTrue(player.getY() >= start.getY() + 3.9, trace + " Walked up without jumping: " + spec + " ended at y " + player.getY() + " from " + start.getY() + " z " + player.getZ());
        }
        ctx.assertTrue(tested > 0, "Found a flight");
        ctx.succeed();
    }

    @GameTest(maxTicks = 600)
    public void aPlayerWalksUpEveryStationTileStairWithoutJumping(GameTestHelper ctx)
    {
        var level = ctx.getLevel().getServer().overworld();
        int tested = 0;
        for (var piece : com.nullandkale.nullspace.worldgen.stations.StationPieces.ALL)
        {
            if (piece.windows() != 1 || piece.role() == com.nullandkale.nullspace.worldgen.stations.StationLayout.Role.CORRIDOR && !piece.id().equals("tunnel_cross"))
                continue;
            var at = new BlockPos(3300 + tested * 24, 150, 3300);
            for (int dx = -2; dx < 20; dx++)
                for (int dy = -1; dy < 20; dy++)
                    for (int dz = -2; dz < 20; dz++)
                        level.setBlock(at.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
            var plan = com.nullandkale.nullspace.worldgen.stations.StationBlueprint.build(new com.nullandkale.nullspace.worldgen.stations.StationLayout.Layout(java.util.List.of(new com.nullandkale.nullspace.worldgen.stations.StationLayout.Room("t", piece.id(), 0, 0, 0)), java.util.List.of()));
            plan.blocks().forEach((pos, state) -> level.setBlock(at.offset(pos), state, 18));
            tested++;
            // Both flights: the landing in front of each, then straight along the wall.
            for (int z : new int[]{14, 1})
            {
                var player = ctx.makeMockServerPlayerInLevel();
                var start = at.offset(4, 2, z);
                player.teleportTo(level, start.getX() + 0.5, start.getY(), start.getZ() + 0.5, java.util.Set.of(), 270, 0, true);
                for (int tick = 0; tick < 200; tick++)
                    player.move(MoverType.SELF, new Vec3(0.2, -0.08, Math.clamp(start.getZ() + 0.5 - player.getZ(), -0.05, 0.05)));
                ctx.assertTrue(player.getY() >= start.getY() + 6.9 && player.getX() > at.getX() + 11.5, "Walked up the station stair without jumping: " + piece.id() + " flight at z " + z + " ended at " + player.position() + " from y " + start.getY());
            }
        }
        ctx.assertTrue(tested >= 5, "Tested the kits: " + tested);
        ctx.succeed();
    }
}
