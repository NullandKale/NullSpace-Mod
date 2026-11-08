package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Taking over a derelict: an unclaimed salvage vessel becomes the claimant's ship. Its identity,
 * blocks and depleted supplies are kept, so its generation site never produces a fresh copy;
 * claiming refills no fuel, hull or cargo. Public service stations are never claimable.
 */
public final class Claims
{
    public static boolean claimable(CompoundTag s)
    {
        return s != null && StationAccess.station(s) && s.getBooleanOr("salvage", false) && s.getIntOr("encounterKind", 0) > 0 && s.getStringOr("owner", "").isEmpty() && StationAccess.ready(s);
    }

    public static String claim(ServerPlayer p, CompoundTag s)
    {
        if (!claimable(s))
            return "This vessel is not an unclaimed derelict";
        String name = s.getStringOr("home", "This vessel");
        var server = p.level().getServer();
        if (TransferLocks.busy(p) || TransferLocks.busy(server, s))
            return "Wait until travel and docking have finished";
        if (!p.level().dimension().identifier().toString().equals(s.getStringOr("dimension", "")) || !WorldStore.bounds(s).box().inflate(1).contains(p.position()))
            return "Step aboard " + name + " to claim it";
        var level = p.level();
        var controller = WorldStore.pos(s, "controller");
        var state = level.getBlockState(controller);
        if (!state.is(ModBlocks.STATION_CORE))
            return name + " is damaged: its controller is destroyed. Place a Ship Controller aboard and open it" + " to claim the wreck";
        // Take command: the derelict's core becomes an ordinary ship controller facing the same way.
        level.setBlock(controller, ModBlocks.COMPUTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, state.getValue(HorizontalDirectionalBlock.FACING)), 3);
        return take(p, s, name);
    }

    /** An unclaimed derelict whose controller has been destroyed. */
    public static boolean damaged(net.minecraft.server.level.ServerLevel level, CompoundTag s)
    {
        return claimable(s) && level.hasChunkAt(WorldStore.pos(s, "controller")) && !level.getBlockState(WorldStore.pos(s, "controller")).is(ModBlocks.STATION_CORE);
    }

    /**
     * Claim wreck: a Ship Controller the player placed aboard a damaged derelict becomes its
     * controller, and the vessel becomes theirs as with an ordinary claim.
     */
    public static String claimWreck(ServerPlayer p, CompoundTag s, net.minecraft.core.BlockPos at)
    {
        if (!damaged(p.level(), s))
            return "";
        String name = s.getStringOr("home", "This vessel");
        if (TransferLocks.busy(p) || TransferLocks.busy(p.level().getServer(), s))
            return "Wait until travel and docking have finished";
        if (!WorldStore.bounds(s).contains(at) || !p.level().getBlockState(at).is(ModBlocks.COMPUTER))
            return "";
        if (!WorldStore.bounds(s).box().inflate(1).contains(p.position()))
            return "Step aboard " + name + " to claim the wreck";
        s.putLong("controller", at.asLong());
        return take(p, s, name);
    }

    private static String take(ServerPlayer p, CompoundTag s, String name)
    {
        var server = p.level().getServer();
        s.putString("kind", "ship");
        s.putString("owner", p.getUUID().toString());
        var users = new CompoundTag();
        users.putBoolean(p.getUUID().toString(), true);
        s.put("recallUsers", users);
        s.putString("access", "private");
        s.putBoolean("claimed", true);
        ShipTitles.ensureHeld(p, s);
        WorldStore.get(server).save();
        return "You claimed " + name + ". Its fuel, supplies and damage are as you found them; its title is in your inventory";
    }

    private Claims()
    {
    }
}
