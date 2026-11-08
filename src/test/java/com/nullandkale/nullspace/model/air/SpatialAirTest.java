package com.nullandkale.nullspace.model.air;

import com.nullandkale.nullspace.gameplay.systems.FuelSystem;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The playtest 2 oxygen balance, measured: drain, breach, supply and refill times. */
class SpatialAirTest
{
    static boolean[] solid()
    {
        var a = new boolean[Atmosphere.CELLS];
        Arrays.fill(a, true);
        return a;
    }

    static int[] empty()
    {
        var a = new int[Atmosphere.CELLS];
        Arrays.fill(a, -1);
        return a;
    }

    static long total(int[] a)
    {
        return Arrays.stream(a).filter(n -> n >= 0).asLongStream().sum();
    }

    /** Seals of a 4x3x5 room (60 blocks) and, optionally, a hole channel through its west wall. */
    static boolean[] room(int holeWidth, int holeHeight)
    {
        var s = solid();
        for (int x = 10; x < 14; x++)
            for (int y = 10; y < 13; y++)
                for (int z = 10; z < 15; z++)
                    s[Atmosphere.index(x, y, z)] = false;
        for (int w = 0; w < holeWidth; w++)
            for (int h = 0; h < holeHeight; h++)
                for (int x = 0; x < 10; x++)
                    s[Atmosphere.index(x, 10 + h, 10 + w)] = false;
        return s;
    }

    /**
     * A room that existed sealed (full, or empty) and then had a hole opened: breaching an existing
     * room is what makes leak faces, exactly as in play.
     */
    static Atmosphere.Topology breached(boolean[] seals, int[] air, boolean full)
    {
        Atmosphere.rebuild(room(0, 0), air, full);
        return Atmosphere.rebuild(seals, air, false);
    }

    static long capacity(Atmosphere.Topology t)
    {
        return t.rooms().stream().mapToLong(r -> r.cells().length * (long) Atmosphere.FULL).sum();
    }

    /** Ticks until the room total reaches {@code target}, stepping one tick at a time. */
    static int ticksUntil(int[] a, Atmosphere.Topology t, boolean[] s, Map<Integer, Integer> pumps, int exterior, long target, boolean rising)
    {
        for (int tick = 1; tick <= 5000; tick++)
        {
            SpatialAir.step(a, t, s, pumps, Map.of(), 1, exterior);
            if (rising ? total(a) >= target : total(a) <= target)
                return tick;
        }
        return -1;
    }

    @Test
    void oneHoleEmptiesAFullRoomInAboutThirtySeconds()
    {
        var s = room(1, 1);
        var a = empty();
        var t = breached(s, a, true);
        assertEquals(1, t.rooms().size());
        assertEquals(1, t.rooms().getFirst().leaks(), "one open face");
        int ticks = ticksUntil(a, t, s, Map.of(), 0, 0, false);
        assertTrue(ticks >= 590 && ticks <= 610, "one hole: 100 to 0 in about 30 s, took " + ticks);
    }

    @Test
    void drainScalesWithTotalHoleAreaAndABigBreachIsAlmostInstant()
    {
        var two = room(2, 1);
        var a = empty();
        var t = breached(two, a, true);
        int ticks = ticksUntil(a, t, two, Map.of(), 0, 0, false);
        assertTrue(ticks >= 295 && ticks <= 305, "two holes drain twice as fast, took " + ticks);
        var breach = room(5, 3);
        a = empty();
        t = breached(breach, a, true);
        ticks = ticksUntil(a, t, breach, Map.of(), 0, 0, false);
        assertTrue(ticks <= 45, "a 5x3 breach empties the room in about 2 s, took " + ticks);
    }

    static final int NORMAL = SpatialAir.output(SpatialAir.SUPPLY_POWER);

    @Test
    void supplyOutputIsLinearToFourPowerThenHalfAsSteepToEight()
    {
        assertEquals(4, SpatialAir.SUPPLY_POWER);
        assertEquals(0, SpatialAir.output(0));
        assertEquals(SpatialAir.FULL_OUTPUT / 2, SpatialAir.output(2));
        assertEquals(SpatialAir.FULL_OUTPUT, SpatialAir.output(4));
        assertEquals(SpatialAir.FULL_OUTPUT * 3 / 2, SpatialAir.output(8), "8 power is 150%");
        assertEquals(SpatialAir.output(8), SpatialAir.output(20), "a supply uses at most 8 power");
    }

    @Test
    void twoNormalPowerAirSuppliesExactlyHoldOneHole()
    {
        var s = room(1, 1);
        var a = empty();
        var t = breached(s, a, true);
        long full = capacity(t);
        var pumps = Map.of(Atmosphere.index(11, 10, 11), NORMAL, Atmosphere.index(12, 10, 12), NORMAL);
        for (int tick = 0; tick < 1200; tick++)
            SpatialAir.step(a, t, s, pumps, Map.of(), 1, 0);
        assertTrue(total(a) >= full * 99 / 100, "two supplies at 4 power hold a one-block leak");
        // With a supply running the room settles at a trace of air, shown to players as 0.
        var one = Map.of(Atmosphere.index(11, 10, 11), NORMAL);
        int ticks = ticksUntil(a, t, s, one, 0, full / 100, false);
        assertTrue(ticks >= 1150 && ticks <= 1250, "one supply only halves the leak, took " + ticks);
    }

    @Test
    void oneOverdrivenAirSupplyCoversThreeQuartersOfAHole()
    {
        var s = room(1, 1);
        var a = empty();
        var t = breached(s, a, true);
        long full = capacity(t);
        var overdriven = Map.of(Atmosphere.index(11, 10, 11), SpatialAir.output(8));
        // Net loss is a quarter of the hole: about 120 s from full to empty instead of 30 s.
        int ticks = ticksUntil(a, t, s, overdriven, 0, full / 100, false);
        assertTrue(ticks >= 2300 && ticks <= 2450, "one supply at 8 power holds 75%, took " + ticks);
    }

    @Test
    void anIdleStarterShipBurnsAboutFourFuelAnHour()
    {
        // Controls 1, one Air Supply at normal power, doors 1, scanner and camera 2.
        int idle = 1 + SpatialAir.SUPPLY_POWER + 1 + 2;
        double perHour = FuelSystem.fuelPerHour(idle);
        assertTrue(perHour >= 3.5 && perHour <= 4.5, "idle ship: " + perHour + " fuel/hour");
        double leak = FuelSystem.fuelPerHour(idle + SpatialAir.SUPPLY_POWER);
        assertTrue(leak - perHour >= 1.5, "holding a leak with a second supply is a noticeable cost");
    }

    @Test
    void suppliesCannotRefillABreachUntilItIsPatched()
    {
        var s = room(5, 3);
        var a = empty();
        var t = breached(s, a, true);
        var pumps = Map.of(Atmosphere.index(11, 10, 11), NORMAL, Atmosphere.index(12, 10, 12), NORMAL);
        for (int tick = 0; tick < 200; tick++)
            SpatialAir.step(a, t, s, pumps, Map.of(), 1, 0);
        assertTrue(total(a) < capacity(t) / 100, "a large breach stays empty (0 to players) while open");
    }

    @Test
    void oneAirSupplyFillsAnEmptySealedRoomInAboutAMinute()
    {
        var s = room(0, 0);
        var a = empty();
        var t = Atmosphere.rebuild(s, a, false);
        long full = capacity(t);
        int ticks = ticksUntil(a, t, s, Map.of(Atmosphere.index(11, 10, 11), NORMAL), 0, full, true);
        assertTrue(ticks >= 1190 && ticks <= 1210, "one supply: 0 to 100 in about 60 s, took " + ticks);
        long before = total(a);
        SpatialAir.step(a, t, s, Map.of(Atmosphere.index(11, 10, 11), NORMAL), Map.of(), 5, 0);
        assertEquals(before, total(a), "a full sealed room is never overfilled");
    }

    @Test
    void anOpenRoomOnABreathablePlanetRefillsFromOutside()
    {
        var s = room(1, 1);
        var a = empty();
        var t = breached(s, a, false);
        long full = capacity(t);
        int ticks = ticksUntil(a, t, s, Map.of(), Atmosphere.FULL, full, true);
        assertTrue(ticks >= 590 && ticks <= 610, "outside air flows in through the hole, took " + ticks);
    }

    @Test
    void gasIsAccountedAndRoomsStayEven()
    {
        var s = room(1, 1);
        var a = empty();
        var t = breached(s, a, true);
        long before = total(a);
        var r = SpatialAir.step(a, t, s, Map.of(Atmosphere.index(11, 10, 11), 1), Map.of(Atmosphere.index(12, 11, 12), 3), 5, 0);
        assertEquals(before + r.supplied() - r.escaped() - r.consumed(), total(a));
        var cells = t.rooms().getFirst().cells();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int c : cells)
        {
            min = Math.min(min, a[c]);
            max = Math.max(max, a[c]);
        }
        assertTrue(max - min <= 1, "air is shared evenly within a sealed room");
    }

    @Test
    void leaksShowParticlesAtTheHoleAndAFullSealedRoomShowsNone()
    {
        var s = room(1, 1);
        var a = empty();
        var t = breached(s, a, true);
        var r = SpatialAir.step(a, t, s, Map.of(), Map.of(), 1, 0);
        assertTrue(r.flows().stream().anyMatch(SpatialAir.Flow::exterior), "the hole shows escaping air");
        var sealed = room(0, 0);
        var b = empty();
        var u = Atmosphere.rebuild(sealed, b, true);
        assertTrue(SpatialAir.step(b, u, sealed, Map.of(), Map.of(), 5, 0).flows().isEmpty());
    }

    @Test
    void playersSeeAirFromZeroToOneHundred()
    {
        assertEquals(100, SpatialAir.percent(Atmosphere.FULL));
        assertEquals(25, SpatialAir.percent(Atmosphere.BREATHABLE));
        assertEquals(0, SpatialAir.percent(-1));
    }
}
