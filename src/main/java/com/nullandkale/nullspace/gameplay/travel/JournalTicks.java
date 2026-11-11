package com.nullandkale.nullspace.gameplay.travel;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.*;

/** Scheduled work belongs to the captured blocks, including after a process restart erased the source queue. */
public final class JournalTicks
{
    public static CompoundTag capture(ServerLevel level, Set<BlockPos> positions)
    {
        var result = new CompoundTag();
        var blocks = new ListTag();
        var fluids = new ListTag();
        var chunks = new HashSet<ChunkPos>();
        positions.forEach(p -> chunks.add(new ChunkPos(p.getX() >> 4, p.getZ() >> 4)));
        for (var at : chunks)
        {
            var chunk = (net.minecraft.world.level.chunk.LevelChunk) level.getChunk(at.x(), at.z());
            ((LevelChunkTicks<net.minecraft.world.level.block.Block>) chunk.getBlockTicks()).getAll().filter(t -> positions.contains(t.pos())).forEach(t -> blocks.add(encode(level, t, BuiltInRegistries.BLOCK.getKey(t.type()).toString())));
            ((LevelChunkTicks<net.minecraft.world.level.material.Fluid>) chunk.getFluidTicks()).getAll().filter(t -> positions.contains(t.pos())).forEach(t -> fluids.add(encode(level, t, BuiltInRegistries.FLUID.getKey(t.type()).toString())));
        }
        result.put("blocks", blocks);
        result.put("fluids", fluids);
        return result;
    }

    private static CompoundTag encode(ServerLevel level, ScheduledTick<?> tick, String type)
    {
        var result = new CompoundTag();
        result.putString("type", type);
        result.putLong("at", tick.pos().asLong());
        result.putLong("delay", Math.max(0, tick.triggerTick() - level.getGameTime()));
        result.putInt("priority", tick.priority().getValue());
        result.putLong("order", tick.subTickOrder());
        return result;
    }

    public static void restore(ServerLevel level, CompoundTag saved, BlockPos delta)
    {
        for (var element : saved.getListOrEmpty("blocks"))
        {
            var tick = (CompoundTag) element;
            var id = Identifier.parse(tick.getStringOr("type", ""));
            if (!BuiltInRegistries.BLOCK.containsKey(id))
                throw new IllegalArgumentException("Scheduled block is unavailable: " + id);
            level.getBlockTicks().schedule(new ScheduledTick<>(BuiltInRegistries.BLOCK.getValue(id), BlockPos.of(tick.getLongOr("at", 0)).offset(delta), level.getGameTime() + tick.getLongOr("delay", 0), TickPriority.byValue(tick.getIntOr("priority", 0)), tick.getLongOr("order", 0)));
        }
        for (var element : saved.getListOrEmpty("fluids"))
        {
            var tick = (CompoundTag) element;
            var id = Identifier.parse(tick.getStringOr("type", ""));
            if (!BuiltInRegistries.FLUID.containsKey(id))
                throw new IllegalArgumentException("Scheduled fluid is unavailable: " + id);
            level.getFluidTicks().schedule(new ScheduledTick<>(BuiltInRegistries.FLUID.getValue(id), BlockPos.of(tick.getLongOr("at", 0)).offset(delta), level.getGameTime() + tick.getLongOr("delay", 0), TickPriority.byValue(tick.getIntOr("priority", 0)), tick.getLongOr("order", 0)));
        }
    }

    private JournalTicks()
    {
    }
}
