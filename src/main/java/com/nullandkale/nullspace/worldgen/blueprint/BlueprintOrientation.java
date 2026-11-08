package com.nullandkale.nullspace.worldgen.blueprint;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Final pass over a generated blueprint (ships, stations, encounters). Generated blocks are placed
 * without neighbor updates, so this computes what an ordinary player placement would: interactive
 * blocks face an open side instead of a wall, beds have their headboard against the wall, and
 * panes, bars and fences connect to their neighbors instead of standing as spikes.
 */
public final class BlueprintOrientation
{
    public record Report(int fronts, int beds, int connections)
    {
        public int total()
        {
            return fronts + beds + connections;
        }
    }

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** Blocks a player uses from the front: turn them toward a walkable side. */
    private static boolean frontUsed(BlockState state)
    {
        var block = state.getBlock();
        return (block instanceof AbstractFurnaceBlock || block instanceof ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE || block instanceof BarrelBlock || block instanceof LecternBlock || block instanceof LoomBlock || block instanceof DispenserBlock) && horizontalFacing(state) != null;
    }

    private static Direction horizontalFacing(BlockState state)
    {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        if (state.hasProperty(BlockStateProperties.FACING) && state.getValue(BlockStateProperties.FACING).getAxis().isHorizontal())
            return state.getValue(BlockStateProperties.FACING);
        return null;
    }

    private static BlockState withFacing(BlockState state, Direction facing)
    {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing) : state.setValue(BlockStateProperties.FACING, facing);
    }

    private static boolean open(Map<BlockPos, BlockState> blocks, BlockPos at)
    {
        var state = blocks.get(at);
        return state == null || state.isAir();
    }

    /** Fixes the map in place and reports how many blocks changed, by kind. */
    public static Report fix(Map<BlockPos, BlockState> blocks)
    {
        int fronts = 0, beds = 0, connections = 0;
        for (var entry : List.copyOf(blocks.entrySet()))
        {
            var at = entry.getKey();
            var state = blocks.get(at);
            if (!frontUsed(state))
                continue;
            var facing = horizontalFacing(state);
            if (open(blocks, at.relative(facing)))
                continue;
            // Prefer turning around, then the sides, so a block against a wall faces the room.
            for (var candidate : List.of(facing.getOpposite(), facing.getClockWise(), facing.getCounterClockWise()))
                if (open(blocks, at.relative(candidate)))
                {
                    blocks.put(at, withFacing(state, candidate));
                    fronts++;
                    break;
                }
        }
        var flipped = new HashSet<BlockPos>();
        for (var entry : List.copyOf(blocks.entrySet()))
        {
            var head = entry.getKey();
            var state = blocks.get(head);
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.HEAD || flipped.contains(head))
                continue;
            var facing = state.getValue(BedBlock.FACING);
            var foot = head.relative(facing.getOpposite());
            var footState = blocks.get(foot);
            if (footState == null || !(footState.getBlock() instanceof BedBlock))
                continue;
            // Headboard should rest on a wall: flip when the head points into open space while the
            // foot end is the one against a wall.
            if (open(blocks, head.relative(facing)) && !open(blocks, foot.relative(facing.getOpposite())))
            {
                var turned = facing.getOpposite();
                blocks.put(foot, state.setValue(BedBlock.FACING, turned).setValue(BedBlock.PART, BedPart.HEAD));
                blocks.put(head, footState.setValue(BedBlock.FACING, turned).setValue(BedBlock.PART, BedPart.FOOT));
                flipped.add(foot);
                beds++;
            }
        }
        for (var entry : List.copyOf(blocks.entrySet()))
        {
            var at = entry.getKey();
            var state = entry.getValue();
            if (!(state.getBlock() instanceof CrossCollisionBlock))
                continue;
            var next = state;
            for (var side : HORIZONTAL)
            {
                var neighbor = blocks.getOrDefault(at.relative(side), Blocks.AIR.defaultBlockState());
                boolean sturdy = neighbor.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, side.getOpposite());
                boolean connect = state.getBlock() instanceof IronBarsBlock bars ? bars.attachsTo(neighbor, sturdy) : state.getBlock() instanceof FenceBlock fence && fence.connectsTo(neighbor, sturdy, side.getOpposite());
                next = next.setValue(CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(side), connect);
            }
            if (next != state)
            {
                blocks.put(at, next);
                connections++;
            }
        }
        return new Report(fronts, beds, connections);
    }

    private BlueprintOrientation()
    {
    }
}
