package com.nullandkale.nullspace.worldgen.blueprint;

import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.*;

import net.minecraft.core.BlockPos;

/** Local room landmarks shared by encounter supplies and walking checks. */
public final class ShipRooms
{
    public enum Use
    {
        BRIDGE, ENGINEERING, CARGO, WORKSHOP, GALLEY, CREW, LAB, INFIRMARY
    }

    /** {@code walk} is a spot to stand in the room; {@code supplies} its container, if any. */
    public record Room(String name, Use use, BlockPos walk, BlockPos supplies)
    {}

    /** A shipyard design's room landmarks, from catalog/ships.json. */
    public static List<Room> rooms(String id)
    {
        return ShipBlueprints.rooms(id);
    }

    private ShipRooms()
    {
    }
}
