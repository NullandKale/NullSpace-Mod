package com.nullandkale.nullspace.worldgen.condition;

import java.util.Random;
import java.util.Set;

/**
 * What happened to a derelict. The history is a function of the site's seed and danger alone, so the
 * loot, and the damage pass that follows it, read the same story. Danger 0 and 1 are evacuated or
 * interrupted work, 2 adds breach and power failure, and 3 is breach or power failure.
 */
public enum DerelictHistory
{
    EVACUATED, BREACH, POWER_FAILURE, INTERRUPTED_WORK;

    /** The rooms whose electronics a power failure takes. */
    private static final Set<String> ELECTRIC = Set.of("bridge", "engineering", "operations", "maintenance");

    public static DerelictHistory of(long seed, int danger)
    {
        var random = new Random(mix(seed ^ 0x4157081CL));
        return switch (Math.max(0, Math.min(3, danger)))
        {
            case 0, 1 -> random.nextBoolean() ? EVACUATED : INTERRUPTED_WORK;
            case 2 -> new DerelictHistory[]{BREACH, POWER_FAILURE, INTERRUPTED_WORK}[random.nextInt(3)];
            default -> random.nextBoolean() ? BREACH : POWER_FAILURE;
        };
    }

    /** Spreads nearby values apart, for the damage pass's choices. */
    public static long scatter(long value)
    {
        return mix(value);
    }

    /** Spreads nearby seeds apart, since Java's generator gives similar first values to similar seeds. */
    private static long mix(long seed)
    {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** True when a breach opened this room: a third of the rooms, by seed. */
    public static boolean wound(long seed, String room)
    {
        return roll(seed, room, 3);
    }

    private static boolean roll(long seed, String room, int in)
    {
        return Math.floorMod(new Random(mix(seed * 31 + room.hashCode())).nextInt(), in) == 0;
    }

    /** True when the room's loot is the dead variant: a power failure in a room with electronics. */
    public boolean dead(String use)
    {
        return this == POWER_FAILURE && ELECTRIC.contains(use);
    }
}
