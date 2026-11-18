package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.model.air.Atmosphere;

import java.util.Arrays;
import java.util.Optional;

import net.minecraft.nbt.CompoundTag;

/**
 * How a vessel's air is kept in its saved systems. An ordinary station or ship keeps one number for each cell of its grid. A large station (a hab
 * can be 512 blocks across, tens of millions of cells) is kept as runs of equal cells, since almost all of it is solid or outside, and is written
 * when the world is saved rather than every tick: see {@link ShipSystems#flushLargeAir}.
 */
final class AirStore
{
    /** The cells of the largest ordinary station: a grid with more cells than this is a large station. */
    static final int ORDINARY_CELLS = 128 * 32 * 128;

    static boolean large(Atmosphere.Grid grid)
    {
        return grid.cells() > ORDINARY_CELLS;
    }

    /** The saved air, whichever way it is kept, or empty when none was saved. */
    static Optional<int[]> read(CompoundTag systems, Atmosphere.Grid grid)
    {
        var runs = systems.getIntArray("airz");
        if (runs.isPresent())
            return Optional.of(decode(runs.get(), grid.cells()));
        return systems.getIntArray("air");
    }

    /** Keeps the air in the systems the way its grid is kept. */
    static void write(CompoundTag systems, int[] air, Atmosphere.Grid grid)
    {
        if (large(grid))
        {
            systems.putIntArray("airz", encode(air));
            systems.remove("air");
        }
        else
            systems.putIntArray("air", air);
    }

    /** What a tick does to keep the air: an ordinary grid is written as it stands, a large one is written when the world is saved. */
    static void tick(CompoundTag systems, int[] air, Atmosphere.Grid grid)
    {
        if (!large(grid))
            systems.putIntArray("air", air);
    }

    /** The saved seals of a large vessel, or empty when none were saved. */
    static Optional<boolean[]> readSeals(CompoundTag systems, Atmosphere.Grid grid)
    {
        var runs = systems.getIntArray("sealz");
        if (runs.isEmpty())
            return Optional.empty();
        var values = decode(runs.get(), grid.cells());
        var seals = new boolean[values.length];
        for (int i = 0; i < values.length; i++)
            seals[i] = values[i] != 0;
        return Optional.of(seals);
    }

    static void writeSeals(CompoundTag systems, boolean[] seals)
    {
        var values = new int[seals.length];
        for (int i = 0; i < values.length; i++)
            values[i] = seals[i] ? 1 : 0;
        systems.putIntArray("sealz", encode(values));
    }

    static int[] encode(int[] air)
    {
        var out = new int[1024];
        int n = 0;
        for (int i = 0; i < air.length;)
        {
            int value = air[i], j = i + 1;
            while (j < air.length && air[j] == value)
                j++;
            if (n + 2 > out.length)
                out = Arrays.copyOf(out, out.length * 2);
            out[n++] = value;
            out[n++] = j - i;
            i = j;
        }
        return Arrays.copyOf(out, n);
    }

    static int[] decode(int[] runs, int cells)
    {
        var air = new int[cells];
        int at = 0;
        for (int i = 0; i + 1 < runs.length; i += 2)
        {
            if (runs[i + 1] < 0 || at + runs[i + 1] > cells)
                throw new IllegalStateException("Invalid saved atmosphere");
            Arrays.fill(air, at, at + runs[i + 1], runs[i]);
            at += runs[i + 1];
        }
        if (at != cells)
            throw new IllegalStateException("Invalid saved atmosphere");
        return air;
    }

    private AirStore()
    {
    }
}
