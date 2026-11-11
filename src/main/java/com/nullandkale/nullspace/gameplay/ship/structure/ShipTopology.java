package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.docking.DockingTunnel;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/**
 * Equipment discovery works on damaged ships, independently of launch validation. Never loads
 * terrain.
 */
public final class ShipTopology
{
    // Carved hulls can remain joined at an edge or corner. Air gaps still split components.
    static final List<BlockPos> CONNECTIONS = BlockPos.betweenClosedStream(new BlockPos(-1, -1, -1), new BlockPos(1, 1, 1)).filter(p -> !p.equals(BlockPos.ZERO)).map(BlockPos::immutable).toList();

    public record Resolution(CompoundTag ship, String problem)
    {}

    public static CompoundTag resolve(ServerLevel level, BlockPos controller)
    {
        var result = find(level, controller);
        if (result.ship() == null)
            throw new IllegalArgumentException(result.problem());
        return result.ship();
    }

    public static Resolution find(ServerLevel level, BlockPos controller)
    {
        var store = WorldStore.get(level.getServer());
        var selected = level.dimension().equals(TravelService.SPACE) ? java.util.Optional.<PadRegistry.Pad>empty() : PadRegistry.below(level, controller);
        var existing = store.at(level, controller);
        if (selected.isPresent() && existing != null && !existing.getStringOr("homeId", "").equals(selected.get().id()))
        {
            var pad = selected.get();
            // A re-registered pad is not a new ship. Only a different stacked floor (or an
            // unidentifiable wreck) requires a fresh allocation.
            if (WorldStore.pos(existing, "base").getY() != pad.bounds().y() + 1 || !ModBlocks.navigation(level.getBlockState(WorldStore.pos(existing, "controller"))) && !ModBlocks.navigation(level.getBlockState(controller)))
                existing = null;
            else
            {
                var delta = WorldStore.pos(existing, "base").subtract(new BlockPos(pad.bounds().minX(), pad.bounds().y() + 1, pad.bounds().minZ()));
                ShipSystems.cancelTransit(level, existing);
                var reframed = existing;
                com.nullandkale.nullspace.gameplay.travel.RecordJournal.run(level.getServer(), "reframe-pad", () ->
                {
                    store.repository().mutate(new ShipRepository.ShipId(reframed.getStringOr("id", "")), ShipRepository.Mutation.CONFIGURATION, candidate ->
                    {
                        PadAllocation.resize(candidate, pad.bounds(), PadRegistry.buildHeight(level, pad.bounds()));
                        candidate.putString("homeId", pad.id());
                        candidate.putString("home", pad.name());
                    });
                    return null;
                });
                if (!delta.equals(BlockPos.ZERO))
                    ComputerService.rebase(level.getServer(), existing.getStringOr("id", ""), delta);
            }
        }
        if (existing != null)
        {
            if (ModBlocks.navigation(level.getBlockState(controller)) && !controller.equals(WorldStore.pos(existing, "controller")))
            {
                var connected = discover(level, existing);
                long helms = connected.stream().filter(p -> ModBlocks.navigation(level.getBlockState(p))).count();
                if (!connected.contains(controller) || helms != 1)
                    return new Resolution(null, "Resolve competing or disconnected helms before claiming this ship");
                var beforeController = store.snapshot();
                existing.putLong("controller", controller.asLong());
                store.requestSave();
                com.nullandkale.nullspace.gameplay.ship.ShipIntegrity.stamp(level, controller, existing.getStringOr("id", ""));
                com.nullandkale.nullspace.gameplay.travel.RecordJournal.commit(level.getServer(), "replace-controller", beforeController);
            }
            return new Resolution(existing, "");
        }
        for (var pad : selected.stream().toList())
        {
            var b = pad.bounds();
            if (!pad.dimension().equals(level.dimension().identifier().toString()) || controller.getX() < b.minX() || controller.getX() > b.maxX() || controller.getZ() < b.minZ() || controller.getZ() > b.maxZ() || controller.getY() <= b.y() || controller.getY() > b.y() + 32)
                continue;
            var probe = new CompoundTag();
            probe.putLong("base", new BlockPos(b.minX(), b.y() + 1, b.minZ()).asLong());
            probe.putLong("controller", controller.asLong());
            probe.putInt("width", b.width());
            probe.putInt("depth", b.depth());
            probe.putInt("height", PadRegistry.buildHeight(level, b));
            if (!ModBlocks.navigation(level.getBlockState(controller)))
            {
                var queue = new ArrayDeque<BlockPos>();
                var visited = new HashSet<BlockPos>();
                queue.add(controller);
                BlockPos found = null;
                while (!queue.isEmpty())
                {
                    var p = queue.removeFirst();
                    if (!WorldStore.bounds(probe).contains(p) || !visited.add(p) || !level.hasChunkAt(p) || level.getBlockState(p).isAir())
                        continue;
                    if (ModBlocks.navigation(level.getBlockState(p)))
                    {
                        if (found != null)
                            return new Resolution(null, "Multiple helms/computers in this build");
                        found = p;
                    }
                    for (var d : Direction.values())
                        queue.add(p.relative(d));
                }
                if (found == null)
                    return new Resolution(null, "Connect this controller to a helm or ship computer");
                controller = found;
            }
            var tag = new CompoundTag();
            tag.putString("id", UUID.randomUUID().toString());
            tag.putString("dimension", pad.dimension());
            tag.putLong("base", new BlockPos(b.minX(), b.y() + 1, b.minZ()).asLong());
            tag.putLong("controller", controller.asLong());
            tag.putString("homeId", pad.id());
            tag.putString("home", pad.name());
            tag.put("crew", new ListTag());
            tag.putInt("width", b.width());
            tag.putInt("depth", b.depth());
            tag.putInt("height", PadRegistry.buildHeight(level, b));
            var beforeRegistration = store.snapshot();
            store.ships().add(tag);
            store.requestSave();
            com.nullandkale.nullspace.gameplay.ship.ShipIntegrity.stamp(level, controller, tag.getStringOr("id", ""));
            com.nullandkale.nullspace.gameplay.travel.RecordJournal.commit(level.getServer(), "register-ship", beforeRegistration);
            return new Resolution(tag, "");
        }
        return new Resolution(null, "Register the pad below this ship first");
    }

    /** Query availability only: automation must not complete a partially loaded hull. */
    public static boolean loaded(ServerLevel level, CompoundTag ship)
    {
        var bounds = WorldStore.bounds(ship);
        for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
            for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                if (!level.hasChunk(x, z))
                    return false;
        return true;
    }

    public static Set<BlockPos> discover(ServerLevel level, CompoundTag ship)
    {
        var bounds = WorldStore.bounds(ship);
        var start = WorldStore.pos(ship, "controller");
        if (!level.hasChunkAt(start))
            return Set.of();
        if (ModBlocks.navigation(level.getBlockState(start)) || level.getBlockState(start).is(ModBlocks.STATION_CORE))
            return flood(level, bounds, start);
        // Keep one surviving bus: select the largest component containing actual supply/control
        // hardware. Never join disconnected wreck fragments through metadata alone.
        Set<BlockPos> best = Set.of();
        var visited = new HashSet<BlockPos>();
        boolean ambiguous = false;
        for (BlockPos p : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            if (visited.contains(p) || !level.hasChunkAt(p))
                continue;
            var state = level.getBlockState(p);
            if (!state.is(ModBlocks.COMPUTER) && !state.is(ModBlocks.GENERATOR) && !state.is(ModBlocks.BATTERY))
                continue;
            var component = flood(level, bounds, p.immutable());
            visited.addAll(component);
            if (component.size() > best.size())
            {
                best = component;
                ambiguous = false;
            }
            else if (component.size() == best.size() && !component.isEmpty())
                ambiguous = true;
        }
        return ambiguous ? Set.of() : best;
    }

    private static Set<BlockPos> flood(ServerLevel level, HullScan.Bounds bounds, BlockPos start)
    {
        var found = new HashSet<BlockPos>();
        var collars = DockingTunnel.positions(level);
        var queue = new ArrayDeque<BlockPos>();
        queue.add(start);
        var queued = new HashSet<BlockPos>();
        queued.add(start);
        while (!queue.isEmpty())
        {
            var p = queue.removeFirst();
            if (!bounds.contains(p) || found.contains(p) || collars.contains(p) || !level.hasChunkAt(p))
                continue;
            var state = level.getBlockState(p);
            if (state.isAir())
                continue;
            found.add(p);
            for (var offset : CONNECTIONS)
            {
                var next = p.offset(offset);
                if (queued.add(next))
                    queue.add(next);
            }
        }
        return Set.copyOf(found);
    }

    private ShipTopology()
    {
    }
}
