package com.nullandkale.nullspace.model.space;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** The same bounded, update-tag-only visual contract for ship volumes and terrain sections. */
public final class VisualSnapshot
{
    public static final int MAX_ENTITIES = 64, MAX_ENTITY_BYTES = 8192, MAX_BYTES = 16384;

    /**
     * What a snapshot keeps first when a volume has more block entities than the budget allows. Lower is kept first: the mod's
     * own machines and displays, then the rest (banners, heads, pots, beacons and so on), and last the furniture and
     * containers (beds, signs, chests, barrels and the like), which are the first to go. Blocks, light and creatures are not
     * part of this budget at all, so they are never dropped for it.
     */
    public static int priority(String id)
    {
        if (id.startsWith("nullspace:"))
            return 0;
        return switch (id.substring(id.indexOf(':') + 1))
        {
            case "bed", "sign", "hanging_sign", "chest", "trapped_chest", "ender_chest", "barrel", "shulker_box", "furnace", "blast_furnace", "smoker", "brewing_stand", "hopper", "dropper", "dispenser", "crafter", "jukebox", "chiseled_bookshelf", "campfire", "lectern" -> 2;
            default -> 1;
        };
    }

    public static final class Collector
    {
        private record Candidate(int priority, int cell, CompoundTag visual)
        {}

        private final ListTag entities = new ListTag();
        private final java.util.List<Candidate> candidates = new java.util.ArrayList<>();
        private int bytes;

        public void add(ServerLevel level, BlockPos position, int cell)
        {
            var be = level.getBlockEntity(position);
            if (be == null)
                return;
            var tag = be.getUpdateTag(level.registryAccess()).copy();
            String id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString();
            tag.putString("id", id);
            if (tag.sizeInBytes() > MAX_ENTITY_BYTES)
                return;
            var visual = new CompoundTag();
            visual.putInt("cell", cell);
            visual.put("data", tag);
            offer(priority(id), visual);
        }

        /** Proposes a visual with its priority (see {@link #priority}); what fits is chosen when the snapshot is read. */
        public void offer(int priority, CompoundTag visual)
        {
            candidates.add(new Candidate(priority, visual.getIntOr("cell", 0), visual));
        }

        /**
         * Adds a visual unless the snapshot would pass its entity or byte budget, and says whether it was added. The size is
         * measured on the list after the add: a list charges each element a few bytes beyond the element itself, so adding the
         * sizes up beforehand lets a snapshot just over the budget through, and the client rejects the whole volume.
         */
        public boolean accept(CompoundTag visual)
        {
            if (entities.size() >= MAX_ENTITIES)
                return false;
            entities.add(visual);
            if (entities.sizeInBytes() > MAX_BYTES)
            {
                entities.removeLast();
                return false;
            }
            bytes = entities.sizeInBytes();
            return true;
        }

        private void settle()
        {
            if (candidates.isEmpty())
                return;
            // The most important first, and within a priority the lowest cell, so the choice is the same every time.
            candidates.sort(java.util.Comparator.comparingInt(Candidate::priority).thenComparingInt(Candidate::cell));
            for (var candidate : candidates)
                accept(candidate.visual());
            candidates.clear();
        }

        public ListTag entities()
        {
            settle();
            return entities;
        }

        public int bytes()
        {
            settle();
            return bytes;
        }
    }

    public static ListTag capture(ServerLevel level, BlockPos base, int size, int[] states)
    {
        var out = new Collector();
        for (int i = 0; i < states.length; i++)
            if (net.minecraft.world.level.block.Block.stateById(states[i]).hasBlockEntity())
            {
                var pos = base.offset(i % size, i / (size * size), i / size % size);
                if (level.hasChunkAt(pos))
                    out.add(level, pos, i);
            }
        return out.entities();
    }

    public static void validate(ListTag entities, int cells)
    {
        if (entities.size() > MAX_ENTITIES || entities.sizeInBytes() > MAX_BYTES)
            throw new IllegalArgumentException("Visual snapshot exceeds budget");
        for (Tag tag : entities)
        {
            if (!(tag instanceof CompoundTag visual) || visual.getIntOr("cell", -1) < 0 || visual.getIntOr("cell", -1) >= cells || visual.getCompoundOrEmpty("data").sizeInBytes() > MAX_ENTITY_BYTES)
                throw new IllegalArgumentException("Invalid visual block entity");
        }
    }

    private VisualSnapshot()
    {
    }
}
