package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYard;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationDeployment;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.station.StationGraph;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.flight.ShipHeading;
import com.nullandkale.nullspace.worldgen.blueprint.ShipRooms;
import com.nullandkale.nullspace.worldgen.stations.ManufacturerStation;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.*;

public final class ManufacturerTemplateGameTest
{
    @GameTest
    public void everyDeliverableShipUsesItsEnginesToDefineTheBow(GameTestHelper ctx)
    {
        for (var preset : ShipBlueprints.PRESETS)
        {
            var exhausts = ShipBlueprints.blocks(preset).values().stream().filter(s -> s.is(ModBlocks.ENGINE)).map(s -> s.getValue(HorizontalDirectionalBlock.FACING)).toList();
            ctx.assertTrue(!exhausts.isEmpty() && ShipHeading.fromExhausts(exhausts) == Direction.NORTH, preset.name() + " must fly toward its bow, regardless of its side-mounted controller");
        }
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:manufacturer_save", maxTicks = 80)
    public void savingUsesAFrozenDocumentAndRetainsChangesMadeDuringTheWrite(GameTestHelper ctx) throws Exception
    {
        var store = WorldStore.get(ctx.getLevel().getServer());
        store.document("manufacturerSaveProbe", net.minecraft.nbt.IntTag.valueOf(1));
        var observer = WorldStore.writeObserver;
        boolean[] changed = {false};
        try
        {
            WorldStore.writeObserver = progress ->
            {
                if (!changed[0] && progress.file().getFileName().toString().startsWith("ships-"))
                {
                    changed[0] = true;
                    store.document("manufacturerSaveProbe", net.minecraft.nbt.IntTag.valueOf(2));
                }
            };
            store.save();
        }
        finally
        {
            WorldStore.writeObserver = observer;
        }
        ctx.assertTrue(changed[0] && WorldStore.read(store.storeFile()).getIntOr("manufacturerSaveProbe", -1) == 1, "The committed file is the complete original snapshot, not changing live metadata");
        ctx.runAfterDelay(25, () ->
        {
            try
            {
                WorldStore.worldSaved(ctx.getLevel().getServer(), false);
                ctx.assertTrue(WorldStore.read(store.storeFile()).getIntOr("manufacturerSaveProbe", -1) == 2, "A change during IO retains its request and reaches the next world save");
                store.document("manufacturerSaveProbe", null);
                ctx.succeed();
            }
            catch (java.io.IOException error)
            {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }

    @GameTest(environment = "nullspace:manufacturer_templates", maxTicks = 300)
    public void manufacturerScoutsHaveDistinctHullProfilesAndReachableSealedRooms(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        int index = 0;
        var footprints = new HashSet<Set<BlockPos>>();
        for (var preset : ShipBlueprints.PRESETS)
        {
            if (!preset.role().equals("Scout"))
                continue;
            var base = new BlockPos(600000 + index++ * 4096, 80, 600000);
            ShipYard.buildPad(level, base, 32, 32, "Manufacturer test " + preset.id());
            var pad = new PadScanner.Pad(base.getX(), base.getX() + 31, base.getY(), base.getZ(), base.getZ() + 31);
            var origin = ShipBlueprints.origin(preset, pad, 32);
            var plan = ShipBlueprints.blocks(preset);
            var floor = new HashSet<BlockPos>();
            for (var e : plan.entrySet())
            {
                level.setBlock(origin.offset(e.getKey()), e.getValue(), 18);
                if (e.getKey().getY() == 0)
                    floor.add(e.getKey());
            }
            ctx.assertTrue(footprints.add(floor), "Manufacturer hulls are different geometry, not paint swaps");
            var scan = HullScan.inspect(level, origin.offset(preset.helm()), pad, true);
            ctx.assertTrue(scan.blocks().size() == plan.size(), "Every pod and mounted part is face-connected");
            var ship = ShipTopology.resolve(level, origin.offset(preset.helm()));
            for (var at : scan.blocks())
                FuelSystem.provision(level, at, 64);
            SystemsUpgrade.upgrade(level, ship);
            ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, 4, 1, 3, 2, 0});
            ShipSystems.tick(level, ship);
            var port = DockingPorts.firstPort(level, ship);
            ctx.assertTrue(DockingPorts.ports(level, ship).size() == 1 && port.anchors().size() >= 2, "Scout has one usable powered dock");
            for (var room : ShipRooms.rooms(preset.id()))
            {
                ctx.assertTrue(scan.pressure().breathable(origin.offset(room.walk()).above()) && reachable(level, port.anchors().getFirst(), origin.offset(room.walk())), preset.name() + " has a safe walking route to " + room.name());
                if (room.supplies() != null)
                    ctx.assertTrue(level.getBlockEntity(origin.offset(room.supplies())) instanceof net.minecraft.world.Container, "Resource nooks have actual storage");
            }
        }
        ctx.assertTrue(index == 3, "One review scout per manufacturer");
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:manufacturer_showcase", maxTicks = 300)
    public void compactShowcaseBuildsOnceAndConnectsBothBerthsToAllManufacturerRooms(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var source = StationGameTest.fixture(space, new BlockPos(950000, 96, 950000), true);
        source.putDouble("simX", 4000);
        source.putDouble("simZ", 4000);
        var p = ctx.makeMockServerPlayerInLevel();
        var boarding = StationGameTest.boardingPoint(space, source);
        p.teleportTo(space, boarding.x, boarding.y, boarding.z, Set.of(), 0, 0, true);
        var station = StationDeployment.showcase(p);
        ctx.assertTrue(StationDeployment.showcase(p) == station, "Repeated requests keep one station identity");
        ctx.onEachTick(() ->
        {
            if (!StationAccess.ready(station) || com.nullandkale.nullspace.gameplay.travel.BuildJournal.pending(server, station.getStringOr("id", "")))
                return;
            var base = WorldStore.pos(station, "base");
            var ports = DockingPorts.ports(space, station);
            ctx.assertTrue(ports.size() == 2, "Compact station has two opposite usable berths");
            var graph = StationGraph.inspect(space, station);
            ctx.assertTrue(graph.nodes().size() == 18, "Sixteen room doors and two dock nodes come from physical blocks: " + graph.nodes().size());
            for (var port : ports)
                for (var room : ManufacturerStation.ROOMS.entrySet())
                {
                    var at = base.offset(room.getValue());
                    ctx.assertTrue(reachable(space, port.anchors().getFirst(), at), "Berth reaches " + room.getKey());
                    ctx.assertTrue(ShipAir.breathable(space, station, at.above()), "Room is sealed and supplied: " + room.getKey());
                }
            for (var at : ShipSystems.connected(space, station))
                ctx.assertTrue(WorldStore.bounds(station).contains(at), "Showcase fits one 32-block allocation");
            var edited = base.offset(24, 2, 3);
            space.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
            ctx.assertTrue(StationDeployment.showcase(p).getStringOr("id", "").equals(station.getStringOr("id", "")) && space.getBlockState(edited).is(Blocks.DIAMOND_BLOCK), "Revisiting keeps the saved station identity and never erases edits");
            ctx.succeed();
        });
    }

    private static boolean reachable(net.minecraft.server.level.ServerLevel level, BlockPos from, BlockPos to)
    {
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(from);
        while (!queue.isEmpty() && seen.size() < 4096)
        {
            var at = queue.removeFirst();
            if (!seen.add(at))
                continue;
            if (at.equals(to))
                return true;
            for (var d : Direction.Plane.HORIZONTAL)
            {
                var next = at.relative(d);
                if (!level.getBlockState(next.below()).isCollisionShapeFullBlock(level, next.below()))
                    continue;
                var low = level.getBlockState(next);
                var high = level.getBlockState(next.above());
                boolean door = low.getBlock() instanceof DoorBlock && ShipDoors.framed(level, next);
                if (door || low.getCollisionShape(level, next).isEmpty() && high.getCollisionShape(level, next.above()).isEmpty())
                    queue.add(next);
            }
        }
        return false;
    }
}
