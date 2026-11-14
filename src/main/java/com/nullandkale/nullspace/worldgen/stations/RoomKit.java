package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.shipgen.ShipBlocks;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.*;

import java.util.*;
import java.util.random.RandomGenerator;

import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/**
 * Everything a room design needs to furnish one room. A room is laid out in its own frame: {@code u}
 * across (to the right when walking in), {@code v} deeper from the door, and a row above the floor.
 * Blocks go into the plan's block map, and the entities a room shows (item frames, armor stands,
 * paintings, animals) are recorded as marked points for a spawn-once pass. The cells in front of the
 * door stay clear, and anything already placed is never overwritten.
 */
final class RoomKit
{
    /** The four places furniture stands against a wall; {@code DOOR} is the wall the door is in. */
    enum Side
    {
        LEFT, RIGHT, BACK, DOOR
    }

    /** A block that may face somewhere: given the direction it should face, the state to place. */
    interface Spec
    {
        BlockState get(Direction face);
    }

    final StationStyle style;
    final ShipBlocks.Palette palette;
    final Map<BlockPos, BlockState> out;
    final List<Piece.Point> points;
    final Direction fwd, right, left, back;
    /** Interior size: across, deep and rows above the floor. */
    final int w, d, h;
    /** The column of the door, across. */
    final int doorU;
    final RandomGenerator rng;
    final int variant;
    final Block stairs, slab, fence, gate, planks, log;
    private final BlockPos origin;
    private final Set<BlockPos> used = new HashSet<>();
    /** Cells an entity (frame, stand, painting, animal) takes: no torch may go there. */
    final Set<BlockPos> entityCells = new HashSet<>();

    RoomKit(StationStyle style, ShipBlocks.Palette palette, Map<BlockPos, BlockState> out, List<Piece.Point> points, Room room, Door door, RandomGenerator rng, int variant)
    {
        this.style = style;
        this.palette = palette;
        this.out = out;
        this.points = points;
        this.rng = rng;
        this.variant = variant;
        this.fwd = door.a().equals(room.id()) ? door.through().getOpposite() : door.through();
        this.right = fwd.getClockWise();
        this.left = fwd.getCounterClockWise();
        this.back = fwd.getOpposite();
        var i = room.interior();
        this.h = Math.min(StationPlan.PITCH - 2, i.y1() - i.y0() + 1);
        boolean alongZ = fwd.getAxis() == Direction.Axis.Z;
        this.w = alongZ ? i.x1() - i.x0() + 1 : i.z1() - i.z0() + 1;
        this.d = alongZ ? i.z1() - i.z0() + 1 : i.x1() - i.x0() + 1;
        int x = right.getStepX() > 0 || fwd.getStepX() > 0 ? i.x0() : i.x1();
        int z = right.getStepZ() > 0 || fwd.getStepZ() > 0 ? i.z0() : i.z1();
        this.origin = new BlockPos(x, i.y0(), z);
        var inside = door.at().relative(fwd);
        this.doorU = (inside.getX() - x) * right.getStepX() + (inside.getZ() - z) * right.getStepZ();
        switch (style.maker())
        {
            case RIVET ->
            {
                stairs = Blocks.SPRUCE_STAIRS;
                slab = Blocks.SPRUCE_SLAB;
                fence = Blocks.SPRUCE_FENCE;
                gate = Blocks.SPRUCE_FENCE_GATE;
            }
            case HALCYON ->
            {
                stairs = Blocks.BIRCH_STAIRS;
                slab = Blocks.BIRCH_SLAB;
                fence = Blocks.BIRCH_FENCE;
                gate = Blocks.BIRCH_FENCE_GATE;
            }
            default ->
            {
                stairs = Blocks.DARK_OAK_STAIRS;
                slab = Blocks.DARK_OAK_SLAB;
                fence = Blocks.DARK_OAK_FENCE;
                gate = Blocks.DARK_OAK_FENCE_GATE;
            }
        }
        this.planks = style.wood;
        this.log = style.beam;
    }

    // ---- Geometry ----

    boolean big()
    {
        return w >= 11 && d >= 10;
    }

    BlockPos at(int u, int v, int row)
    {
        return origin.offset(right.getStepX() * u + fwd.getStepX() * v, row, right.getStepZ() * u + fwd.getStepZ() * v);
    }

    int mirror(int u)
    {
        return w - 1 - u;
    }

    /** True for the clear lane in front of the door. */
    boolean lane(int u, int v)
    {
        return Math.abs(u - doorU) <= 1 && v <= 1;
    }

    boolean inside(int u, int v, int row)
    {
        return u >= 0 && u < w && v >= 0 && v < d && row >= 0 && row < h;
    }

    boolean free(int u, int v, int row)
    {
        return inside(u, v, row) && !lane(u, v) && !used.contains(at(u, v, row));
    }

    /** The cell on a wall at position {@code a} along it, as {@code {u, v}}. */
    int[] wall(Side side, int a)
    {
        return switch (side)
        {
            case LEFT -> new int[]{0, a};
            case RIGHT -> new int[]{w - 1, a};
            case BACK -> new int[]{a, d - 1};
            case DOOR -> new int[]{a, 0};
        };
    }

    /** The direction something against {@code side} faces: into the room. */
    Direction face(Side side)
    {
        return switch (side)
        {
            case LEFT -> right;
            case RIGHT -> left;
            case BACK -> back;
            case DOOR -> fwd;
        };
    }

    /** How far a wall runs: cells along it. */
    int length(Side side)
    {
        return side == Side.LEFT || side == Side.RIGHT ? d : w;
    }

    // ---- Placing ----

    static BlockState face(Block block, Direction d)
    {
        var s = block.defaultBlockState();
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
        if (s.hasProperty(BlockStateProperties.FACING))
            return s.setValue(BlockStateProperties.FACING, d);
        return s;
    }

    static Spec of(Block block)
    {
        return f -> face(block, f);
    }

    static Spec plain(Block block)
    {
        return f -> block.defaultBlockState();
    }

    static Spec state(BlockState state)
    {
        return f -> state;
    }

    /** An anvil's long axis runs across the way it faces. */
    static Spec anvil()
    {
        return f -> Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, f.getClockWise());
    }

    /** A stair used as a seat against a wall: its back to the wall, facing into the room. */
    Spec seatAgainst()
    {
        return f -> stairs.defaultBlockState().setValue(StairBlock.FACING, f.getOpposite());
    }

    BlockState slabTop()
    {
        return slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
    }

    BlockState slabBottom()
    {
        return slab.defaultBlockState();
    }

    boolean put(int u, int v, int row, BlockState state)
    {
        if (!free(u, v, row))
            return false;
        var at = at(u, v, row);
        out.put(at, state);
        used.add(at);
        return true;
    }

    boolean put(int u, int v, int row, Spec spec, Direction face)
    {
        return free(u, v, row) && put(u, v, row, spec.get(face));
    }

    /** Replaces the floor itself at a cell (accent stripes, soil, grass). */
    void floor(int u, int v, BlockState state)
    {
        if (u >= 0 && u < w && v >= 0 && v < d)
            out.put(at(u, v, -1), state);
    }

    void floor(int u, int v, Block block)
    {
        floor(u, v, block.defaultBlockState());
    }

    /** Reserves a cell without placing a block, for entities and open space that must stay clear. */
    void keep(int u, int v, int row)
    {
        if (inside(u, v, row))
            used.add(at(u, v, row));
    }

    /** A stack of blocks from the floor up in one column, all facing {@code face}. */
    void column(int u, int v, Direction face, Spec... rows)
    {
        for (int r = 0; r < rows.length; r++)
            if (rows[r] != null)
                put(u, v, r, rows[r], face);
    }

    /** Fixtures in a run against a wall, from {@code from} to {@code to} along it: one spec per row above the floor. */
    void run(Side side, int from, int to, Spec... rows)
    {
        for (int a = Math.min(from, to); a <= Math.max(from, to); a++)
        {
            var c = wall(side, a);
            column(c[0], c[1], face(side), rows);
        }
    }

    /** As {@link #run}, but every {@code step}th cell only. */
    void bank(Side side, int from, int to, int step, Spec... rows)
    {
        for (int a = Math.min(from, to); a <= Math.max(from, to); a += step)
        {
            var c = wall(side, a);
            column(c[0], c[1], face(side), rows);
        }
    }

    /** A block set into every cell of a box (u0..u1 by v0..v1) at one row. */
    void fill(int u0, int v0, int u1, int v1, int row, BlockState state)
    {
        for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++)
            for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++)
                put(u, v, row, state);
    }

    /** A table: slab tops over a box, with a decoration on one cell. */
    void table(int u0, int v0, int uLen, int vLen, BlockState decor)
    {
        for (int u = u0; u < u0 + uLen; u++)
            for (int v = v0; v < v0 + vLen; v++)
                put(u, v, 0, slabTop());
        if (decor != null)
            put(u0 + uLen / 2, v0 + vLen / 2, 1, decor);
    }

    /** A seat at a cell for someone looking in {@code view}: the stair's back is behind them. */
    void chair(int u, int v, Direction view)
    {
        put(u, v, 0, stairs.defaultBlockState().setValue(StairBlock.FACING, view.getOpposite()));
    }

    /** A chair at each end and along the long sides of a table, all facing it. */
    void seats(int u0, int v0, int uLen, int vLen)
    {
        for (int u = u0; u < u0 + uLen; u++)
        {
            chair(u, v0 - 1, fwd);
            chair(u, v0 + vLen, back);
        }
        for (int v = v0; v < v0 + vLen; v++)
        {
            chair(u0 - 1, v, right);
            chair(u0 + uLen, v, left);
        }
    }

    BlockState lantern()
    {
        return Blocks.LANTERN.defaultBlockState();
    }

    /** The room's air supply, in a back corner, never in the way. */
    void air()
    {
        int[][] corners = {{0, d - 1}, {w - 1, d - 1}, {0, 2}, {w - 1, 2}};
        for (var c : corners)
            if (free(c[0], c[1], 0))
            {
                put(c[0], c[1], 0, ModBlocks.AIR_SUPPLY.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, c[0] == 0 ? right : left));
                // It breathes out into the cell it faces: nothing else may stand there.
                keep(c[0] == 0 ? 1 : w - 2, c[1], 0);
                return;
            }
    }

    // ---- Things that are entities, written as points ----

    private void point(int u, int v, int row, String name, String argument)
    {
        points.add(new Piece.Point(at(u, v, row), name, argument));
    }

    /**
     * An item frame on a wall holding {@code item}: a single handy thing hung where it would be reached
     * for. {@code row} is the row it should hang at (1, the second block, is eye height); the next free
     * row above is used when something already stands there.
     */
    void frame(Side side, int a, int row, String item)
    {
        var c = wall(side, a);
        for (int r = row; r <= row + 2 && r < h; r++)
            if (free(c[0], c[1], r))
            {
                used.add(at(c[0], c[1], r));
                entityCells.add(at(c[0], c[1], r));
                point(c[0], c[1], r, "frame", item + " " + face(side).getName());
                return;
            }
    }

    /** A painting on a wall, {@code pw} by {@code ph} blocks, its lowest-left cell at {@code a}, at {@code row} or the first free row above. */
    void painting(Side side, int a, int row, int pw, int ph)
    {
        for (int r = row; r + ph <= h && r <= row + 2; r++)
        {
            boolean clear = true;
            for (int i = 0; i < pw && clear; i++)
                for (int j = 0; j < ph && clear; j++)
                {
                    var c = wall(side, a + i);
                    clear = free(c[0], c[1], r + j);
                }
            if (!clear)
                continue;
            for (int i = 0; i < pw; i++)
                for (int j = 0; j < ph; j++)
                {
                    var c = wall(side, a + i);
                    used.add(at(c[0], c[1], r + j));
                    entityCells.add(at(c[0], c[1], r + j));
                }
            var c = wall(side, a);
            point(c[0], c[1], r, "painting", face(side).getName() + " " + pw + " " + ph);
            return;
        }
    }

    /** An armor stand wearing a space suit ({@code plain} or {@code armored}) at a cell, facing {@code view}. */
    void stand(int u, int v, Direction view, String suit)
    {
        if (!free(u, v, 0) || !free(u, v, 1))
            return;
        for (int r = 0; r < 2; r++)
        {
            used.add(at(u, v, r));
            entityCells.add(at(u, v, r));
        }
        point(u, v, 0, "stand", view.getName() + " " + suit);
    }

    /** A creature that lives here, spawned once on first visit. */
    void animal(int u, int v, String entity)
    {
        entityCells.add(at(u, v, 0));
        entityCells.add(at(u, v, 1));
        point(u, v, 0, "animal", entity);
    }

    // ---- Floors ----

    /** The whole floor in one block. */
    void floorFill(Block block)
    {
        for (int u = 0; u < w; u++)
            for (int v = 0; v < d; v++)
                floor(u, v, block);
    }

    /** The floor in a check of two blocks. */
    void floorChecker(Block a, Block b)
    {
        for (int u = 0; u < w; u++)
            for (int v = 0; v < d; v++)
                floor(u, v, (u + v) % 2 == 0 ? a : b);
    }

    /** A border of one block round the edge of the floor. */
    void floorBorder(Block block)
    {
        for (int u = 0; u < w; u++)
            for (int v = 0; v < d; v++)
                if (u == 0 || v == 0 || u == w - 1 || v == d - 1)
                    floor(u, v, block);
    }

    /** A stripe of one block down the floor at column {@code u}, from the door to {@code toV}. */
    void floorStripe(int u, int toV, Block block)
    {
        for (int v = 0; v <= toV && v < d; v++)
            floor(u, v, block);
    }

    // ---- Furniture that has to be walked round ----

    /** True when every cell in the box is inside the room at the floor row and nothing stands there. */
    private boolean clearBox(int u0, int v0, int u1, int v1)
    {
        for (int u = u0; u <= u1; u++)
            for (int v = v0; v <= v1; v++)
                if (u < 0 || u >= w || v < 0 || v >= d || used.contains(at(u, v, 0)))
                    return false;
        return true;
    }

    /**
     * A table, with seats along its long sides when {@code seats}, in the place nearest the middle of
     * the room that leaves a free cell all the way round it (and its seats). Tries the largest table up
     * to {@code maxU} by {@code maxV} first and smaller ones after; when none leaves a walkway round
     * it, there is no table. Returns whether one was placed.
     */
    boolean tableSet(int maxU, int maxV, boolean seats, BlockState decor)
    {
        double cu = (w - 1) / 2.0, cv = (d - 1) / 2.0;
        for (int area = maxU * maxV; area >= 1; area--)
            for (int uLen = maxU; uLen >= 1; uLen--)
            {
                if (area % uLen != 0 || area / uLen > maxV)
                    continue;
                int vLen = area / uLen;
                boolean longU = uLen >= vLen;
                int seatU = longU || !seats ? 0 : 1, seatV = longU && seats ? 1 : 0;
                int[] best = null;
                double bestDistance = Double.MAX_VALUE;
                for (int u0 = 0; u0 < w; u0++)
                    for (int v0 = 0; v0 < d; v0++)
                    {
                        if (!clearBox(u0 - seatU - 1, v0 - seatV - 1, u0 + uLen + seatU, v0 + vLen + seatV))
                            continue;
                        boolean placeable = true;
                        for (int u = u0; u < u0 + uLen; u++)
                            for (int v = v0; v < v0 + vLen; v++)
                                placeable &= free(u, v, 0);
                        if (!placeable)
                            continue;
                        double distance = Math.abs(u0 + (uLen - 1) / 2.0 - cu) * 2 + Math.abs(v0 + (vLen - 1) / 2.0 - cv);
                        if (distance < bestDistance)
                        {
                            bestDistance = distance;
                            best = new int[]{u0, v0};
                        }
                    }
                if (best == null)
                    continue;
                table(best[0], best[1], uLen, vLen, decor);
                if (seats)
                    for (int i = 0; i < (longU ? uLen : vLen); i++)
                        if (longU)
                        {
                            chair(best[0] + i, best[1] - 1, fwd);
                            chair(best[0] + i, best[1] + vLen, back);
                        }
                        else
                        {
                            chair(best[0] - 1, best[1] + i, right);
                            chair(best[0] + uLen, best[1] + i, left);
                        }
                return true;
            }
        return false;
    }

    /** A double chest on two cells, the second one step toward {@code toward} (a local direction), both facing {@code face}. */
    boolean doubleChest(int u, int v, Direction toward, Direction face)
    {
        int du = toward == right ? 1 : toward == left ? -1 : 0, dv = toward == fwd ? 1 : toward == back ? -1 : 0;
        if (!free(u, v, 0) || !free(u + du, v + dv, 0))
            return false;
        var a = at(u, v, 0);
        var b = at(u + du, v + dv, 0);
        boolean aRight = b.equals(a.relative(face.getCounterClockWise()));
        var base = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face);
        put(u, v, 0, base.setValue(ChestBlock.TYPE, aRight ? ChestType.RIGHT : ChestType.LEFT));
        put(u + du, v + dv, 0, base.setValue(ChestBlock.TYPE, aRight ? ChestType.LEFT : ChestType.RIGHT));
        return true;
    }

    /**
     * Chests along a wall from {@code from} to {@code to}: double chests where two cells fit and a
     * single one for the odd cell, all on the floor in a row as a player would place them.
     */
    void chestRun(Side side, int from, int to)
    {
        var along = side == Side.LEFT || side == Side.RIGHT ? fwd : right;
        int a = Math.min(from, to);
        while (a <= Math.max(from, to))
        {
            var c = wall(side, a);
            if (a < Math.max(from, to) && doubleChest(c[0], c[1], along, face(side)))
                a += 2;
            else
            {
                put(c[0], c[1], 0, Fx.CHEST, face(side));
                a++;
            }
        }
    }
}
