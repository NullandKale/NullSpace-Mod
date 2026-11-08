package com.nullandkale.nullspace.gameplay.pads;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.PadRepository;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Rescans completed ring edits without claiming incomplete pads or loading remote chunks. */
public final class PadRefresh
{
    private static final ServerContext.Key<PadRefresh> KEY = new ServerContext.Key<>();
    private final Map<String, Integer> dirty = new LinkedHashMap<>();
    private int cursor;

    public static void changed(ServerLevel level, BlockPos position)
    {
        if (TravelService.worldMutation())
            return;
        var state = ServerContext.get(level.getServer()).service(KEY, PadRefresh::new);
        for (var pad : PadRepository.get(level.getServer()).dimension(level.dimension().identifier().toString()))
        {
            var b = pad.bounds();
            if (position.getY() == b.y() && position.getX() >= b.minX() - 1 && position.getX() <= b.maxX() + 1 && position.getZ() >= b.minZ() - 1 && position.getZ() <= b.maxZ() + 1 || position.equals(pad.computer()))
                state.dirty.put(pad.id(), level.getServer().getTickCount() + 5);
        }
    }

    public static void refresh(ServerLevel level, PadRegistry.Pad pad)
    {
        if (!level.hasChunkAt(pad.computer()) || !level.getBlockState(pad.computer()).is(PadComputers.BLOCK) || TransferLocks.locked(level, pad.computer()) || TransferLocks.locked(level, new BlockPos(pad.bounds().minX(), pad.bounds().y() + 1, pad.bounds().minZ())))
            return;
        var result = PadComputers.inspect(level, pad.computer());
        if (!result.valid() || result.pad().equals(pad.bounds()))
            return;
        try
        {
            // Preserve identity/name and retain other unloaded registrations. Registration performs the
            // same overlap checks as a manual action and atomically publishes the new footprint.
            PadComputers.register(level, pad.computer(), result.pad(), pad.name(), false);
        }
        catch (java.io.IOException failure)
        {
            NullSpace.LOGGER.error("Could not refresh pad {}", pad.name(), failure);
        }
    }

    /** Interaction fallback also repairs saved worlds whose ring was edited before this service. */
    public static void near(ServerLevel level, BlockPos position)
    {
        for (var pad : PadRepository.get(level.getServer()).dimension(level.dimension().identifier().toString()))
        {
            var b = pad.bounds();
            if (position.getY() > b.y() && position.getY() <= b.y() + 32 && Math.abs(position.getX() - pad.computer().getX()) <= 33 && Math.abs(position.getZ() - pad.computer().getZ()) <= 33)
                refresh(level, pad);
        }
    }

    private void tick(MinecraftServer server)
    {
        String id = null;
        for (var entry : dirty.entrySet())
            if (entry.getValue() <= server.getTickCount())
            {
                id = entry.getKey();
                break;
            }
        var repository = PadRepository.get(server);
        PadRegistry.Pad pad;
        if (id != null)
        {
            dirty.remove(id);
            pad = repository.id(id);
        }
        else
        {
            // One background scan per second catches chunk reloads and pre-existing saved edits.
            var pads = repository.all();
            if (server.getTickCount() % 20 != 0 || pads.isEmpty())
                return;
            pad = pads.get(Math.floorMod(cursor++, pads.size()));
        }
        if (pad == null)
            return;
        var level = server.getAllLevels();
        for (var candidate : level)
            if (candidate.dimension().identifier().toString().equals(pad.dimension()))
            {
                refresh(candidate, pad);
                break;
            }
    }

    public static void initialize()
    {
        ServerContext.tick(ServerContext.Phase.SESSIONS, server -> ServerContext.get(server).service(KEY, PadRefresh::new).tick(server));
    }

    private PadRefresh()
    {
    }
}
