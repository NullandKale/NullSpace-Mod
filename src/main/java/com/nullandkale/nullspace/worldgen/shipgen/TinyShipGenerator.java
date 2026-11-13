package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

import static com.nullandkale.nullspace.worldgen.shipgen.Kind.*;

/** Two actual compartments; maker treatments share a compact, editable survival layout. */
public final class TinyShipGenerator
{
    public static ShipGenerator.Ship build(Spec.Maker maker, long seed)
    {
        var grid = new Grid();
        var interior = new HashSet<BlockPos>();
        // Nine wide, fourteen long, plus the one-block boarding step at the nose.
        for (int x = 0; x < 9; x++)
            for (int z = 1; z < 15; z++)
                for (int y = 0; y < 5; y++)
                {
                    var at = new BlockPos(x, y, z);
                    boolean room = x > 0 && x < 8 && z > 1 && z < 14 && z != 8 && y > 0 && y < 4;
                    if (room)
                        interior.add(at);
                    else
                        grid.set(at, y == 0 ? FLOOR : y == 4 ? CEILING : z == 8 ? TRIM : WALL);
                }
        // Bevel the four vertical corner edges without opening a face of either cabin.
        for (int x : new int[]{0, 8})
            for (int z : new int[]{1, 14})
                for (int y = 1; y <= 4; y++)
                    grid.clear(new BlockPos(x, y, z));
        for (int x : new int[]{0, 8})
            for (int z = 2; z <= 13; z++)
            {
                grid.set(new BlockPos(x, 1, z), ACCENT);
                if (z == 3 || z == 6 || maker != Spec.Maker.BASTION && z == 4)
                    grid.set(new BlockPos(x, 2, z), GLASS);
            }
        for (int x : new int[]{1, 2, 6, 7})
            grid.set(new BlockPos(x, 2, 1), GLASS);
        if (maker == Spec.Maker.HALCYON)
            for (int x = 2; x <= 6; x++)
                for (int z = 3; z <= 5; z++)
                    grid.set(new BlockPos(x, 4, z), GLASS);
        door(grid, 1, Direction.NORTH);
        door(grid, 8, Direction.SOUTH);
        interior.add(new BlockPos(4, 1, 8));
        interior.add(new BlockPos(4, 2, 8));
        grid.set(new BlockPos(4, 0, 1), DOCK_BASE, Direction.NORTH);
        grid.set(new BlockPos(4, 0, 0), STAIR, Direction.SOUTH);
        grid.set(new BlockPos(4, 3, 0), CAMERA, Direction.NORTH);

        grid.set(new BlockPos(7, 1, 3), COMPUTER, Direction.WEST);
        grid.set(new BlockPos(1, 1, 2), SCANNER, Direction.EAST);
        grid.set(new BlockPos(7, 1, 2), TERMINAL, Direction.WEST);
        grid.set(new BlockPos(7, 2, 2), MONITOR, Direction.WEST);
        grid.set(new BlockPos(1, 1, 4), BED, Direction.NORTH);
        grid.set(new BlockPos(1, 1, 5), BED_FOOT, Direction.NORTH);
        grid.set(new BlockPos(1, 1, 6), CRAFTING_TABLE);
        grid.set(new BlockPos(7, 1, 6), FURNACE, Direction.WEST);
        grid.set(new BlockPos(7, 1, 7), BARREL, Direction.WEST);
        // Each closed compartment has its own outlet and ordinary room-to-room airflow still works.
        grid.set(new BlockPos(8, 1, 5), AIR_SUPPLY, Direction.WEST);
        grid.set(new BlockPos(8, 1, 12), AIR_SUPPLY, Direction.WEST);
        for (int x : new int[]{1, 7})
        {
            grid.set(new BlockPos(x, 1, 10), GENERATOR, x == 1 ? Direction.EAST : Direction.WEST);
            grid.set(new BlockPos(x, 2, 10), BATTERY, x == 1 ? Direction.EAST : Direction.WEST);
        }
        grid.set(new BlockPos(1, 1, 13), BARREL, Direction.EAST);
        for (int x : new int[]{2, 6})
            grid.set(new BlockPos(x, 1, 14), ENGINE, Direction.SOUTH);
        for (int z : new int[]{4, 6, 10, 12})
            grid.set(new BlockPos(4, 4, z), LIGHT);

        var rooms = List.of(new ShipGenerator.Landmark("Bridge", RoomUse.BRIDGE, new BlockPos(4, 1, 3), new BlockPos(7, 1, 7)), new ShipGenerator.Landmark("Engineering", RoomUse.ENGINEERING, new BlockPos(4, 1, 10), new BlockPos(1, 1, 13)));
        return new ShipGenerator.Ship(new Spec(maker, Spec.Shape.SPINE, Spec.Tier.SMALL, Spec.Role.SCOUT, seed), Map.copyOf(grid.cells()), Set.copyOf(interior), 9, 5, 15, 1, new BlockPos(7, 1, 3), rooms, new BlockPos(4, 1, 1));
    }

    private static void door(Grid grid, int z, Direction facing)
    {
        for (int x = 3; x <= 5; x++)
            for (int y = 1; y <= 3; y++)
                grid.set(new BlockPos(x, y, z), x == 4 && y < 3 ? y == 1 ? DOOR_LOWER : DOOR_UPPER : TRIM, facing);
    }

    private TinyShipGenerator()
    {
    }
}
