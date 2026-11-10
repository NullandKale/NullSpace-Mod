package com.nullandkale.nullspace.worldgen.stations;

import java.util.*;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** G5: station pieces turn their doorways, save compactly and are placed by doorway. */
class StationPiecesTest
{
    @Test
    void catalogHasRoomsTunnelsAndDomes()
    {
        var ids = StationPieces.ALL.stream().map(StationPieces.Definition::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "Piece ids are unique");
        for (var role : List.of(StationLayout.Role.ARRIVAL, StationLayout.Role.WORKSHOP, StationLayout.Role.HABITATION, StationLayout.Role.ENGINEERING))
            assertEquals(role, StationPieces.forRole(role).role());
        assertEquals(4, StationPieces.ALL.stream().filter(d -> d.role() == StationLayout.Role.CORRIDOR && d.windows() == 1).count(), "Straight, corner, T and cross tunnels");
        // Every room, tunnel and dome comes in a few-windows and a many-windows version with the same doorways.
        for (var d : StationPieces.ALL)
            if (d.windows() == 1)
            {
                var bright = StationPieces.get(d.id() + "_bright");
                assertEquals(2, bright.windows(), d.id());
                assertEquals(d.doors(), bright.doors(), d.id());
                assertEquals(d.kit(), bright.kit(), d.id());
                assertEquals(d.id(), bright.family());
            }
        assertTrue(StationPieces.ALL.stream().anyMatch(d -> d.id().startsWith("dome_")), "Domes exist");
        assertTrue(StationPieces.ALL.stream().allMatch(d -> !d.doors().isEmpty()), "Every piece can be reached");
    }

    @Test
    void turningMovesDoorwaysAndSavedFormsRoundTrip()
    {
        var corner = new StationLayout.Room("1_0", "tunnel_corner", 1, 1, 0);
        assertEquals(EnumSet.of(Direction.EAST, Direction.SOUTH), corner.doors(), "A north-east corner turned once opens east and south");
        assertEquals("tunnel_corner@1", corner.encode());
        assertEquals(corner, StationLayout.Room.decode("1_0", "tunnel_corner@1"));
        var legacy = StationLayout.Room.decode("0_0", "ARRIVAL");
        assertEquals("arrival", legacy.piece(), "Old saves store a role name");
        assertEquals(0, legacy.rotation());
        assertEquals("tunnel_straight", new StationLayout.Room("2_0", "tunnel_straight", 4, 2, 0).encode(), "Four turns is no turn");
    }

    @Test
    void piecesArePlacedByDoorway()
    {
        var arrival = new StationLayout.Room("0_0", StationLayout.Role.ARRIVAL, 0, 0);
        var existing = List.of(arrival);
        var straight = StationLayout.place(existing, "tunnel_straight", 1, 0, null);
        assertTrue(straight.doors().contains(Direction.WEST), "An east-west tunnel is chosen to meet the room: " + straight.doors());
        assertTrue(StationLayout.joined(arrival, straight));
        assertThrows(IllegalArgumentException.class, () -> StationLayout.place(existing, "tunnel_straight", 1, 0, 0), "A north-south tunnel cannot meet a room to its west");
        assertThrows(IllegalArgumentException.class, () -> StationLayout.place(existing, "tunnel_straight", 2, 0, null), "Tiles must adjoin the station");
        assertThrows(IllegalArgumentException.class, () -> StationLayout.place(existing, "dome_garden", 0, 0, null), "Occupied tiles are refused");
        assertThrows(IllegalArgumentException.class, () -> StationLayout.place(List.of(), "workshop", 1, 1, null), "The first tile is 0,0");
        var withTunnel = List.of(arrival, straight);
        var dome = StationLayout.place(withTunnel, "dome_observation", 2, 0, null);
        assertTrue(StationLayout.joined(straight, dome), "A dome continues the tunnel");
        var blocked = List.of(arrival, StationLayout.place(existing, "tunnel_corner", 1, 0, null));
        var cornerDoors = blocked.get(1).doors();
        assertTrue(cornerDoors.contains(Direction.WEST), "The corner turns to meet the room: " + cornerDoors);
    }

    @Test
    void jointsNeedDoorwaysOnBothSides()
    {
        var arrival = new StationLayout.Room("a", StationLayout.Role.ARRIVAL, 0, 0);
        var northSouth = new StationLayout.Room("t", "tunnel_straight", 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> new StationLayout.Layout(List.of(arrival, northSouth), List.of(new StationLayout.Joint("a", "t"))));
        var eastWest = new StationLayout.Room("t", "tunnel_straight", 1, 1, 0);
        assertEquals(1, new StationLayout.Layout(List.of(arrival, eastWest), List.of(new StationLayout.Joint("a", "t"))).joints().size());
    }
}
