package com.nullandkale.nullspace.worldgen.piece;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads and writes vanilla structure template NBT without a server, so templates load on the
 * client (shipyard previews), on the server and in tests alike. The files stay editable with
 * vanilla structure blocks.
 */
public final class TemplateCodec
{
    /** A template's contents in local coordinates. Air entries mean "clear this block". */
    public record Template(Vec3i size, Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> blockEntities)
    {
        public Template
        {
            blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
            blockEntities = Collections.unmodifiableMap(new LinkedHashMap<>(blockEntities));
        }

        /** The smallest template that holds these blocks, measured from the origin. */
        public static Template of(Map<BlockPos, BlockState> blocks)
        {
            int x = 0, y = 0, z = 0;
            for (var at : blocks.keySet())
            {
                if (at.getX() < 0 || at.getY() < 0 || at.getZ() < 0)
                    throw new IllegalArgumentException("Template blocks must have non-negative coordinates: " + at);
                x = Math.max(x, at.getX() + 1);
                y = Math.max(y, at.getY() + 1);
                z = Math.max(z, at.getZ() + 1);
            }
            return new Template(new Vec3i(x, y, z), blocks, Map.of());
        }
    }

    public static Template read(CompoundTag root)
    {
        return read(root, BuiltInRegistries.BLOCK);
    }

    /** The size recorded in template NBT; needs no block registry. */
    public static Vec3i size(CompoundTag root)
    {
        var size = root.getListOrEmpty("size");
        return new Vec3i(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0));
    }

    public static Template read(CompoundTag root, HolderGetter<Block> registry)
    {
        var dimensions = size(root);
        // Vanilla writes "palettes" when a template has random palette variants; the first is the default.
        var palette = root.contains("palette") ? root.getListOrEmpty("palette") : root.getListOrEmpty("palettes").getListOrEmpty(0);
        var states = new ArrayList<BlockState>();
        for (var entry : palette)
            states.add(NbtUtils.readBlockState(registry, (CompoundTag) entry));
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var entities = new LinkedHashMap<BlockPos, CompoundTag>();
        for (var entry : root.getListOrEmpty("blocks"))
        {
            var block = (CompoundTag) entry;
            var pos = block.getListOrEmpty("pos");
            var at = new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0));
            int index = block.getIntOr("state", 0);
            if (index < 0 || index >= states.size())
                throw new IllegalArgumentException("Template block at " + at + " names palette entry " + index + " of " + states.size());
            blocks.put(at, states.get(index));
            block.getCompound("nbt").ifPresent(nbt -> entities.put(at, nbt.copy()));
        }
        return new Template(dimensions, blocks, entities);
    }

    public static CompoundTag write(Template template)
    {
        var root = new CompoundTag();
        var size = new ListTag();
        size.add(IntTag.valueOf(template.size().getX()));
        size.add(IntTag.valueOf(template.size().getY()));
        size.add(IntTag.valueOf(template.size().getZ()));
        root.put("size", size);
        var palette = new ListTag();
        var indices = new HashMap<BlockState, Integer>();
        var blocks = new ListTag();
        // A stable order (y, z, x) keeps saved files diffable.
        var positions = new ArrayList<>(template.blocks().keySet());
        positions.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getZ()).thenComparingInt(p -> p.getX()));
        for (var at : positions)
        {
            var state = template.blocks().get(at);
            int index = indices.computeIfAbsent(state, s ->
            {
                palette.add(NbtUtils.writeBlockState(s));
                return palette.size() - 1;
            });
            var block = new CompoundTag();
            var pos = new ListTag();
            pos.add(IntTag.valueOf(at.getX()));
            pos.add(IntTag.valueOf(at.getY()));
            pos.add(IntTag.valueOf(at.getZ()));
            block.put("pos", pos);
            block.putInt("state", index);
            var nbt = template.blockEntities().get(at);
            if (nbt != null)
                block.put("nbt", nbt.copy());
            blocks.add(block);
        }
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtUtils.addDataVersion(root, SharedConstants.getCurrentVersion().dataVersion().version());
        return root;
    }

    public static Template read(InputStream in) throws IOException
    {
        return read(readFile(in));
    }

    /** The raw NBT of a compressed template file. */
    public static CompoundTag readFile(InputStream in) throws IOException
    {
        return NbtIo.readCompressed(in, NbtAccounter.create(64L * 1024 * 1024));
    }

    public static void write(Path file, Template template) throws IOException
    {
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(write(template), file);
    }

    private TemplateCodec()
    {
    }
}
