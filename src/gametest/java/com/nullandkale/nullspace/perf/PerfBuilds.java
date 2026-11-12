package com.nullandkale.nullspace.perf;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.*;
import com.nullandkale.nullspace.gameplay.ownership.*;
import com.nullandkale.nullspace.gameplay.pads.*;
import com.nullandkale.nullspace.gameplay.station.*;
import com.nullandkale.nullspace.gameplay.travel.*;
import com.nullandkale.nullspace.worldgen.stations.*;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** The BUILD workload uses the same services as room kits, delivery beacons and boarding collars. */
final class PerfBuilds implements PerfRun.Operation
{
    private final PerfWorld world;
    private CompoundTag workshop;
    private final BlockPos floor = new BlockPos(7168, 96, 7168);
    private final BlockPos beacon = floor.offset(-1, 1, 1);
    private final BlockPos computer;
    private CompoundTag delivered, pending;
    private int nextRoom = 1;
    private boolean furnished;
    private boolean awaitingScrap;
    private boolean awaitingCollar;

    PerfBuilds(PerfWorld world) throws java.io.IOException
    {
        this.world = world;
        var level = world.server().overworld();
        com.nullandkale.nullspace.gameplay.shipyard.ShipYard.buildPad(level, floor, 32, 32, "Perf Delivery");
        for (int x = (floor.getX() - 1) >> 4; x <= (floor.getX() + 32) >> 4; x++)
            for (int z = (floor.getZ() - 1) >> 4; z <= (floor.getZ() + 32) >> 4; z++)
                level.setChunkForced(x, z, true);
        level.setBlock(beacon, ModBlocks.SHIP_BEACON.defaultBlockState(), 18);
        computer = PadRegistry.read(world.server()).stream().filter(p -> p.name().equals("Perf Delivery")).findFirst().orElseThrow().computer();
        world.builder.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        world.builder.getInventory().add(new ItemStack(ModBlocks.STATION_MODULE, 64));
        workshop = StationBuilds.queue(world.server(), UUID.nameUUIDFromBytes("perf-workshop".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), "Perf Workshop", com.nullandkale.nullspace.model.galaxy.LocalSpaces.HOME, new Vec3(20000, 0, 20000), world.builder.getUUID().toString(), StationBlueprint.foundation(), true);
        // The workshop is fixture setup, rather than a player deploying an inventory kit.
        BuildJournal.resume(level, workshop.getStringOr("buildJournal", "")).step(() ->
        {
            workshop.putBoolean("buildPaid", true);
            return null;
        });
    }

    boolean prepare()
    {
        workshop = WorldStore.get(world.server()).repository().find(workshop.getStringOr("id", ""));
        if (!StationAccess.ready(workshop))
            return false;
        // The operator works at these berths during the workload. Ambient construction
        // tickets expire after setup; keep the actual collar hulls available through warm-up.
        for (var station : world.stations.subList(0, Math.min(2, world.stations.size())))
        {
            var level = WorldStore.level(world.server(), station.getStringOr("dimension", ""));
            var bounds = WorldStore.bounds(station);
            for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
                for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                {
                    level.setChunkForced(x, z, true);
                    level.getChunk(x, z);
                }
        }
        if (!furnished)
        {
            var level = WorldStore.level(world.server(), workshop.getStringOr("dimension", ""));
            var base = WorldStore.pos(workshop, "base");
            world.builder.teleportTo(level, base.getX() - 2.5, base.getY() + 1, base.getZ() - 2.5, Set.of(), 0, 0, true);
            StationBuilds.expand(world.builder, workshop);
            StationBuilds.buildRoom(world.builder, workshop, StationPieces.forRole(StationLayout.Role.ARRIVAL).id() + " 0 0", false);
            furnished = true;
            return false;
        }
        return true;
    }

    @Override
    public String run(int ordinal)
    {
        world.refresh();
        workshop = WorldStore.get(world.server()).repository().find(workshop.getStringOr("id", ""));
        if (awaitingCollar)
        {
            if (BuildJournal.pending(world.server(), world.stations.getFirst().getStringOr("id", "")))
                return PerfRun.PENDING;
            awaitingCollar = false;
            return collar();
        }
        if (pending != null)
        {
            pending = WorldStore.get(world.server()).repository().find(pending.getStringOr("id", ""));
            if (!StationAccess.ready(pending) || BuildJournal.pending(world.server(), pending.getStringOr("id", "")))
                return PerfRun.PENDING;
            pending = null;
            return "";
        }
        return switch (ordinal % 3)
        {
            case 0 -> room();
            case 1 -> delivery();
            default -> collar();
        };
    }

    private String room()
    {
        if (nextRoom >= 64)
            throw new IllegalStateException("The fixture ran out of room footprints; use the representative 40/100/300 sizes or shorter phases");
        int row = nextRoom / 8, column = nextRoom % 8;
        if (row % 2 == 1)
            column = 7 - column;
        var level = WorldStore.level(world.server(), workshop.getStringOr("dimension", ""));
        var base = WorldStore.pos(workshop, "base");
        world.builder.teleportTo(level, base.getX() - 2.5, base.getY() + 1, base.getZ() - 2.5, Set.of(), 0, 0, true);
        StationBuilds.buildRoom(world.builder, workshop, StationPieces.forRole(StationLayout.Role.HABITATION).id() + " " + column + " " + row, false);
        nextRoom++;
        pending = workshop;
        return PerfRun.PENDING;
    }

    private String delivery()
    {
        var level = world.server().overworld();
        if (awaitingScrap)
        {
            if (TransferLocks.areaBusy(level, new com.nullandkale.nullspace.gameplay.ship.structure.HullScan.Bounds(floor.above(), floor.offset(31, 32, 31))))
                return PerfRun.PENDING;
            awaitingScrap = false;
        }
        world.builder.teleportTo(level, floor.getX() - 2.5, floor.getY() + 1, floor.getZ() + 1.5, Set.of(), 0, 0, true);
        if (delivered != null)
        {
            world.builder.teleportTo(level, computer.getX() + .5, computer.getY() + 1, computer.getZ() + .5, Set.of(), 0, 0, true);
            String selection = UUID.nameUUIDFromBytes(("recall:" + delivered.getStringOr("id", "")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
            String confirmation = ShipScrap.scrap(world.builder, computer, selection);
            String result = ShipScrap.scrap(world.builder, computer, selection);
            if (!result.contains("scrapped"))
                throw new IllegalStateException("Fixture delivery cleanup failed: " + confirmation + "; " + result);
            delivered = null;
            awaitingScrap = true;
            return PerfRun.PENDING;
        }
        world.builder.teleportTo(level, floor.getX() - 2.5, floor.getY() + 1, floor.getZ() + 1.5, Set.of(), 0, 0, true);
        try
        {
            delivered = com.nullandkale.nullspace.gameplay.shipyard.ShipYardService.deliver(world.builder, beacon, "rivet_bolt");
            CrewRelease.authorize(delivered, world.builder);
            pending = delivered;
            return PerfRun.PENDING;
        }
        catch (IllegalArgumentException loading)
        {
            if (loading.getMessage().contains("loading"))
                return PerfRun.PENDING;
            throw loading;
        }
    }

    private String collar()
    {
        var source = world.stations.getFirst();
        var target = world.stations.size() > 1 ? world.stations.get(1) : source;
        var level = WorldStore.level(world.server(), source.getStringOr("dimension", ""));
        if (source.contains("dock") && !source.getCompoundOrEmpty("dock").getBooleanOr("tunnelRetracted", false))
        {
            DockingTunnel.retract(world.server(), source.getCompoundOrEmpty("dock"));
            awaitingCollar = true;
            return PerfRun.PENDING;
        }
        RecordJournal.run(world.server(), "perf-collar-link", () ->
        {
            var a = DockingPorts.ports(level, source).getFirst();
            var b = DockingPorts.ports(level, target).get(target == source ? 1 : 0);
            var dock = new CompoundTag();
            dock.putString("source", source.getStringOr("id", ""));
            dock.putString("target", target.getStringOr("id", ""));
            dock.putString("state", "CLAMPED");
            dock.putDouble("gap", 3);
            source.put("dock", dock);
            DockingTunnel.ensure(world.server(), dock, a, b);
            return "";
        });
        pending = source;
        return PerfRun.PENDING;
    }
}
