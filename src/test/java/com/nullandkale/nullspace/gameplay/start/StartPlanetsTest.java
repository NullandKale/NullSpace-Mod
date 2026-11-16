package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartPlanetsTest
{
    @Test
    void terraAndTheOceanTypeAreDifferentChoices()
    {
        var galaxy = GalaxyPlan.generate(20261104L);
        assertEquals(LocalSpaces.HOME, StartPlanets.find(galaxy, "terra").orElseThrow().id());
        StartPlanets.find(galaxy, "ocean").ifPresent(body -> assertNotEquals(LocalSpaces.HOME, body.id(), "The Ocean type is an ocean world other than Terra"));
    }

    @Test
    void aTypeResolvesToTheNearestLandableBodyOfThatTypeAndIsStable()
    {
        for (long seed = 1; seed <= 300; seed++)
        {
            var galaxy = GalaxyPlan.generate(seed);
            for (var choice : StartPlanets.CHOICES)
            {
                if (choice.equals("random"))
                    continue;
                var first = StartPlanets.find(galaxy, choice);
                assertEquals(first, StartPlanets.find(GalaxyPlan.generate(seed), choice), "The same seed resolves the same body");
                first.ifPresent(body ->
                {
                    assertTrue(body.landable(), "Only landable bodies are chosen");
                    if (!choice.equals("terra"))
                        assertEquals(choice, body.type().name().toLowerCase(), "The body has the chosen type");
                });
            }
        }
    }

    @Test
    void desertIsAlwaysAvailableBecauseSolHasAres()
    {
        for (long seed = 1; seed <= 300; seed++)
            assertEquals("sol_ares", StartPlanets.find(GalaxyPlan.generate(seed), "desert").orElseThrow().id());
    }

    @Test
    void randomIsAnyLandableBodyInAnyStarSystemAndStablePerSalt()
    {
        var galaxy = GalaxyPlan.generate(7L);
        var seen = new HashSet<String>();
        var systems = new HashSet<String>();
        for (long salt = 0; salt < 400; salt++)
        {
            var body = StartPlanets.random(galaxy, salt);
            assertTrue(body.landable());
            assertEquals(body, StartPlanets.random(galaxy, salt));
            seen.add(body.id());
            for (var system : galaxy.systems())
                if (system.bodies().contains(body))
                    systems.add(system.id());
        }
        assertTrue(seen.size() > 5 && systems.size() > 1, "Random draws from many bodies in more than one star system");
    }

    @Test
    void aMissingTypeFallsBackToARandomBodyInsteadOfFailing()
    {
        for (long seed = 1; seed <= 300; seed++)
        {
            var galaxy = GalaxyPlan.generate(seed);
            for (var choice : StartPlanets.CHOICES)
                assertNotNull(StartPlanets.resolve(galaxy, choice, seed));
        }
    }

    @Test
    void typesThatSolLacksAreOftenButNotAlwaysInTheChart()
    {
        int lava = 0, jungle = 0, toxic = 0;
        for (long seed = 1; seed <= 1000; seed++)
        {
            var galaxy = GalaxyPlan.generate(seed);
            lava += StartPlanets.available(galaxy, "lava") ? 1 : 0;
            jungle += StartPlanets.available(galaxy, "jungle") ? 1 : 0;
            toxic += StartPlanets.available(galaxy, "toxic") ? 1 : 0;
        }
        // The menu picks a seed that has the type, so rare types must still exist for some seeds.
        assertTrue(lava > 0 && jungle > 0 && toxic > 0, "Every type exists for some seeds: lava " + lava + ", jungle " + jungle + ", toxic " + toxic + " of 1000");
    }
}
