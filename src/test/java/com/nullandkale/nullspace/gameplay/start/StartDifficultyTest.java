package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.gameplay.start.StartDifficulty.Level;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartDifficultyTest
{
    @Test
    void aPlanetWithoutAirIsVeryHardAndOneWithoutTreesIsNeverEasy()
    {
        assertEquals(Level.EASY, StartDifficulty.planet("terra"));
        assertEquals(Level.EASY, StartDifficulty.planet("jungle"));
        assertEquals(Level.NORMAL, StartDifficulty.planet("ocean"), "Air but no trees: not easy");
        for (var choice : new String[]{"desert", "ice", "lava", "barren", "toxic"})
            assertEquals(Level.VERY_HARD, StartDifficulty.planet(choice), choice + " has no air");
    }

    @Test
    void warningsNameWhatTheChoiceCosts()
    {
        var desert = StartDifficulty.describe("desert", true, null);
        assertTrue(desert.warnings().stream().anyMatch(w -> w.contains("No air outside") && w.contains("one air canister")));
        assertTrue(desert.warnings().stream().anyMatch(w -> w.contains("No trees")));
        assertTrue(desert.warnings().stream().anyMatch(w -> w.contains("wrecked")));
        var terra = StartDifficulty.describe("terra", false, null);
        assertEquals(Level.EASY, terra.level());
        assertTrue(terra.warnings().isEmpty(), "Terra has nothing to warn about");
        assertTrue(StartDifficulty.describe("random", true, null).warnings().getFirst().contains("any star system"), "A random crash says it may be far away");
        // A start in orbit is always over a planet of the home system, so it must not warn about distance (0.9.0 did).
        var orbit = StartDifficulty.describe("random", false, null).warnings();
        assertTrue(orbit.getFirst().contains("home system") && orbit.stream().noneMatch(w -> w.contains("any star system") || w.contains("far from Terra")), "Random in orbit stays in the home system: " + orbit);
    }

    @Test
    void aBiggerShipMakesACrashEasierButNeverBelowWhatThePlanetAllows()
    {
        for (var choice : new String[]{"terra", "jungle", "ocean", "desert", "lava"})
        {
            var small = StartDifficulty.describe(choice, true, null, 0).level();
            var medium = StartDifficulty.describe(choice, true, null, 1).level();
            var large = StartDifficulty.describe(choice, true, null, 2).level();
            assertTrue(large.ordinal() <= medium.ordinal() && medium.ordinal() <= small.ordinal(), "A bigger ship is never harder on " + choice);
            assertTrue(large.ordinal() >= StartDifficulty.planet(choice).ordinal() - 2);
        }
        assertEquals(Level.HARD, StartDifficulty.describe("desert", true, null, 2).level(), "Without air a crash is at least Hard, even in a large ship");
        assertEquals(Level.NORMAL, StartDifficulty.describe("ocean", true, null, 2).level(), "Without trees it is at least Normal");
        assertEquals(Level.VERY_HARD, StartDifficulty.describe("desert", true, null, 0).level());
        assertEquals(Level.VERY_HARD, StartDifficulty.describe("desert", false, null, 2).level(), "The ship only matters to a crash start");
        assertTrue(StartDifficulty.describe("terra", true, null, 2).warnings().stream().anyMatch(w -> w.contains("plenty to salvage")));
        assertEquals(0, StartDifficulty.shipClass(1));
        assertEquals(1, StartDifficulty.shipClass(2));
        assertEquals(2, StartDifficulty.shipClass(3));
        assertEquals(2, StartDifficulty.shipClass(4));
    }

    @Test
    void distanceFromTerraMakesAStartHarderUpToVeryHard()
    {
        for (long seed = 1; seed <= 200; seed++)
        {
            var galaxy = GalaxyPlan.generate(seed);
            var body = StartPlanets.find(galaxy, "jungle");
            if (body.isEmpty())
                continue;
            int jumps = StartPlanets.jumpsFromSol(galaxy, body.get());
            var result = StartDifficulty.describe("jungle", false, galaxy);
            if (jumps == 0)
                assertEquals(Level.EASY, result.level());
            else
            {
                assertTrue(result.level().ordinal() > Level.EASY.ordinal(), "A jungle " + jumps + " jumps away is harder than one at home");
                assertTrue(result.warnings().stream().anyMatch(w -> w.contains("from Terra")));
            }
        }
    }
}
