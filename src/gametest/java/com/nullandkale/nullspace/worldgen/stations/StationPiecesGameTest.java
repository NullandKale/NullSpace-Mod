package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.blueprint.GenerationPalette;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Station pieces are generated to the catalog, close unused doorways and hold air. */
public final class StationPiecesGameTest
{
    @GameTest
    public void everyPieceIsGeneratedToItsCatalogEntry(GameTestHelper ctx)
    {
        for (var definition : StationPieces.ALL)
        {
            var plan = StationBlueprint.build(new StationLayout.Layout(List.of(new StationLayout.Room("t", definition.id(), 0, 0, 0)), List.of()));
            var blocks = plan.blocks();
            ctx.assertTrue(blocks.keySet().stream().allMatch(at -> at.getX() >= 0 && at.getX() < 16 && at.getZ() >= 0 && at.getZ() < 16 && at.getY() >= 0 && at.getY() < StationLayout.SIZE), definition.id() + " fills one tile and no more");
            ctx.assertTrue(blocks.values().stream().anyMatch(s -> s.is(ModBlocks.AIR_SUPPLY)), definition.id() + " has its own Air Supply");
            ctx.assertTrue(GenerationPalette.violations(blocks).isEmpty(), definition.id() + " uses cheap blocks");
            // Every doorway slot on every story and side is closed wall until a neighbour is joined.
            for (var side : Direction.Plane.HORIZONTAL)
                for (var at : StationLayout.doorways(new StationLayout.Room("t", definition.id(), 0, 0, 0), side))
                {
                    // A berth is the one doorway a lone tile opens on purpose.
                    if (plan.docks().contains(at.below()))
                        continue;
                    var state = blocks.get(at);
                    ctx.assertTrue(state != null && state.canOcclude(), definition.id() + " closes the " + side + " doorway at " + at);
                }
        }
        ctx.succeed();
    }

    /**
     * Arrival, tunnel, dome and a corner tunnel, joined by doorway: with doors shut, the inside of
     * every tile is cut off from space.
     */
    @GameTest
    public void joinedTunnelsAndDomesAreSealed(GameTestHelper ctx)
    {
        var rooms = new ArrayList<StationLayout.Room>();
        rooms.add(new StationLayout.Room("0_0", StationLayout.Role.ARRIVAL, 0, 0));
        rooms.add(StationLayout.place(rooms, "tunnel_straight", 1, 0, null));
        rooms.add(StationLayout.place(rooms, "dome_garden", 2, 0, null));
        rooms.add(StationLayout.place(rooms, "tunnel_corner", 2, 1, 3));
        rooms.add(StationLayout.place(rooms, "dome_observation", 1, 1, null));
        var named = new ArrayList<StationLayout.Room>();
        for (var r : rooms)
            named.add(new StationLayout.Room(r.x() + "_" + r.z(), r.piece(), r.rotation(), r.x(), r.z()));
        var joints = new ArrayList<StationLayout.Joint>();
        for (int i = 0; i < named.size(); i++)
            for (int j = i + 1; j < named.size(); j++)
                if (StationLayout.joined(named.get(i), named.get(j)))
                    joints.add(new StationLayout.Joint(named.get(i).id(), named.get(j).id()));
        ctx.assertTrue(joints.size() >= 4, "Every piece joins the station: " + joints);
        var plan = StationBlueprint.build(new StationLayout.Layout(named, joints));
        var outside = outside(plan.blocks());
        for (var room : named)
        {
            int open = 0;
            for (int x = 1; x <= 14; x++)
                for (int z = 1; z <= 14; z++)
                    for (int y = 2; y <= 13; y++)
                    {
                        var inside = room.origin().offset(x, y, z);
                        var state = plan.blocks().get(inside);
                        if (state == null || state.isAir())
                        {
                            open++;
                            ctx.assertTrue(!outside.contains(inside), room.piece() + " at " + room.x() + "," + room.z() + " is sealed from space: " + inside);
                        }
                    }
            ctx.assertTrue(open > 400, room.piece() + " is a big open hall: " + open);
        }
        ctx.succeed();
    }

    /** Open cells reachable from beyond the build's box; doors count as shut. */
    private static Set<BlockPos> outside(Map<BlockPos, BlockState> blocks)
    {
        BlockPos lo = null, hi = null;
        for (var at : blocks.keySet())
        {
            lo = lo == null ? at : BlockPos.min(lo, at);
            hi = hi == null ? at : BlockPos.max(hi, at);
        }
        lo = lo.offset(-1, -1, -1);
        hi = hi.offset(1, 1, 1);
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(lo);
        seen.add(lo);
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (var d : Direction.values())
            {
                var next = at.relative(d);
                if (next.getX() < lo.getX() || next.getY() < lo.getY() || next.getZ() < lo.getZ() || next.getX() > hi.getX() || next.getY() > hi.getY() || next.getZ() > hi.getZ() || seen.contains(next))
                    continue;
                var state = blocks.get(next);
                boolean open = state == null || state.isAir() || state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, next).isEmpty() && !(state.getBlock() instanceof DoorBlock);
                if (open)
                {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        return seen;
    }
}
