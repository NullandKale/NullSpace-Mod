package com.nullandkale.nullspace.worldgen.stations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StationPiersTest
{
    @Test
    void pierLinesAreMirroredEvenlySpacedAndInsideTheWall()
    {
        for (int spacing = 4; spacing <= 6; spacing++)
            for (int length = 5; length <= 40; length++)
            {
                var line = StationStructure.pierLine(length, spacing);
                for (int i = 0; i < line.size(); i++)
                {
                    assertEquals(length - line.get(line.size() - 1 - i), line.get(i), "mirrored at length " + length + " spacing " + spacing);
                    assertTrue(line.get(i) > 0 && line.get(i) < length);
                }
                int gap = Integer.MAX_VALUE, widest = 0;
                var all = new java.util.ArrayList<Integer>();
                all.add(0);
                all.addAll(line);
                all.add(length);
                for (int i = 1; i < all.size(); i++)
                {
                    gap = Math.min(gap, all.get(i) - all.get(i - 1));
                    widest = Math.max(widest, all.get(i) - all.get(i - 1));
                }
                assertTrue(widest - gap <= 1 || line.isEmpty(), "bays differ by at most one block: " + all);
            }
    }
}
