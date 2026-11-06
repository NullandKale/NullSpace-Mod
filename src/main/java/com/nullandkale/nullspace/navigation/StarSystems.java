package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.ShipStore;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/** World-owned star chart. Menus read saved definitions, never reroll a system. */
public final class StarSystems {
  private record Catalog(
      CompoundTag document,
      List<StarSystemPlan.System> systems,
      List<LocalSpaces.Destination> destinations) {}

  private static final ServerContext.Key<Catalog> CACHE = new ServerContext.Key<>();

  public static void initialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          // Resolve only metadata. This does not load surface chunks or construct every station.
          for (var region : destinations(server)) SceneCatalog.region(server, region.id());
          ShipStore.get(server).save();
        });
  }

  public static List<LocalSpaces.Destination> destinations(MinecraftServer server) {
    return catalog(server).destinations();
  }

  public static List<StarSystemPlan.System> systems(MinecraftServer server) {
    return catalog(server).systems();
  }

  private static Catalog catalog(MinecraftServer server) {
    var store = ShipStore.get(server);
    var document = store.section("starSystems");
    if (document.isEmpty()) {
      var systems = new ListTag();
      for (var system : StarSystemPlan.generate(server.overworld().getSeed()))
        systems.add(encode(system));
      document.putInt("version", StarSystemPlan.VERSION);
      document.putLong("worldSeed", server.overworld().getSeed());
      document.put("systems", systems);
      store.save();
    }
    var context = ServerContext.get(server);
    var cached = context.existing(CACHE);
    if (cached != null && cached.document() == document) return cached;
    if (document.getIntOr("version", 0) != StarSystemPlan.VERSION)
      throw new IllegalStateException("Unsupported saved star chart version");
    var systems = new ArrayList<StarSystemPlan.System>();
    var destinations = new ArrayList<>(LocalSpaces.DESTINATIONS);
    var ids = new HashSet<String>();
    for (var d : destinations) ids.add(d.id());
    for (var tag : document.getListOrEmpty("systems")) {
      var system = decode((CompoundTag) tag);
      for (var region : system.regions()) {
        if (!ids.add(region.destination().id()))
          throw new IllegalStateException("Duplicate saved region identity");
        destinations.add(region.destination());
      }
      systems.add(system);
    }
    cached = new Catalog(document, List.copyOf(systems), List.copyOf(destinations));
    context.put(CACHE, cached);
    return cached;
  }

  private static CompoundTag encode(StarSystemPlan.System system) {
    var t = new CompoundTag();
    t.putString("id", system.id());
    t.putString("name", system.name());
    t.putLong("seed", system.seed());
    t.putString("spectralClass", system.spectralClass());
    t.putInt("color", system.color());
    t.putDouble("radius", system.radius());
    var regions = new ListTag();
    for (var region : system.regions()) {
      var d = region.destination();
      var r = new CompoundTag();
      r.putString("id", d.id());
      r.putString("name", d.name());
      r.putLong("seed", d.seed());
      r.putInt("color", d.color());
      r.putBoolean("moon", d.moon());
      r.putDouble("x", region.center().x);
      r.putDouble("y", region.center().y);
      r.putDouble("z", region.center().z);
      r.putDouble("radius", region.radius());
      r.putDouble("atmosphere", region.atmosphere());
      regions.add(r);
    }
    t.put("regions", regions);
    return t;
  }

  private static StarSystemPlan.System decode(CompoundTag t) {
    var regions = new ArrayList<StarSystemPlan.Region>();
    for (var tag : t.getListOrEmpty("regions")) {
      var r = (CompoundTag) tag;
      var d =
          new LocalSpaces.Destination(
              r.getStringOr("id", ""),
              t.getStringOr("id", ""),
              t.getStringOr("name", ""),
              r.getStringOr("name", ""),
              r.getLongOr("seed", 0),
              r.getIntOr("color", -1),
              r.getBooleanOr("moon", false));
      regions.add(
          new StarSystemPlan.Region(
              d,
              new Vec3(r.getDoubleOr("x", 0), r.getDoubleOr("y", 0), r.getDoubleOr("z", 0)),
              r.getDoubleOr("radius", 0),
              r.getDoubleOr("atmosphere", 0)));
    }
    return new StarSystemPlan.System(
        t.getStringOr("id", ""),
        t.getStringOr("name", ""),
        t.getLongOr("seed", 0),
        t.getStringOr("spectralClass", "G"),
        t.getIntOr("color", -1),
        t.getDoubleOr("radius", 0),
        regions);
  }

  public static Optional<CompoundTag> scene(MinecraftServer server, String address) {
    for (var system : systems(server))
      for (var region : system.regions())
        if (region.destination().id().equals(address)) return Optional.of(scene(system, region));
    return Optional.empty();
  }

  /** The same star/planet identities are translated into each region's local flight frame. */
  public static CompoundTag scene(StarSystemPlan.System system, StarSystemPlan.Region region) {
    var offset = new Vec3(0, -2200, 5200).subtract(region.center());
    var bodies = new ArrayList<SpaceDefinition.Body>();
    bodies.add(
        new SpaceDefinition.Body(
            "nullspace:" + system.id() + ":star",
            0,
            offset,
            Vec3.ZERO,
            system.radius(),
            system.color(),
            6,
            0,
            system.seed(),
            0));
    for (var body : system.regions()) {
      var d = body.destination();
      bodies.add(
          new SpaceDefinition.Body(
              "nullspace:" + d.id(),
              d.moon() ? 2 : 1,
              body.center().add(offset),
              Vec3.ZERO,
              body.radius(),
              d.color(),
              0,
              0,
              d.seed(),
              body.atmosphere(),
              new Vec3(0, 1, 0),
              .0005));
    }
    var rng = new Random(region.destination().seed());
    var asteroid = new Vec3(-300 - rng.nextInt(200), 80, 480 + rng.nextInt(200));
    bodies.add(
        new SpaceDefinition.Body(
            "nullspace:" + address(region) + ":rock",
            3,
            asteroid,
            Vec3.ZERO,
            12,
            0xff817267,
            0,
            .15,
            rng.nextLong(),
            0));
    var fields =
        List.of(
            new SpaceDefinition.Field(
                "nullspace:" + address(region) + ":belt",
                asteroid.add(-160, -40, 160),
                new Vec3(120, 48, 120),
                32,
                .12,
                1.5,
                6,
                rng.nextLong(),
                0xff817267));
    var definition =
        new SpaceDefinition(
            "nullspace:" + address(region), 1, system.seed(), 1, 0, bodies, List.of(), fields);
    var scene = SpaceDefinitionCodec.encode(definition);
    scene.putInt("layoutVersion", 1);
    scene.putInt("systemGenerator", StarSystemPlan.VERSION);
    scene.putString("system", system.id());
    scene.putString("systemName", system.name());
    scene.putString("spectralClass", system.spectralClass());
    for (var tag : scene.getListOrEmpty("bodies")) {
      var body = (CompoundTag) tag;
      String id = body.getStringOr("id", "");
      body.putString(
          "name",
          id.endsWith(":star")
              ? system.name()
              : system.regions().stream()
                  .filter(r -> id.equals("nullspace:" + address(r)))
                  .map(r -> r.destination().name())
                  .findFirst()
                  .orElse("Asteroid"));
    }
    return scene;
  }

  private static String address(StarSystemPlan.Region r) {
    return r.destination().id();
  }

  private StarSystems() {}
}
