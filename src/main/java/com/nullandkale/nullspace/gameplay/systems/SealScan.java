package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.model.air.Atmosphere;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Finds which cells of a vessel's grid seal, a chunk column at a time, so that the work can be spread over many ticks. A section with only air is skipped,
 * and the blocks of the others are read from the section itself, whose block states are looked up once each. Only a door goes through
 * {@link HullScan#seals}, since whether it seals depends on its neighbours and its docking mate.
 *
 * <p>An ordinary vessel runs the whole scan at once. A large one that is being built (a natural hab) scans as its last blocks go in, a few milliseconds
 * a tick, and hands the result to {@link AirSystem} through {@link #keep}, so that registering it does not stop the server for the seconds a pass over
 * millions of cells takes. The doors are looked at again when the result is taken, since the build links them after it has been scanned.
 */
public final class SealScan
{
    private static final Map<String, SealScan> READY = new ConcurrentHashMap<>();

    private final ServerLevel level;
    private final BlockPos base;
    private final Atmosphere.Grid grid;
    private final HullScan.Bounds bounds;
    private final boolean[] seals;
    private final Map<BlockState, Boolean> solid = new HashMap<>();
    private final LongArrayList doors = new LongArrayList();
    private final int x1, y1, z1, firstX, firstZ, columnsX, columns;
    private int next;
    private long nanos;

    public SealScan(ServerLevel level, BlockPos base, Atmosphere.Grid grid, HullScan.Bounds bounds)
    {
        this.level = level;
        this.base = base;
        this.grid = grid;
        this.bounds = bounds;
        seals = new boolean[grid.cells()];
        x1 = base.getX() + grid.width() - 1;
        y1 = base.getY() + grid.height() - 1;
        z1 = base.getZ() + grid.depth() - 1;
        firstX = base.getX() >> 4;
        firstZ = base.getZ() >> 4;
        columnsX = (x1 >> 4) - firstX + 1;
        columns = columnsX * ((z1 >> 4) - firstZ + 1);
    }

    /** Scans columns until about {@code budgetNanos} have gone, and says whether the whole grid has been scanned. */
    public boolean step(long budgetNanos)
    {
        long started = System.nanoTime();
        while (next < columns)
        {
            column(firstX + next % columnsX, firstZ + next / columnsX);
            next++;
            if (System.nanoTime() - started >= budgetNanos)
                break;
        }
        nanos += System.nanoTime() - started;
        return next >= columns;
    }

    public boolean done()
    {
        return next >= columns;
    }

    /** The time spent scanning so far, over however many ticks. */
    public long nanos()
    {
        return nanos;
    }

    boolean[] seals()
    {
        return seals;
    }

    boolean matches(BlockPos base, Atmosphere.Grid grid)
    {
        return done() && this.base.equals(base) && this.grid.equals(grid);
    }

    /** The seals with the doors looked at again, as they are now. */
    boolean[] settled()
    {
        var at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < doors.size(); i++)
        {
            at.set(doors.getLong(i));
            seals[grid.index(at.getX() - base.getX(), at.getY() - base.getY(), at.getZ() - base.getZ())] = HullScan.seals(level, at);
        }
        return seals;
    }

    /** Leaves this finished scan for the vessel {@code id}, whose registration takes it instead of scanning. */
    public void keep(String id)
    {
        if (!done())
            throw new IllegalStateException("The seal scan is not finished");
        READY.put(id, this);
    }

    /** Drops what was kept for {@code id}, if registration did not take it. */
    public static void discard(String id)
    {
        READY.remove(id);
    }

    static SealScan take(String id)
    {
        return READY.remove(id);
    }

    private void column(int cx, int cz)
    {
        var chunk = level.getChunk(cx, cz);
        var at = new BlockPos.MutableBlockPos();
        for (int sy = base.getY() >> 4; sy <= y1 >> 4; sy++)
        {
            int index = chunk.getSectionIndex(sy << 4);
            if (index < 0 || index >= chunk.getSectionsCount())
                continue;
            var section = chunk.getSection(index);
            if (section.hasOnlyAir())
                continue;
            for (int y = Math.max(base.getY(), sy << 4); y <= Math.min(y1, (sy << 4) + 15); y++)
                for (int z = Math.max(base.getZ(), cz << 4); z <= Math.min(z1, (cz << 4) + 15); z++)
                    for (int x = Math.max(base.getX(), cx << 4); x <= Math.min(x1, (cx << 4) + 15); x++)
                    {
                        var state = section.getBlockState(x & 15, y & 15, z & 15);
                        if (state.isAir())
                            continue;
                        at.set(x, y, z);
                        if (!bounds.contains(at))
                            continue;
                        boolean sealed;
                        if (ShipDoors.isDoor(state) || state.getBlock() instanceof DoorBlock)
                        {
                            sealed = HullScan.seals(level, at);
                            doors.add(at.asLong());
                        }
                        else
                        {
                            Boolean known = solid.get(state);
                            if (known == null)
                            {
                                known = state.isCollisionShapeFullBlock(level, at.immutable()) && !(state.getBlock() instanceof LeavesBlock);
                                solid.put(state, known);
                            }
                            sealed = known;
                        }
                        seals[grid.index(x - base.getX(), y - base.getY(), z - base.getZ())] = sealed;
                    }
        }
    }
}
