package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TinyShipGeneratorTest
{
    @Test
    void twoCompartmentsFitSmallPadsAndKeepEveryFixtureReachable()
    {
        for (var maker : Spec.Maker.values())
        {
            var ship = TinyShipGenerator.build(maker, 1);
            assertEquals(ship, TinyShipGenerator.build(maker, 1));
            assertTrue(ship.width() <= 16 && ship.depth() <= 16);
            assertEquals(1, ship.decks());
            assertEquals(List.of(RoomUse.BRIDGE, RoomUse.ENGINEERING), ship.rooms().stream().map(ShipGenerator.Landmark::use).toList());
            var cells = new Walk.Cells()
            {
                public Kind kind(BlockPos at)
                {
                    return ship.kind(at);
                }

                public boolean interior(BlockPos at)
                {
                    return ship.interior().contains(at);
                }
            };
            var reach = Walk.reachable(cells, ship.dock().south());
            for (var room : ship.rooms())
                assertTrue(reach.contains(room.walk()), maker + " cannot enter " + room.name());
            for (var entry : ship.voxels().entrySet())
                if (entry.getValue().kind().fixture() && entry.getValue().kind() != Kind.CAMERA)
                    assertTrue(Walk.usable(reach, entry.getKey()), maker + " cannot use " + entry);
            for (var kind : List.of(Kind.COMPUTER, Kind.SCANNER, Kind.BED, Kind.BED_FOOT, Kind.CRAFTING_TABLE, Kind.FURNACE))
                assertEquals(1, ship.voxels().values().stream().filter(v -> v.kind() == kind).count(), maker + " " + kind);
            var head = ship.voxels().entrySet().stream().filter(e -> e.getValue().kind() == Kind.BED).findFirst().orElseThrow();
            assertEquals(Kind.BED_FOOT, ship.kind(head.getKey().relative(head.getValue().facing().getOpposite())));
            var seen = new HashSet<BlockPos>();
            var queue = new ArrayDeque<BlockPos>();
            seen.add(ship.helm());
            queue.add(ship.helm());
            while (!queue.isEmpty())
            {
                var at = queue.removeFirst();
                for (var direction : Direction.values())
                {
                    var next = at.relative(direction);
                    if (ship.voxels().containsKey(next) && seen.add(next))
                        queue.addLast(next);
                }
            }
            assertEquals(ship.voxels().keySet(), seen, maker + " detached blocks");
        }
    }
}
