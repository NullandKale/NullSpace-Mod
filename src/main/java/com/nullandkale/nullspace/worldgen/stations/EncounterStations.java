package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

import java.util.Random;

/** The abandoned station a journey contact leads to: a brief fixed by the encounter seed, planned like every other station. */
public final class EncounterStations
{
    public static StationSpec encounter(long seed)
    {
        var random = new Random(seed ^ 0x5A7E11A5L);
        var maker = Maker.values()[random.nextInt(Maker.values().length)];
        var purpose = StationSpec.Purpose.values()[random.nextInt(StationSpec.Purpose.values().length)];
        var size = random.nextInt(100) < 60 ? StationSpec.Size.COMPACT : StationSpec.Size.STANDARD;
        return new StationSpec(maker, purpose, size, StationSpec.Condition.DERELICT, seed);
    }

    /** The station's blocks, entity points and one marked loot chest per room, which a found station stocks. */
    public static StationBlueprint.Plan build(StationSpec spec)
    {
        return StationStructure.build(StationPlanner.generate(spec));
    }

    /** The same station as a wreck: the zone's danger sets how much of it is destroyed, the seed which story it tells. */
    public static StationBlueprint.Plan build(StationSpec spec, int danger)
    {
        return build(spec, danger, null);
    }

    /** As above, telling the story of {@code history} when it is not null. */
    public static StationBlueprint.Plan build(StationSpec spec, int danger, com.nullandkale.nullspace.worldgen.condition.DerelictHistory history)
    {
        var layout = StationPlanner.generate(spec);
        return com.nullandkale.nullspace.worldgen.condition.DerelictDamage.apply(layout, 0, StationStructure.build(layout), spec.seed(), danger, history).plan();
    }

    private EncounterStations()
    {
    }
}
