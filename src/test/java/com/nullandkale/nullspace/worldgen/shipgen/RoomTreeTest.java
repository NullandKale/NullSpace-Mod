package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The rooms of a ship, joined by doors and stairs, form a tree: one way between any two rooms, none of them in a loop. */
class RoomTreeTest
{
    /** Edges between spaces: one per door, one per stair flight. */
    static List<int[]> edges(Layout layout)
    {
        var out = new ArrayList<int[]>();
        for (var link : layout.links)
        {
            int a = -1, b = -1;
            for (int i = 0; i < layout.spaces.size(); i++)
            {
                var cells = layout.spaces.get(i).cells();
                if (cells.contains(link.at().relative(link.across())))
                    a = i;
                if (cells.contains(link.at().relative(link.across().getOpposite())))
                    b = i;
            }
            if (a >= 0 && b >= 0 && a != b)
                out.add(new int[]{a, b});
        }
        for (var flight : layout.flights)
        {
            // The stair lane sits in a widened part of the walkway: the room it joins is the one within two blocks across of the landing below and of the last step above.
            int lower = near(layout, flight.foot().relative(flight.up().getOpposite()));
            int upper = near(layout, flight.foot().relative(flight.up(), flight.steps() - 1).above(flight.steps()));
            if (lower >= 0 && upper >= 0 && lower != upper)
                out.add(new int[]{lower, upper});
        }
        // Mirrored pairs (two stair lanes, a door either side) join the same two rooms: one connection.
        var seen = new HashSet<List<Integer>>();
        out.removeIf(e -> !seen.add(List.of(Math.min(e[0], e[1]), Math.max(e[0], e[1]))));
        return out;
    }

    private static int near(Layout layout, net.minecraft.core.BlockPos at)
    {
        for (int reach = 0; reach <= 2; reach++)
            for (int i = 0; i < layout.spaces.size(); i++)
                for (int dx = -reach; dx <= reach; dx++)
                    for (int dz = -reach; dz <= reach; dz++)
                        if (layout.spaces.get(i).cells().contains(at.offset(dx, 0, dz)))
                            return i;
        return -1;
    }

    @Test
    void roomsFormATreeFromTheDock()
    {
        var bad = new ArrayList<String>();
        for (var spec : ShipGeneratorTest.specs())
        {
            var layout = Shapes.layout(spec);
            var edges = edges(layout);
            int n = layout.spaces.size();
            var parent = new int[n];
            Arrays.setAll(parent, i -> i);
            int cycles = 0;
            for (var e : edges)
            {
                int a = find(parent, e[0]), b = find(parent, e[1]);
                if (a == b)
                    cycles++;
                else
                    parent[a] = b;
            }
            int parts = 0;
            for (int i = 0; i < n; i++)
                if (find(parent, i) == i)
                    parts++;
            // A twin hull's two walkways meet at the bridge end and at engineering, so it has exactly one ring by design. Spines and domes are trees.
            int allowed = spec.shape() == Spec.Shape.TWIN ? 1 : 0;
            if (cycles > allowed || parts > 1)
                bad.add(spec + ": " + n + " rooms, " + edges.size() + " joins, " + cycles + " loops, " + parts + " separate parts");
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    private static int find(int[] parent, int i)
    {
        while (parent[i] != i)
            i = parent[i] = parent[parent[i]];
        return i;
    }
}
