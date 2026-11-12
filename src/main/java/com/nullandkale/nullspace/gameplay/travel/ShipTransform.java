package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/** Exact quarter turns shared by physical movement, saved coordinates and journal recovery. */
public record ShipTransform(BlockPos source, BlockPos target, int width, int depth, int turns, BlockPos offset)
{
    public ShipTransform(BlockPos source, BlockPos target, int width, int depth, int turns)
    {
        this(source, target, width, depth, turns, BlockPos.ZERO);
    }

    public ShipTransform
    {
        turns = Math.floorMod(turns, 4);
        if (turns != 0 && (width < 1 || depth < 1))
            throw new IllegalArgumentException("Rotated ship dimensions must be positive");
    }

    public BlockPos local(BlockPos p)
    {
        BlockPos rotated = switch (turns)
        {
            case 1 -> new BlockPos(depth - 1 - p.getZ(), p.getY(), p.getX());
            case 2 -> new BlockPos(width - 1 - p.getX(), p.getY(), depth - 1 - p.getZ());
            case 3 -> new BlockPos(p.getZ(), p.getY(), width - 1 - p.getX());
            default -> p;
        };
        return rotated.offset(offset);
    }

    public BlockPos apply(BlockPos p)
    {
        return target.offset(local(p.subtract(source)));
    }

    public Vec3 local(Vec3 p)
    {
        Vec3 rotated = switch (turns)
        {
            case 1 -> new Vec3(depth - p.z, p.y, p.x);
            case 2 -> new Vec3(width - p.x, p.y, depth - p.z);
            case 3 -> new Vec3(p.z, p.y, width - p.x);
            default -> p;
        };
        return rotated.add(Vec3.atLowerCornerOf(offset));
    }

    public Vec3 apply(Vec3 p)
    {
        return local(p.subtract(Vec3.atLowerCornerOf(source))).add(Vec3.atLowerCornerOf(target));
    }

    public Vec3 velocity(Vec3 p)
    {
        return switch (turns)
        {
            case 1 -> new Vec3(-p.z, p.y, p.x);
            case 2 -> new Vec3(-p.x, p.y, -p.z);
            case 3 -> new Vec3(p.z, p.y, -p.x);
            default -> p;
        };
    }

    public float yaw(float yaw)
    {
        return net.minecraft.util.Mth.wrapDegrees(yaw + turns * 90);
    }

    public Rotation rotation()
    {
        return switch (turns)
        {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public HullScan.Bounds bounds(HullScan.Bounds original)
    {
        var a = apply(original.min());
        var b = apply(original.max());
        return new HullScan.Bounds(BlockPos.min(a, b), BlockPos.max(a, b));
    }

    public void save(CompoundTag journal, HullScan.Bounds original)
    {
        var data = new CompoundTag();
        data.putLong("source", source.asLong());
        data.putLong("target", target.asLong());
        data.putInt("width", width);
        data.putInt("depth", depth);
        data.putInt("turns", turns);
        data.putLong("offset", offset.asLong());
        journal.put("transform", data);
        var bounds = bounds(original);
        journal.putLong("destinationMin", bounds.min().asLong());
        journal.putLong("destinationMax", bounds.max().asLong());
    }

    public static ShipTransform fromJournal(CompoundTag journal)
    {
        if (!journal.contains("transform"))
            return new ShipTransform(BlockPos.ZERO, WorldStore.pos(journal, "delta"), 0, 0, 0);
        var data = journal.getCompoundOrEmpty("transform");
        return new ShipTransform(WorldStore.pos(data, "source"), WorldStore.pos(data, "target"), data.getIntOr("width", 0), data.getIntOr("depth", 0), data.getIntOr("turns", 0), WorldStore.pos(data, "offset"));
    }

    public static HullScan.Bounds destinationBounds(CompoundTag journal)
    {
        return fromJournal(journal).bounds(new HullScan.Bounds(WorldStore.pos(journal, "min"), WorldStore.pos(journal, "max")));
    }

    private static ListTag ints(int x, int y, int z)
    {
        var out = new ListTag();
        out.add(IntTag.valueOf(x));
        out.add(IntTag.valueOf(y));
        out.add(IntTag.valueOf(z));
        return out;
    }

    /** Rotate the saved template itself so placement and exact block-entity reload agree. */
    public CompoundTag template(ServerLevel level, CompoundTag original, BlockPos min)
    {
        var out = original.copy();
        if (turns == 0)
            return out;
        var registry = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        java.util.function.Consumer<ListTag> rotatePalette = palette ->
        {
            for (int i = 0; i < palette.size(); i++)
                palette.set(i, NbtUtils.writeBlockState(NbtUtils.readBlockState(registry, (CompoundTag) palette.get(i)).rotate(rotation())));
        };
        if (out.contains("palette"))
            rotatePalette.accept(out.getListOrEmpty("palette"));
        for (var palette : out.getListOrEmpty("palettes"))
            rotatePalette.accept((ListTag) palette);
        var size = out.getListOrEmpty("size");
        var box = bounds(new HullScan.Bounds(min, min.offset(size.getIntOr(0, 0) - 1, size.getIntOr(1, 0) - 1, size.getIntOr(2, 0) - 1)));
        out.put("size", ints(box.max().getX() - box.min().getX() + 1, box.max().getY() - box.min().getY() + 1, box.max().getZ() - box.min().getZ() + 1));
        for (var entry : out.getListOrEmpty("blocks"))
        {
            var block = (CompoundTag) entry;
            var pos = block.getListOrEmpty("pos");
            var at = apply(min.offset(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)));
            var local = at.subtract(box.min());
            block.put("pos", ints(local.getX(), local.getY(), local.getZ()));
            if (block.contains("nbt"))
            {
                var nbt = block.getCompoundOrEmpty("nbt");
                nbt.putInt("x", at.getX());
                nbt.putInt("y", at.getY());
                nbt.putInt("z", at.getZ());
            }
        }
        return out;
    }
}
