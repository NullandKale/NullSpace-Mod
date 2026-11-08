package com.nullandkale.nullspace.render.compute;

import com.nullandkale.nullspace.model.air.Atmosphere;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Structural visual exclusion only. It does not decide gameplay pressure or oxygen. */
public final class VisualCabins
{
    private record Cached(int[] states, int[] mask)
    {}

    private static final LinkedHashMap<String, Cached> CACHE = new LinkedHashMap<>(16, .75f, true);

    public static int[] mask(CompoundTag subject)
    {
        var provided = subject.getIntArray("interiorMask");
        if (provided.isPresent())
        {
            if (provided.get().length != 1024)
                throw new IllegalArgumentException("Invalid section interior mask");
            return provided.get();
        }
        String id = subject.getStringOr("geometryId", subject.getStringOr("id", ""));
        int[] states = subject.getIntArray("blocks").orElse(new int[0]);
        var old = CACHE.get(id);
        if (old != null && old.states == states)
            return old.mask;
        int[] mask = new int[1024];
        if (states.length == Atmosphere.CELLS)
        {
            boolean[] seals = new boolean[Atmosphere.CELLS];
            for (int i = 0; i < states.length; i++)
            {
                var state = Block.stateById(states[i]);
                seals[i] = solid(state);
                if (state.getBlock() instanceof DoorBlock)
                    seals[i] = door(states, i);
            }
            int[] history = new int[Atmosphere.CELLS];
            Arrays.fill(history, -1);
            for (var room : Atmosphere.rooms(seals, history, false))
                if (room.leaks() == 0)
                    for (int cell : room.cells())
                        mask[cell >>> 5] |= 1 << (cell & 31);
        }
        CACHE.put(id, new Cached(states, mask));
        while (CACHE.size() > 64)
            CACHE.remove(CACHE.keySet().iterator().next());
        return mask;
    }

    private static boolean solid(BlockState state)
    {
        return !state.isAir() && !(state.getBlock() instanceof LeavesBlock) && !(state.getBlock() instanceof DoorBlock) && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static BlockState at(int[] states, int x, int y, int z)
    {
        return x < 0 || x > 31 || y < 0 || y > 31 || z < 0 || z > 31 ? Blocks.AIR.defaultBlockState() : Block.stateById(states[Atmosphere.index(x, y, z)]);
    }

    private static boolean door(int[] states, int i)
    {
        int x = i % 32, y = i / 1024, z = i / 32 % 32;
        var state = Block.stateById(states[i]);
        if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
            y--;
        var low = at(states, x, y, z);
        var high = at(states, x, y + 1, z);
        if (low.getBlock() != high.getBlock() || !(low.getBlock() instanceof DoorBlock) || low.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || high.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER || low.getValue(DoorBlock.OPEN) || high.getValue(DoorBlock.OPEN) || low.getValue(DoorBlock.FACING) != high.getValue(DoorBlock.FACING) || low.getValue(DoorBlock.HINGE) != high.getValue(DoorBlock.HINGE))
            return false;
        var side = low.getValue(DoorBlock.FACING).getClockWise();
        return solid(at(states, x, y - 1, z)) && solid(at(states, x, y + 2, z)) && solid(at(states, x + side.getStepX(), y, z + side.getStepZ())) && solid(at(states, x - side.getStepX(), y, z - side.getStepZ())) && solid(at(states, x + side.getStepX(), y + 1, z + side.getStepZ())) && solid(at(states, x - side.getStepX(), y + 1, z - side.getStepZ()));
    }

    public static void clear()
    {
        CACHE.clear();
    }

    private VisualCabins()
    {
    }
}
