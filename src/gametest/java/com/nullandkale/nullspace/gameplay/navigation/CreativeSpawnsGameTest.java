package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.encounters.SpacePopulation;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;

/** The Creative System Upgrade makes a Scanner tier 4 and lets it spawn star systems and encounters as destinations. */
public final class CreativeSpawnsGameTest
{
    @GameTest
    public void theCreativeUpgradeMakesAScannerTierFourAndGoesNowhereElse(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var at = ctx.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(at, ModBlocks.LOCAL_SCANNER.defaultBlockState(), 3);
        var scanner = (MachineBlockEntity) level.getBlockEntity(at);
        var creative = new ItemStack(ModBlocks.CREATIVE_UPGRADE);
        ctx.assertTrue(ModBlocks.upgradeRank(creative) == 4, "It ranks above the exotic upgrade");
        ctx.assertTrue(scanner.canPlaceItem(0, creative), "A Scanner accepts it");
        scanner.setItem(0, creative.copy());
        ctx.assertTrue(scanner.tier() == 4, "One creative upgrade makes a Scanner tier 4, was " + scanner.tier());
        for (var block : List.of(ModBlocks.ENGINE, ModBlocks.GENERATOR, ModBlocks.BATTERY))
        {
            level.setBlock(at.above(), block.defaultBlockState(), 3);
            var machine = (MachineBlockEntity) level.getBlockEntity(at.above());
            ctx.assertTrue(!machine.canPlaceItem(0, creative), "Only a Scanner takes it: " + block.getName().getString());
        }
        ctx.assertTrue(level.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, net.minecraft.resources.Identifier.parse("nullspace:creative_system_upgrade"))).isEmpty(), "It has no recipe");
        ctx.succeed();
    }

    @GameTest(maxTicks = 100)
    public void spawningASystemAddsReachableDestinationsToTheSavedChart(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var ship = StationGameTest.fixture(space, new BlockPos(1500000, 96, 1500000), true);
        var original = StarSystems.galaxy(server);
        try
        {
            var before = StarSystems.destinations(server).stream().map(LocalSpaces.Destination::id).collect(java.util.stream.Collectors.toSet());
            int systems = StarSystems.galaxy(server).systems().size();
            String line = CreativeSpawns.system(server, ship);
            var galaxy = StarSystems.galaxy(server);
            ctx.assertTrue(galaxy.systems().size() == systems + 1, "The chart gains one system");
            var added = galaxy.systems().getLast();
            var fresh = StarSystems.destinations(server).stream().map(LocalSpaces.Destination::id).filter(id -> !before.contains(id)).toList();
            ctx.assertTrue(!fresh.isEmpty() && fresh.containsAll(added.bodies().stream().map(GalaxyPlan.Body::id).toList()), "Every body of the new system is a destination: " + fresh);
            ctx.assertTrue(line.contains(added.name()), "The Scanner says what was made: " + line);
            ctx.assertTrue(GalaxyPlan.connected(galaxy, GalaxyPlan.STARTING_RANGE), "The new system is reachable at the starting range");
            ctx.assertTrue(LocalSpaces.get(server, added.bodies().getFirst().id()).systemName().equals(added.name()), "Its zones resolve to the new system");
            ctx.succeed();
        }
        finally
        {
            // The chart is the world's; other tests expect Sol and four systems.
            StarSystems.replace(server, original);
            StationGameTest.cleanup(space, ship);
        }
    }

    @GameTest(environment = "nullspace:space_population", maxTicks = 6000)
    public void spawningAnEncounterMakesADestinationThatBuildsItsSiteAtTheChosenDanger(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var space = server.getLevel(TravelService.SPACE);
        var source = StationGameTest.fixture(space, new BlockPos(1600000, 96, 1600000), true);
        var original = StarSystems.galaxy(server);
        String line = CreativeSpawns.encounter(server, source, 3, 2);
        var zone = StarSystems.galaxy(server).features().getLast();
        ctx.assertTrue(line.contains(zone.id()) && line.contains("danger 2"), "The Scanner says where it is: " + line);
        ctx.assertTrue(LocalSpaces.get(server, zone.id()) != null && StarSystems.destinations(server).stream().anyMatch(d -> d.id().equals(zone.id())), "The encounter is a destination");
        ctx.assertTrue(StarSystems.danger(server, zone.id()) == 2, "Its danger is the one chosen");
        source.putString("localSpace", zone.id());
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "creative-visitor"), false);
        var player = new net.minecraft.server.level.ServerPlayer(server, ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var at = StationGameTest.boardingPoint(space, source);
        player.teleportTo(space, at.x, at.y, at.z, Set.of(), 0, 0, true);
        var sites = SpacePopulation.plan(server, zone.id());
        ctx.assertTrue(sites.size() == 1 && sites.getFirst().role().equals("creative"), "The zone holds only the spawned encounter");
        var poll = new Runnable[1];
        var tries = new int[1];
        poll[0] = () ->
        {
            var station = WorldStore.get(server).repository().find(sites.getFirst().id());
            if (station == null || !StationAccess.ready(station) || BuildJournal.pending(server, sites.getFirst().id()))
            {
                if (++tries[0] > 1150)
                    ctx.assertTrue(false, "The spawned encounter was never built");
                ctx.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            try
            {
                ctx.assertTrue(station.getStringOr("populationRole", "").equals("creative"), "It is recorded as the zone's spawned encounter");
                ctx.assertTrue(station.getIntOr("encounterKind", -1) == 3, "It is the kind that was chosen: " + station.getIntOr("encounterKind", -1));
                ctx.assertTrue(station.getIntOr("encounterDanger", -1) == 2, "It keeps the chosen danger");
                ctx.succeed();
            }
            finally
            {
                server.getPlayerList().remove(player);
                StationGameTest.cleanup(space, source);
                StationGameTest.cleanup(space, station);
                WorldStore.get(server).section("spacePopulation").remove(zone.id());
                WorldStore.get(server).section("creativeSites").remove(zone.id());
                StarSystems.replace(server, original);
                WorldStore.get(server).save();
            }
        };
        ctx.runAfterDelay(5, () -> poll[0].run());
    }
}
