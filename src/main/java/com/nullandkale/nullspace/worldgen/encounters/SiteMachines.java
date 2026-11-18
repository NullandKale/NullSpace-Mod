package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.defence.DefenceBlocks;

import java.util.ArrayList;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * The rare machine a deep site still has working: a repair bay standing against a wall of one of its rooms. Only the farthest
 * sites have one, so a player who raids a danger 2 or 3 ship or station may come away with a bay to take home (the other way to
 * get one is to build it from a green gem block). Built once with the site, after its loot and defences.
 */
public final class SiteMachines
{
    /** The chance of a repair bay for a site at each danger, in percent. */
    public static final int[] BAY_PERCENT = {0, 0, 20, 45};

    public static final String BAY = "encounterBay";

    /** Whether a site with this seed has a bay at this danger; the same answer every time. */
    public static boolean hasBay(int danger, long seed)
    {
        return new Random(seed ^ 0xBA11BA5L).nextInt(100) < BAY_PERCENT[Math.clamp(danger, 0, 3)];
    }

    public static void apply(ServerLevel level, CompoundTag ship, int danger, long seed)
    {
        if (ship.getBooleanOr(BAY, false))
            return;
        ship.putBoolean(BAY, true);
        if (!hasBay(danger, seed))
            return;
        var bounds = WorldStore.bounds(ship);
        var docks = new ArrayList<BlockPos>();
        var doors = new ArrayList<BlockPos>();
        var candidates = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            var state = level.getBlockState(p);
            if (state.is(com.nullandkale.nullspace.content.ModBlocks.DOCKING_BASE))
                docks.add(p.immutable());
            else if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                doors.add(p.immutable());
            else if (SiteDefences.free(level, p, ship) && againstWall(level, p))
                candidates.add(p.immutable());
        }
        java.util.Collections.shuffle(candidates, new Random(seed ^ 0xBA7BA7L));
        for (var cell : candidates)
            if (SiteDefences.far(docks, cell, SiteDefences.DOCK_CLEARANCE) && SiteDefences.far(doors, cell, 3))
            {
                level.setBlock(cell, DefenceBlocks.REPAIR_BAY.defaultBlockState(), 18);
                return;
            }
    }

    /** A floor cell with a solid wall on one side and open floor on the opposite, so the bay stands against the wall and can be walked up to. */
    private static boolean againstWall(ServerLevel level, BlockPos cell)
    {
        for (var side : Direction.Plane.HORIZONTAL)
            if (level.getBlockState(cell.relative(side)).isFaceSturdy(level, cell.relative(side), side.getOpposite()) && level.getBlockState(cell.relative(side.getOpposite())).isAir() && level.getBlockState(cell.relative(side.getOpposite()).above()).isAir())
                return true;
        return false;
    }

    private SiteMachines()
    {
    }
}
