package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomProgramTest
{
    @Test
    void allFittingUsesAppearBeforeAnyRepeatAcrossDecks()
    {
        for (var role : Spec.Role.values())
        {
            var bag = new Program.Bag(new Spec(Spec.Maker.RIVET, Spec.Shape.SPINE, Spec.Tier.LARGE, role, 7));
            var first = EnumSet.noneOf(RoomUse.class);
            for (int i = 0; i < 14; i++)
                assertTrue(first.add(bag.next(9, 9)), "Repeated a room before using the whole bag: " + first);
            assertEquals(14, first.size());
            var second = EnumSet.noneOf(RoomUse.class);
            for (int i = 0; i < 14; i++)
                assertTrue(second.add(bag.next(9, 9)));
            assertEquals(first, second);
        }
    }

    @Test
    void smallRoomsUseOnlyFittingRoomsAndDoNotExhaustTheBagForLargerRooms()
    {
        var bag = new Program.Bag(new Spec(Spec.Maker.RIVET, Spec.Shape.TWIN, Spec.Tier.LARGE, Spec.Role.SCOUT, 2));
        for (int i = 0; i < 20; i++)
            assertTrue(Program.fits(bag.next(5, 4), 5, 4));
        var larger = EnumSet.noneOf(RoomUse.class);
        for (int i = 0; i < 10; i++)
            larger.add(bag.next(9, 9));
        assertTrue(larger.containsAll(List.of(RoomUse.CRAFTING, RoomUse.ENCHANTING, RoomUse.HYDROPONICS, RoomUse.LAB)));
    }
}
