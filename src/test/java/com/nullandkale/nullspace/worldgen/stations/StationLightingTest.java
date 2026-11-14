package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.stations.StationLighting.Torch;

import java.util.*;

import net.minecraft.core.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StationLightingTest
{
    /** A closed room: interior {@code w} by {@code d} by five, walls and slabs opaque. */
    private record Box(int w, int d)
    {
        boolean inside(BlockPos at)
        {
            return at.getX() >= 1 && at.getX() <= w && at.getZ() >= 1 && at.getZ() <= d && at.getY() >= 1 && at.getY() <= 5;
        }

        StationLighting.Space space()
        {
            return this::inside;
        }

        List<BlockPos> standing()
        {
            var out = new ArrayList<BlockPos>();
            for (int x = 1; x <= w; x++)
                for (int z = 1; z <= d; z++)
                    out.add(new BlockPos(x, 1, z));
            return out;
        }

        List<Torch> walls()
        {
            var out = new ArrayList<Torch>();
            for (int x = 1; x <= w; x++)
                for (int z = 1; z <= d; z++)
                    for (var side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST))
                        if (!inside(new BlockPos(x, 3, z).relative(side)))
                            out.add(new Torch(new BlockPos(x, 3, z), side));
            return out;
        }

        List<Torch> floor()
        {
            return standing().stream().map(c -> new Torch(c, null)).toList();
        }
    }

    private static List<Torch> all(Box box)
    {
        var all = new ArrayList<>(box.walls());
        all.addAll(box.floor());
        return all;
    }

    @Test
    void roundsLightEveryStandingCellOfALargeRoom()
    {
        var box = new Box(16, 16);
        var result = StationLighting.light(box.space(), box.standing(), List.of(), List.of(), all(box), 12);
        assertTrue(result.dark().isEmpty(), result.dark().size() + " cells still dark");
        assertTrue(result.rounds() >= 1, "A big dark room needs fixing rounds");
        assertTrue(result.torches().size() < 40, "Torch count stays modest: " + result.torches().size());
    }

    @Test
    void aSmallRoomNeedsOnlyItsRegularTorches()
    {
        var box = new Box(5, 5);
        var baseline = List.of(new Torch(new BlockPos(3, 3, 1), Direction.NORTH), new Torch(new BlockPos(3, 3, 5), Direction.SOUTH));
        var result = StationLighting.light(box.space(), box.standing(), List.of(), baseline, all(box), 12);
        assertTrue(result.dark().isEmpty());
        assertEquals(2, result.torches().size(), "Two mirrored torches light a small room");
        assertEquals(0, result.rounds());
    }

    @Test
    void existingTorchesCountAsLightSources()
    {
        var box = new Box(16, 16);
        var first = StationLighting.light(box.space(), box.standing(), List.of(), List.of(), all(box), 12);
        var placed = first.torches().stream().map(Torch::at).toList();
        var again = StationLighting.light(box.space(), box.standing(), placed, List.of(), all(box), 12);
        assertTrue(again.torches().isEmpty(), "A second run adds nothing when the room is already lit");
    }

    @Test
    void torchesGoOnlyWhereTheCandidatesAllow()
    {
        var box = new Box(16, 16);
        var candidates = box.walls();
        var result = StationLighting.light(box.space(), box.standing(), List.of(), List.of(), candidates, 12);
        assertTrue(result.torches().stream().allMatch(candidates::contains));
        assertFalse(result.dark().isEmpty(), "Wall torches alone cannot light the middle of a sixteen-block room");
    }

    @Test
    void theSameInputGivesTheSameTorches()
    {
        var box = new Box(14, 18);
        var a = StationLighting.light(box.space(), box.standing(), List.of(), List.of(), all(box), 12);
        var b = StationLighting.light(box.space(), box.standing(), List.of(), List.of(), all(box), 12);
        assertEquals(a, b);
    }
}
