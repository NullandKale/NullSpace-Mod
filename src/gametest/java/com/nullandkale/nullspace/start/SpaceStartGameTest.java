package com.nullandkale.nullspace.start;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.station.DockingService;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class SpaceStartGameTest {
  @GameTest(environment = "nullspace:space_start")
  public void nativePlayerDataAndBackupsNeverAwardAnotherStartingShip(GameTestHelper ctx)
      throws Exception {
    var server = ctx.getLevel().getServer();
    var data = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
    java.nio.file.Files.createDirectories(data);
    for (String suffix : List.of(".dat", ".dat_old")) {
      var player = ctx.makeMockServerPlayerInLevel();
      var saved = data.resolve(player.getUUID() + suffix);
      java.nio.file.Files.writeString(saved, "Existing native player data");
      ctx.assertTrue(
          !SpaceStart.firstArrival(player),
          "Native player " + suffix + " must preserve the existing start");
      java.nio.file.Files.delete(saved);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:space_start", maxTicks = 300)
  public void existingPlayerBackupsArePreservedAndInterruptedStartsResumeTheirShip(
      GameTestHelper ctx) throws Exception {
    var existing = ctx.makeMockServerPlayerInLevel();
    var server = ctx.getLevel().getServer();
    var backup =
        server
            .getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
            .resolve(existing.getUUID() + ".dat_old");
    java.nio.file.Files.createDirectories(backup.getParent());
    java.nio.file.Files.writeString(backup, "existing player backup");
    ctx.assertTrue(
        !SpaceStart.firstArrival(existing),
        "Existing player backups never trigger a starting ship");
    var player = ctx.makeMockServerPlayerInLevel();
    var settings = new WorldStartSettings(true, "sparrow");
    var ship = SpaceStart.start(player, settings);
    var record =
        ShipStore.get(server)
            .section("spaceStarts")
            .getCompoundOrEmpty(player.getUUID().toString());
    record.putBoolean("completed", false);
    ctx.assertTrue(
        SpaceStart.firstArrival(player),
        "A saved construction intent resumes even when player data already exists");
    var floor = ShipStore.pos(ship, "base").offset(2, 0, 3);
    ctx.assertTrue(
        !player.level().getBlockState(floor).isAir(), "Fixture removes an actual deck block");
    player.level().setBlock(floor, Blocks.AIR.defaultBlockState(), 18);
    int bread = player.getInventory().countItem(Items.BREAD);
    var resumed = SpaceStart.start(player, new WorldStartSettings(true, "atlas"));
    ctx.assertTrue(
        resumed == ship && resumed.getStringOr("blueprint", "").equals("sparrow"),
        "Recovery keeps the reserved ship and preset");
    ctx.assertTrue(
        !player.level().getBlockState(floor).isAir(),
        "Interrupted construction finishes missing hull blocks");
    ctx.assertTrue(
        player.getInventory().countItem(Items.BREAD) == bread,
        "Recovery never duplicates previously delivered food");
    ctx.assertTrue(!SpaceStart.firstArrival(player), "A finished start cannot be awarded again");
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:space_start", maxTicks = 300)
  public void firstJoinHasAWorkingShipAndRepeatJoinsNeverReplaceIt(GameTestHelper ctx) {
    var player = ctx.makeMockServerPlayerInLevel();
    var server = ctx.getLevel().getServer();
    var settings = new WorldStartSettings(true, "sparrow");
    var ship = SpaceStart.start(player, settings);
    ctx.assertTrue(
        player.level().dimension().equals(TravelService.SPACE), "New player boards space ship");
    ctx.assertTrue(
        ship.getStringOr("owner", "").equals(player.getUUID().toString()),
        "Ship belongs to the player");
    ctx.assertTrue(ship.getStringOr("blueprint", "").equals("sparrow"), "Selected ship was built");
    ctx.assertTrue(
        ShipSystems.enabled(ship)
            && ShipSystems.breathable(player.level(), ship, player.blockPosition().above()),
        "New cabin has functioning finite air");
    ctx.assertTrue(
        FuelSystem.available(player.level(), ship) > 0, "Generator contains real burnable fuel");
    ctx.assertTrue(
        !DockingService.ports(player.level(), ship).isEmpty(),
        "Ship has a usable normal-door dock");
    ctx.assertTrue(SpaceSurvival.suited(player), "Player has a complete suit");
    ctx.assertTrue(player.getInventory().hasAnyOf(Set.of(Items.BREAD)), "Player has food");
    var planet = LocalSpaces.get(server, LocalSpaces.address(ship));
    ctx.assertTrue(!planet.moon(), "Start orbits a landable planet rather than a moon");
    var surface = com.nullandkale.nullspace.navigation.PlanetSurfaces.dimension(server, planet.id());
    ctx.assertTrue(
        com.nullandkale.nullspace.ship.PadRegistry.read(server).stream()
            .anyMatch(
                p ->
                    p.dimension().equals(surface.identifier().toString())
                        && com.nullandkale.nullspace.ship.PadRegistry.check(
                                    server.getLevel(surface), p)
                                .state()
                            == com.nullandkale.nullspace.ship.PadRegistry.State.READY),
        "The first starting planet already has a usable landing destination, before any FTL jump");
    ctx.assertTrue(
        LocalSpaces.address(ship).equals(SpaceStart.planet(server, player.getUUID())),
        "Random starting planet is stable for the same player and world");
    String id = ship.getStringOr("id", "");
    int before = ShipStore.get(server).ships().size();
    var hull = ShipStore.pos(ship, "base").offset(2, 0, 3);
    ctx.assertTrue(
        !player.level().getBlockState(hull).isAir(), "Fixture removes an actual deck block");
    player.level().setBlock(hull, Blocks.AIR.defaultBlockState(), 18);
    player.getInventory().clearContent();
    SpaceStart.start(player, new WorldStartSettings(true, "atlas"));
    ctx.assertTrue(
        ShipStore.get(server).ships().size() == before,
        "Rejoin or changed server preset never grants another ship");
    ctx.assertTrue(
        ShipStore.get(server).repository().find(id) == ship, "Existing ship identity is retained");
    ctx.assertTrue(
        player.level().getBlockState(hull).isAir(),
        "Rejoining never repairs or rebuilds player changes");
    ctx.assertTrue(player.getInventory().isEmpty(), "Rejoining never refills supplies");
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:space_start", maxTicks = 300)
  public void separatePlayersGetSeparateSelectedShipsAndPlanetsVary(GameTestHelper ctx) {
    var first = ctx.makeMockServerPlayerInLevel();
    var second = ctx.makeMockServerPlayerInLevel();
    var a = SpaceStart.start(first, new WorldStartSettings(true, "wayfarer"));
    var b = SpaceStart.start(second, new WorldStartSettings(true, "halcyon_swift"));
    ctx.assertTrue(
        !a.getStringOr("id", "").equals(b.getStringOr("id", "")),
        "Each new player gets their own ship");
    ctx.assertTrue(
        !ShipStore.bounds(a).box().intersects(ShipStore.bounds(b).box()),
        "Ships never share physical storage");
    ctx.assertTrue(
        a.getStringOr("blueprint", "").equals("wayfarer")
            && b.getStringOr("blueprint", "").equals("halcyon_swift"),
        "Multi-deck and manufacturer ship choices work");
    ctx.assertTrue(
        ShipSystems.breathable(first.level(), a, first.blockPosition().above())
            && ShipSystems.breathable(second.level(), b, second.blockPosition().above()),
        "Both starting cabins are breathable");
    var planets = new HashSet<String>();
    for (int i = 0; i < 64; i++)
      planets.add(SpaceStart.planet(ctx.getLevel().getServer(), new UUID(i, i * 31L)));
    ctx.assertTrue(planets.size() > 1, "New players are spread across different planets");
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:space_start", maxTicks = 100)
  public void vanillaStartsDoNotBuildOrMoveAnything(GameTestHelper ctx) {
    var player = ctx.makeMockServerPlayerInLevel();
    var level = player.level();
    var position = player.position();
    int before = ShipStore.get(level.getServer()).ships().size();
    ctx.assertTrue(
        SpaceStart.start(player, WorldStartSettings.DEFAULT) == null, "Disabled start has no ship");
    ctx.assertTrue(
        player.level() == level && player.position().equals(position),
        "Vanilla position is preserved");
    ctx.assertTrue(
        ShipStore.get(level.getServer()).ships().size() == before,
        "Vanilla start creates no vessels");
    ctx.succeed();
  }
}
