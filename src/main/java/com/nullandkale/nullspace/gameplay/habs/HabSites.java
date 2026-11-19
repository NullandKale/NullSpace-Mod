package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.SplittableRandom;

/** One repeatable dungeon per village-scale region, independent of who explores it first. */
public final class HabSites
{
    public static final int SPACING = 768;
    private static final int[][] OFFSETS = {{0, 0}, {-64, -64}, {0, -64}, {64, -64}, {-64, 0}, {64, 0}, {-64, 64}, {0, 64}, {64, 64}};
    public static final int ATTEMPTS = OFFSETS.length;
    private static final StationSpec.Purpose[] PURPOSES = {StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY};

    public record Site(int x, int z, StationSpec spec)
    {}

    public static Site site(long worldSeed, int cellX, int cellZ)
    {
        long seed = worldSeed ^ cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xBF58476D1CE4E5B9L;
        var random = new SplittableRandom(seed);
        int x = Math.addExact(Math.multiplyExact(cellX, SPACING), SPACING / 2 + random.nextInt(-32, 33));
        int z = Math.addExact(Math.multiplyExact(cellZ, SPACING), SPACING / 2 + random.nextInt(-32, 33));
        int draw = random.nextInt(10);
        var size = draw < 6 ? StationSpec.Size.COMPACT : draw < 9 ? StationSpec.Size.STANDARD : StationSpec.Size.LARGE;
        return new Site(x, z, new StationSpec(Maker.values()[random.nextInt(Maker.values().length)], PURPOSES[random.nextInt(PURPOSES.length)], size, StationSpec.Condition.OPERATIONAL, random.nextLong()));
    }

    /** Nearby terrain alternatives keep the same dungeon and remain clear of neighboring regions. */
    public static Site site(long worldSeed, int cellX, int cellZ, int attempt)
    {
        var site = site(worldSeed, cellX, cellZ);
        return new Site(Math.addExact(site.x(), OFFSETS[attempt][0]), Math.addExact(site.z(), OFFSETS[attempt][1]), site.spec());
    }

    private HabSites()
    {
    }
}
