package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.SpaceMaterials;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.defence.DefenceBlocks;
import com.nullandkale.nullspace.gameplay.defence.TurretBlockEntity;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * What a generated site does to defend itself (chunk A5 of {@code docs/design/rewards-work-plan.md}), on top of its occupants and its
 * damage. Built once with the site, at danger 1 and up:
 * <ul>
 * <li><b>Security turrets</b>: a hostile {@link TurretBlockEntity} covering a doorway, one at danger 1, two at 2 and four at 3.
 * They shoot players, never the occupants, and only while the site has power (a power failure leaves them dark).</li>
 * <li><b>Green fluid</b>: at danger 2 and 3, a small pool in some rooms, away from the docks.</li>
 * <li><b>Locked doors</b>: at danger 2 and 3, some doors are iron, which no one can open by hand.</li>
 * </ul>
 * Using the site's Station Core with its power restored {@link #secure secures} it: the turrets stand down and the doors become wood.
 */
public final class SiteDefences
{
    public static final int[] TURRETS = {0, 1, 2, 4};

    /** Pools of fluid and iron doors a site gets at each danger. */
    public static final int[] POOLS = {0, 0, 1, 2}, LOCKED = {0, 0, 1, 2};

    /** How far from a docking base nothing is placed. */
    public static final int DOCK_CLEARANCE = 6;

    public static final String DEFENDED = "encounterDefended", SECURED = "encounterSecured", LOCKED_DOORS = "lockedDoors";

    public static void apply(ServerLevel level, CompoundTag ship, int danger, long seed)
    {
        danger = Math.clamp(danger, 0, 3);
        if (danger < 1 || ship.getBooleanOr(DEFENDED, false))
            return;
        ship.putBoolean(DEFENDED, true);
        var bounds = WorldStore.bounds(ship);
        var random = new Random(seed ^ 0x5EC0DE5L);
        var docks = new ArrayList<BlockPos>();
        var doors = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            var state = level.getBlockState(p);
            if (state.is(ModBlocks.DOCKING_BASE))
                docks.add(p.immutable());
            else if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
                doors.add(p.immutable());
        }
        doors.sort(Comparator.comparingLong(BlockPos::asLong));
        Collections.shuffle(doors, random);
        var taken = new ArrayList<BlockPos>();
        int turrets = 0;
        for (var door : doors)
        {
            if (turrets >= TURRETS[danger])
                break;
            for (var side : shuffled(random))
            {
                var cell = door.relative(side, 2);
                if (free(level, cell, ship) && far(docks, cell, DOCK_CLEARANCE) && far(taken, cell, 2))
                {
                    level.setBlock(cell, DefenceBlocks.SALVAGE_TURRET.defaultBlockState(), 18);
                    if (level.getBlockEntity(cell) instanceof TurretBlockEntity turret)
                        turret.hostile(true);
                    taken.add(cell);
                    turrets++;
                    break;
                }
            }
        }
        int locked = 0;
        var lockedList = new ListTag();
        for (var door : doors)
        {
            if (locked >= LOCKED[danger])
                break;
            if (!far(docks, door, DOCK_CLEARANCE) || !far(taken, door, 1))
                continue;
            swap(level, door, Blocks.IRON_DOOR);
            lockedList.add(LongTag.valueOf(door.asLong()));
            locked++;
        }
        ship.put(LOCKED_DOORS, lockedList);
        for (int pool = 0, tries = 0; pool < POOLS[danger] && tries < 200; tries++)
        {
            var min = bounds.min();
            var at = new BlockPos(min.getX() + random.nextInt(bounds.max().getX() - min.getX() + 1), min.getY() + random.nextInt(bounds.max().getY() - min.getY() + 1), min.getZ() + random.nextInt(bounds.max().getZ() - min.getZ() + 1));
            if (!free(level, at, ship) || !far(docks, at, DOCK_CLEARANCE + 2) || !far(taken, at, 2) || !far(doors, at, 3))
                continue;
            level.setBlock(at, SpaceMaterials.GREEN_FLUID_BLOCK.defaultBlockState(), 18);
            for (var d : Direction.Plane.HORIZONTAL)
                if (free(level, at.relative(d), ship) && random.nextBoolean())
                    level.setBlock(at.relative(d), SpaceMaterials.GREEN_FLUID_BLOCK.defaultBlockState(), 18);
            taken.add(at);
            pool++;
        }
    }

    /**
     * Using the Station Core with the site's power restored: its security turrets stand down and its iron doors become wood. Returns
     * the message to show, or null when this is not a defended, unsecured site.
     */
    public static String secure(ServerPlayer player, BlockPos inside)
    {
        var level = player.level();
        var ship = WorldStore.get(level.getServer()).at(level, inside);
        if (ship == null || !ship.getBooleanOr(DEFENDED, false) || ship.getBooleanOr(SECURED, false))
            return null;
        if (!ShipSystems.enabled(ship) || ShipSystems.power(level, ship, PowerBus.System.CONTROLS) < 1)
            return "The security systems have no power: restore the generator, then use the core again";
        ship.putBoolean(SECURED, true);
        int doors = 0;
        for (var tag : ship.getListOrEmpty(LOCKED_DOORS))
        {
            var door = BlockPos.of(((LongTag) tag).value());
            if (level.getBlockState(door).is(Blocks.IRON_DOOR))
            {
                swap(level, door, Blocks.OAK_DOOR);
                doors++;
            }
        }
        ship.put(LOCKED_DOORS, new ListTag());
        player.sendSystemMessage(Component.literal("Security systems answer to you: the turrets stand down and " + doors + (doors == 1 ? " door unlocks" : " doors unlock")));
        return "";
    }

    /** True when this ship's hostile turrets still shoot players: it has been defended and not secured. */
    public static boolean guarded(CompoundTag ship)
    {
        return !ship.getBooleanOr(SECURED, false);
    }

    private static List<Direction> shuffled(Random random)
    {
        var sides = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        Collections.shuffle(sides, random);
        return sides;
    }

    static boolean free(ServerLevel level, BlockPos cell, CompoundTag ship)
    {
        return WorldStore.bounds(ship).contains(cell) && WorldStore.bounds(ship).contains(cell.above()) && level.getBlockState(cell).isAir() && level.getBlockState(cell.above()).isAir() && level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP);
    }

    static boolean far(List<BlockPos> others, BlockPos cell, int distance)
    {
        for (var other : others)
            if (other.distSqr(cell) < (double) distance * distance)
                return false;
        return true;
    }

    /** Replaces both halves of a door with another door block, keeping its facing, hinge and halves. */
    private static void swap(ServerLevel level, BlockPos lower, Block to)
    {
        for (var half : new BlockPos[]{lower, lower.above()})
        {
            var state = level.getBlockState(half);
            if (state.getBlock() instanceof DoorBlock)
                level.setBlock(half, to.withPropertiesOf(state).setValue(DoorBlock.OPEN, false), 18);
        }
    }

    private SiteDefences()
    {
    }
}
