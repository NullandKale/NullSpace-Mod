package com.nullandkale.nullspace.ui.apps;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChartLabelsTest
{
    @Test
    void crowdedContactsAndEdgeLabelsRemainVisibleWithoutOverlapping()
    {
        var contacts = new ArrayList<ChartLabels.Contact>();
        for (int i = 0; i < 14; i++)
            contacts.add(new ChartLabels.Contact("place" + i, "Contact " + i, i < 12 ? 500 : 1000, i < 12 ? 500 : 1000));
        var placed = ChartLabels.place(contacts, 76, 18);
        var occupied = new HashSet<ChartLabels.Cell>();
        for (var p : placed)
            assertTrue(occupied.add(new ChartLabels.Cell(p.x(), p.y())));
        for (var p : placed)
        {
            assertEquals(contacts.stream().filter(c -> c.id().equals(p.id())).findFirst().orElseThrow().name(), p.label());
            assertTrue(p.labelX() >= 0 && p.labelX() + p.label().length() <= 76 && p.labelY() >= 0 && p.labelY() < 18);
            for (int i = 0; i < p.label().length(); i++)
                assertTrue(occupied.add(new ChartLabels.Cell(p.labelX() + i, p.labelY())), p.id());
        }
        for (var p : placed)
            assertTrue(Collections.disjoint(occupied, p.line()), "Leader dots cannot cover another contact or label");
        assertTrue(placed.stream().anyMatch(p -> !p.line().isEmpty()));
    }

    @Test
    void changingLabelPriorityDoesNotMoveContactGlyphs()
    {
        var contacts = new ArrayList<>(List.of(new ChartLabels.Contact("a", "Alpha", 500, 500), new ChartLabels.Contact("b", "Beta", 500, 500)));
        var before = ChartLabels.place(contacts, 76, 18);
        Collections.reverse(contacts);
        var after = ChartLabels.place(contacts, 76, 18);
        for (var p : before)
        {
            var same = after.stream().filter(q -> q.id().equals(p.id())).findFirst().orElseThrow();
            assertEquals(new ChartLabels.Cell(p.x(), p.y()), new ChartLabels.Cell(same.x(), same.y()));
        }
    }
}
