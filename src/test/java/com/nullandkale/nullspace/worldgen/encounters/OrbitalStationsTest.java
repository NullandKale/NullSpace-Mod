package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.stations.StationPlanner;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrbitalStationsTest
{
    private static List<GalaxyPlan.Galaxy> galaxies()
    {
        var out = new ArrayList<GalaxyPlan.Galaxy>();
        for (long seed = 1; seed <= 40; seed++)
            out.add(GalaxyPlan.generate(seed * 7919 - 31));
        return out;
    }

    private static boolean feature(GalaxyPlan.Galaxy galaxy, String zone)
    {
        return galaxy.placeOfZone(zone) instanceof GalaxyPlan.Feature;
    }

    @Test
    void aTenthOfBodiesAndHalfOfFeaturesHaveAStation()
    {
        int bodies = 0, bodyStations = 0, features = 0, featureStations = 0;
        for (var galaxy : galaxies())
        {
            long world = galaxy.seed() * 31 + 5;
            var fixed = GalaxyPlan.population(galaxy);
            for (var zone : galaxy.zones())
            {
                if (fixed.containsKey(zone) && (fixed.get(zone).waypoint() || fixed.get(zone).exchange()))
                    continue;
                boolean placed = OrbitalStations.placed(world, galaxy, zone);
                if (feature(galaxy, zone))
                {
                    features++;
                    featureStations += placed ? 1 : 0;
                }
                else
                {
                    bodies++;
                    bodyStations += placed ? 1 : 0;
                }
            }
        }
        double bodyShare = bodyStations / (double) bodies, featureShare = featureStations / (double) features;
        assertEquals(.10, bodyShare, .03, "bodies: " + bodyStations + " of " + bodies);
        assertEquals(.50, featureShare, .08, "features: " + featureStations + " of " + features);
    }

    @Test
    void solsHomeOrbitAlwaysHasOneAndTheRollsDoNotChange()
    {
        for (var galaxy : galaxies())
        {
            assertTrue(OrbitalStations.placed(1, galaxy, LocalSpaces.HOME));
            assertEquals(1, OrbitalStations.rate(galaxy, LocalSpaces.HOME));
            assertEquals(0, OrbitalStations.rate(galaxy, "no_such_zone"));
            for (var zone : galaxy.zones())
            {
                assertEquals(OrbitalStations.placed(77, galaxy, zone), OrbitalStations.placed(77, galaxy, zone));
                assertEquals(OrbitalStations.spec(77, zone), OrbitalStations.spec(77, zone));
            }
        }
    }

    @Test
    void briefsUseEveryMakerPurposeAndSizeAndMostAreSmall()
    {
        var makers = new HashSet<>();
        var purposes = new HashSet<>();
        var sizes = new EnumMap<StationSpec.Size, Integer>(StationSpec.Size.class);
        int total = 0;
        for (var galaxy : galaxies())
            for (var zone : galaxy.zones())
            {
                var spec = OrbitalStations.spec(9, zone);
                makers.add(spec.maker());
                purposes.add(spec.purpose());
                sizes.merge(spec.size(), 1, Integer::sum);
                total++;
                assertEquals(StationSpec.Condition.OPERATIONAL, spec.condition());
            }
        assertEquals(3, makers.size());
        assertEquals(StationSpec.Purpose.values().length, purposes.size());
        assertEquals(.45, sizes.get(StationSpec.Size.COMPACT) / (double) total, .05);
        assertEquals(.40, sizes.get(StationSpec.Size.STANDARD) / (double) total, .05);
        assertEquals(.15, sizes.get(StationSpec.Size.LARGE) / (double) total, .05);
    }

    @Test
    void everyChosenBriefPlansAStation()
    {
        int planned = 0;
        for (var galaxy : galaxies())
            for (var zone : galaxy.zones())
                if (OrbitalStations.placed(5, galaxy, zone) && planned < 150)
                {
                    var spec = OrbitalStations.spec(5, zone);
                    assertDoesNotThrow(() -> StationPlanner.generate(spec), spec.description());
                    planned++;
                }
        assertEquals(150, planned);
    }

    @Test
    void theSiteHasAStableIdentityAndAnOrbitOfItsOwn()
    {
        var ids = new HashSet<String>();
        int sites = 0;
        for (var galaxy : galaxies())
            for (var zone : galaxy.zones())
            {
                var region = new LocalSpaces.Destination(zone, "sys", "System", "Zone " + zone, 1, 0, false);
                var site = SpacePopulationPlan.orbital(3, region, galaxy);
                assertEquals(OrbitalStations.placed(3, galaxy, zone), site.isPresent());
                if (site.isEmpty())
                    continue;
                sites++;
                assertEquals("orbital", site.get().role());
                assertEquals(site, SpacePopulationPlan.orbital(3, region, galaxy));
                assertDoesNotThrow(() -> UUID.fromString(site.get().id()));
                assertTrue(ids.add(galaxy.seed() + site.get().id()));
                for (int attempt = 0; attempt < 32; attempt++)
                    assertTrue(site.get().candidate(attempt).length() >= 900);
            }
        assertTrue(sites > 100);
    }
}
