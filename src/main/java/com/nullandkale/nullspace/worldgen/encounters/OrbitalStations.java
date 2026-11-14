package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.Random;

/**
 * Which zones have a planned station in orbit, and what it is. One roll per zone, a function of the
 * world seed and the zone alone, so it does not depend on who arrives first or on reloads: a tenth of
 * the bodies in star systems, and half of the features (nebulae, black holes, rogue planets, pulsars
 * and asteroid fields). Sol's home orbit, and the zones that once held an old Waypoint or Exchange, always have one.
 */
public final class OrbitalStations
{
    public static final double BODY_RATE = .10, FEATURE_RATE = .50;

    /** The chance a zone has an orbital station: certain at Sol's home orbit, zero for zones the chart does not know. */
    public static double rate(GalaxyPlan.Galaxy galaxy, String zone)
    {
        try
        {
            // Sol's home orbit and the zones that used to get a Waypoint or Exchange always have one.
            var population = GalaxyPlan.population(galaxy).get(zone);
            if (population != null && (population.waypoint() || population.exchange()))
                return 1;
            if (zone.equals(LocalSpaces.HOME))
                return 1;
            return galaxy.placeOfZone(zone) instanceof GalaxyPlan.Feature ? FEATURE_RATE : BODY_RATE;
        }
        catch (IllegalArgumentException unknown)
        {
            return 0;
        }
    }

    private static long mix(long worldSeed, String zone)
    {
        return worldSeed * 0x9E3779B97F4A7C15L ^ zone.hashCode() * 0xBF58476D1CE4E5B9L ^ 0x0B17A15EL;
    }

    /** True when the zone has an orbital station. */
    public static boolean placed(long worldSeed, GalaxyPlan.Galaxy galaxy, String zone)
    {
        double rate = rate(galaxy, zone);
        return rate > 0 && new Random(mix(worldSeed, zone)).nextDouble() < rate;
    }

    /**
     * The brief for the zone's station: any maker and any purpose, and mostly small or medium (45 percent
     * compact, 40 standard, 15 large), so large stations are the exception. Always operational for now.
     */
    public static StationSpec spec(long worldSeed, String zone)
    {
        var random = new Random(mix(worldSeed, zone) ^ 0x57A7105L);
        var maker = Maker.values()[random.nextInt(Maker.values().length)];
        var purpose = StationSpec.Purpose.values()[random.nextInt(StationSpec.Purpose.values().length)];
        int roll = random.nextInt(100);
        var size = roll < 45 ? StationSpec.Size.COMPACT : roll < 85 ? StationSpec.Size.STANDARD : StationSpec.Size.LARGE;
        return new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, random.nextLong());
    }

    private OrbitalStations()
    {
    }
}
