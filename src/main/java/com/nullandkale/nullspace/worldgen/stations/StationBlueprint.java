package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.shipgen.*;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Pure block plan; placement never overwrites an existing player's build. */
public final class StationBlueprint
{
    /**
     * A build ready to place. {@code points} are the named spots from its pieces (loot, mobs), in
     * the same local coordinates; hand-coded plans have none.
     */
    public record Plan(Map<BlockPos, BlockState> blocks, BlockPos core, List<BlockPos> docks, List<Piece.Point> points)
    {
        public Plan
        {
            // Every generated station or encounter gets settled facing and connections.
            var settled = new LinkedHashMap<>(blocks);
            BlueprintOrientation.fix(settled);
            blocks = Collections.unmodifiableMap(settled);
            docks = List.copyOf(docks);
            points = List.copyOf(points);
        }

        public Plan(Map<BlockPos, BlockState> blocks, BlockPos core, List<BlockPos> docks)
        {
            this(blocks, core, docks, List.of());
        }

        public List<Piece.Point> points(String name)
        {
            return points.stream().filter(p -> p.name().equals(name)).toList();
        }
    }

    private static void machine(Map<BlockPos, BlockState> out, BlockPos at, Block block, Direction face)
    {
        var state = block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face);
        if (block == ModBlocks.CAMERA || block == ModBlocks.MONITOR)
        {
            var backing = at.relative(face.getOpposite());
            if (!out.containsKey(backing) || out.get(backing).isAir())
                out.put(backing, Blocks.QUARTZ_BLOCK.defaultBlockState());
        }
        out.put(at, state);
    }

    private static void door(Map<BlockPos, BlockState> out, BlockPos at, Direction face)
    {
        var s = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, face);
        out.put(at, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        out.put(at.above(), s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void opening(Map<BlockPos, BlockState> out, BlockPos at)
    {
        out.put(at, Blocks.AIR.defaultBlockState());
        out.put(at.above(), Blocks.AIR.defaultBlockState());
    }

    /**
     * Joins two neighbouring tiles. Each side has two doorway slots on each of the two stories, so
     * four doors: a closed iron door in the first tile's wall, and the second tile's wall cut open
     * behind it. Everything else about the seam is already wall.
     */
    public static void connect(Map<BlockPos, BlockState> out, StationLayout.Room a, StationLayout.Room b)
    {
        var face = StationLayout.direction(a, b);
        var da = StationLayout.doorways(a, face);
        var db = StationLayout.doorways(b, face.getOpposite());
        for (int i = 0; i < da.size(); i++)
        {
            door(out, da.get(i), face);
            opening(out, db.get(i));
        }
    }

    /** The cells of {@code a} that {@link #connect} replaces with a door: the wall there is expected, not an obstruction. */
    public static List<BlockPos> seam(StationLayout.Room a, StationLayout.Room b)
    {
        var out = new ArrayList<BlockPos>();
        for (var door : StationLayout.doorways(a, StationLayout.direction(a, b)))
        {
            out.add(door);
            out.add(door.above());
        }
        return out;
    }

    private static final Map<String, Map<BlockPos, BlockState>> TILES = new HashMap<>();

    /** The generated blocks of a piece, unturned. */
    private static synchronized Map<BlockPos, BlockState> tile(StationPieces.Definition piece, Spec.Maker maker)
    {
        return TILES.computeIfAbsent(piece.id() + "/" + maker, k -> ShipBlocks.blocks(StationGenerator.tile(piece.kit(), piece.doors(), piece.windows(), maker)));
    }

    public static Plan build(StationLayout.Layout layout)
    {
        return build(layout, Spec.Maker.HALCYON);
    }

    public static Plan build(StationLayout.Layout layout, Spec.Maker maker)
    {
        return build(layout, room -> maker);
    }

    /** As {@link #build(StationLayout.Layout)}, with each room in its own maker's materials. */
    public static Plan build(StationLayout.Layout layout, java.util.function.Function<StationLayout.Room, Spec.Maker> makers)
    {
        var out = new LinkedHashMap<BlockPos, BlockState>();
        var docks = new ArrayList<BlockPos>();
        BlockPos core = null;
        for (var room : layout.rooms())
        {
            var def = StationPieces.get(room.piece());
            var size = new Vec3i(StationGenerator.TILE, StationGenerator.HEIGHT, StationGenerator.TILE);
            var piece = new Piece(def.id(), size, tile(def, makers.apply(room)), List.of(), List.of()).rotate(room.turn());
            var b = room.origin();
            for (var e : piece.blocks().entrySet())
            {
                out.put(b.offset(e.getKey()), e.getValue());
                if (e.getValue().is(ModBlocks.STATION_CORE))
                    core = b.offset(e.getKey());
            }
        }
        if (core == null)
            core = layout.rooms().getFirst().origin().offset(1, StationGenerator.DOOR_ROWS[0], 1);
        for (var edge : layout.joints())
            connect(out, layout.room(edge.a()), layout.room(edge.b()));
        boolean large = layout.rooms().stream().anyMatch(r -> r.x() > 1 || r.z() > 1);
        for (var room : layout.rooms())
        {
            var faces = new ArrayList<Direction>();
            if (!large && room.z() == 0)
                faces.add(room.x() == 0 ? Direction.WEST : Direction.EAST);
            if (large)
            {
                if ((room.x() == 2 || room.x() == 6) && room.z() == 0)
                    faces.add(Direction.NORTH);
                if ((room.x() == 1 || room.x() == 5) && room.z() == 7)
                    faces.add(Direction.SOUTH);
                if ((room.z() == 1 || room.z() == 5) && room.x() == 0)
                    faces.add(Direction.WEST);
                if ((room.z() == 2 || room.z() == 6) && room.x() == 7)
                    faces.add(Direction.EAST);
            }
            for (var face : faces)
            {
                if (!room.doors().contains(face) || layout.rooms().stream().anyMatch(r -> r.x() == room.x() + face.getStepX() && r.z() == room.z() + face.getStepZ()))
                    continue;
                var at = StationLayout.doorway(room, face);
                compact(out, at, face);
                docks.add(at.below());
            }
        }
        return new Plan(out, core, docks);
    }

    public static void compact(Map<BlockPos, BlockState> out, BlockPos door, Direction facing)
    {
        var side = facing.getClockWise();
        for (var direction : List.of(side, side.getOpposite()))
            for (int y = 0; y < 2; y++)
                out.put(door.relative(direction).above(y), Blocks.QUARTZ_BLOCK.defaultBlockState());
        out.put(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        out.put(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        machine(out, door.below(), ModBlocks.DOCKING_BASE, facing);
        out.put(door.above(2), Blocks.QUARTZ_BLOCK.defaultBlockState());
        for (int depth = 1; depth <= 2; depth++)
        {
            out.put(door.relative(facing.getOpposite(), depth), Blocks.AIR.defaultBlockState());
            out.put(door.relative(facing.getOpposite(), depth).above(), Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * Where a builder stands when they arrive at a construction platform: on the platform, in the
     * aisle by the west wall, where no kit puts anything (so the first room can go up around them).
     */
    public static final BlockPos ARRIVAL = new BlockPos(4, StationGenerator.BASE + 1, 7);

    public static Plan foundation()
    {
        var out = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = 4; x <= 11; x++)
            for (int z = 4; z <= 11; z++)
                out.put(new BlockPos(x, StationGenerator.BASE, z), Blocks.POLISHED_ANDESITE.defaultBlockState());
        var core = new BlockPos(5, StationGenerator.BASE + 1, 5);
        machine(out, core, ModBlocks.STATION_CORE, Direction.SOUTH);
        return new Plan(out, core, List.of());
    }

    private StationBlueprint()
    {
    }
}
