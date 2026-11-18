package com.nullandkale.nullspace.gameplay.systems;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.model.air.Atmosphere;

import java.util.Arrays;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class AirStoreTest
{
    @Test
    void runsRoundTripAndAreSmallForMostlySolidAir()
    {
        var grid = new Atmosphere.Grid(512, 40, 512);
        var air = new int[grid.cells()];
        Arrays.fill(air, -1);
        for (int i = 0; i < 5000; i++)
            air[grid.index(100 + i % 50, 3, 100 + i / 50)] = i % 7 * 100;
        var runs = AirStore.encode(air);
        assertTrue(runs.length < 100_000, "a mostly solid grid is a few runs: " + runs.length);
        assertArrayEquals(air, AirStore.decode(runs, grid.cells()));
    }

    @Test
    void aLargeGridIsKeptAsRunsAndAnOrdinaryOneAsItWas()
    {
        var large = new Atmosphere.Grid(256, 32, 256);
        var small = new Atmosphere.Grid(128, 32, 128);
        assertTrue(AirStore.large(large));
        assertFalse(AirStore.large(small));
        var air = new int[large.cells()];
        var systems = new CompoundTag();
        AirStore.write(systems, air, large);
        assertTrue(systems.contains("airz") && !systems.contains("air"));
        assertArrayEquals(air, AirStore.read(systems, large).orElseThrow());
        var ordinary = new int[small.cells()];
        var other = new CompoundTag();
        AirStore.write(other, ordinary, small);
        assertTrue(other.contains("air") && !other.contains("airz"));
        assertArrayEquals(ordinary, AirStore.read(other, small).orElseThrow());
    }

    @Test
    void badRunsAreRefused()
    {
        assertThrows(IllegalStateException.class, () -> AirStore.decode(new int[]{1, 5}, 10));
        assertThrows(IllegalStateException.class, () -> AirStore.decode(new int[]{1, 20}, 10));
    }
}
