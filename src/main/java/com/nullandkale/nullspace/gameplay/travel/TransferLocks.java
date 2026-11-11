package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.Boarding;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.travel.TravelService.Pending;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.*;

/** Which ships, players and areas are busy with a transfer, so two moves never overlap. */
public final class TransferLocks
{

    /** The areas around every ship and pending move in space, read once per tick instead of once per slot asked about. */
    private record Occupancy(int tick, int ships, int pending, long generation, List<net.minecraft.world.phys.AABB> areas)
    {}

    private static final com.nullandkale.nullspace.core.ServerContext.Key<Occupancy> OCCUPANCY = new com.nullandkale.nullspace.core.ServerContext.Key<>();

    private static Occupancy occupancy(MinecraftServer server)
    {
        var store = WorldStore.get(server);
        var pending = TravelService.pending(server);
        var context = com.nullandkale.nullspace.core.ServerContext.get(server);
        var known = context.existing(OCCUPANCY);
        int tick = server.getTickCount(), ships = store.ships().size();
        long generation = store.repository().generation();
        // A reservation or a new ship changes a count, a move changes the generation: the cache then reads again.
        if (known != null && known.tick() == tick && known.ships() == ships && known.pending() == pending.size() && known.generation() == generation)
            return known;
        String space = TravelService.SPACE.identifier().toString();
        var areas = new ArrayList<net.minecraft.world.phys.AABB>();
        for (var p : pending.values())
        {
            if (p.sourceDimension.equals(space))
                areas.add(p.sourceBounds.box().inflate(128));
            if (p.targetDimension.equals(space))
                areas.add(p.targetBounds.box().inflate(128));
        }
        for (var tag : store.ships())
        {
            var ship = (CompoundTag) tag;
            if (ship.getStringOr("dimension", "").equals(space))
                areas.add(WorldStore.bounds(ship).box().inflate(128));
        }
        var built = new Occupancy(tick, ships, pending.size(), generation, areas);
        context.put(OCCUPANCY, built);
        return built;
    }

    public static boolean spaceSlotFree(MinecraftServer server, int slot)
    {
        if (slot < 0 || slot > 7000)
            return false;
        var base = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
        var area = new net.minecraft.world.phys.AABB(base.getX() - 128, -32, -128, base.getX() + 160, 256, 160);
        var space = server.getLevel(TravelService.SPACE);
        if (space != null && UnresolvedJournals.areaBusy(space, new HullScan.Bounds(base.offset(-128, -128, -128), base.offset(159, 159, 159))))
            return false;
        for (var occupied : occupancy(server).areas())
            if (area.intersects(occupied))
                return false;
        return true;
    }

    public static boolean locked(ServerLevel level, BlockPos pos)
    {
        if (TravelService.worldMutation())
            return false;
        // Terra Harbor is not a transit area: its shell has its own player protection.
        return ShipStorage.locked(level, pos) || StationAccess.buildingAt(level, pos) || NavigationService.locked(level, pos) || physicalAreaBusy(level, new HullScan.Bounds(pos, pos), null);
    }

    static boolean physicalAreaBusy(ServerLevel level, HullScan.Bounds bounds, Pending ignored)
    {
        String dimension = level.dimension().identifier().toString();
        if (UnresolvedJournals.areaBusy(level, bounds))
            return true;
        return TravelService.pending(level.getServer()).values().stream().anyMatch(p -> p != ignored && (p.sourceDimension.equals(dimension) && p.sourceBounds.box().intersects(bounds.box()) || p.targetDimension.equals(dimension) && p.targetBounds.box().intersects(bounds.box())));
    }

    public static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds)
    {
        return areaBusy(level, bounds, null, null);
    }

    public static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds, UUID recallOwner)
    {
        return areaBusy(level, bounds, null, recallOwner);
    }

    static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds, Pending ignored, UUID recallOwner)
    {
        return physicalAreaBusy(level, bounds, ignored) || ShipStorage.areaBusy(level, bounds, recallOwner) || NavigationService.areaBusy(level, bounds);
    }

    public static boolean inTransit(MinecraftServer server, CompoundTag ship)
    {
        if (ship == null)
            return false;
        String id = ship.getStringOr("id", "");
        return physicalBusy(server, ship) || ShipStorage.active(server, id) || NavigationService.pending(server, id);
    }

    public static boolean physicalBusy(MinecraftServer server, CompoundTag ship)
    {
        if (ship == null)
            return false;
        String id = ship.getStringOr("id", ""), dimension = ship.getStringOr("dimension", "");
        if (UnresolvedJournals.shipBusy(server, id))
            return true;
        var bounds = WorldStore.bounds(ship);
        return TravelService.pending(server).values().stream().anyMatch(p -> !id.isEmpty() && p.ship.equals(id) || p.sourceDimension.equals(dimension) && p.sourceBounds.box().intersects(bounds.box()) || p.targetDimension.equals(dimension) && p.targetBounds.box().intersects(bounds.box()));
    }

    public static boolean busy(MinecraftServer server, CompoundTag ship)
    {
        return inTransit(server, ship) || ship != null && Boarding.boarding(server, ship.getStringOr("id", ""));
    }

    public static boolean active(MinecraftServer server)
    {
        return ShipStorage.active(server) || physicalTransferActive(server);
    }

    static boolean physicalTransferActive(MinecraftServer server)
    {
        return StationAccess.exclusiveConstruction(server) || !WorldStore.get(server).list("stationBoarding").isEmpty() || !TravelService.pending(server).isEmpty();
    }

    public static boolean physicalBusy(ServerPlayer player)
    {
        return TravelService.pending(player.level().getServer()).values().stream().anyMatch(p -> p.crew.containsKey(player.getUUID()));
    }

    public static boolean busy(ServerPlayer player)
    {
        return Boarding.busy(player) || NavigationService.busy(player) || physicalBusy(player);
    }

    private TransferLocks()
    {
    }
}
