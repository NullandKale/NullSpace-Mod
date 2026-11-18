package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipDoors;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

final class AirSystem
{
    static void rebuild(ServerLevel level, CompoundTag ship, ShipRuntime r, boolean initialize)
    {
        var s = state(ship);
        var grid = grid(ship);
        var base = WorldStore.pos(ship, "base");
        if (r.air == null)
        {
            r.air = AirStore.read(s, grid).orElseGet(() ->
            {
                int[] a = new int[grid.cells()];
                Arrays.fill(a, -1);
                return a;
            });
            if (r.air.length != grid.cells())
                throw new IllegalStateException("Invalid saved atmosphere");
        }
        boolean[] seals;
        if (AirStore.large(grid))
        {
            // A large vessel keeps its seals, saved with it and changed a cell at a time (reseal): the world is read for them once, and its chunks need not be
            // loaded for the air to be worked out again.
            if (r.seals == null)
                r.seals = AirStore.readSeals(s, grid).orElse(null);
            if (r.seals == null)
            {
                r.seals = new boolean[grid.cells()];
                scan(level, ship, grid, base, r.seals);
                AirStore.writeSeals(s, r.seals);
            }
            seals = r.seals;
        }
        else
        {
            seals = new boolean[grid.cells()];
            scan(level, ship, grid, base, seals);
        }
        r.seals = seals;
        long flood = System.nanoTime();
        r.atmosphere = Atmosphere.rebuild(seals, r.air, initialize, grid);
        if (System.nanoTime() - flood > 50_000_000L)
            com.nullandkale.nullspace.NullSpace.LOGGER.info("SYSTEMS air flood over {} cells took {} ms", grid.cells(), (System.nanoTime() - flood) / 1_000_000);
        r.rooms = r.atmosphere.rooms();
        if (r.atmosphere.vented() > 0)
            s.putLong("constructionVented", s.getLongOr("constructionVented", 0) + r.atmosphere.vented());
        AirStore.write(s, r.air, grid);
    }

    /**
     * Finds which cells of the grid seal, a chunk section at a time: a section with only air is skipped, and the blocks of the others are read from the
     * section itself, whose block states are looked up once each. Only a door goes through {@link HullScan#seals}, since whether it seals depends on its
     * neighbours and its docking mate. A buried hab is mostly rock, so this reads a few cells for each block a full lookup would.
     */
    private static void scan(ServerLevel level, CompoundTag ship, Atmosphere.Grid grid, BlockPos base, boolean[] seals)
    {
        var bounds = WorldStore.bounds(ship);
        var solid = new HashMap<BlockState, Boolean>();
        var at = new BlockPos.MutableBlockPos();
        int x1 = base.getX() + grid.width() - 1, y1 = base.getY() + grid.height() - 1, z1 = base.getZ() + grid.depth() - 1;
        for (int cx = base.getX() >> 4; cx <= x1 >> 4; cx++)
            for (int cz = base.getZ() >> 4; cz <= z1 >> 4; cz++)
            {
                var chunk = level.getChunk(cx, cz);
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
                                boolean sealed = ShipDoors.isDoor(state) || state.getBlock() instanceof DoorBlock ? HullScan.seals(level, at) : solid.computeIfAbsent(state, st -> st.isCollisionShapeFullBlock(level, at.immutable()) && !(st.getBlock() instanceof LeavesBlock));
                                seals[grid.index(x - base.getX(), y - base.getY(), z - base.getZ())] = sealed;
                            }
                }
            }
    }

    /** What the worker that works the air out again gives back: the rooms, and the air with the rooms' new cells filled in. */
    record Flooded(Atmosphere.Topology atmosphere, int[] air)
    {}

    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(task ->
    {
        var thread = new Thread(task, "nullspace-air");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /**
     * The air of a large vessel is worked out again off the server thread, since a pass over millions of cells takes much of a second: the seals and the air
     * are copied, the worker rebuilds the rooms from the copies, and the result is put in place on a later tick. Until then the air goes on as it was.
     */
    static void refresh(ServerLevel level, CompoundTag ship, ShipRuntime r, long now)
    {
        if (r.pending != null)
        {
            if (!r.pending.isDone())
                return;
            var done = r.pending;
            r.pending = null;
            try
            {
                var result = done.join();
                r.air = result.air();
                r.atmosphere = result.atmosphere();
                r.rooms = r.atmosphere.rooms();
                r.revision++;
            }
            catch (RuntimeException failure)
            {
                com.nullandkale.nullspace.NullSpace.LOGGER.error("The air of {} could not be worked out again", ship.getStringOr("home", ""), failure);
                r.airDirty = true;
            }
        }
        if (!r.airDirty || now - r.airAt < 40 || r.seals == null || r.air == null)
            return;
        r.airDirty = false;
        r.airAt = now;
        var grid = grid(ship);
        var seals = r.seals.clone();
        var air = r.air.clone();
        r.pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> new Flooded(Atmosphere.rebuild(seals, air, false, grid), air), WORKER);
    }

    /** Works out again the cells a changed block can change: its own and those round it, where a door's frame is. Only for a vessel that keeps its seals. */
    static boolean reseal(ServerLevel level, CompoundTag ship, ShipRuntime r, BlockPos changed)
    {
        boolean any = false;
        var grid = grid(ship);
        var base = WorldStore.pos(ship, "base");
        var bounds = WorldStore.bounds(ship);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -1; dz <= 1; dz++)
                {
                    var p = changed.offset(dx, dy, dz);
                    if (bounds.contains(p) && level.hasChunkAt(p))
                    {
                        int cell = grid.index(p.getX() - base.getX(), p.getY() - base.getY(), p.getZ() - base.getZ());
                        boolean sealed = HullScan.seals(level, p);
                        any |= r.seals[cell] != sealed;
                        r.seals[cell] = sealed;
                    }
                }
        return any;
    }

    static void tick(ServerLevel level, CompoundTag ship, ShipRuntime r)
    {
        var s = state(ship);
        if (level.getServer().getTickCount() % 5 == 0)
        {
            if (r.dirty)
                rebuild(level, ship, r, false);
            var supplies = new HashMap<Integer, Integer>();
            var outlets = new ArrayList<BlockPos>();
            for (var p : r.equipment)
                if (level.getBlockState(p).is(ModBlocks.AIR_SUPPLY) && active(r, p))
                {
                    var front = p.relative(level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING));
                    if (WorldStore.bounds(ship).contains(front))
                        outlets.add(front);
                }
            // The air bus is shared evenly: that gets the most air from it, since power past
            // normal output (4) buys only half as much (SpatialAir.output).
            int power = grant(r, PowerBus.System.AIR);
            for (int i = 0; i < outlets.size(); i++)
            {
                int share = power / outlets.size() + (i < power % outlets.size() ? 1 : 0);
                supplies.merge(local(ship, outlets.get(i)), SpatialAir.output(share), Integer::sum);
            }
            // A golden apple tree breathes out like an Air Supply at normal power, and needs no power: a hab with a tree has air of its own.
            for (var tree : r.trees)
            {
                var out = com.nullandkale.nullspace.gameplay.trees.GoldenTrees.outlet(level, tree);
                if (out != null && WorldStore.bounds(ship).contains(out))
                    supplies.merge(local(ship, out), com.nullandkale.nullspace.model.air.SpatialAir.FULL_OUTPUT, Integer::sum);
            }
            var occupants = new HashMap<Integer, Integer>();
            for (var p : level.players())
                if (WorldStore.bounds(ship).contains(BlockPos.containing(p.getEyePosition())))
                    // Breathing is a small, steady draw; leaks and supplies dominate the balance.
                    occupants.merge(local(ship, BlockPos.containing(p.getEyePosition())), 1, Integer::sum);
            boolean vacuum = PlanetSurfaces.vacuum(level);
            var flow = SpatialAir.step(r.air, r.atmosphere, r.seals, supplies, occupants, 5, vacuum ? 0 : Atmosphere.FULL);
            AirEffects.send(level, WorldStore.pos(ship, "base"), flow.flows(), grid(ship));
            AirStore.tick(s, r.air, grid(ship));
        }
    }
}
