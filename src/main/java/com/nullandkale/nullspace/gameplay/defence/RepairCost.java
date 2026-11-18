package com.nullandkale.nullspace.gameplay.defence;

/** What a repair bay charges and how fast it works: plain numbers, so the rules can be tested without Minecraft. */
public final class RepairCost
{
    /** Fuel to restore a diamond pickaxe's whole durability, and the cheapest job, in fuel (one coal is 1.00). */
    public static final double FULL_FUEL = 64.0, MINIMUM_FUEL = 8.0;

    public static final int FULL_DURABILITY = 1561;

    /** Durability restored per second, and the internal fuel units in one fuel. */
    public static final int PER_SECOND = 10, UNITS_PER_FUEL = 12800;

    /** What restoring {@code damage} points of durability costs, in fuel. */
    public static double fuelFor(int damage)
    {
        return Math.max(MINIMUM_FUEL, FULL_FUEL * damage / FULL_DURABILITY);
    }

    /** The same cost in the generators' internal units, rounded up. */
    public static int unitsFor(int damage)
    {
        return (int) Math.ceil(fuelFor(damage) * UNITS_PER_FUEL);
    }

    private RepairCost()
    {
    }
}
