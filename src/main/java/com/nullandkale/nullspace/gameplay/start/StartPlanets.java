package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan.Body;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan.BodyType;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan.Galaxy;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.*;

/**
 * Which planet a new player starts on. The choice is {@code random}, {@code terra} (the home world) or a planet type; the
 * chart is a pure function of the world seed, so the world creation screen and the server resolve it identically. Any
 * landable body can be chosen, moons included.
 */
public final class StartPlanets
{
    /** The choices in menu order. */
    public static final List<String> CHOICES = List.of("random", "terra", "desert", "ice", "lava", "ocean", "jungle", "barren", "toxic");

    public static boolean valid(String choice)
    {
        return CHOICES.contains(choice);
    }

    public static String label(String choice)
    {
        return switch (choice)
        {
            case "random" -> "Random planet";
            case "terra" -> "Terra (home world)";
            default -> BodyType.valueOf(choice.toUpperCase(Locale.ROOT)).label;
        };
    }

    /** True for the home world and the types with air. */
    public static boolean breathable(String choice)
    {
        return choice.equals("terra") || choice.equals("ocean") || choice.equals("jungle");
    }

    /** True for the home world and jungles: wood to build and to burn. */
    public static boolean trees(String choice)
    {
        return choice.equals("terra") || choice.equals("jungle");
    }

    private static List<Body> landable(Galaxy galaxy)
    {
        var out = new ArrayList<Body>();
        for (var system : galaxy.systems())
            for (var body : system.bodies())
                if (body.landable())
                    out.add(body);
        return out;
    }

    private static GalaxyPlan.StarSystem system(Galaxy galaxy, Body body)
    {
        for (var system : galaxy.systems())
            if (system.bodies().contains(body))
                return system;
        throw new IllegalArgumentException("Body is not on the chart: " + body.id());
    }

    private static double fromSol(Galaxy galaxy, Body body)
    {
        return GalaxyPlan.distance(galaxy.systems().getFirst(), system(galaxy, body));
    }

    /** True when the chart holds a body for the choice; {@code random} is always available. */
    public static boolean available(Galaxy galaxy, String choice)
    {
        return choice.equals("random") || find(galaxy, choice).isPresent();
    }

    /**
     * The body for a type or Terra: the nearest to Sol by chart distance, planets before moons, then by orbit and id, so the
     * answer is stable. The Ocean type is the ocean worlds other than Terra.
     */
    public static Optional<Body> find(Galaxy galaxy, String choice)
    {
        if (choice.equals("random"))
            return Optional.empty();
        var candidates = landable(galaxy).stream().filter(b -> choice.equals("terra") ? b.id().equals(LocalSpaces.HOME) : !b.id().equals(LocalSpaces.HOME) && b.type().name().equalsIgnoreCase(choice)).toList();
        return candidates.stream().min(Comparator.comparingDouble((Body b) -> fromSol(galaxy, b)).thenComparing(Body::moon).thenComparingDouble(Body::orbitRadius).thenComparing(Body::id));
    }

    /** Any landable body in any star system, drawn from {@code salt}: the same salt gives the same body. */
    public static Body random(Galaxy galaxy, long salt)
    {
        var all = landable(galaxy).stream().sorted(Comparator.comparing(Body::id)).toList();
        return all.get(new Random(salt).nextInt(all.size()));
    }

    /** The body the choice means, or a random one (from {@code salt}) when the chart has none: a start never fails for want of a planet. */
    public static Body resolve(Galaxy galaxy, String choice, long salt)
    {
        return find(galaxy, choice).orElseGet(() -> random(galaxy, salt));
    }

    /** Jumps from Sol to the body's star system at the starting range; 0 in Sol. */
    public static int jumpsFromSol(Galaxy galaxy, Body body)
    {
        var target = system(galaxy, body);
        if (target.id().equals(GalaxyPlan.HOME))
            return 0;
        var route = GalaxyPlan.route(galaxy, GalaxyPlan.HOME, target.id(), GalaxyPlan.STARTING_RANGE);
        return route == null || route.isEmpty() ? 99 : route.size() - 1;
    }

    private StartPlanets()
    {
    }
}
