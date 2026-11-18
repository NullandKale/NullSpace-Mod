package com.nullandkale.nullspace.gameplay.trees;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TreeGrowthTest
{
    private static final long DAY = TreeGrowth.DAY;

    @Test
    void aTreeGrowsOneAppleForEachWholeDayAndKeepsTheRestOfTheDay()
    {
        var tree = TreeGrowth.advance(new TreeGrowth.State(0, 1000), 1000 + 2 * DAY + 5000);
        assertEquals(2, tree.apples());
        assertEquals(1000 + 2 * DAY, tree.seen(), "the part of a day left over counts toward the next apple");
        assertEquals(new TreeGrowth.State(0, 1000), TreeGrowth.advance(new TreeGrowth.State(0, 1000), 1000 + DAY - 1), "not yet a day");
    }

    @Test
    void aTreeNeverHoldsMoreThanEightAndANewTreeStartsLooking()
    {
        assertEquals(8, TreeGrowth.advance(new TreeGrowth.State(3, 0), 100 * DAY).apples());
        var first = TreeGrowth.advance(new TreeGrowth.State(0, TreeGrowth.NEVER), 5000);
        assertEquals(new TreeGrowth.State(0, 5000), first, "a tree that has not looked starts counting now");
    }

    @Test
    void aFullTreeBanksNoTimeSoPickingStartsTheNextDayThen()
    {
        var full = TreeGrowth.advance(new TreeGrowth.State(8, 0), 50 * DAY);
        assertEquals(new TreeGrowth.State(8, 50 * DAY), full);
        var picked = new TreeGrowth.State(7, full.seen());
        assertEquals(7, TreeGrowth.advance(picked, full.seen() + DAY - 1).apples());
        assertEquals(8, TreeGrowth.advance(picked, full.seen() + DAY).apples());
    }

    @Test
    void aClockThatRanBackwardsAddsNothing()
    {
        assertEquals(new TreeGrowth.State(2, 10_000), TreeGrowth.advance(new TreeGrowth.State(2, 10_000), 5_000));
    }

    @Test
    void theTreeShapeHasATrunkACrownAndEightDistinctPlacesAnAppleHangsUnderTheCrown()
    {
        var crown = new HashSet<>(TreeShape.crown());
        assertEquals(TreeShape.TRUNK - 1, TreeShape.logs().size());
        assertEquals(8, TreeShape.fruit().size());
        assertEquals(8, new HashSet<>(TreeShape.fruit()).size(), "no two apples in one place");
        for (var cell : TreeShape.fruit())
        {
            assertFalse(crown.contains(cell), cell + " is open air, not a leaf");
            assertTrue(crown.contains(new TreeShape.Cell(cell.dx(), cell.dy() + 1, cell.dz())), cell + " hangs from the bottom of a leaf");
        }
        for (var log : TreeShape.logs())
            assertFalse(crown.contains(log), "the trunk is not leaves: " + log);
        assertTrue(crown.contains(new TreeShape.Cell(0, 6, 0)), "leaves close over the top of the trunk");
    }
}
