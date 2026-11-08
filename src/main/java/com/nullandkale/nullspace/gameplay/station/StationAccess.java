package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;

import java.util.*;

import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Who may use, build on or visit a station, and the doors visitors may open. */
public final class StationAccess
{

    public static boolean station(CompoundTag s)
    {
        return s != null && s.getStringOr("kind", "").equals("station");
    }

    public static boolean ready(CompoundTag s)
    {
        return !s.contains("stationBuild");
    }

    public static boolean building(MinecraftServer server)
    {
        return WorldStore.get(server).ships().stream().anyMatch(t -> ((CompoundTag) t).contains("stationBuild"));
    }

    /** Aggregate status for administrative maintenance, never a lock on unrelated player ships. */
    public static boolean exclusiveConstruction(MinecraftServer server)
    {
        return WorldStore.get(server).ships().stream().map(t -> (CompoundTag) t).anyMatch(s -> s.contains("stationBuild") && !s.getBooleanOr("worldGenerated", false));
    }

    public static boolean buildingAt(ServerLevel level, BlockPos at)
    {
        if (StationService.PLACING.get())
            return false;
        var s = WorldStore.get(level.getServer()).at(level, at);
        return s != null && !ready(s);
    }

    public static boolean owner(ServerPlayer p, CompoundTag s)
    {
        return s.getStringOr("owner", "").equals(p.getUUID().toString()) || p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean builder(ServerPlayer p, CompoundTag s)
    {
        return !station(s) || owner(p, s) || s.getCompoundOrEmpty("builders").getBooleanOr(p.getUUID().toString(), false);
    }

    public static boolean allowed(ServerPlayer p, CompoundTag s)
    {
        return !station(s) || s.getStringOr("access", "private").equals("public") || builder(p, s);
    }

    public static boolean returningVisitor(ServerPlayer p, CompoundTag s)
    {
        return s.getListOrEmpty("crew").stream().map(t -> (CompoundTag) t).anyMatch(m -> m.getStringOr("id", "").equals(p.getUUID().toString()) && !m.getStringOr("returnShip", "").isEmpty());
    }

    public static void requireBuilder(ServerPlayer p, CompoundTag s)
    {
        if (!builder(p, s))
            throw new IllegalArgumentException("Station building is restricted to its owner and invited builders");
    }

    public static boolean mayEdit(ServerPlayer p, BlockPos at)
    {
        var s = WorldStore.get(p.level().getServer()).at(p.level(), at);
        return s == null || (!station(s) || builder(p, s) || s.getBooleanOr("salvage", false)) && ready(s);
    }

    public static boolean mayUse(ServerPlayer p, BlockPos at)
    {
        var s = WorldStore.get(p.level().getServer()).at(p.level(), at);
        if (!station(s))
            return true;
        if (!ready(s))
            return false;
        if (s.getBooleanOr("salvage", false))
            return true;
        var state = p.level().getBlockState(at);
        if (!builder(p, s) && (allowed(p, s) || returningVisitor(p, s)) && state.getBlock() instanceof DoorBlock)
        {
            var door = state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? at.below() : at;
            if (p.level().getBlockState(door.below()).is(ModBlocks.DOCKING_BASE))
                return true;
            return ShipDoors.framed(p.level(), door);
        }
        return builder(p, s) || (allowed(p, s) || returningVisitor(p, s)) && (state.is(ModBlocks.DOCKING_BASE) || state.is(ModBlocks.STATION_CORE) || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.STONECUTTER));
    }

    public static boolean useDoor(ServerPlayer p, BlockPos at)
    {
        var level = p.level();
        if (TransferLocks.busy(p) || TransferLocks.locked(level, at))
            return false;
        var state = level.getBlockState(at);
        if (!station(WorldStore.get(level.getServer()).at(level, at)) || !(state.getBlock() instanceof DoorBlock) || !mayUse(p, at))
            return false;
        var door = state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? at.below() : at;
        if (DockingPorts.ownsDoor(level, door))
            return false;
        if (!ShipDoors.powered(level, door))
            ShipDoors.setOpen(level, door, !ShipDoors.open(state));
        else
            p.sendOverlayMessage(Component.literal("Redstone is controlling this door"));
        return true;
    }

    private StationAccess()
    {
    }
}
