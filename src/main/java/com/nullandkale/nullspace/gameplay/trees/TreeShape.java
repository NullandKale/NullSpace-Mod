package com.nullandkale.nullspace.gameplay.trees;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a golden apple tree, as offsets from the cell its trunk core stands in: a trunk six high and a rounded crown of leaves over its
 * top, with eight places an apple can hang. It is the same shape whether the tree grew from a sapling or was built with a hab's grove, so
 * it is plain numbers here and the blocks are put on it in {@link GoldenTrees}.
 */
public final class TreeShape
{
    /** Cells of trunk above the ground: the core is the first, and the oak logs above it make six in all. */
    public static final int TRUNK = 6;

    /** One cell of the tree: its offset from the core (up is {@code dy}). */
    public record Cell(int dx, int dy, int dz)
    {}

    /** The crown: three rows five across with the corners cut, a row of three and a cap shaped like a plus. The trunk runs up through its middle. */
    public static List<Cell> crown()
    {
        var cells = new ArrayList<Cell>();
        for (int dy = 4; dy <= 8; dy++)
        {
            int radius = dy <= 6 ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++)
                {
                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                    boolean trunk = dx == 0 && dz == 0 && dy < TRUNK;
                    boolean cap = dy == 8 && dx != 0 && dz != 0;
                    if (!(corner && radius == 2) && !trunk && !cap)
                        cells.add(new Cell(dx, dy, dz));
                }
        }
        return List.copyOf(cells);
    }

    /** The cells above the core that hold the trunk's logs. */
    public static List<Cell> logs()
    {
        var cells = new ArrayList<Cell>();
        for (int dy = 1; dy < TRUNK; dy++)
            cells.add(new Cell(0, dy, 0));
        return List.copyOf(cells);
    }

    /**
     * The eight cells apples hang in, in the order they fill: each is the open cell directly under a leaf of the crown's lowest row, so an apple
     * hangs from the bottom of a leaf. First the four on the cross, then the four between.
     */
    public static List<Cell> fruit()
    {
        return List.of(new Cell(2, 3, 0), new Cell(-2, 3, 0), new Cell(0, 3, 2), new Cell(0, 3, -2), new Cell(1, 3, 1), new Cell(-1, 3, -1), new Cell(1, 3, -1), new Cell(-1, 3, 1));
    }

    private TreeShape()
    {
    }
}
