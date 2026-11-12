package com.nullandkale.nullspace.model.air;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OxygenStatusTest
{
    @Test
    void healthyCabinsAreQuietAndRefillingIsVisible()
    {
        assertEquals("", OxygenStatus.text(1000, 100, true, false, false, 0));
        assertEquals("", OxygenStatus.text(800, 80, true, false, false, 0));
        assertTrue(OxygenStatus.text(600, 100, true, false, false, 0).contains("recovering"));
    }

    @Test
    void LowRoomAirAndDangerGiveAnActionInsteadOfTetherNoise()
    {
        assertTrue(OxygenStatus.text(600, 30, true, false, false, 0).contains("check air supply"));
        assertTrue(OxygenStatus.text(700, 0, false, false, false, 0).contains("VACUUM"));
        assertTrue(OxygenStatus.text(100, 0, false, true, true, 50).contains("return to air"));
        assertEquals("Suit air 90% | Canister 50%", OxygenStatus.text(900, 0, false, true, true, 50));
    }
}
