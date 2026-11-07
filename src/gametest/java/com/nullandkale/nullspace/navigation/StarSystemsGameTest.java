package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.content.ContentCatalog;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;

public final class StarSystemsGameTest {
  @GameTest(environment = "nullspace:star_chart", maxTicks = 200)
  public void startupCreatesAWorldChartAndAllScenesBeforeAnyNavigationMenu(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var store = ShipStore.get(server);
    ctx.assertTrue(!store.section("galaxy").isEmpty(), "Server startup saves the galaxy chart");
    var galaxy = StarSystems.galaxy(server);
    ctx.assertTrue(galaxy.systems().size() == 5, "Sol plus four random systems");
    ctx.assertTrue(
        GalaxyPlan.connected(galaxy, GalaxyPlan.STARTING_RANGE),
        "Every place is reachable with the starting engine");
    ctx.assertTrue(
        LocalSpaces.destinations(server).size() == galaxy.zones().size(),
        "Every body and feature is a destination zone");
    for (var zone : galaxy.zones()) {
      ctx.assertTrue(
          store.section("localScenes").contains(zone) || zone.equals(LocalSpaces.HOME),
          "Scene already exists before discovery reads it: " + zone);
      var scene = SceneCatalog.region(server, zone);
      var place = galaxy.placeOfZone(zone);
      if (!zone.equals(LocalSpaces.HOME))
        ctx.assertTrue(
            scene.getStringOr("system", "").equals(place.id()), "Scene belongs to its place");
      var own = new CompoundTag();
      own.putString("localSpace", zone);
      ctx.assertTrue(
          LocalSpaces.label(server, own).contains(place.name()), "Labels use this world's chart");
      ctx.assertTrue(LocalSpaces.known(zone), "Saved selections accept the zone id: " + zone);
      var arrival = NavigationService.arrivalOrigin(server, own);
      var definition = SpaceDefinitionCodec.decode(scene);
      for (var body : definition.bodies())
        ctx.assertTrue(
            arrival.add(16, 16, 16).distanceTo(body.center()) > body.radius() + 32,
            "An arrival is clear of celestial geometry: " + zone);
    }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:star_surfaces", maxTicks = 600)
  public void landableBodiesHaveIndependentSurfacesAndOrbitOnlyBodiesHaveNone(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var dimensions = new HashSet<String>();
    for (var system : StarSystems.galaxy(server).systems())
      for (var body : system.bodies()) {
        String id = body.id();
        var key = PlanetSurfaces.dimension(server, id);
        if (!body.landable()) {
          ctx.assertTrue(key == null, "You cannot land on " + id);
          continue;
        }
        var level = server.getLevel(key);
        ctx.assertTrue(
            level != null && dimensions.add(key.identifier().toString()),
            "Every landable body has a separate surface: " + id + " -> " + key.identifier() + (level == null ? " (not loaded)" : " (duplicate)"));
        ctx.assertTrue(
            id.equals(PlanetSurfaces.address(level)), "Launch returns to the correct zone: " + id);
        boolean breathable =
            id.equals(LocalSpaces.HOME)
                || ContentCatalog.surfaceOfDimension(key.identifier().toString())
                    .orElseThrow()
                    .breathable();
        ctx.assertTrue(
            PlanetSurfaces.breathable(level) == breathable, "Surface air matches the pool");
        if (id.equals(LocalSpaces.HOME)) continue;
        var sky = new CompoundTag();
        ctx.assertTrue(
            PlanetSky.decorate(sky, level, new BlockPos(0, 65, 0))
                && sky.getStringOr("surfaceBody", "").equals("nullspace:" + id),
            "Surface sky uses its actual saved celestial body: " + id);
      }
    String id = TestZones.landable(server);
    var body = StarSystems.body(server, id).orElseThrow();
    var level = server.getLevel(PlanetSurfaces.dimension(server, id));
    PlanetSurfaces.ensureLanding(server, id);
    var pad =
        PadRegistry.read(server).stream()
            .filter(p -> p.dimension().equals(level.dimension().identifier().toString()))
            .findFirst()
            .orElseThrow();
    PadRegistry.validate(level, pad);
    var center =
        new BlockPos(
            (pad.bounds().minX() + pad.bounds().maxX()) / 2,
            pad.bounds().y() + 1,
            (pad.bounds().minZ() + pad.bounds().maxZ()) / 2);
    for (int y = 0; y < 30; y++)
      ctx.assertTrue(
          level.getBlockState(center.above(y)).isAir(), "The pad has clear sky for a ship");
    String biome =
        level.getBiome(center).unwrapKey().orElseThrow().identifier().toString();
    ctx.assertTrue(
        biome.startsWith(
            "nullspace:planet/" + body.type().name().toLowerCase(java.util.Locale.ROOT) + "_"),
        "The surface uses only its type's biomes, found " + biome);
    var marker = new BlockPos(pad.bounds().minX() + 2, pad.bounds().y() + 1, pad.bounds().minZ() + 2);
    level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
    PlanetSurfaces.ensureLanding(server, id);
    ctx.assertTrue(
        level.getBlockState(marker).is(Blocks.GOLD_BLOCK), "Revisiting preserves construction");
    level.setBlock(marker, Blocks.AIR.defaultBlockState(), 18);
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:star_chart_reload", maxTicks = 100)
  public void reloadingPreservesTheChartEditedScenesAndSelectedGeneratedDestinations(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var store = ShipStore.get(server);
    var chart = store.section("galaxy").copy();
    var region = LocalSpaces.get(server, TestZones.otherSystem(server));
    var scene = SceneCatalog.region(server, region.id());
    scene.putString("playerMarker", "preserve this edit");
    var oldHome = SceneCatalog.get(server).copy();
    var ship = new CompoundTag();
    var selected =
        new ShipDestination(
            ShipDestination.Kind.REGION,
            region.id(),
            BlockPos.ZERO,
            region.id(),
            region.name(),
            "Orbit");
    ship.put("destination", selected.save());
    store.save();
    ShipStore.forget(server);
    var loaded = ShipStore.get(server);
    ctx.assertTrue(
        loaded.section("galaxy").equals(chart), "Chart is read from the world save, not rerolled");
    ctx.assertTrue(
        LocalSpaces.get(server, region.id()).equals(region),
        "Per-world cache refreshes after reload");
    ctx.assertTrue(
        ShipDestination.selected(ship).region().equals(region.id()),
        "Selection accepts generated stable addresses");
    ctx.assertTrue(
        SceneCatalog.region(server, region.id())
            .getStringOr("playerMarker", "")
            .equals("preserve this edit"),
        "Edited regional scenes are never replaced");
    ctx.assertTrue(SceneCatalog.get(server).equals(oldHome), "Existing home scene is preserved");
    SceneCatalog.region(server, region.id()).remove("playerMarker");
    loaded.save();
    ctx.succeed();
  }
}
