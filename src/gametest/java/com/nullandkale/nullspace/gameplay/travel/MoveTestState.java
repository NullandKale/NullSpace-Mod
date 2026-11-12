package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.*;
import com.nullandkale.nullspace.gameplay.pads.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Test expectations use a known quarter turn and the authoritative controller as their anchor. */
public final class MoveTestState
{
    public static ShipTransform arrived(CompoundTag ship, BlockPos originalController, int turns)
    {
        return new ShipTransform(originalController, WorldStore.pos(ship, "controller"), 1, 1, turns);
    }

    /** Translation-only fixtures keep their entrance aligned; dedicated arrival tests exercise rotation. */
    public static void alignNorth(ServerLevel level, String name) throws java.io.IOException
    {
        var repository = PadRepository.get(level.getServer());
        var pads = new java.util.ArrayList<>(repository.all());
        for (int i = 0; i < pads.size(); i++)
        {
            var pad = pads.get(i);
            if (!pad.name().equals(name))
                continue;
            var bounds = pad.bounds();
            var at = new BlockPos((bounds.minX() + bounds.maxX()) / 2, bounds.y() + 1, bounds.minZ() - 1);
            level.setBlock(at, level.getBlockState(pad.computer()), 18);
            level.setBlock(pad.computer(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
            pads.set(i, new PadRegistry.Pad(pad.id(), pad.name(), pad.dimension(), at, bounds));
            repository.replace(pads);
            return;
        }
        throw new AssertionError("Missing fixture pad: " + name);
    }

    private MoveTestState() {}
}
