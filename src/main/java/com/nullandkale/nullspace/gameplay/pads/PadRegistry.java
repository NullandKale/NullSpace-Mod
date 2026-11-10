package com.nullandkale.nullspace.gameplay.pads;

import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.PadAllocation;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.nio.file.*;
import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import com.google.gson.*;

public final class PadRegistry
{
    public record Pad(String id, String name, String dimension, BlockPos computer, PadScanner.Pad bounds)
    {}

    public enum State
    {
        READY, OCCUPIED, UNAVAILABLE, UNKNOWN
    }

    public record Status(Pad pad, State state, String detail)
    {}

    /**
     * Prefer the nearest registered floor below a controller; never choose a lower stacked pad by
     * file order.
     */
    public static java.util.Optional<Pad> below(ServerLevel level, BlockPos at)
    {
        PadRefresh.near(level, at);
        return PadRepository.get(level.getServer()).dimension(level.dimension().identifier().toString()).stream().filter(p ->
        {
            var b = p.bounds();
            return p.dimension().equals(level.dimension().identifier().toString()) && at.getX() >= b.minX() && at.getX() <= b.maxX() && at.getZ() >= b.minZ() && at.getZ() <= b.maxZ() && at.getY() > b.y() && at.getY() <= b.y() + 32;
        }).max(java.util.Comparator.comparingInt(p -> p.bounds().y()));
    }

    public static int buildHeight(ServerLevel level, PadScanner.Pad pad)
    {
        int ceiling = pad.y() + 33;
        for (var other : PadRepository.get(level.getServer()).dimension(level.dimension().identifier().toString()))
        {
            var b = other.bounds();
            if (other.dimension().equals(level.dimension().identifier().toString()) && b.y() > pad.y() && b.minX() <= pad.maxX() && b.maxX() >= pad.minX() && b.minZ() <= pad.maxZ() && b.maxZ() >= pad.minZ())
                ceiling = Math.min(ceiling, b.y());
        }
        return Math.clamp(ceiling - pad.y() - 1, 0, 32);
    }

    /** Registration changes resize ground allocations while retaining their world-space state. */
    public static void reconcileHeights(ServerLevel level)
    {
        var store = WorldStore.get(level.getServer());
        var pads = read(level.getServer());
        for (var tag : store.ships())
        {
            var ship = (net.minecraft.nbt.CompoundTag) tag;
            if (!ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) || level.dimension().equals(TravelService.SPACE))
                continue;
            if (TransferLocks.locked(level, WorldStore.pos(ship, "controller")))
                continue;
            var pad = pads.stream().filter(p -> p.id().equals(ship.getStringOr("homeId", ""))).findFirst();
            if (pad.isEmpty())
                continue;
            var b = pad.get().bounds();
            int height = buildHeight(level, b);
            var nextBase = new BlockPos(b.minX(), b.y() + 1, b.minZ());
            var delta = WorldStore.pos(ship, "base").subtract(nextBase);
            if (ship.getIntOr("height", 32) != height || ship.getIntOr("width", 32) != b.width() || ship.getIntOr("depth", 32) != b.depth() || !delta.equals(BlockPos.ZERO))
            {
                ShipSystems.cancelTransit(level, ship);
                store.repository().mutate(new ShipRepository.ShipId(ship.getStringOr("id", "")), ShipRepository.Mutation.CONFIGURATION, candidate -> PadAllocation.resize(candidate, b, height));
                ComputerService.rebase(level.getServer(), ship.getStringOr("id", ""), delta);
            }
        }
    }

    public static List<Status> states(MinecraftServer server)
    {
        return read(server).stream().map(p -> state(server, p)).toList();
    }

    /** Listing never loads remote terrain. UNKNOWN is distinct from an inspected unusable pad. */
    public static Status state(MinecraftServer server, Pad pad)
    {
        return PadStatus.status(server, pad);
    }

    public static Status inspectState(MinecraftServer server, Pad pad)
    {
        var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(pad.dimension())));
        if (level == null)
            return new Status(pad, State.UNAVAILABLE, "Dimension missing");
        var b = pad.bounds();
        for (int x = b.minX() - 1; x <= b.maxX() + 1; x++)
            for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++)
                if (!level.hasChunkAt(new BlockPos(x, b.y(), z)))
                    return new Status(pad, State.UNKNOWN, "Terrain not loaded");
        if (!level.hasChunkAt(pad.computer()))
            return new Status(pad, State.UNKNOWN, "Computer not loaded");
        var validation = check(level, pad, false);
        if (validation.state() != State.READY)
            return validation;
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int y = b.y() + 1; y <= level.getMaxY(); y++)
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir() && !HullScan.cover(level.getBlockState(new BlockPos(x, y, z))))
                        return new Status(pad, State.OCCUPIED, "Launch column contains " + ShipMessages.block(level, new BlockPos(x, y, z)) + " at " + x + ", " + y + ", " + z);
        return new Status(pad, State.READY, "Surface, border and sky clear");
    }

    public static List<Pad> read(MinecraftServer server)
    {
        return PadRepository.get(server).all();
    }

    public static void reload(MinecraftServer server)
    {
        PadRepository.get(server).reload();
    }

    /** Legacy launch/shipyard adapter; landing and status checks consume the result directly. */
    public static void validate(ServerLevel level, Pad pad)
    {
        var result = check(level, pad);
        if (result.state() != State.READY)
            throw new IllegalArgumentException(result.detail());
    }

    public static Status check(ServerLevel level, Pad pad)
    {
        return check(level, pad, true);
    }

    private static Status check(ServerLevel level, Pad pad, boolean load)
    {
        var p = pad.computer();
        if (load)
            level.getChunk(p);
        else if (!level.hasChunkAt(p))
            return new Status(pad, State.UNKNOWN, "Computer not loaded");
        if (!level.getBlockState(p).is(PadComputers.BLOCK))
            return new Status(pad, State.UNAVAILABLE, "Pad computer is missing: " + pad.name());
        var state = level.getBlockState(p);
        var facing = state.getValue(PadComputerBlock.FACING);
        boolean[] unloaded = {false};
        var result = PadScanner.scan((x, y, z) ->
        {
            var pos = new BlockPos(x, y, z);
            if (!load && !level.hasChunkAt(pos))
            {
                unloaded[0] = true;
                return PadScanner.Cell.OTHER;
            }
            var s = level.getBlockState(pos);
            var floor = PadFloor.cell(s);
            if (floor != null)
                return floor;
            if (s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE))
                return PadScanner.Cell.BORDER;
            return PadScanner.Cell.OTHER;
        }, p.getX(), p.getY(), p.getZ(), facing.getStepX(), facing.getStepZ());
        if (unloaded[0])
            return new Status(pad, State.UNKNOWN, "Terrain not loaded");
        if (!result.valid() || !result.pad().equals(pad.bounds()))
            return new Status(pad, State.UNAVAILABLE, "Pad changed: " + result.message());
        return new Status(pad, State.READY, "Surface and border valid");
    }

    private PadRegistry()
    {
    }
}
