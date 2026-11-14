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

    /** The rooms where work was under way. */
    private static final Set<String> WORK = Set.of("workshop", "foundry", "kitchen", "growing_bay", "lab", "receiving_bay", "cargo_hold");

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

    /**
     * The loot tier of a room: the danger, nudged by at most one by the history. Evacuated rooms are about
     * half a tier lower (never the archive or the hoard), the work rooms of interrupted work are one higher,
     * and so are the rooms a breach opened, so a wrecked room is never emptier than an intact one.
     */
    public int tier(int danger, long seed, String room, String use, boolean hoard)
    {
        int shift = switch (this)
        {
            case EVACUATED -> !hoard && !use.equals("archive") && roll(seed, room, 2) ? -1 : 0;
            case INTERRUPTED_WORK -> WORK.contains(use) ? 1 : 0;
            case BREACH -> wound(seed, room) ? 1 : 0;
            case POWER_FAILURE -> 0;
        };
        return Math.max(0, Math.min(3, danger + shift));
    }
}
