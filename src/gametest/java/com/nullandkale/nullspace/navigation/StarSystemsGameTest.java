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
    ctx.assertTrue(!store.section("starSystems").isEmpty(), "Server startup saves the star chart");
    ctx.assertTrue(StarSystems.systems(server).size() == 4, "Four complete procedural systems");
    ctx.assertTrue(
        LocalSpaces.destinations(server).size() == 17,
        "Original addresses plus twelve new orbital regions");
    for (var system : StarSystems.systems(server))
      for (var region : system.regions()) {
        String id = region.destination().id();
        ctx.assertTrue(
            store.section("localScenes").contains(id),
            "Scene already exists before discovery reads it");
        var scene = SceneCatalog.region(server, id);
        ctx.assertTrue(
            scene.getStringOr("system", "").equals(system.id()),
            "Scene belongs to its saved system");
        var own = new CompoundTag();
        own.putString("localSpace", id);
        ctx.assertTrue(
            LocalSpaces.label(server, own).contains(system.name()),
            "Labels use this world's chart");
        var arrival = NavigationService.arrivalOrigin(server, own);
        var definition = SpaceDefinitionCodec.decode(scene);
        for (var body : definition.bodies())
          ctx.assertTrue(
              arrival.add(16, 16, 16).distanceTo(body.center()) > body.radius() + 32,
              "An arrival is clear of celestial geometry");
      }
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:star_surfaces", maxTicks = 300)
  public void generatedBodiesHaveIndependentLandableSurfacesAndMatchingSkies(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var dimensions = new HashSet<String>();
    for (var system : StarSystems.systems(server))
      for (var region : system.regions()) {
        String id = region.destination().id();
        var key = PlanetSurfaces.dimension(id);
        var level = server.getLevel(key);
        ctx.assertTrue(
            level != null && dimensions.add(key.identifier().toString()),
            "Every body has a separate surface");
        ctx.assertTrue(
            PlanetSurfaces.address(level).equals(id),
            "Launch returns to the correct orbital region");
        ctx.assertTrue(
            PlanetSurfaces.breathable(level) == ContentCatalog.planet(id).breathable(),
            "Surface atmosphere matches the chart");
        var sky = new CompoundTag();
        ctx.assertTrue(
            PlanetSky.decorate(sky, level, new BlockPos(0, 65, 0))
                && sky.getStringOr("surfaceBody", "").equals("nullspace:" + id),
            "Surface sky uses its actual saved celestial body");
      }
    var region = StarSystems.systems(server).getFirst().regions().getFirst();
    String id = region.destination().id();
    var level = server.getLevel(PlanetSurfaces.dimension(id));
    PlanetSurfaces.ensureLanding(server, id);
    var pad =
        PadRegistry.read(server).stream()
            .filter(p -> p.dimension().equals(level.dimension().identifier().toString()))
            .findFirst()
            .orElseThrow();
    PadRegistry.validate(level, pad);
    var marker = new BlockPos(pad.bounds().minX() + 2, 65, pad.bounds().minZ() + 2);
    level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
    PlanetSurfaces.ensureLanding(server, id);
    ctx.assertTrue(
        level.getBlockState(marker).is(Blocks.GOLD_BLOCK),
        "Revisiting preserves player construction");
    level.setBlock(marker, Blocks.AIR.defaultBlockState(), 18);
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:star_chart_reload", maxTicks = 100)
  public void reloadingPreservesTheChartEditedScenesAndSelectedGeneratedDestinations(
      GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var store = ShipStore.get(server);
    var chart = store.section("starSystems").copy();
    var region = StarSystems.systems(server).getLast().regions().getLast().destination();
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
        loaded.section("starSystems").equals(chart),
        "Chart is read from the world save without rerolling");
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
