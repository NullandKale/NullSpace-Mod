package com.nullandkale.nullspace.model.galaxy;

import java.util.*;

/**
 * P9 galaxy chart: a per-world random 2D layout of star systems and features, generated once from
 * the world seed. Pure data, no Minecraft state, so it can be checked over thousands of seeds.
 *
 * <p>Every body and feature is a zone: a region ships can fly in and players meet. The chart
 * guarantees that a ship with the starting engine can reach every place by hopping.
 */
public final class GalaxyPlan
{
    public static final int VERSION = 1;

    /** Chart width and height in light-years. */
    public static final double SIZE = 100;

    /** Maximum jump distance in light-years for engine upgrade tiers 0 to 3. */
    public static final double[] RANGES = {25, 40, 60, 100};

    public static final double STARTING_RANGE = RANGES[0];

    /** Systems never sit closer than this, so the chart stays readable and sparse. */
    static final double SYSTEM_SPACING = 18;

    /** Stepping stones split long links into hops no longer than this share of the range. */
    static final double HOP_MARGIN = .85;

    public static final String HOME = "sol";

    /** What a body is. Landable types only become landable when a surface slot is assigned. */
    public enum BodyType
    {
        DESERT("Desert world", true, 0xffc89a5a), ICE("Ice world", true, 0xffbfd9e8), LAVA("Lava world", true, 0xffa8442a), OCEAN("Ocean world", true, 0xff3f6fb0), JUNGLE("Jungle world", true, 0xff4f8a3c), BARREN("Barren world", true, 0xff8d8a85), GAS_GIANT("Gas giant", false, 0xffd2a679), TOXIC("Toxic world", false, 0xffa5b44a);

        public final String label;
        public final boolean surface;
        public final int color;

        BodyType(String label, boolean surface, int color)
        {
            this.label = label;
            this.surface = surface;
            this.color = color;
        }
    }

    public enum FeatureType
    {
        NEBULA("Nebula", 0xff9a6fd0), ASTEROID_FIELD("Asteroid field", 0xff817267), ROGUE_PLANET("Rogue planet", 0xff5d6470), PULSAR("Pulsar", 0xff9fe8ff), BLACK_HOLE("Black hole", 0xff2a1f3a);

        public final String label;
        public final int color;

        FeatureType(String label, int color)
        {
            this.label = label;
            this.color = color;
        }
    }

    /**
     * Fixed pool of landable surface dimensions per type (Minecraft creates dimensions only when a
     * world is made). Sol's Terra, Luna and Ares use their own dimensions and are not drawn from it.
     */
    public static final Map<BodyType, Integer> SURFACE_POOL = Collections.unmodifiableMap(new EnumMap<>(Map.of(BodyType.DESERT, 4, BodyType.ICE, 4, BodyType.LAVA, 3, BodyType.OCEAN, 3, BodyType.JUNGLE, 2, BodyType.BARREN, 6)));

    /** The surface dimension id of a pool slot, for example {@code surface_ice_b}. */
    public static String surfaceSlot(BodyType type, int index)
    {
        return "surface_" + type.name().toLowerCase(Locale.ROOT) + "_" + (char) ('a' + index);
    }

    /**
     * A body is a zone. {@code parent} is null for planets and names the planet for moons. {@code
     * surface} is the surface dimension (a pool slot, or Sol's fixed ones) or null if you can't land.
     */
    public record Body(String id, String name, BodyType type, String parent, double orbitRadius, double angle, double radius, int color, long seed, String surface)
    {
        public boolean moon()
        {
            return parent != null;
        }

        public boolean landable()
        {
            return surface != null;
        }
    }

    /** Anything with a position on the 2D chart: a star system or a feature. */
    public sealed interface Place permits StarSystem, Feature
    {
        String id();

        String name();

        double x();

        double y();
    }

    public record StarSystem(String id, String name, double x, double y, String spectralClass, int color, double starRadius, long seed, List<Body> bodies) implements Place
    {
        public StarSystem
        {
            bodies = List.copyOf(bodies);
        }

        public List<Body> planets()
        {
            return bodies.stream().filter(b -> !b.moon()).toList();
        }
    }

    /** A feature is a zone of its own, with nothing to land on. */
    public record Feature(String id, String name, FeatureType type, double x, double y, boolean steppingStone, long seed) implements Place
    {}

    public record Galaxy(long seed, List<StarSystem> systems, List<Feature> features)
    {
        public Galaxy
        {
            systems = List.copyOf(systems);
            features = List.copyOf(features);
        }

        public List<Place> places()
        {
            var all = new ArrayList<Place>(systems);
            all.addAll(features);
            return List.copyOf(all);
        }

        public Place place(String id)
        {
            return places().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown place " + id));
        }

        /** Every zone id: each body in each system, then each feature. */
        public List<String> zones()
        {
            var all = new ArrayList<String>();
            for (var s : systems)
                for (var b : s.bodies())
                    all.add(b.id());
            for (var f : features)
                all.add(f.id());
            return List.copyOf(all);
        }

        /** The chart place a zone belongs to: its system, or the feature itself. */
        public Place placeOfZone(String zone)
        {
            for (var s : systems)
                for (var b : s.bodies())
                    if (b.id().equals(zone))
                        return s;
            for (var f : features)
                if (f.id().equals(zone))
                    return f;
            throw new IllegalArgumentException("Unknown zone " + zone);
        }
    }

    /** What a zone starts with: a Waypoint Station, a Manufacturer Exchange, salvage ships. */
    public record Population(boolean waypoint, boolean exchange, int salvage)
    {
        public static final Population NONE = new Population(false, false, 0);
    }

    /**
     * Sparse generated places (P9), because players build their own: Sol's home orbit has the full
     * set; two or three stations sit in orbit-only zones of other systems or stepping stones; one or
     * two derelicts drift at features. Every other zone starts empty.
     */
    public static Map<String, Population> population(Galaxy galaxy)
    {
        var random = new Random(galaxy.seed() ^ 0x70b17a7e5eedL);
        var out = new LinkedHashMap<String, Population>();
        out.put(LocalSpaces.HOME, new Population(true, true, 1));
        var candidates = new ArrayList<String>();
        for (var s : galaxy.systems().subList(1, galaxy.systems().size()))
        {
            var orbitOnly = s.bodies().stream().filter(b -> !b.landable() && !b.moon()).toList();
            var pick = orbitOnly.isEmpty() ? s.bodies() : orbitOnly;
            candidates.add(pick.get(random.nextInt(pick.size())).id());
        }
        for (var f : galaxy.features())
            if (f.steppingStone())
                candidates.add(f.id());
        Collections.shuffle(candidates, random);
        int stations = Math.min(candidates.size(), 2 + random.nextInt(2));
        for (int i = 0; i < stations; i++)
            out.put(candidates.get(i), new Population(i % 2 == 0, i % 2 == 1, 0));
        var features = new ArrayList<>(galaxy.features());
        Collections.shuffle(features, random);
        int derelicts = 1 + random.nextInt(2);
        for (var f : features)
        {
            if (derelicts == 0)
                break;
            if (out.containsKey(f.id()))
                continue;
            out.put(f.id(), new Population(false, false, 1));
            derelicts--;
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * How dangerous a zone is, 0-3 (procedural generation design): Sol is safe; elsewhere danger
     * grows with chart distance from Sol; nebulae are at least 2; black holes and pulsars are 3.
     */
    public static int danger(Galaxy galaxy, String zone)
    {
        var place = galaxy.placeOfZone(zone);
        if (place.id().equals(HOME))
            return 0;
        double ly = distance(galaxy.place(HOME), place);
        int base = ly <= 35 ? 1 : ly <= 65 ? 2 : 3;
        if (place instanceof Feature f)
            return switch (f.type())
            {
                case BLACK_HOLE, PULSAR -> 3;
                case NEBULA -> Math.max(2, base);
                default -> base;
            };
        return base;
    }

    public static double distance(Place a, Place b)
    {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    /**
     * The cheapest hop route between two places using jumps no longer than {@code range}, start and
     * end included. Every hop costs fuel growing with distance, so this minimizes total fuel. Empty
     * if the target cannot be reached.
     */
    public static List<Place> route(Galaxy galaxy, String from, String to, double range)
    {
        var places = galaxy.places();
        int n = places.size(), start = -1, end = -1;
        for (int i = 0; i < n; i++)
        {
            if (places.get(i).id().equals(from))
                start = i;
            if (places.get(i).id().equals(to))
                end = i;
        }
        if (start < 0 || end < 0)
            throw new IllegalArgumentException("Unknown place");
        var cost = new double[n];
        var previous = new int[n];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);
        cost[start] = 0;
        var done = new boolean[n];
        for (int step = 0; step < n; step++)
        {
            int u = -1;
            for (int i = 0; i < n; i++)
                if (!done[i] && (u < 0 || cost[i] < cost[u]))
                    u = i;
            if (u < 0 || cost[u] == Double.POSITIVE_INFINITY)
                break;
            done[u] = true;
            for (int v = 0; v < n; v++)
            {
                double d = distance(places.get(u), places.get(v));
                if (v == u || done[v] || d > range)
                    continue;
                double c = cost[u] + jumpFuel(d);
                if (c < cost[v])
                {
                    cost[v] = c;
                    previous[v] = u;
                }
            }
        }
        if (cost[end] == Double.POSITIVE_INFINITY)
            return List.of();
        var path = new ArrayList<Place>();
        for (int at = end; at >= 0; at = previous[at])
            path.add(places.get(at));
        Collections.reverse(path);
        return List.copyOf(path);
    }

    /** Fuel (coal equivalents) for one jump between chart places: 1.00 plus 0.10 per light-year. */
    public static double jumpFuel(double lightYears)
    {
        return 1 + .1 * lightYears;
    }

    /** Fuel for a jump between zones of the same system. */
    public static final double LOCAL_JUMP_FUEL = .5;

    /** True if every place can reach every other place with jumps no longer than {@code range}. */
    public static boolean connected(Galaxy galaxy, double range)
    {
        var places = galaxy.places();
        var seen = new boolean[places.size()];
        var queue = new ArrayDeque<Integer>(List.of(0));
        seen[0] = true;
        int count = 1;
        while (!queue.isEmpty())
        {
            int u = queue.poll();
            for (int v = 0; v < places.size(); v++)
                if (!seen[v] && distance(places.get(u), places.get(v)) <= range)
                {
                    seen[v] = true;
                    count++;
                    queue.add(v);
                }
        }
        return count == places.size();
    }

    // ---------------------------------------------------------------- generation

    private static final List<String> PREFIXES = List.of("Al", "Cor", "El", "Ith", "Kel", "Mir", "Nar", "Or", "Tal", "Val", "Bry", "Dra", "Ves", "Zen", "Hal", "Rho", "Sar", "Ulm");
    private static final List<String> SUFFIXES = List.of("ara", "ion", "eris", "ora", "ea", "una", "idia", "os", "ax", "en");
    private static final String[] NUMERALS = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    public static Galaxy generate(long worldSeed)
    {
        var random = new Random(worldSeed ^ 0x5a17c0de9e3779b9L);
        var names = new ArrayList<>(PREFIXES);
        Collections.shuffle(names, random);
        var pool = new EnumMap<BodyType, Integer>(BodyType.class);
        var points = layout(random);
        // Spread planet counts across 1-9: one system from each band, in random order.
        var bands = new ArrayList<int[]>(List.of(new int[]{1, 2}, new int[]{3, 4}, new int[]{5, 6}, new int[]{7, 9}));
        Collections.shuffle(bands, random);
        var systems = new ArrayList<StarSystem>();
        for (int i = 0; i < points.size(); i++)
        {
            long seed = random.nextLong();
            var rng = new Random(seed);
            String id = i == 0 ? HOME : "sys_" + (char) ('a' + i - 1);
            String name = i == 0 ? "Sol" : names.get(i) + SUFFIXES.get(rng.nextInt(SUFFIXES.size()));
            int planets;
            if (i == 0)
                planets = 4;
            else
            {
                var band = bands.get(i - 1);
                planets = band[0] + rng.nextInt(band[1] - band[0] + 1);
            }
            systems.add(system(id, name, points.get(i), planets, seed, rng, pool, i == 0));
        }
        var features = features(random, names.subList(points.size(), names.size()), systems);
        return new Galaxy(worldSeed, systems, features);
    }

    /** Random system positions with Sol first, no two closer than SYSTEM_SPACING. */
    private static List<double[]> layout(Random random)
    {
        var points = new ArrayList<double[]>();
        double margin = 8;
        while (points.size() < 5)
        {
            var p = new double[]{margin + random.nextDouble() * (SIZE - 2 * margin), margin + random.nextDouble() * (SIZE - 2 * margin)};
            if (points.stream().allMatch(q -> Math.hypot(p[0] - q[0], p[1] - q[1]) >= SYSTEM_SPACING))
                points.add(p);
        }
        return points;
    }

    private static StarSystem system(String id, String name, double[] at, int planetCount, long seed, Random rng, Map<BodyType, Integer> pool, boolean home)
    {
        int type = rng.nextInt(4);
        String spectral = List.of("M", "K", "G", "A").get(type);
        int color = new int[]{0xffff9a6a, 0xffffb477, 0xffffdca6, 0xffcadfff}[type];
        double starRadius = new double[]{1100, 1400, 1800, 2400}[type] + rng.nextInt(400);
        var bodies = new ArrayList<Body>();
        double orbit = 60000 + rng.nextInt(20000);
        for (int p = 0; p < planetCount; p++)
        {
            String planetId = home ? homePlanetId(p) : id + "_" + (p + 1);
            String planetName = home ? homePlanetName(p) : name + " " + NUMERALS[p];
            double angle = rng.nextDouble() * Math.PI * 2;
            BodyType planetType;
            String surface;
            if (home && p == 0)
            {
                planetType = BodyType.OCEAN;
                surface = "minecraft:overworld";
            }
            else if (home && p == 1)
            {
                planetType = BodyType.DESERT;
                surface = "nullspace:sol_ares";
            }
            else if (home && p == 2)
            {
                planetType = BodyType.GAS_GIANT;
                surface = null;
            }
            else
            {
                // Inner orbits run hot, outer ones cold; gas giants sit further out.
                double depth = planetCount == 1 ? .5 : p / (double) (planetCount - 1);
                planetType = planetType(rng, depth);
                surface = assign(planetType, rng, pool, .4);
            }
            double radius = planetType == BodyType.GAS_GIANT ? 3000 + rng.nextInt(1500) : 1000 + rng.nextInt(900);
            bodies.add(new Body(planetId, planetName, planetType, null, orbit, angle, radius, tint(planetType.color, rng), rng.nextLong(), surface));
            int moons = home && p == 0 ? 1 : planetType == BodyType.GAS_GIANT ? 1 + rng.nextInt(3) : rng.nextInt(4) == 0 ? 1 : 0;
            for (int m = 0; m < moons; m++)
            {
                String moonId = home && p == 0 ? "sol_luna" : planetId + "_" + (char) ('a' + m);
                String moonName = home && p == 0 ? "Luna" : planetName + "-" + (char) ('a' + m);
                var moonType = home && p == 0 ? BodyType.BARREN : rng.nextInt(3) == 0 ? BodyType.ICE : BodyType.BARREN;
                String moonSurface = home && p == 0 ? "nullspace:sol_luna" : assign(moonType, rng, pool, .4);
                bodies.add(new Body(moonId, moonName, moonType, planetId, 12000 + m * 9000 + rng.nextInt(4000), rng.nextDouble() * Math.PI * 2, 300 + rng.nextInt(250), tint(moonType.color, rng), rng.nextLong(), moonSurface));
            }
            orbit += 25000 + rng.nextInt(20000) + (planetType == BodyType.GAS_GIANT ? 15000 : 0);
        }
        if (!home && bodies.stream().noneMatch(Body::landable))
            guaranteeLanding(bodies, rng, pool);
        return new StarSystem(id, name, at[0], at[1], spectral, color, starRadius, seed, bodies);
    }

    /**
     * Every non-home system has somewhere to land: make one body landable, preferring one whose type
     * has a free surface, otherwise turning the last planet into a type that still does.
     */
    private static void guaranteeLanding(List<Body> bodies, Random rng, Map<BodyType, Integer> pool)
    {
        for (int i = 0; i < bodies.size(); i++)
        {
            var b = bodies.get(i);
            if (b.type().surface && free(b.type(), pool))
            {
                bodies.set(i, withSurface(b, b.type(), pool));
                return;
            }
        }
        for (int i = bodies.size() - 1; i >= 0; i--)
        {
            var b = bodies.get(i);
            if (b.moon())
                continue;
            for (var type : List.of(BodyType.BARREN, BodyType.DESERT, BodyType.ICE, BodyType.LAVA, BodyType.OCEAN, BodyType.JUNGLE))
                if (free(type, pool))
                {
                    bodies.set(i, withSurface(b, type, pool));
                    return;
                }
        }
    }

    private static boolean free(BodyType type, Map<BodyType, Integer> pool)
    {
        return pool.getOrDefault(type, 0) < SURFACE_POOL.get(type);
    }

    private static Body withSurface(Body b, BodyType type, Map<BodyType, Integer> pool)
    {
        int used = pool.getOrDefault(type, 0);
        pool.put(type, used + 1);
        double radius = type == b.type() ? b.radius() : Math.min(b.radius(), 1900);
        return new Body(b.id(), b.name(), type, b.parent(), b.orbitRadius(), b.angle(), radius, type == b.type() ? b.color() : type.color, b.seed(), "nullspace:" + surfaceSlot(type, used));
    }

    private static String homePlanetId(int p)
    {
        return switch (p)
        {
            case 0 -> LocalSpaces.HOME;
            case 1 -> "sol_ares";
            default -> "sol_" + (p + 1);
        };
    }

    private static String homePlanetName(int p)
    {
        return switch (p)
        {
            case 0 -> "Terra";
            case 1 -> "Ares";
            case 2 -> "Jove";
            default -> "Sol " + NUMERALS[p];
        };
    }

    private static BodyType planetType(Random rng, double depth)
    {
        int roll = rng.nextInt(100);
        if (depth < .34)
            return roll < 35 ? BodyType.LAVA : roll < 65 ? BodyType.DESERT : roll < 85 ? BodyType.TOXIC : BodyType.BARREN;
        if (depth < .67)
            return roll < 25 ? BodyType.OCEAN : roll < 45 ? BodyType.JUNGLE : roll < 65 ? BodyType.DESERT : roll < 85 ? BodyType.GAS_GIANT : BodyType.TOXIC;
        return roll < 55 ? BodyType.GAS_GIANT : roll < 85 ? BodyType.ICE : BodyType.BARREN;
    }

    /** Assigns a free pool slot with probability {@code chance}; null keeps the body orbit-only. */
    private static String assign(BodyType type, Random rng, Map<BodyType, Integer> pool, double chance)
    {
        if (!type.surface || rng.nextDouble() >= chance)
            return null;
        int used = pool.getOrDefault(type, 0);
        if (used >= SURFACE_POOL.get(type))
            return null;
        pool.put(type, used + 1);
        return "nullspace:" + surfaceSlot(type, used);
    }

    private static int tint(int color, Random rng)
    {
        int v = rng.nextInt(25) - 12;
        int r = Math.clamp(((color >>> 16) & 255) + v, 0, 255);
        int g = Math.clamp(((color >>> 8) & 255) + v, 0, 255);
        int b = Math.clamp((color & 255) + v, 0, 255);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    /**
     * Stepping stones along every long link of the cheapest connecting network, so each hop fits the
     * starting range; then one rare oddity and, if the chart is still bare, extra features.
     */
    private static List<Feature> features(Random random, List<String> names, List<StarSystem> systems)
    {
        var features = new ArrayList<Feature>();
        var nameQueue = new ArrayDeque<>(names);
        var stoneTypes = List.of(FeatureType.NEBULA, FeatureType.ASTEROID_FIELD, FeatureType.ROGUE_PLANET);
        double hop = STARTING_RANGE * HOP_MARGIN;
        for (var link : spanningLinks(systems))
        {
            var a = systems.get(link[0]);
            var b = systems.get(link[1]);
            double length = distance(a, b);
            int stones = (int) Math.ceil(length / hop) - 1;
            for (int k = 1; k <= stones; k++)
            {
                double t = k / (double) (stones + 1);
                // A little sideways jitter so stones don't sit on a ruler line; hops stay within range.
                double side = (random.nextDouble() - .5) * 3;
                double nx = -(b.y() - a.y()) / length, ny = (b.x() - a.x()) / length;
                var type = stoneTypes.get(random.nextInt(stoneTypes.size()));
                features.add(feature(features.size(), type, nameQueue, a.x() + (b.x() - a.x()) * t + nx * side, a.y() + (b.y() - a.y()) * t + ny * side, true, random));
            }
        }
        var oddity = random.nextBoolean() ? FeatureType.PULSAR : FeatureType.BLACK_HOLE;
        addReachable(features, systems, oddity, nameQueue, random);
        while (features.size() < 3)
            addReachable(features, systems, stoneTypes.get(random.nextInt(stoneTypes.size())), nameQueue, random);
        return features;
    }

    /** Places a free feature within the starting range of an existing place, away from others. */
    private static void addReachable(List<Feature> features, List<StarSystem> systems, FeatureType type, ArrayDeque<String> names, Random random)
    {
        var anchors = new ArrayList<Place>(systems);
        anchors.addAll(features);
        for (int attempt = 0; attempt < 500; attempt++)
        {
            var anchor = anchors.get(random.nextInt(anchors.size()));
            double angle = random.nextDouble() * Math.PI * 2;
            double reach = 8 + random.nextDouble() * (STARTING_RANGE * HOP_MARGIN - 8);
            double x = anchor.x() + Math.cos(angle) * reach, y = anchor.y() + Math.sin(angle) * reach;
            if (x < 2 || y < 2 || x > SIZE - 2 || y > SIZE - 2)
                continue;
            if (anchors.stream().anyMatch(p -> Math.hypot(p.x() - x, p.y() - y) < 8))
                continue;
            features.add(feature(features.size(), type, names, x, y, false, random));
            return;
        }
        throw new IllegalStateException("No room for a feature on the chart");
    }

    private static Feature feature(int index, FeatureType type, ArrayDeque<String> names, double x, double y, boolean steppingStone, Random random)
    {
        String root = names.isEmpty() ? "Far" : names.poll();
        String name = switch (type)
        {
            case NEBULA -> root + " Nebula";
            case ASTEROID_FIELD -> root + " Drift";
            case ROGUE_PLANET -> "Rogue " + root;
            case PULSAR -> root + " Pulsar";
            case BLACK_HOLE -> root + " Deep";
        };
        return new Feature("feature_" + (char) ('a' + index), name, type, x, y, steppingStone, random.nextLong());
    }

    /** Links of the minimum spanning tree over systems (Prim), as index pairs. */
    private static List<int[]> spanningLinks(List<StarSystem> systems)
    {
        int n = systems.size();
        var inTree = new boolean[n];
        var best = new double[n];
        var from = new int[n];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        best[0] = 0;
        var links = new ArrayList<int[]>();
        for (int step = 0; step < n; step++)
        {
            int u = -1;
            for (int i = 0; i < n; i++)
                if (!inTree[i] && (u < 0 || best[i] < best[u]))
                    u = i;
            inTree[u] = true;
            if (step > 0)
                links.add(new int[]{from[u], u});
            for (int v = 0; v < n; v++)
            {
                double d = distance(systems.get(u), systems.get(v));
                if (!inTree[v] && d < best[v])
                {
                    best[v] = d;
                    from[v] = u;
                }
            }
        }
        return links;
    }

    private GalaxyPlan()
    {
    }
}
