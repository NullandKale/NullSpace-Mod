package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WetRoomsTest
{
    @Test
    void allShipFamiliesKeepWaterOnTheFirstFloor()
    {
        int wetShips = 0;
        for (var shape : Spec.Shape.values())
            for (var tier : Spec.Tier.values())
                for (var role : Spec.Role.values())
                    for (long seed = 1; seed <= 8; seed++)
                    {
                        var spec = new Spec(Spec.Maker.RIVET, shape, tier, role, seed);
                        var layout = Shapes.layout(spec);
                        for (var room : layout.spaces)
                            if (room.use == RoomUse.HYDROPONICS)
                                assertEquals(0, room.level, spec + " " + room.id);
                        var ship = ShipGenerator.build(spec);
                        int firstFloor = ship.rooms().stream().mapToInt(r -> r.walk().getY()).min().orElseThrow() - 1;
                        var water = ship.voxels().entrySet().stream().filter(e -> e.getValue().kind() == Kind.WATER).toList();
                        if (!water.isEmpty())
                            wetShips++;
                        for (var entry : water)
                            assertEquals(firstFloor, entry.getKey().getY(), spec + " water above first floor");
                    }
        assertTrue(wetShips > 0, "Exercise real planted rooms, not just dry ships");
    }

    @Test
    void swapPreservesTheRoomBagGeometryAndSpecialRooms()
    {
        var layout = new Layout();
        var bridge = layout.add(Space.box("bridge", RoomUse.BRIDGE, 0, 0, 4, 0, 6, 0, 6, Direction.NORTH, 2));
        var dry = layout.add(Space.box("dry", RoomUse.CRAFTING, 0, 0, 4, 9, 15, 0, 6, Direction.EAST, 0));
        var wet = layout.add(Space.box("wet", RoomUse.HYDROPONICS, 1, 5, 4, 9, 15, 0, 6, Direction.WEST, 2));
        layout.dockSpace = bridge;
        Program.groundWaterRooms(layout);
        assertSame(bridge, layout.spaces.getFirst());
        assertSame(bridge, layout.dockSpace);
        assertEquals(RoomUse.HYDROPONICS, layout.spaces.get(1).use);
        assertEquals(RoomUse.CRAFTING, layout.spaces.get(2).use);
        assertEquals(dry.cells(), layout.spaces.get(1).cells());
        assertEquals(wet.cells(), layout.spaces.get(2).cells());
        assertEquals(dry.forward, layout.spaces.get(1).forward);
        assertEquals(2, layout.spaces.get(1).windows);
        assertEquals(0, layout.spaces.get(2).windows);
    }

    @Test
    void functionalFlooringWinsOverLightsFromEitherFurnishingOrder()
    {
        var lower = Space.box("lower", RoomUse.CREW, 0, 0, 4, 0, 6, 0, 6, Direction.NORTH, 0);
        var upper = Space.box("upper", RoomUse.HYDROPONICS, 1, 5, 4, 0, 6, 0, 6, Direction.NORTH, 2);
        for (var kind : List.of(Kind.FARMLAND, Kind.WATER, Kind.MOSS))
        {
            var grid = new Grid();
            var slab = upper.at(2, 2, -1);
            grid.set(slab, Kind.FLOOR);
            var lowerFrame = new Frame(lower, grid, List.of());
            var upperFrame = new Frame(upper, grid, List.of());
            lowerFrame.light(2, 2);
            assertEquals(Kind.LIGHT, grid.kind(slab));
            upperFrame.floor(2, 2, kind);
            assertEquals(kind, grid.kind(slab));
            lowerFrame.light(2, 2);
            lowerFrame.roofLight(2, 2);
            assertEquals(kind, grid.kind(slab));
        }
    }
}
