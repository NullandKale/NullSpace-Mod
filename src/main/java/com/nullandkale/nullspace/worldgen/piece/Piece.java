package com.nullandkale.nullspace.worldgen.piece;

import java.util.*;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;

/**
 * A template with its markers read out. Authors place two kinds of vanilla marker blocks, which are
 * removed from the piece's blocks:
 * <ul>
 * <li>A <b>jigsaw block</b> is a {@link Connector}: it faces out of the piece, its name is its
 * type and its target names the type it accepts (empty: its own type). As in vanilla, the block becomes its final state (air
 * unless set). A connector only aligns pieces: an end left open is closed by a cap piece.</li>
 * <li>A <b>structure block in DATA mode</b> is a {@link Point}: its metadata names the spot, such as
 * {@code loot:cargo}, {@code mob:repair_bot} or {@code core}.</li>
 * </ul>
 */
public record Piece(String id, Vec3i size, Map<BlockPos, BlockState> blocks, List<Connector> connectors, List<Point> points)
{
    /**
     * Where another piece can join. As with vanilla jigsaws, this connector takes a piece whose
     * connector {@code type} equals this one's {@code target}; an empty target means the same as the
     * type (a symmetric joint such as a spine). {@code fill} is the block left at the connector.
     */
    public record Connector(BlockPos pos, Direction facing, String type, String target, BlockState fill)
    {
        Connector rotate(Rotation rotation, Vec3i size)
        {
            return new Connector(Piece.rotate(pos, rotation, size), rotation.rotate(facing), type, target, fill.rotate(rotation));
        }

        Connector move(Vec3i offset)
        {
            return new Connector(pos.offset(offset), facing, type, target, fill);
        }

        /** True if {@code other} may attach here. */
        public boolean accepts(Connector other)
        {
            return other.type().equals(target);
        }
    }

    /** A named spot: {@code name} is the part before the colon, {@code argument} the rest. */
    public record Point(BlockPos pos, String name, String argument)
    {
        public static Point parse(BlockPos pos, String metadata)
        {
            String text = metadata.strip();
            int colon = text.indexOf(':');
            return colon < 0 ? new Point(pos, text, "") : new Point(pos, text.substring(0, colon), text.substring(colon + 1));
        }

        Point rotate(Rotation rotation, Vec3i size)
        {
            return new Point(Piece.rotate(pos, rotation, size), name, argument);
        }

        Point move(Vec3i offset)
        {
            return new Point(pos.offset(offset), name, argument);
        }
    }

    public Piece
    {
        blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
        connectors = List.copyOf(connectors);
        points = List.copyOf(points);
    }

    /** Reads the markers out of a template. */
    public static Piece of(String id, TemplateCodec.Template template)
    {
        var blocks = new LinkedHashMap<>(template.blocks());
        var connectors = new ArrayList<Connector>();
        var points = new ArrayList<Point>();
        for (var e : template.blocks().entrySet())
        {
            var at = e.getKey();
            var state = e.getValue();
            var nbt = template.blockEntities().getOrDefault(at, new CompoundTag());
            if (state.is(Blocks.JIGSAW))
            {
                var fill = finalState(nbt.getStringOr("final_state", "minecraft:air"));
                // Vanilla stores the name as an identifier, so "spine" comes back as "minecraft:spine".
                String type = nbt.getStringOr("name", "minecraft:empty").replaceFirst("^minecraft:", "");
                String target = nbt.getStringOr("target", "minecraft:empty").replaceFirst("^minecraft:", "");
                connectors.add(new Connector(at, JigsawBlock.getFrontFacing(state), type, target.isEmpty() || target.equals("empty") ? type : target, fill));
                blocks.put(at, fill);
            }
            else if (state.is(Blocks.STRUCTURE_BLOCK) && StructureMode.DATA.name().equals(nbt.getStringOr("mode", "")))
            {
                points.add(Point.parse(at, nbt.getStringOr("metadata", "")));
                blocks.put(at, Blocks.AIR.defaultBlockState());
            }
        }
        return new Piece(id, template.size(), blocks, connectors, points);
    }

    private static BlockState finalState(String text)
    {
        try
        {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        }
        catch (Exception invalid)
        {
            return Blocks.AIR.defaultBlockState();
        }
    }

    /** This piece turned by {@code rotation} about the vertical axis, still starting at the origin. */
    public Piece rotate(Rotation rotation)
    {
        if (rotation == Rotation.NONE)
            return this;
        var turned = new LinkedHashMap<BlockPos, BlockState>();
        blocks.forEach((at, state) -> turned.put(rotate(at, rotation, size), state.rotate(rotation)));
        var newSize = rotation == Rotation.CLOCKWISE_180 ? size : new Vec3i(size.getZ(), size.getY(), size.getX());
        return new Piece(id, newSize, turned, connectors.stream().map(c -> c.rotate(rotation, size)).toList(), points.stream().map(p -> p.rotate(rotation, size)).toList());
    }

    /** This piece's blocks, connectors and points shifted by {@code offset}. */
    public Piece move(Vec3i offset)
    {
        var moved = new LinkedHashMap<BlockPos, BlockState>();
        blocks.forEach((at, state) -> moved.put(at.offset(offset), state));
        return new Piece(id, size, moved, connectors.stream().map(c -> c.move(offset)).toList(), points.stream().map(p -> p.move(offset)).toList());
    }

    /** Where a position inside a box of {@code size} lands when the box turns, origin kept. */
    static BlockPos rotate(BlockPos at, Rotation rotation, Vec3i size)
    {
        int x = at.getX(), y = at.getY(), z = at.getZ(), maxX = size.getX() - 1, maxZ = size.getZ() - 1;
        return switch (rotation)
        {
            case NONE -> at;
            // Clockwise seen from above: north (-z) turns to east (+x).
            case CLOCKWISE_90 -> new BlockPos(maxZ - z, y, x);
            case CLOCKWISE_180 -> new BlockPos(maxX - x, y, maxZ - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, maxX - x);
        };
    }

    /** The rotation that turns {@code from} into {@code to}; both horizontal. */
    public static Rotation turning(Direction from, Direction to)
    {
        for (var rotation : Rotation.values())
            if (rotation.rotate(from) == to)
                return rotation;
        throw new IllegalArgumentException("No horizontal rotation turns " + from + " into " + to);
    }

    public List<Point> points(String name)
    {
        return points.stream().filter(p -> p.name().equals(name)).toList();
    }
}
