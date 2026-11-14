package com.nullandkale.nullspace.model.galaxy;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A creative Scanner grows the saved chart: new systems and feature zones stay reachable, unique and spaced. */
class GalaxyGrowthTest
{
    @Test
    void aNewSystemIsUniqueSpacedFromTheRestAndWithinOneHopAtTheStartingRange()
    {
        var galaxy = GalaxyPlan.generate(77);
        assertTrue(GalaxyPlan.connected(galaxy, GalaxyPlan.STARTING_RANGE));
        var near = galaxy.systems().get(2).id();
        var grown = GalaxyPlan.withSystem(galaxy, near, 5);
        assertEquals(galaxy.systems().size() + 1, grown.systems().size());
        var added = grown.systems().getLast();
        assertTrue(added.id().matches("sys_[a-z]"), added.id());
        assertEquals(grown.systems().size(), new HashSet<>(grown.systems().stream().map(GalaxyPlan.StarSystem::id).toList()).size());
        assertEquals(grown.zones().size(), new HashSet<>(grown.zones()).size(), "Every zone id is unique");
        assertTrue(!added.bodies().isEmpty() && added.bodies().stream().allMatch(b -> LocalSpaces.known(b.id())), "Its zones have valid ids");
        for (var other : galaxy.systems())
            assertTrue(GalaxyPlan.distance(added, other) >= 18 - 1e-9, "Systems keep their spacing");
        assertTrue(GalaxyPlan.distance(added, galaxy.place(near)) <= GalaxyPlan.STARTING_RANGE, "A hop from where it was spawned");
        assertTrue(GalaxyPlan.connected(grown, GalaxyPlan.STARTING_RANGE), "The grown chart is still connected at the starting range");
        assertEquals(grown, GalaxyPlan.withSystem(galaxy, near, 5), "The same seed grows the same system");
    }

    @Test
    void aNewFeatureGetsTheNextLetterAndStaysReachable()
    {
        var galaxy = GalaxyPlan.generate(31);
        var grown = GalaxyPlan.withFeature(galaxy, "sol", GalaxyPlan.FeatureType.ASTEROID_FIELD, "Test signal", 9);
        var added = grown.features().getLast();
        assertTrue(added.id().matches("feature_[a-z]") && LocalSpaces.known(added.id()), added.id());
        assertEquals(grown.features().size(), new HashSet<>(grown.features().stream().map(GalaxyPlan.Feature::id).toList()).size());
        assertTrue(GalaxyPlan.connected(grown, GalaxyPlan.STARTING_RANGE));
        assertTrue(grown.zones().contains(added.id()));
        assertEquals("Test signal", added.name());
    }

    @Test
    void theChartHoldsAtMostTwentySixSystems()
    {
        var galaxy = GalaxyPlan.generate(5);
        int added = 0;
        try
        {
            while (true)
            {
                galaxy = GalaxyPlan.withSystem(galaxy, galaxy.systems().get(added % galaxy.systems().size()).id(), added);
                added++;
                assertTrue(added < 40);
            }
        }
        catch (IllegalArgumentException full)
        {
            assertTrue(galaxy.systems().size() <= 27, "Sol plus at most 26 more");
        }
    }
}
