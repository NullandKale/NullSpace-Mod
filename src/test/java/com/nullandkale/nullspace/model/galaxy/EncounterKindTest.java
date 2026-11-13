package com.nullandkale.nullspace.model.galaxy;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncounterKindTest
{
    @Test
    void savedIdsAndLegacyNamesRemainStable()
    {
        assertEquals(List.of("Abandoned growing station", "Damaged freighter", "Salvage ship", "Drifting cargo"), java.util.stream.IntStream.range(0, 4).mapToObj(JourneyPlan::name).toList());
        for (var kind : EncounterKind.values())
            assertSame(kind, EncounterKind.saved(kind.id));
        assertThrows(IllegalArgumentException.class, () -> EncounterKind.saved(99));
        assertFalse(EncounterKind.shipSalvage(0));
        assertFalse(EncounterKind.shipSalvage(4));
        assertTrue(EncounterKind.shipSalvage(1) && EncounterKind.shipSalvage(2) && EncounterKind.shipSalvage(3));
    }

    @Test
    void stationsAreRareWithinTheExistingSignalPool()
    {
        var counts = new EnumMap<EncounterKind, Integer>(EncounterKind.class);
        for (long roll = 0; roll < 100; roll++)
            counts.merge(EncounterKind.select(roll << 16), 1, Integer::sum);
        assertEquals(Map.of(EncounterKind.GROWING_STATION, 5, EncounterKind.DAMAGED_FREIGHTER, 35, EncounterKind.SALVAGE_SHIP, 35, EncounterKind.DRIFTING_CARGO, 20, EncounterKind.ABANDONED_STATION, 5), counts);
    }
}
