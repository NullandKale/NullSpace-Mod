package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Arriving in a zone that has an orbital station builds it: planned from the zone alone, furnished,
 * published ready with a working port, its frames and stands in place and every container empty.
 */
public final class OrbitalStationGameTest
{
    @GameTest(environment = "nullspace:space_population", maxTicks = 6000)
    public void arrivingInAZoneWithAnOrbitalStationBuildsItFurnished(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var galaxy = StarSystems.galaxy(server);
        long seed = server.overworld().getSeed();
        // The smallest orbital station, in a zone the chart gives no other site, so the build is quick and no other test shares it.
        var others = GalaxyPlan.population(galaxy).keySet();
        String address = galaxy.zones().stream().filter(z -> !others.contains(z) && OrbitalStations.placed(seed, galaxy, z)).min(Comparator.comparingInt(z -> OrbitalStations.spec(seed, z).size().ordinal())).orElseThrow();
        var source = StationGameTest.fixture(space, new BlockPos(1400000, 96, 1400000), true);
        source.putString("localSpace", address);
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "orbital-visitor"), false);
        var player = new net.minecraft.server.level.ServerPlayer(server, ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var at = StationGameTest.boardingPoint(space, source);
        player.teleportTo(space, at.x, at.y, at.z, Set.of(), 0, 0, true);
        var site = SpacePopulation.plan(server, address).stream().filter(s -> s.role().equals("orbital")).findFirst().orElseThrow();
        var poll = new Runnable[1];
        var tries = new int[1];
        poll[0] = () ->
        {
            {
                var station = WorldStore.get(server).repository().find(site.id());
                if (station == null || !StationAccess.ready(station) || BuildJournal.pending(server, site.id()))
                {
                    if (++tries[0] > 1150)
                        ctx.assertTrue(false, "The orbital station was never published ready: " + OrbitalStations.spec(seed, address).description() + ", " + com.nullandkale.nullspace.worldgen.stations.StationStructure.build(com.nullandkale.nullspace.worldgen.stations.StationPlanner.generate(OrbitalStations.spec(seed, address))).blocks().size() + " blocks" + ", record " + (station != null) + ", building " + (station != null && !StationAccess.ready(station)));
                    ctx.runAfterDelay(5, () -> poll[0].run());
                    return;
                }
                try
                {
                    ctx.assertTrue(station.getBooleanOr("worldGenerated", false) && station.getBooleanOr("generatedRooms", false), "Arrival, not a command, created a furnished station");
                    ctx.assertTrue(station.getStringOr("populationRole", "").equals("orbital"), "It is recorded as the zone's orbital station");
                    ctx.assertTrue(!station.getBooleanOr("salvage", false), "A working station is not a derelict site, so it never resets");
                    ctx.assertTrue(!DockingPorts.ports(space, station).isEmpty(), "It has a usable berth");
                    ctx.assertTrue(com.nullandkale.nullspace.gameplay.systems.ShipSystems.power(space, station, com.nullandkale.nullspace.model.power.PowerBus.System.CONTROLS) > 0, "It has controller power");
                    ctx.assertTrue(com.nullandkale.nullspace.gameplay.systems.ShipSystems.power(space, station, com.nullandkale.nullspace.model.power.PowerBus.System.DOORS) > 0, "It has door power: stats " + java.util.Arrays.toString(com.nullandkale.nullspace.gameplay.systems.ShipSystems.powerStats(space, station)) + " fuel " + com.nullandkale.nullspace.gameplay.systems.FuelSystem.available(space, station) + " requests " + java.util.Arrays.toString(station.getCompoundOrEmpty("systems").getIntArray("requests").orElse(new int[0])) + " " + OrbitalStations.spec(seed, address).description());
                    for (var port : DockingPorts.ports(space, station))
                        DockingPorts.readPort(space, station, port.controller(), true);
                    ctx.assertTrue(WorldStore.pos(station, "base").getX() >= 6000 * TravelService.SPACE_SPACING, "Ambient storage does not consume the next player launch slot");
                    var box = WorldStore.bounds(station).box().inflate(4);
                    ctx.assertTrue(!space.getEntitiesOfClass(ItemFrame.class, box).isEmpty(), "Its item frames are hung");
                    int stocked = 0;
                    for (var pos : BlockPos.betweenClosed(WorldStore.bounds(station).min(), WorldStore.bounds(station).max()))
                        if ((space.getBlockEntity(pos) instanceof BarrelBlockEntity || space.getBlockEntity(pos) instanceof ChestBlockEntity) && space.getBlockEntity(pos) instanceof net.minecraft.world.Container container && !container.isEmpty())
                            stocked++;
                    ctx.assertTrue(stocked == 0, "Every container is empty, found " + stocked + " stocked");
                    ctx.succeed();
                }
                finally
                {
                    server.getPlayerList().remove(player);
                    StationGameTest.cleanup(space, source);
                    StationGameTest.cleanup(space, station);
                    WorldStore.get(server).section("spacePopulation").remove(address);
                    WorldStore.get(server).save();
                }
            }
        };
        ctx.runAfterDelay(5, () -> poll[0].run());
    }
}
