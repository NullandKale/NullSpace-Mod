package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

/**
 * One tile of a station, generated: a sixteen-block square of two stories joined by stairs, with
 * windows, lights and vents. The tile's walls are its own shell (no hull layer), so tiles sit edge
 * to edge. Each side has two doorway slots, three blocks in from either corner; a slot is solid
 * wall until a neighbour is joined, when the station builder cuts the door. Pieces are built
 * unturned; the station turns them.
 */
public final class StationGenerator
{
    /** Sixteen square; stories six blocks tall with a one-block slab between; a solid base under the lower floor (so a garden can hold water). */
    public static final int TILE = 16, STORY = 6, DECK = STORY + 1, BASE = 1, HEIGHT = BASE + 2 * DECK + 1;
    /** Where the doorway slots sit along a side, in tile coordinates. */
    public static final int[] SLOTS = {3, 12};
    /** The rows a door stands at: the first row of each story. */
    public static final int[] DOOR_ROWS = {BASE + 1, BASE + DECK + 1};

    /** What a tile is for: the four furnished rooms, a promenade (the tunnel pieces) and two glass domes. */
    public enum Kit
    {
        ENTRANCE, WORKSHOP, HABITATION, POWER, PROMENADE, GARDEN, OBSERVATORY;

        public boolean dome()
        {
            return this == GARDEN || this == OBSERVATORY;
        }
    }

    /** The walls cell of doorway {@code slot} (0 or 1) on {@code side}, at floor level {@code row}. */
    public static BlockPos doorway(Direction side, int slot, int row)
    {
        int along = SLOTS[slot];
        return switch (side)
        {
            case NORTH -> new BlockPos(along, row, 0);
            case SOUTH -> new BlockPos(along, row, TILE - 1);
            case WEST -> new BlockPos(0, row, along);
            default -> new BlockPos(TILE - 1, row, along);
        };
    }

    /** True for a wall cell in the band where a door may be cut: either side of a doorway slot, from the floor to just over the lintel. */
    public static boolean doorBand(BlockPos wall)
    {
        boolean xEdge = wall.getX() == 0 || wall.getX() == TILE - 1, zEdge = wall.getZ() == 0 || wall.getZ() == TILE - 1;
        if (!xEdge && !zEdge)
            return false;
        int along = xEdge ? wall.getZ() : wall.getX();
        boolean slot = false;
        for (int s : SLOTS)
            slot |= Math.abs(along - s) <= 1;
        int y = wall.getY();
        boolean rows = false;
        for (int row : DOOR_ROWS)
            rows |= y >= row && y <= row + 3;
        return slot && rows;
    }

    /**
     * A tile of {@code kit}, unturned, with doorway slots on {@code sides} and {@code windows} (1 a
     * few, 2 many). Builds the same tile every time.
     */
    public static ShipGenerator.Ship tile(Kit kit, Set<Direction> sides, int windows, Spec.Maker maker)
    {
        var layout = new Layout();
        layout.station = kit;
        layout.cx = TILE / 2;
        layout.hullH = 0;
        layout.hullV = 0;
        // A dome is always roofed in glass: with few windows its walls are mostly solid, with many it is glass all round.
        int glass = kit.dome() ? (windows == 1 ? 2 : 3) : windows;
        for (int story = 0; story < 2; story++)
        {
            int floorY = BASE + story * DECK;
            layout.add(Space.box("hall" + story, kit == Kit.POWER ? RoomUse.ENGINEERING : RoomUse.LOUNGE, story, floorY, STORY, 1, TILE - 2, 1, TILE - 2, Direction.NORTH, glass));
            for (var side : sides)
                for (int slot = 0; slot < SLOTS.length; slot++)
                    layout.reserved.add(new Link(doorway(side, slot, floorY + 1), side, 1, 3));
        }
        // The whole tile is a solid block with the halls cut out of it: walls, floors, ceiling and the rim.
        layout.solids.add(new Layout.Box(0, TILE - 1, 0, HEIGHT - 1, 0, TILE - 1));
        // A flight up along the south wall and another along the north, both climbing east, each with a landing in front of the first step. The slab is opened over the last steps, where a person's head would meet it.
        for (int z : new int[]{TILE - 2, 1})
        {
            var foot = new BlockPos(5, BASE + 1, z);
            layout.flights.add(new Layout.Flight(foot, Direction.EAST, DECK));
            for (int k = 3; k < DECK; k++)
                layout.extra.add(new BlockPos(foot.getX() + k, BASE + DECK, z));
        }
        // Where reachability is rooted: the landing, which is always clear.
        layout.dockWall = new BlockPos(4, BASE + 1, TILE - 2).north();
        var spec = new Spec(maker, Spec.Shape.SPINE, Spec.Tier.MEDIUM, Spec.Role.SCOUT, 0);
        return ShipGenerator.assemble(spec, layout, false);
    }

    private StationGenerator()
    {
    }
}
