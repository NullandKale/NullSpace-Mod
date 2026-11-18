package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner.Hab;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Builds a planned hab's blocks with the station structure passes, buried: no glass or roof under the ground, a door from the entry hall's
 * top story out onto the surface, and the cells of every room to dig out of the rock. Coordinates are the plan's: the surface is
 * the row {@link Hab#groundY()}, the top block of the terrain, level with the floor of the entry hall's top story.
 */
public final class HabStructure
{
    /**
     * What a hab is made of. {@code carve} is every cell inside a room that the blocks leave to be air, to dig out of the ground. The
     * {@code entrance} is the lower half of the surface door and {@code outside} the way it faces.
     */
    public record Build(Hab hab, StationBlueprint.Plan plan, Set<BlockPos> carve, BlockPos entrance, Direction outside)
    {}

    public static Build build(Hab hab)
    {
        var made = StationStructure.build(hab.plan(), false, hab.groundY(), true, hab.open());
        var blocks = new LinkedHashMap<>(made.blocks());
        var entry = hab.entry();
        var o = entry.outer();
        int stand = hab.groundY() + 1;
        // The ladder is in a corner on the climb's wall; the surface door is in the middle of the wall opposite, far from it.
        var outside = entry.climb().getOpposite();
        int cx = (o.x0() + o.x1()) / 2, cz = (o.z0() + o.z1()) / 2;
        var door = switch (outside)
        {
            case NORTH -> new BlockPos(cx, stand, o.z0());
            case SOUTH -> new BlockPos(cx, stand, o.z1());
            case WEST -> new BlockPos(o.x0(), stand, cz);
            default -> new BlockPos(o.x1(), stand, cz);
        };
        var state = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, outside);
        blocks.put(door, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        blocks.put(door.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        var plate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
        blocks.put(door.relative(outside), plate);
        blocks.put(door.relative(outside.getOpposite()), plate);
        var points = new ArrayList<>(made.points());
        var grove = hab.groveRoom().interior();
        points.add(new Piece.Point(new BlockPos((grove.x0() + grove.x1()) / 2, grove.y0(), (grove.z0() + grove.z1()) / 2), "tree", hab.grove()));
        // An open connection has no door: the wall between the two rooms is taken out across the whole shared width, with the plates, and the floor
        // there is the rooms' own (moss or grass, never farmland or water), so the two are one room.
        for (var opening : hab.open())
            openUp(hab.plan(), opening, blocks);
        // A door only power opens has a door panel on each side where the others have a stone pressure plate.
        var panel = com.nullandkale.nullspace.gameplay.habs.HabDoors.PANEL.defaultBlockState();
        for (var powered : hab.powered())
        {
            blocks.put(powered.at().relative(powered.through()), panel);
            blocks.put(powered.at().relative(powered.through().getOpposite()), panel);
        }
        // The caves under open mine heads add their ladders and ore to the blocks, so they are dug before the plan is made.
        var caves = new HashSet<BlockPos>();
        for (var point : points)
            if (point.name().equals("mineshaft") && point.argument().equals("open"))
                cave(point.pos(), blocks, caves);
        var plan = new StationBlueprint.Plan(blocks, made.core(), made.docks(), points);
        var carve = new HashSet<BlockPos>();
        for (var room : hab.plan().rooms())
        {
            var i = room.interior();
            for (int x = i.x0(); x <= i.x1(); x++)
                for (int y = i.y0(); y <= i.y1(); y++)
                    for (int z = i.z0(); z <= i.z1(); z++)
                    {
                        var at = new BlockPos(x, y, z);
                        if (!plan.blocks().containsKey(at) || plan.blocks().get(at).isAir())
                            carve.add(at);
                    }
        }
        carve.addAll(caves);
        return new Build(hab, plan, carve, door, outside);
    }

    private static void openUp(StationPlan plan, StationPlan.Door door, Map<BlockPos, BlockState> blocks)
    {
        var a = plan.room(door.a()).interior();
        var b = plan.room(door.b()).interior();
        boolean alongX = door.through().getAxis() == Direction.Axis.X;
        int low = alongX ? Math.max(a.z0(), b.z0()) : Math.max(a.x0(), b.x0()), high = alongX ? Math.min(a.z1(), b.z1()) : Math.min(a.x1(), b.x1());
        int y0 = Math.max(a.y0(), b.y0()), y1 = Math.min(a.y1(), b.y1());
        var air = Blocks.AIR.defaultBlockState();
        for (int along = low; along <= high; along++)
        {
            var wall = alongX ? new BlockPos(door.at().getX(), y0, along) : new BlockPos(along, y0, door.at().getZ());
            for (int y = y0; y <= y1; y++)
                blocks.put(wall.atY(y), air);
            // The floor of the wall's cell is the floor beside it, unless that is farmland or water.
            var beside = blocks.get(wall.atY(y0 - 1).relative(door.through().getOpposite()));
            var floor = beside != null && (beside.is(Blocks.GRASS_BLOCK) || beside.is(Blocks.MOSS_BLOCK) || beside.is(Blocks.DIRT_PATH)) ? beside : Blocks.MOSS_BLOCK.defaultBlockState();
            blocks.put(wall.atY(y0 - 1), floor);
        }
        // The plates that stood in front of the door, one cell inside each room, go too: an opening has no door for them to open.
        blocks.put(door.at().relative(door.through()), air);
        blocks.put(door.at().relative(door.through().getOpposite()), air);
    }

    /** How far below the deepest floor a mine head's cave may reach: the shaft and the chamber under it. */
    public static final int CAVE_DEPTH = 18;

    /**
     * A cave under a mine head: a shaft three across from the floor of its room, a ladder down one side, and a chamber at the bottom with a
     * smaller one off each side, with a few veins of ore in its walls. Everything is dug out of the ground the hab stands in. {@code at} is the
     * mid cell of the opening, at standing height.
     */
    private static void cave(BlockPos at, Map<BlockPos, BlockState> blocks, Set<BlockPos> carve)
    {
        var rng = new SplittableRandom(at.asLong());
        int top = at.getY() - 1, bottom = top - 9;
        // The shaft, with a wall of brick for the ladder to stand against on its west side.
        for (int y = top; y >= bottom; y--)
        {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    dig(new BlockPos(at.getX() + dx, y, at.getZ() + dz), blocks, carve);
            blocks.put(new BlockPos(at.getX() - 2, y, at.getZ()), Blocks.STONE_BRICKS.defaultBlockState());
            blocks.put(new BlockPos(at.getX() - 1, y, at.getZ()), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
            carve.remove(new BlockPos(at.getX() - 1, y, at.getZ()));
        }
        int cy = bottom - 3;
        var lobes = new int[][]{{0, 0, 6}, {-(8 + rng.nextInt(3)), rng.nextInt(5) - 2, 4}, {8 + rng.nextInt(3), rng.nextInt(5) - 2, 4}};
        if (rng.nextBoolean())
            lobes = new int[][]{{0, 0, 6}, {rng.nextInt(5) - 2, -(8 + rng.nextInt(3)), 4}, {rng.nextInt(5) - 2, 8 + rng.nextInt(3), 4}};
        var ores = new Block[]{Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.COAL_ORE};
        for (var lobe : lobes)
        {
            int cx = at.getX() + lobe[0], cz = at.getZ() + lobe[1], r = lobe[2];
            for (int x = cx - r - 2; x <= cx + r + 2; x++)
                for (int y = cy - 6; y <= cy + 6; y++)
                    for (int z = cz - r - 2; z <= cz + r + 2; z++)
                    {
                        double d = Math.sqrt(Math.pow((x - cx) / (double) r, 2) + Math.pow((y - cy) / 3.5, 2) + Math.pow((z - cz) / (double) r, 2));
                        var cell = new BlockPos(x, y, z);
                        if (d <= 1.0)
                            dig(cell, blocks, carve);
                        else if (d <= 1.3 && rng.nextInt(14) == 0 && !blocks.containsKey(cell) && !carve.contains(cell))
                            blocks.put(cell, ores[rng.nextInt(ores.length)].defaultBlockState());
                    }
        }
    }

    private static void dig(BlockPos cell, Map<BlockPos, BlockState> blocks, Set<BlockPos> carve)
    {
        var there = blocks.get(cell);
        if (there == null || there.isAir())
            carve.add(cell);
    }

    private HabStructure()
    {
    }
}
