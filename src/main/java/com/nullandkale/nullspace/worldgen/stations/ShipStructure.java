package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.shipgen.ShipBlocks;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders a planned ship: the station shell, floors, doors and room designs, plus what makes it a vessel. The helm
 * is the controller, engineering has its engines in the stern wall, a camera looks out over the docking door, and
 * a three-wide ramp climbs from the ground to the door, so the ship works on any pad.
 */
public final class ShipStructure
{
    /** A built ship: its blocks and entity points, shifted so the ramp starts at the origin, and its size. */
    public record Built(ShipSpec spec, StationPlan plan, StationBlueprint.Plan blueprint, int width, int height, int depth, List<Landmark> rooms, List<StationPlan.Door> doors)
    {
        public BlockPos helm()
        {
            return blueprint.core();
        }
    }

    /** A room's name, use, a clear standing cell just inside its door, and its first container, if any; all in ship coordinates. */
    public record Landmark(String name, Use use, BlockPos walk, BlockPos supplies)
    {}

    public static Built build(ShipSpec spec)
    {
        var plan = ShipPlanner.generate(spec);
        var made = StationStructure.build(plan, true);
        var palette = ShipBlocks.palette(spec.maker());
        var style = StationStyle.of(spec.maker(), spec.seed());
        var out = new LinkedHashMap<BlockPos, BlockState>();
        int dz = ShipPlanner.RAMP;
        made.blocks().forEach((at, state) -> out.put(at.offset(0, 0, dz), state));
        var berth = plan.berths().getFirst();
        var port = berth.port().offset(0, 0, dz);
        // A camera on the hull over the docking door, looking out.
        out.put(port.above(2).north(), ModBlocks.CAMERA.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        // The ramp: two steps up to the door's floor, as wide as the door frame, solid underneath so it is ground, not a bridge.
        int rise = port.getY();
        var step = palette.stairs().defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        var fill = style.skirting.defaultBlockState();
        for (int k = 0; k < rise; k++)
            for (int side = -1; side <= 1; side++)
            {
                var at = new BlockPos(port.getX() + side, k, port.getZ() - rise + k);
                out.put(at, step);
                for (int under = 0; under < k; under++)
                    out.put(new BlockPos(at.getX(), under, at.getZ()), fill);
            }
        // Air entries only cleared earlier blocks while the plan was built; a ship's blocks are the solid ones.
        out.values().removeIf(BlockState::isAir);
        var points = new ArrayList<Piece.Point>();
        for (var p : made.points())
            points.add(new Piece.Point(p.pos().offset(0, 0, dz), p.name(), p.argument()));
        var docks = made.docks().stream().map(d -> d.offset(0, 0, dz)).toList();
        var blueprint = new StationBlueprint.Plan(out, made.core().offset(0, 0, dz), docks, points);
        var doors = plan.doors().stream().map(d -> d.shift(0, dz)).toList();
        var rooms = new ArrayList<Landmark>();
        for (var room : plan.rooms())
        {
            if (room.use() == Use.CORRIDOR || room.use() == Use.ARRIVAL)
                continue;
            var door = doors.stream().filter(d -> d.a().equals(room.id()) || d.b().equals(room.id())).findFirst().orElseThrow();
            // The cell inside the door is kept clear by every room design.
            var walk = door.a().equals(room.id()) ? door.at().relative(door.through().getOpposite()) : door.at().relative(door.through());
            // The room's supplies are its loot chest, the one a found vessel stocks.
            BlockPos supplies = points.stream().filter(p -> p.name().equals("roomloot") && p.argument().startsWith(room.id() + " ")).map(Piece.Point::pos).findFirst().orElse(null);
            String name = room.use().name().charAt(0) + room.use().name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            rooms.add(new Landmark(name, room.use(), walk, supplies));
        }
        return new Built(spec, plan, blueprint, plan.width(), plan.height(), plan.depth() + dz, List.copyOf(rooms), doors);
    }

    /** True for the uses a ship has exactly one of. */
    public static boolean single(Use use)
    {
        return use == Use.BRIDGE || use == Use.ENGINEERING;
    }

    private ShipStructure()
    {
    }
}
