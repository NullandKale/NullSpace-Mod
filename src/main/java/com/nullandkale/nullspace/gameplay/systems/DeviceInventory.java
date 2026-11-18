package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ContentCatalog;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.habs.HabVessels;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

final class DeviceInventory
{
    static boolean device(Block block)
    {
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof ModBlocks.Machine && ContentCatalog.equipment(BuiltInRegistries.BLOCK.getKey(block).getPath()).map(d -> d.has("device")).orElse(false);
    }

    static void rebuild(ServerLevel level, CompoundTag ship, ShipRuntime r)
    {
        boolean large = ShipSystems.large(ship);
        Collection<BlockPos> scanned;
        if (large)
        {
            // A large vessel reads its few candidates, those in chunks that are loaded: the others are off until they load.
            if (r.members == null)
                initialize(level, ship, r);
            r.blocks = HabVessels.view(r.members);
            var loaded = new ArrayList<BlockPos>();
            for (long packed : r.candidates)
            {
                var at = BlockPos.of(packed);
                if (level.hasChunkAt(at))
                    loaded.add(at);
            }
            scanned = loaded;
        }
        else
        {
            r.blocks = ShipTopology.discover(level, ship);
            scanned = r.blocks;
        }
        var s = state(ship);
        var base = WorldStore.pos(ship, "base");
        var devices = s.getListOrEmpty("devices");
        for (var pos : scanned)
        {
            var block = level.getBlockState(pos);
            if (!device(block.getBlock()) || block.getBlock() instanceof DoorBlock && block.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER)
                continue;
            long local = pos.subtract(base).asLong();
            String type = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            if (devices.stream().noneMatch(t ->
            {
                var d = (CompoundTag) t;
                return d.getLongOr("position", Long.MIN_VALUE) == local && d.getStringOr("type", "").equals(type);
            }))
            {
                var d = new CompoundTag();
                d.putString("id", UUID.randomUUID().toString());
                d.putString("type", type);
                d.putLong("position", local);
                d.putInt("energy", 0);
                devices.add(d);
            }
        }
        r.equipment = scanned.stream().filter(p -> device(level.getBlockState(p).getBlock())).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        r.turrets = scanned.stream().filter(p -> level.getBlockState(p).is(com.nullandkale.nullspace.gameplay.defence.DefenceBlocks.SALVAGE_TURRET)).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        r.bays = scanned.stream().filter(p -> level.getBlockState(p).is(com.nullandkale.nullspace.gameplay.defence.DefenceBlocks.REPAIR_BAY)).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        r.trees = scanned.stream().filter(p -> com.nullandkale.nullspace.gameplay.trees.GoldenTrees.isTrunk(level.getBlockState(p).getBlock())).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        r.base = base;
        r.devices.clear();
        for (Tag tag : devices)
        {
            var d = (CompoundTag) tag;
            var at = base.offset(BlockPos.of(d.getLongOr("position", 0)));
            if (level.hasChunkAt(at) && level.getBlockEntity(at) instanceof MachineBlockEntity machine)
                d.putString("label", machine.label);
            r.devices.put(d.getLongOr("position", Long.MIN_VALUE), d);
        }
        r.batteryDevices = devices.stream().map(t -> (CompoundTag) t).filter(d -> d.getStringOr("type", "").equals("nullspace:battery") && r.blocks.contains(base.offset(BlockPos.of(d.getLongOr("position", 0)))) && (!large || level.hasChunkAt(base.offset(BlockPos.of(d.getLongOr("position", 0)))))).sorted(Comparator.comparing(d -> d.getStringOr("id", ""))).toList();
    }

    /** True for a block a large vessel keeps in its candidates: it is something the systems act on. */
    static boolean candidate(net.minecraft.world.level.block.state.BlockState state)
    {
        return device(state.getBlock()) || state.is(com.nullandkale.nullspace.gameplay.defence.DefenceBlocks.SALVAGE_TURRET) || state.is(com.nullandkale.nullspace.gameplay.defence.DefenceBlocks.REPAIR_BAY) || com.nullandkale.nullspace.gameplay.trees.GoldenTrees.isTrunk(state.getBlock());
    }

    /** Finds a large vessel's own blocks and its candidates: the candidates were saved with it, and are found among its blocks only when it has none saved. */
    private static void initialize(ServerLevel level, CompoundTag ship, ShipRuntime r)
    {
        r.members = HabVessels.members(level, ship);
        var saved = state(ship).getLongArray("candidates");
        if (saved.isPresent())
        {
            r.candidates = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(saved.get());
            return;
        }
        r.candidates = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        var at = new BlockPos.MutableBlockPos();
        for (long packed : r.members)
        {
            at.set(packed);
            if (level.hasChunkAt(at) && candidate(level.getBlockState(at)))
                r.candidates.add(packed);
        }
        state(ship).putLongArray("candidates", r.candidates.toLongArray());
    }

    /**
     * A block of a large vessel has changed: keeps its blocks and candidates, and says whether a candidate came or went (the devices are then found
     * again). A block placed next to the vessel's own blocks joins it; one that is now air leaves it.
     */
    static boolean track(ServerLevel level, ShipRuntime r, BlockPos p)
    {
        var state = level.getBlockState(p);
        long packed = p.asLong();
        boolean was = r.candidates.contains(packed);
        if (state.isAir())
        {
            r.members.remove(packed);
            r.candidates.remove(packed);
            return was;
        }
        if (!r.members.contains(packed))
            for (var d : Direction.values())
                if (r.members.contains(p.relative(d).asLong()))
                {
                    r.members.add(packed);
                    break;
                }
        boolean is = r.members.contains(packed) && candidate(state);
        if (is)
            r.candidates.add(packed);
        else
            r.candidates.remove(packed);
        // Only a candidate coming or going changes the device list: a door that opens or shuts is the same device, and is the seals' business.
        return was != is;
    }
}
