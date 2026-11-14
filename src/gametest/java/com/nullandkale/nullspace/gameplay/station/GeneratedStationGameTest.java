package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingManeuver;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.*;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A planned, furnished station built through the real construction queue in space: it is published
 * ready, its docking ports work, its item frames and armor stands appear once, and every chest and
 * barrel in it is empty.
 */
public final class GeneratedStationGameTest
{
    @GameTest(environment = "nullspace:station_building", maxTicks = 900)
    public void aPlannedStationIsBuiltFurnishedAndItsDisplaysAppear(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var spec = new StationSpec(Maker.HALCYON, StationSpec.Purpose.RESEARCH, StationSpec.Size.COMPACT, StationSpec.Condition.OPERATIONAL, 91);
        var blueprint = StationStructure.build(StationPlanner.generate(spec));
        var metadata = new CompoundTag();
        metadata.putBoolean("generatedRooms", true);
        metadata.put("stationSpec", spec.save());
        var station = StationBuilds.queue(server, UUID.randomUUID().toString(), "Planned station", "sol_luna", new Vec3(-2000, 0, -2000), "", blueprint, false, metadata);
        ctx.assertTrue(station != null, "Space is reserved for the planned station");
        long frames = blueprint.points().stream().filter(p -> p.name().equals("frame")).count();
        long stands = blueprint.points().stream().filter(p -> p.name().equals("stand")).count();
        ctx.assertTrue(frames > 0, "The furnished rooms ask for item frames");
        var poll = new Runnable[1];
        var tries = new int[1];
        poll[0] = () ->
        {
            // The build's journal commits after the station is ready; cleaning up before it does lets the commit put blocks back.
            if (!StationAccess.ready(station) || com.nullandkale.nullspace.gameplay.travel.BuildJournal.pending(server, station.getStringOr("id", "")))
            {
                if (++tries[0] > 40)
                    ctx.assertTrue(false, "The planned station was never published ready");
                ctx.runAfterDelay(20, () -> poll[0].run());
                return;
            }
            var level = server.getLevel(TravelService.SPACE);
            try
            {
                var box = WorldStore.bounds(station).box().inflate(4);
                ctx.assertTrue(DockingPorts.ports(level, station).size() >= 1, "The planned station has a working docking port");
                int placed = level.getEntitiesOfClass(ItemFrame.class, box).size();
                ctx.assertTrue(placed >= frames * 8 / 10, "Item frames appear once the build is done: " + placed + " of " + frames);
                int standing = level.getEntitiesOfClass(ArmorStand.class, box).size();
                ctx.assertTrue(standing >= stands * 8 / 10, "Armor stands appear once the build is done: " + standing + " of " + stands);
                int stocked = 0;
                for (var at : BlockPos.betweenClosed(WorldStore.bounds(station).min(), WorldStore.bounds(station).max()))
                    if ((level.getBlockEntity(at) instanceof BarrelBlockEntity || level.getBlockEntity(at) instanceof ChestBlockEntity) && level.getBlockEntity(at) instanceof net.minecraft.world.Container container && !container.isEmpty())
                        stocked++;
                ctx.assertTrue(stocked == 0, "Designed rooms leave every container empty, found " + stocked + " stocked");
                ctx.succeed();
            }
            finally
            {
                StationGameTest.cleanup(level, station);
            }
        };
        ctx.runAfterDelay(20, () -> poll[0].run());
    }

    /** A visiting ship asks to dock at a planned, operational station: its ports have power and the capture completes. */
    @GameTest(environment = "nullspace:station_building", maxTicks = 3000)
    public void aShipCanDockAtAPlannedStation(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var spec = new StationSpec(Maker.HALCYON, StationSpec.Purpose.RESEARCH, StationSpec.Size.COMPACT, StationSpec.Condition.OPERATIONAL, 91);
        var blueprint = StationStructure.build(StationPlanner.generate(spec));
        var metadata = new CompoundTag();
        metadata.putBoolean("generatedRooms", true);
        metadata.putBoolean("worldGenerated", true);
        metadata.put("stationSpec", spec.save());
        var station = StationBuilds.queue(server, UUID.randomUUID().toString(), "Dock station", "sol_terra", new Vec3(-3000, 0, -2800), "", blueprint, false, metadata);
        ctx.assertTrue(station != null, "Space is reserved for the planned station");
        var ship = StationGameTest.fixture(space, new BlockPos(1600000, 96, 1600000), true);
        var player = ctx.makeMockServerPlayerInLevel();
        var at = StationGameTest.boardingPoint(space, ship);
        player.teleportTo(space, at.x, at.y, at.z, Set.of(), 0, 0, true);
        var poll = new Runnable[1];
        var tries = new int[1];
        var requested = new boolean[1];
        poll[0] = () ->
        {
            try
            {
                if (!StationAccess.ready(station) || com.nullandkale.nullspace.gameplay.travel.BuildJournal.pending(server, station.getStringOr("id", "")))
                {
                    if (++tries[0] > 60)
                        ctx.assertTrue(false, "The planned station was never published ready");
                    ctx.runAfterDelay(20, () -> poll[0].run());
                    return;
                }
                var ports = DockingPorts.ports(space, station);
                ctx.assertTrue(!ports.isEmpty(), "The station has berths");
                ctx.assertTrue(ShipSystems.power(space, station, PowerBus.System.CONTROLS) > 0, "The station has controller power");
                ctx.assertTrue(ShipSystems.power(space, station, PowerBus.System.DOORS) > 0, "The station has door power");
                for (var port : ports)
                    DockingPorts.readPort(space, station, port.controller(), true);
                if (!requested[0])
                {
                    requested[0] = true;
                    var found = WorldStore.get(server).repository().find(station.getStringOr("id", ""));
                    ctx.assertTrue(found != null, "The station is found by id");
                    ctx.assertTrue(com.nullandkale.nullspace.model.galaxy.LocalSpaces.together(ship, found), "Ship and station share a space: " + ship.getStringOr("localSpace", "") + " / " + found.getStringOr("localSpace", ""));
                    ctx.assertTrue(StationAccess.allowed(player, found), "A visitor is allowed: access " + found.getStringOr("access", ""));
                    ctx.assertTrue(StationAccess.ready(found) && !found.contains("dock"), "The station is idle: " + found.getCompoundOrEmpty("dock"));
                    DockingManeuver.request(player, ship, station.getStringOr("id", ""), ports.getFirst().controller().subtract(WorldStore.pos(station, "base")));
                    tries[0] = 0;
                }
                var current = WorldStore.get(server).repository().find(ship.getStringOr("id", ""));
                if (current == null || !current.getCompoundOrEmpty("dock").getStringOr("state", "").equals("CLAMPED"))
                {
                    if (++tries[0] > 120)
                        ctx.assertTrue(false, "The ship never captured: " + (current == null ? "record missing" : current.getStringOr("flightStatus", "") + " " + current.getCompoundOrEmpty("dock")));
                    ctx.runAfterDelay(20, () -> poll[0].run());
                    return;
                }
                ctx.succeed();
            }
            catch (RuntimeException failure)
            {
                StationGameTest.cleanup(space, ship);
                StationGameTest.cleanup(space, station);
                throw failure;
            }
            StationGameTest.cleanup(space, ship);
            StationGameTest.cleanup(space, station);
        };
        ctx.runAfterDelay(20, () -> poll[0].run());
    }
}
