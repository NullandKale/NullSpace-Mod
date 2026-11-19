package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

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
                // A hab that was being built has been scanned already, a few milliseconds a tick (SealScan); only its doors are looked at again.
                var ready = SealScan.take(ship.getStringOr("id", ""));
                r.seals = ready != null && ready.matches(base, grid) ? ready.settled() : scan(level, ship, grid, base, r);
                AirStore.writeSeals(s, r.seals);
            }
            seals = r.seals;
        }
        else
        {
            seals = scan(level, ship, grid, base, r);
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

    /** Finds which cells of the grid seal, all at once on the calling thread: what an ordinary vessel does, and a large one that was not scanned as it was built. */
    private static boolean[] scan(ServerLevel level, CompoundTag ship, Atmosphere.Grid grid, BlockPos base, ShipRuntime r)
    {
        r.sealScans++;
        var scan = new SealScan(level, base, grid, WorldStore.bounds(ship));
        scan.step(Long.MAX_VALUE);
        return scan.seals();
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
