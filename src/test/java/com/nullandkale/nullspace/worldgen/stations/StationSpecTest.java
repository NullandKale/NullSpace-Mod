package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StationSpecTest
{
    @Test
    void supportedBriefsRoundTripWithoutLosingIndependentChoices()
    {
        for (var maker : Maker.values())
            for (var purpose : StationSpec.Purpose.values())
                for (var size : StationSpec.Size.values())
                    for (var condition : StationSpec.Condition.values())
                    {
                        var spec = new StationSpec(maker, purpose, size, condition, Long.MIN_VALUE);
                        assertEquals(spec, StationSpec.read(spec.save()));
                        assertEquals(spec, StationSpec.parse(maker.name(), purpose.name().toLowerCase(Locale.ROOT), size.name(), condition.name(), Long.MIN_VALUE));
                    }
    }

    @Test
    void badSelectionsAndFutureVersionsAreNotSilentlyReinterpreted()
    {
        assertThrows(IllegalArgumentException.class, () -> StationSpec.parse("missing", "harbor", "compact", "derelict", 0));
        assertThrows(IllegalArgumentException.class, () -> StationSpec.parse("rivet", "missing", "compact", "derelict", 0));
        assertThrows(IllegalArgumentException.class, () -> StationSpec.parse("rivet", "harbor", "missing", "derelict", 0));
        assertThrows(IllegalArgumentException.class, () -> StationSpec.parse("rivet", "harbor", "compact", "missing", 0));
        var spec = StationSpec.parse("rivet", "harbor", "compact", "derelict", 0);
        var future = spec.save();
        future.putInt("version", StationSpec.VERSION + 1);
        assertThrows(IllegalArgumentException.class, () -> StationSpec.read(future));
        var incomplete = spec.save();
        incomplete.remove("seed");
        assertThrows(IllegalArgumentException.class, () -> StationSpec.read(incomplete));
    }
}
