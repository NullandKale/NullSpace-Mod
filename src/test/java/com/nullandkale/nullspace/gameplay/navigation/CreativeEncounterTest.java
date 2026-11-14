package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.worldgen.condition.DerelictHistory;
import com.nullandkale.nullspace.worldgen.shipgen.Spec;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The settings a creative Scanner sends, and what they make. */
class CreativeEncounterTest
{
    @Test
    void everythingLeftToChanceIsDrawnFromTheSeed()
    {
        var c = CreativeEncounter.parse("2 1 auto auto auto auto auto random", 99);
        assertEquals(2, c.kind());
        assertEquals(1, c.danger());
        assertEquals(99, c.seed());
        assertNull(c.history());
        assertEquals("", c.design());
        assertNull(c.maker());
        assertNull(c.purpose());
        assertNull(c.size());
    }

    @Test
    void everySettingIsReadWhenChosen()
    {
        var c = CreativeEncounter.parse("4 3 breach rivet_hauler halcyon research large 12345", 99);
        assertEquals(4, c.kind());
        assertEquals(3, c.danger());
        assertEquals(12345, c.seed());
        assertEquals(DerelictHistory.BREACH, c.history());
        assertEquals("rivet_hauler", c.design());
        assertEquals(Spec.Maker.HALCYON, c.maker());
        assertEquals(StationSpec.Purpose.RESEARCH, c.purpose());
        assertEquals(StationSpec.Size.LARGE, c.size());
        assertEquals(DerelictHistory.POWER_FAILURE, CreativeEncounter.parse("4 0 power_failure auto auto auto auto 1", 0).history());
        assertEquals(DerelictHistory.INTERRUPTED_WORK, CreativeEncounter.parse("1 0 interrupted_work auto auto auto auto 1", 0).history());
    }

    @Test
    void aStationBriefKeepsWhatWasChosenAndDrawsTheRest()
    {
        var brief = CreativeEncounter.parse("4 2 auto auto bastion auto compact 321", 0).spec();
        assertEquals(Spec.Maker.BASTION, brief.maker());
        assertEquals(StationSpec.Size.COMPACT, brief.size());
        assertEquals(StationSpec.Condition.DERELICT, brief.condition());
        assertEquals(321, brief.seed());
        var drawn = com.nullandkale.nullspace.worldgen.stations.EncounterStations.encounter(321);
        assertEquals(drawn.purpose(), brief.purpose(), "The open purpose is the seed's own");
    }

    @Test
    void aWordMakesTheSameSeedEveryTime()
    {
        assertEquals(CreativeEncounter.parse("2 0 auto auto auto auto auto hello", 1).seed(), CreativeEncounter.parse("2 0 auto auto auto auto auto hello", 2).seed());
        assertNotEquals(CreativeEncounter.parse("2 0 auto auto auto auto auto hello", 1).seed(), CreativeEncounter.parse("2 0 auto auto auto auto auto world", 1).seed());
    }

    @Test
    void badSettingsAreRefusedWithAReason()
    {
        assertThrows(IllegalArgumentException.class, () -> CreativeEncounter.parse("2 1", 0));
        assertThrows(IllegalArgumentException.class, () -> CreativeEncounter.parse("9 1 auto auto auto auto auto random", 0));
        assertThrows(IllegalArgumentException.class, () -> CreativeEncounter.parse("2 1 nonsense auto auto auto auto random", 0));
        assertThrows(IllegalArgumentException.class, () -> CreativeEncounter.parse("2 1 auto rivet_spark auto auto auto random", 0));
    }

    @Test
    void dangerIsClampedToTheZones()
    {
        assertEquals(3, CreativeEncounter.parse("2 9 auto auto auto auto auto random", 0).danger());
        assertEquals(0, CreativeEncounter.parse("2 -4 auto auto auto auto auto random", 0).danger());
    }

    @Test
    void theSavedSettingsCarryTheStationBriefOnlyForAStation()
    {
        assertTrue(CreativeEncounter.parse("4 1 evacuated auto rivet auto auto 5", 0).save().contains("stationSpec"));
        assertFalse(CreativeEncounter.parse("2 1 evacuated rivet_hauler auto auto auto 5", 0).save().contains("stationSpec"));
        assertEquals(DerelictHistory.EVACUATED.ordinal(), CreativeEncounter.parse("2 1 evacuated auto auto auto auto 5", 0).save().getIntOr("history", -1));
    }
}
