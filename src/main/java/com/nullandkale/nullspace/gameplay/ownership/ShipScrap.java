package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Scrapping a ship for good, and outlining the blocks Store would take. */
public final class ShipScrap
{

    /** The blocks Store would capture for a ship on this pad: inside both the ship and the column. */
    static List<BlockPos> captured(ServerLevel level, CompoundTag ship, PadRegistry.Pad pad)
    {
        var column = ShipStorage.padColumn(pad);
        var saved = WorldStore.bounds(ship);
        var overlap = ShipStorage.overlap(column, saved);
        var blocks = new ArrayList<BlockPos>();
        for (var cursor : BlockPos.betweenClosed(overlap.min(), overlap.max()))
            if (level.hasChunkAt(cursor) && !level.getBlockState(cursor).isAir())
                blocks.add(cursor.immutable());
        return blocks;
    }

    /** Outlines every block Store would take, for the asking player only. */
    public static String show(ServerPlayer p, BlockPos at, String selection)
    {
        String access = ShipStorage.access(p, at);
        if (!access.isEmpty())
            return access;
        var server = p.level().getServer();
        var pad = ShipStorage.pad(p, at);
        var ship = ShipStorage.find(server, selection);
        if (pad == null || ship == null || !ShipStorage.onPad(ship, pad))
            return "Choose a ship standing on this pad";
        var blocks = captured(p.level(), ship, pad);
        for (var b : blocks)
            p.level().sendParticles(p, net.minecraft.core.particles.ParticleTypes.END_ROD, true, false, b.getX() + .5, b.getY() + .5, b.getZ() + .5, 1, .2, .2, .2, 0);
        return "Showing the " + blocks.size() + " blocks Store takes. Anything outside the pad's column stays behind";
    }

    static final ServerContext.Key<Map<UUID, String>> SCRAP = new ServerContext.Key<>();

    /**
     * Scraps a ship permanently: its blocks and everything aboard are removed and every title stops
     * working. Owner only, nobody aboard, and the button must be pressed twice.
     */
    public static String scrap(ServerPlayer p, BlockPos at, String selection)
    {
        String access = ShipStorage.access(p, at);
        if (!access.isEmpty())
            return access;
        var server = p.level().getServer();
        var pad = ShipStorage.pad(p, at);
        var ship = ShipStorage.find(server, selection);
        if (pad == null || ship == null)
            return "Choose one of your ships from the list";
        String name = ship.getStringOr("home", "This ship");
        if (!ShipTitles.owner(p, ship))
            return "Only the owner can scrap " + name;
        boolean here = ShipStorage.onPad(ship, pad);
        boolean space = ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString());
        if (!here && !space)
            return "Bring " + name + " to this pad or space to scrap it";
        String problem = here ? ShipStorage.surfaceProblem(server, p.level(), ship, p.getUUID()) : ShipStorage.unavailable(server, ship, p.getUUID());
        if (!problem.isEmpty())
            return problem;
        var pending = ServerContext.get(server).service(SCRAP, HashMap::new);
        String previous = pending.getOrDefault(p.getUUID(), "");
        String id = ship.getStringOr("id", "");
        boolean confirmed = previous.startsWith(id + "@") && server.getTickCount() - Long.parseLong(previous.substring(id.length() + 1)) <= 200;
        if (!confirmed)
        {
            pending.put(p.getUUID(), id + "@" + server.getTickCount());
            return "Scrap " + name + " for good? Its blocks and everything aboard are lost. Press Scrap ship again to" + " confirm";
        }
        pending.remove(p.getUUID());
        NavigationService.closeShip(server, ship.getStringOr("id", ""));
        var level = here ? p.level() : server.getLevel(TravelService.SPACE);
        var blocks = here ? captured(level, ship, pad) : BlockPos.betweenClosedStream(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()).map(BlockPos::immutable).toList();
        if (!here)
            for (int x = WorldStore.bounds(ship).min().getX() >> 4; x <= WorldStore.bounds(ship).max().getX() >> 4; x++)
                for (int z = WorldStore.bounds(ship).min().getZ() >> 4; z <= WorldStore.bounds(ship).max().getZ() >> 4; z++)
                    level.getChunk(x, z);
        return com.nullandkale.nullspace.gameplay.travel.BuildJournal.run(level, "scrap-ship", List.of(id), WorldStore.bounds(ship), () ->
        {
            CrewRelease.releaseOfflineCrew(server, ship, p.getUUID());
            for (var b : blocks)
                if (!level.getBlockState(b).isAir())
                {
                    level.removeBlockEntity(b);
                    level.setBlock(b, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
                }
            WorldStore.get(server).ships().remove(ship);
            WorldStore.get(server).requestSave();
            return name + " was scrapped. Its titles no longer work";
        });
    }

    private ShipScrap()
    {
    }
}
