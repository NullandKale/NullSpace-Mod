package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.scene.SceneCatalog;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationBuilds;
import com.nullandkale.nullspace.gameplay.station.StationSpace;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.docking.DockingMotion;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import com.nullandkale.nullspace.worldgen.stations.ManufacturerStation;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/** Persistent orbital population. Menus only discover places; they never create them. */
public final class SpacePopulation {
  private static final ServerContext.Key<Map<String, Integer>> RETRY = new ServerContext.Key<>();

  public static void initialize() {
    ServerContext.tick(ServerContext.Phase.TRAVEL, SpacePopulation::tick);
  }

  public static void tick(MinecraftServer server) {
    if (server.getTickCount() % 20 != 0) return;
    var regions = new TreeSet<String>();
    for (var player : server.getPlayerList().getPlayers())
      if (player.level().dimension().equals(TravelService.SPACE)) {
        var observer = SceneService.observer(player);
        if (observer != null) regions.add(LocalSpaces.address(observer));
      }
    var retry = ServerContext.get(server).service(RETRY, HashMap::new);
    for (var address : regions) {
      if (retry.getOrDefault(address, 0) > server.getTickCount()) continue;
      try {
        discover(server, address);
      } catch (RuntimeException failure) {
        NullSpace.LOGGER.error(
            "Could not save orbital plan for region {}; retrying in 60 seconds", address, failure);
        retry.put(address, server.getTickCount() + 1200);
      }
    }
    resetAbandoned(server, regions);
    if (StationAccess.building(server)) return;
    var ledger = WorldStore.get(server).section("spacePopulation");
    // Finish saved regions after their explorer leaves, but queue at most one place at a time.
    for (var address : new TreeSet<>(ledger.keySet())) {
      if (retry.getOrDefault(address, 0) > server.getTickCount()) continue;
      try {
        if (populate(server, address, ledger.getCompoundOrEmpty(address))) return;
      } catch (RuntimeException failure) {
        // IO/commissioning faults are real errors, not normal availability checks.
        NullSpace.LOGGER.error(
            "Orbital population failed for region {}; retrying in 60 seconds", address, failure);
        retry.put(address, server.getTickCount() + 1200);
      }
    }
  }

  /** 2.5 in-game days: a derelict resets this long after the last player left its zone. */
  public static final long RESET_TICKS = 24000L * 5 / 2;

  /**
   * Fixed loot sites (derelicts) reset once nobody has been in their zone for {@link #RESET_TICKS}:
   * everything in the site's bounds is disposed of and the site is planned again with a new seed,
   * so population rebuilds a fresh variation. Claimed derelicts are players' ships and never reset.
   * Service stations (Waypoint, Exchange) do not reset.
   */
  static void resetAbandoned(MinecraftServer server, Set<String> occupied) {
    var store = WorldStore.get(server);
    var ledger = store.section("spacePopulation");
    long now = server.overworld().getGameTime();
    for (var address : ledger.keySet()) {
      for (var tag : ledger.getCompoundOrEmpty(address).getListOrEmpty("sites")) {
        var site = (CompoundTag) tag;
        if (!site.getStringOr("role", "").startsWith("salvage-")
            || site.getStringOr("state", "").equals("claimed")) continue;
        var ship = store.repository().find(site.getStringOr("id", ""));
        if (ship != null && !ship.getStringOr("owner", "").isEmpty()) {
          site.putString("state", "claimed");
          store.requestSave();
          continue;
        }
        if (occupied.contains(address)) {
          site.putLong("seen", now);
          continue;
        }
        // Never visited sites wait; a site resets only after someone was there and left.
        if (!site.contains("seen") || now - site.getLongOr("seen", now) < RESET_TICKS) continue;
        reset(server, site, ship);
      }
    }
  }

  /** Removes a generated site completely: every entity and block in its bounds, and its record. */
  public static void dispose(MinecraftServer server, CompoundTag ship) {
    var level = server.getLevel(TravelService.SPACE);
    var bounds = WorldStore.bounds(ship);
    for (var entity :
        level.getEntities(
            (net.minecraft.world.entity.Entity) null,
            bounds.box(),
            e -> !(e instanceof net.minecraft.world.entity.player.Player))) entity.discard();
    for (var at : net.minecraft.core.BlockPos.betweenClosed(bounds.min(), bounds.max()))
      if (!level.getBlockState(at).isAir()) {
        level.removeBlockEntity(at);
        level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
      }
    WorldStore.get(server).ships().remove(ship);
  }

  /** Disposes of a site's contents and plans it again with a new variation. */
  static void reset(MinecraftServer server, CompoundTag site, CompoundTag ship) {
    var store = WorldStore.get(server);
    if (ship != null) dispose(server, ship);
    long resets = site.getLongOr("resets", 0) + 1;
    site.putLong("resets", resets);
    site.putLong("seed", site.getLongOr("seed", 0) ^ resets * 0x9e3779b97f4a7c15L);
    site.putString("state", "planned");
    site.remove("seen");
    store.requestSave();
    NullSpace.LOGGER.info("Reset derelict site {} ({})", site.getStringOr("id", ""), resets);
  }

  /** The places this region starts with, as the galaxy chart allocates them (P9). */
  public static List<SpacePopulationPlan.Site> plan(MinecraftServer server, String address) {
    var population =
        GalaxyPlan.population(StarSystems.galaxy(server))
            .getOrDefault(address, GalaxyPlan.Population.NONE);
    return SpacePopulationPlan.sites(
        server.overworld().getSeed(), LocalSpaces.get(server, address), population);
  }

  /** Record the region before reserving storage, so reloads cannot reroll its inventory. */
  public static void discover(MinecraftServer server, String address) {
    if (JourneyService.special(server, address)) return;
    var store = WorldStore.get(server);
    var ledger = store.section("spacePopulation");
    // The first visit records the region's plan; later visits keep it, including removals.
    if (ledger.contains(address)) return;
    var region = new CompoundTag();
    var sites = new ListTag();
    region.putInt("version", SpacePopulationPlan.VERSION);
    for (var site : plan(server, address)) {
      var saved = new CompoundTag();
      saved.putString("id", site.id());
      saved.putString("role", site.role());
      saved.putString("name", site.name());
      saved.putLong("seed", site.seed());
      saved.putString("state", "planned");
      sites.add(saved);
    }
    region.put("sites", sites);
    ledger.put(address, region);
    store.save();
  }

  private static boolean populate(MinecraftServer server, String address, CompoundTag region) {
    if (region.getIntOr("version", 0) < 1
        || region.getIntOr("version", 0) > SpacePopulationPlan.VERSION) return false;
    var store = WorldStore.get(server);
    for (var tag : region.getListOrEmpty("sites")) {
      var site = (CompoundTag) tag;
      var existing = store.repository().find(site.getStringOr("id", ""));
      if (existing != null) {
        if (!site.getStringOr("state", "").equals("placed")) {
          site.putString("state", "placed");
          store.requestSave();
        }
        continue;
      }
      if (!site.getStringOr("state", "").equals("planned")) {
        // Removal is intentional. A later visit never replaces a salvaged/dismantled place.
        if (!site.getStringOr("state", "").equals("removed")) {
          site.putString("state", "removed");
          store.requestSave();
        }
        continue;
      }
      var definition = SpaceDefinitionCodec.decode(SceneCatalog.region(server, address));
      var obstacles = new ArrayList<DockingMotion.Obstacle>();
      double time = server.overworld().getGameTime() / 20. - definition.epoch();
      for (var body : definition.bodies())
        obstacles.add(
            new DockingMotion.Obstacle(body.at(time), body.radius() * (1 + body.roughness())));
      for (var field : definition.fields())
        obstacles.add(new DockingMotion.Obstacle(field.center(), field.extent().length()));
      for (var vessel : store.ships()) {
        var ship = (CompoundTag) vessel;
        if (LocalSpaces.address(ship).equals(address))
          obstacles.add(
              new DockingMotion.Obstacle(
                  SceneService.pose(ship)
                      .toUniverse(
                          new Vec3(
                              ship.getIntOr("width", 32) / 2.,
                              16,
                              ship.getIntOr("depth", 32) / 2.)),
                  StationSpace.radius(ship) + 64));
      }
      var plan =
          new SpacePopulationPlan.Site(
              site.getStringOr("role", ""),
              site.getStringOr("id", ""),
              site.getStringOr("name", "Station"),
              site.getLongOr("seed", 0));
      Vec3 position = null;
      for (int attempt = 0; attempt < 32; attempt++) {
        var candidate = plan.candidate(attempt);
        if (DockingMotion.clear(
            candidate.add(16, 16, 16), candidate.add(16, 16, 16), obstacles, 64)) {
          position = candidate;
          break;
        }
      }
      if (position == null) continue; // Occupied space is ordinary; wait for a safe site.
      var metadata = new CompoundTag();
      metadata.putBoolean("worldGenerated", true);
      metadata.putLong("populationSeed", plan.seed());
      metadata.putInt("populationVersion", SpacePopulationPlan.VERSION);
      metadata.putString("populationRole", plan.role());
      StationBlueprint.Plan blocks;
      if (plan.role().equals("exchange")) {
        blocks = ManufacturerStation.build();
        metadata.putString("template", "manufacturer_showcase_v1");
      } else if (plan.role().startsWith("salvage-")) {
        int kind = SpacePopulationPlan.salvageKind(plan.seed());
        String design = EncounterSites.design(kind, plan.seed());
        blocks = EncounterSites.plan(kind, plan.seed());
        metadata.putBoolean("salvage", true);
        metadata.putInt("encounterKind", kind);
        metadata.putLong("encounterSeed", plan.seed());
        metadata.putString("encounterDesign", design);
      } else {
        var layout = StationLayout.generate(plan.seed());
        blocks = StationBlueprint.build(layout);
        var pieces = new CompoundTag();
        for (var room : layout.rooms())
          pieces.putString(room.x() + "_" + room.z(), room.role().name());
        metadata.put("roomPieces", pieces);
      }
      var station =
          StationBuilds.queueAmbient(
              server, plan.id(), plan.name(), address, position, blocks, metadata);
      if (station == null) return false; // No clear storage section yet; never throw for this.
      site.putString("state", "placed");
      store.save();
      NullSpace.LOGGER.info(
          "Orbital place registered: {} in {} at {}", plan.name(), address, position);
      return true;
    }
    return false;
  }

  private SpacePopulation() {}
}
