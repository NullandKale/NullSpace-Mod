package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.model.galaxy.GalaxyPlan.Body;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan.Galaxy;

import java.util.ArrayList;
import java.util.List;

/**
 * How hard a starting choice is, and what to warn the player about before they commit. The rules are the owner's: a planet
 * without trees is never easy, a planet without air is very hard, and distance from Terra makes any start harder.
 */
public final class StartDifficulty
{
    public enum Level
    {
        EASY("Easy", 0x55ff55), NORMAL("Normal", 0xffff55), HARD("Hard", 0xffaa00), VERY_HARD("Very hard", 0xff5555);

        public final String label;
        public final int color;

        Level(String label, int color)
        {
            this.label = label;
            this.color = color;
        }
    }

    public record Result(Level level, List<String> warnings)
    {}

    private static Level step(Level level, int steps)
    {
        return Level.values()[Math.min(Level.values().length - 1, level.ordinal() + steps)];
    }

    /** The base difficulty of a planet choice, before distance: air decides it, then trees. */
    public static Level planet(String choice)
    {
        return !StartPlanets.breathable(choice) ? Level.VERY_HARD : !StartPlanets.trees(choice) ? Level.NORMAL : Level.EASY;
    }

    /** The size class of a ship by its decks: 0 small, 1 medium, 2 large. Bigger ships have more to salvage. */
    public static int shipClass(int decks)
    {
        return decks >= 3 ? 2 : decks == 2 ? 1 : 0;
    }

    /**
     * The difficulty and warnings for a choice. {@code galaxy} is the chart the world will have, or null when it is not known
     * (a blank seed), in which case a specific planet is judged by its type alone.
     */
    public static Result describe(String choice, boolean crash, Galaxy galaxy)
    {
        return describe(choice, crash, galaxy, 0);
    }

    /**
     * As above for a ship of the given {@link #shipClass}. A crash on a bigger ship is easier: it has more furnishings and more
     * machines to salvage and move, so it can be flying again sooner. It is never easier than the planet allows: without air it
     * stays at least Hard, and without trees at least Normal.
     */
    public static Result describe(String choice, boolean crash, Galaxy galaxy, int shipClass)
    {
        var warnings = new ArrayList<String>();
        if (choice.equals("random"))
        {
            warnings.add("Any planet in any star system: it may have no air, and you may start far from Terra.");
            if (crash)
                warnings.add("The ship is wrecked: repairs come first.");
            return new Result(Level.HARD, warnings);
        }
        var level = planet(choice);
        if (!StartPlanets.breathable(choice))
            warnings.add("No air outside. You have a suit, one air canister and your ship's tether (72 blocks).");
        if (!StartPlanets.trees(choice))
            warnings.add("No trees: little wood or fuel nearby. You start with 16 coal.");
        int jumps = 0;
        if (galaxy != null)
        {
            Body body = StartPlanets.find(galaxy, choice).orElse(null);
            jumps = body == null ? 0 : StartPlanets.jumpsFromSol(galaxy, body);
        }
        if (jumps > 0)
        {
            level = step(level, jumps >= 3 ? 2 : 1);
            warnings.add(jumps + (jumps == 1 ? " jump" : " jumps") + " from Terra: fuel to get home is a real cost.");
        }
        if (crash)
        {
            warnings.add("The ship is wrecked: repairs come first.");
            if (shipClass > 0)
            {
                var floor = !StartPlanets.breathable(choice) ? Level.HARD : !StartPlanets.trees(choice) ? Level.NORMAL : Level.EASY;
                level = Level.values()[Math.max(floor.ordinal(), level.ordinal() - shipClass)];
                warnings.add(shipClass == 1 ? "A medium ship: more to salvage, and more machines to repair." : "A large ship: plenty to salvage, and its intact machines can be moved to repair the rest, so it can fly again soon.");
            }
        }
        return new Result(level, warnings);
    }

    private StartDifficulty()
    {
    }
}
