package com.nullandkale.nullspace.navigation;

import com.nullandkale.nullspace.navigation.GalaxyPlan.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.FuelSystem;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * World-owned galaxy chart (P9). Generated once from the world seed and saved; menus, jumps and
 * scenes read the saved chart and never reroll it. Every body and feature is a zone.
 */
public final class StarSystems {
  private record Catalog(
      CompoundTag document, Galaxy galaxy, List<LocalSpaces.Destination> destinations) {}

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

  public static Galaxy galaxy(MinecraftServer server) {
    return catalog(server).galaxy();
  }

  private static Catalog catalog(MinecraftServer server) {
    var store = ShipStore.get(server);
    var document = store.section("galaxy");
    if (document.isEmpty()) {
      var galaxy = GalaxyPlan.generate(server.overworld().getSeed());
      document.merge(encode(galaxy));
      store.save();
    }
    var context = ServerContext.get(server);
    var cached = context.existing(CACHE);
    if (cached != null && cached.document() == document) return cached;
    if (document.getIntOr("version", 0) != GalaxyPlan.VERSION)
      throw new IllegalStateException("Unsupported saved galaxy chart version");
    var galaxy = decode(document);
    cached = new Catalog(document, galaxy, destinations(galaxy));
    context.put(CACHE, cached);
    return cached;
  }

  static List<LocalSpaces.Destination> destinations(Galaxy galaxy) {
    var out = new ArrayList<LocalSpaces.Destination>();
    for (var s : galaxy.systems())
      for (var b : s.bodies())
        out.add(
            new LocalSpaces.Destination(
                b.id(), s.id(), s.name(), b.name(), b.seed(), b.color(), b.moon()));
    for (var f : galaxy.features())
      out.add(
          new LocalSpaces.Destination(
              f.id(), f.id(), f.name(), f.name(), f.seed(), f.type().color, false));
    return List.copyOf(out);
  }

  // ------------------------------------------------------------------ zone lookups

  public static Optional<Body> body(MinecraftServer server, String zone) {
    for (var s : galaxy(server).systems())
      for (var b : s.bodies()) if (b.id().equals(zone)) return Optional.of(b);
    return Optional.empty();
  }

  public static Optional<Feature> feature(MinecraftServer server, String zone) {
    return galaxy(server).features().stream().filter(f -> f.id().equals(zone)).findFirst();
  }

  /** A short zone description for destination lists, e.g. "Ice world - land" or "Nebula". */
  public static String describe(MinecraftServer server, String zone) {
    var body = body(server, zone);
    if (body.isPresent())
      return (body.get().moon() ? "Moon: " : "")
          + body.get().type().label
          + (body.get().landable() ? " - land" : "");
    return feature(server, zone).map(f -> f.type().label).orElse("Orbit");
  }

  /** The surface dimension of a zone, or null if you cannot land there. */
  public static String surface(MinecraftServer server, String zone) {
    return body(server, zone).map(Body::surface).orElse(null);
  }

  /** The zone whose surface is this dimension, or null. */
  public static String zoneOfSurface(MinecraftServer server, String dimension) {
    for (var s : galaxy(server).systems())
      for (var b : s.bodies()) if (dimension.equals(b.surface())) return b.id();
    return null;
  }

  /**
   * The chart place of any zone. Journey corridors and encounter signals are not on the chart;
   * their saved region records name the place they left from.
   */
  public static Place placeOf(MinecraftServer server, String zone) {
    var galaxy = galaxy(server);
    if (galaxy.zones().contains(zone)) return galaxy.placeOfZone(zone);
    return galaxy.place(LocalSpaces.get(server, zone).system());
  }

  /** How dangerous a zone is, 0-3; journey zones take the danger of the place they left from. */
  public static int danger(MinecraftServer server, String zone) {
    var galaxy = galaxy(server);
    var place = placeOf(server, zone);
    String chartZone =
        place instanceof StarSystem s ? s.bodies().getFirst().id() : place.id();
    return GalaxyPlan.danger(galaxy, galaxy.zones().contains(zone) ? zone : chartZone);
  }

  /** Chart distance between the places of two zones; 0 inside one system. */
  public static double lightYears(MinecraftServer server, String from, String to) {
    return GalaxyPlan.distance(placeOf(server, from), placeOf(server, to));
  }

  public static boolean sameSystem(MinecraftServer server, String from, String to) {
    return placeOf(server, from) == placeOf(server, to);
  }

  /** Base fuel units for a jump between zones, before ship mass is applied. */
  public static int jumpBase(MinecraftServer server, String from, String to) {
    if (sameSystem(server, from, to)) return FuelSystem.LOCAL_JUMP;
    return (int)
        Math.round(GalaxyPlan.jumpFuel(lightYears(server, from, to)) * FuelSystem.UNITS_PER_FUEL);
  }

  /** The best active engine's upgrade tier. */
  public static int engineTier(ServerLevel level, CompoundTag ship) {
    return com.nullandkale.nullspace.systems.ShipSystems.connected(level, ship).stream()
        .filter(
            p ->
                level.getBlockState(p).is(ShipBlocks.ENGINE)
                    && com.nullandkale.nullspace.systems.ShipSystems.active(ship, p))
        .mapToInt(p -> FuelSystem.tier(level, p))
        .max()
        .orElse(0);
  }

  public static double range(ServerLevel level, CompoundTag ship) {
    return GalaxyPlan.RANGES[Math.clamp(engineTier(level, ship), 0, GalaxyPlan.RANGES.length - 1)];
  }

  /** Empty if the jump fits the ship's engine range, otherwise why not and where to hop first. */
  public static String rangeReason(ServerLevel level, CompoundTag ship, String to) {
    var server = level.getServer();
    String from = LocalSpaces.address(ship);
    if (sameSystem(server, from, to)) return "";
    double ly = lightYears(server, from, to), range = range(level, ship);
    if (ly <= range + 1e-9) return "";
    var galaxy = galaxy(server);
    var route =
        GalaxyPlan.route(galaxy, placeOf(server, from).id(), placeOf(server, to).id(), range);
    String hop = route.size() > 1 ? " Jump to " + route.get(1).name() + " first." : "";
    return String.format(
        Locale.ROOT,
        "Out of range: %.1f ly, engine range %.0f ly. Upgrade engines or hop.%s",
        ly,
        range,
        hop);
  }

  // ------------------------------------------------------------------ saving

  static CompoundTag encode(Galaxy galaxy) {
    var t = new CompoundTag();
    t.putInt("version", GalaxyPlan.VERSION);
    t.putLong("worldSeed", galaxy.seed());
    var systems = new ListTag();
    for (var s : galaxy.systems()) {
      var st = new CompoundTag();
      st.putString("id", s.id());
      st.putString("name", s.name());
      st.putDouble("x", s.x());
      st.putDouble("y", s.y());
      st.putString("spectralClass", s.spectralClass());
      st.putInt("color", s.color());
      st.putDouble("starRadius", s.starRadius());
      st.putLong("seed", s.seed());
      var bodies = new ListTag();
      for (var b : s.bodies()) {
        var bt = new CompoundTag();
        bt.putString("id", b.id());
        bt.putString("name", b.name());
        bt.putString("type", b.type().name());
        if (b.parent() != null) bt.putString("parent", b.parent());
        bt.putDouble("orbit", b.orbitRadius());
        bt.putDouble("angle", b.angle());
        bt.putDouble("radius", b.radius());
        bt.putInt("color", b.color());
        bt.putLong("seed", b.seed());
        if (b.surface() != null) bt.putString("surface", b.surface());
        bodies.add(bt);
      }
      st.put("bodies", bodies);
      systems.add(st);
    }
    t.put("systems", systems);
    var features = new ListTag();
    for (var f : galaxy.features()) {
      var ft = new CompoundTag();
      ft.putString("id", f.id());
      ft.putString("name", f.name());
      ft.putString("type", f.type().name());
      ft.putDouble("x", f.x());
      ft.putDouble("y", f.y());
      ft.putBoolean("steppingStone", f.steppingStone());
      ft.putLong("seed", f.seed());
      features.add(ft);
    }
    t.put("features", features);
    return t;
  }

  static Galaxy decode(CompoundTag t) {
    var systems = new ArrayList<StarSystem>();
    for (var tag : t.getListOrEmpty("systems")) {
      var st = (CompoundTag) tag;
      var bodies = new ArrayList<Body>();
      for (var bTag : st.getListOrEmpty("bodies")) {
        var bt = (CompoundTag) bTag;
        bodies.add(
            new Body(
                bt.getStringOr("id", ""),
                bt.getStringOr("name", ""),
                BodyType.valueOf(bt.getStringOr("type", "BARREN")),
                bt.getString("parent").orElse(null),
                bt.getDoubleOr("orbit", 0),
                bt.getDoubleOr("angle", 0),
                bt.getDoubleOr("radius", 1000),
                bt.getIntOr("color", -1),
                bt.getLongOr("seed", 0),
                bt.getString("surface").orElse(null)));
      }
      systems.add(
          new StarSystem(
              st.getStringOr("id", ""),
              st.getStringOr("name", ""),
              st.getDoubleOr("x", 0),
              st.getDoubleOr("y", 0),
              st.getStringOr("spectralClass", "G"),
              st.getIntOr("color", -1),
              st.getDoubleOr("starRadius", 1800),
              st.getLongOr("seed", 0),
              bodies));
    }
    var features = new ArrayList<Feature>();
    for (var tag : t.getListOrEmpty("features")) {
      var ft = (CompoundTag) tag;
      features.add(
          new Feature(
              ft.getStringOr("id", ""),
              ft.getStringOr("name", ""),
              FeatureType.valueOf(ft.getStringOr("type", "NEBULA")),
              ft.getDoubleOr("x", 0),
              ft.getDoubleOr("y", 0),
              ft.getBooleanOr("steppingStone", false),
              ft.getLongOr("seed", 0)));
    }
    return new Galaxy(t.getLongOr("worldSeed", 0), systems, features);
  }

  // ------------------------------------------------------------------ orbit views

  public static Optional<CompoundTag> scene(MinecraftServer server, String address) {
    var galaxy = galaxy(server);
    if (!galaxy.zones().contains(address)) return Optional.empty();
    return Optional.of(scene(galaxy, address));
  }

  /** Where a body sits in its system's frame. */
  static Vec3 position(StarSystem system, Body body) {
    var orbit = orbit(body.angle(), body.orbitRadius());
    if (!body.moon()) return orbit;
    var parent =
        system.bodies().stream().filter(b -> b.id().equals(body.parent())).findFirst().orElseThrow();
    return position(system, parent).add(orbit);
  }

  private static Vec3 orbit(double angle, double radius) {
    return new Vec3(
        Math.cos(angle) * radius, Math.sin(angle * 2) * radius * .015, Math.sin(angle) * radius);
  }

  private static final Vec3 VIEW = new Vec3(0, -2200, 5200);

  /** The orbit view of one zone: its system translated around the body, or the feature itself. */
  public static CompoundTag scene(Galaxy galaxy, String zone) {
    var place = galaxy.placeOfZone(zone);
    var bodies = new ArrayList<SpaceDefinition.Body>();
    var volumes = new ArrayList<SpaceDefinition.Volume>();
    var fields = new ArrayList<SpaceDefinition.Field>();
    var names = new HashMap<String, String>();
    long seed;
    var rng = new Random(zone.hashCode() * 31L + galaxy.seed());
    if (place instanceof StarSystem system) {
      seed = system.seed();
      var here =
          system.bodies().stream().filter(b -> b.id().equals(zone)).findFirst().orElseThrow();
      var offset = VIEW.subtract(position(system, here));
      String starId = "nullspace:" + system.id() + ":star";
      bodies.add(
          new SpaceDefinition.Body(
              starId, 0, offset, Vec3.ZERO, system.starRadius(), system.color(), 6, 0,
              system.seed(), 0));
      names.put(starId, system.name());
      int atmospheres = 0;
      for (var b : system.bodies()) {
        boolean air =
            atmospheres < 10
                && (b.type() == BodyType.GAS_GIANT
                    || b.landable() && b.type() == BodyType.OCEAN
                    || b.landable() && b.type() == BodyType.JUNGLE);
        if (air) atmospheres++;
        String id = "nullspace:" + b.id();
        bodies.add(
            new SpaceDefinition.Body(
                id,
                b.moon() ? 2 : 1,
                position(system, b).add(offset),
                Vec3.ZERO,
                b.radius(),
                b.color(),
                0,
                0,
                b.seed(),
                air ? (b.type() == BodyType.GAS_GIANT ? .08 : .04) : 0,
                new Vec3(0, 1, 0),
                .0005));
        names.put(id, b.name());
      }
      var asteroid = new Vec3(-300 - rng.nextInt(200), 80, 480 + rng.nextInt(200));
      String rock = "nullspace:" + zone + ":rock";
      bodies.add(
          new SpaceDefinition.Body(
              rock, 3, asteroid, Vec3.ZERO, 12, 0xff817267, 0, .15, rng.nextLong(), 0));
      names.put(rock, "Asteroid");
      fields.add(
          new SpaceDefinition.Field(
              "nullspace:" + zone + ":belt",
              asteroid.add(-160, -40, 160),
              new Vec3(120, 48, 120),
              32,
              .12,
              1.5,
              6,
              rng.nextLong(),
              0xff817267));
    } else {
      var feature = (Feature) place;
      seed = feature.seed();
      featureScene(feature, rng, bodies, volumes, fields, names);
    }
    var definition =
        new SpaceDefinition("nullspace:" + zone, 1, seed, 1, 0, bodies, volumes, fields);
    var scene = SpaceDefinitionCodec.encode(definition);
    scene.putInt("layoutVersion", 1);
    scene.putInt("systemGenerator", GalaxyPlan.VERSION);
    scene.putString("system", place.id());
    scene.putString("systemName", place.name());
    if (place instanceof StarSystem s) scene.putString("spectralClass", s.spectralClass());
    for (var tag : scene.getListOrEmpty("bodies")) {
      var body = (CompoundTag) tag;
      body.putString("name", names.getOrDefault(body.getStringOr("id", ""), "Asteroid"));
    }
    return scene;
  }

  private static void featureScene(
      Feature f,
      Random rng,
      List<SpaceDefinition.Body> bodies,
      List<SpaceDefinition.Volume> volumes,
      List<SpaceDefinition.Field> fields,
      Map<String, String> names) {
    String id = "nullspace:" + f.id();
    switch (f.type()) {
      case NEBULA -> {
        volumes.add(
            new SpaceDefinition.Volume(
                id + ":cloud", new Vec3(0, -600, 6000), new Vec3(5200, 2600, 5200), Vec3.ZERO,
                f.type().color, .35, .02, .35, .1, f.seed(), 0));
        volumes.add(
            new SpaceDefinition.Volume(
                id + ":wisp", new Vec3(-4200, 900, 2600), new Vec3(2600, 1400, 2400), Vec3.ZERO,
                0xffd07a9a, .25, .02, .25, -.1, f.seed() + 1, 0));
      }
      case ASTEROID_FIELD -> {
        fields.add(
            new SpaceDefinition.Field(
                id + ":field", new Vec3(0, 0, 900), new Vec3(700, 160, 700), 48, .2, 2, 14,
                f.seed(), f.type().color));
        fields.add(
            new SpaceDefinition.Field(
                id + ":near", new Vec3(-200, 20, 260), new Vec3(160, 40, 160), 24, .15, 1, 6,
                f.seed() + 1, 0xff6f655c));
      }
      case ROGUE_PLANET -> {
        bodies.add(
            new SpaceDefinition.Body(
                id, 1, VIEW, Vec3.ZERO, 1400, f.type().color, 0, 0, f.seed(), 0));
        names.put(id, f.name());
      }
      case PULSAR -> {
        bodies.add(
            new SpaceDefinition.Body(
                id, 0, new Vec3(0, -800, 9000), Vec3.ZERO, 140, f.type().color, 40, 0, f.seed(),
                0));
        names.put(id, f.name());
        volumes.add(
            new SpaceDefinition.Volume(
                id + ":glow", new Vec3(0, -800, 9000), new Vec3(1400, 1400, 1400), Vec3.ZERO,
                f.type().color, .2, .01, .6, .3, f.seed(), 0));
      }
      case BLACK_HOLE -> {
        bodies.add(
            new SpaceDefinition.Body(
                id, 0, new Vec3(0, -600, 8000), Vec3.ZERO, 600, 0xff050308, 0, 0, f.seed(), 0));
        names.put(id, f.name());
        volumes.add(
            new SpaceDefinition.Volume(
                id + ":disk", new Vec3(0, -600, 8000), new Vec3(3200, 220, 3200), Vec3.ZERO,
                0xffff9a4a, .45, .02, 1.2, .2, f.seed(), 0));
      }
    }
    // Every feature has a little loose rock to fly around.
    String rock = id + ":rock";
    bodies.add(
        new SpaceDefinition.Body(
            rock, 3, new Vec3(-320, 60, 520), Vec3.ZERO, 10, 0xff817267, 0, .15, rng.nextLong(), 0));
    names.put(rock, "Asteroid");
  }

  private StarSystems() {}
}
