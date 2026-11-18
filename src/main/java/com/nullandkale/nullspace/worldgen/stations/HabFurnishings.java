package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner.Wing;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Room;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What makes a hab a hab, apart from the rooms the hab designs furnish (see {@code docs/design/habs.md}): the surface lobby, which holds what
 * a crew keeps at an airlock, and which designs a hab's rooms take.
 */
final class HabFurnishings
{
    /**
     * The surface lobby, the entry hall's top story: lockers in groups of three with a gap between them along each side wall, stopping short of
     * the ladder, a suit on a stand in each gap, and a potted fern in each corner by the door. The middle is left open, and the cells in front
     * of the door and the ladder stay clear. The suits are entities, so they are written as marked points. Returns the cells used, which torches
     * must leave alone.
     */
    static Set<BlockPos> entryHall(StationPlan plan, int groundY, Map<BlockPos, BlockState> out, List<Piece.Point> points)
    {
        var used = new HashSet<BlockPos>();
        var hall = plan.room(plan.arrival());
        var i = hall.interior();
        var inward = hall.climb();
        var right = inward.getClockWise();
        int stand = groundY + 1;
        int depth = inward.getAxis() == Direction.Axis.X ? i.x1() - i.x0() + 1 : i.z1() - i.z0() + 1;
        int across = inward.getAxis() == Direction.Axis.X ? i.z1() - i.z0() + 1 : i.x1() - i.x0() + 1;
        int half = (depth - 1) / 2, wide = (across - 1) / 2;
        var middle = new BlockPos((i.x0() + i.x1()) / 2, stand, (i.z0() + i.z1()) / 2);
        for (int side : new int[]{-wide, wide})
        {
            var facing = side > 0 ? right.getOpposite() : right;
            for (int d = 2; d <= depth - 6; d++)
            {
                var at = cell(middle, inward, right, d - half, side);
                if (d % 4 != 1)
                    place(out, used, at, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, facing));
                else
                {
                    // A suit on a stand in the gap, its face to the room.
                    points.add(new Piece.Point(at, "stand", facing.getName() + " plain"));
                    used.add(at);
                    used.add(at.above());
                }
            }
            place(out, used, cell(middle, inward, right, -half, side), Blocks.POTTED_FERN.defaultBlockState());
        }
        return used;
    }

    private static BlockPos cell(BlockPos middle, Direction inward, Direction right, int along, int lateral)
    {
        return middle.relative(inward, along).relative(right, lateral);
    }

    private static boolean place(Map<BlockPos, BlockState> out, Set<BlockPos> used, BlockPos at, BlockState state)
    {
        var there = out.get(at);
        if (there == null || there.isAir())
        {
            out.put(at, state);
            used.add(at);
            return true;
        }
        return false;
    }

    /**
     * The variant of a hab room's design: a stores room follows the wing it is in (spares in the works, seed in the gardens, general elsewhere),
     * a power plant is sound or has failed with a leak by the seed, and a mine head on the deepest floor is open to the cave under the hab; the
     * rest follow the station rules.
     */
    static int variant(StationPlan plan, Room room)
    {
        switch (room.use())
        {
            case STORES:
                for (var door : plan.doors())
                {
                    String other = door.a().equals(room.id()) ? door.b() : door.b().equals(room.id()) ? door.a() : null;
                    if (other == null)
                        continue;
                    var next = plan.room(other);
                    var wing = next.use() == Use.ARRIVAL ? Wing.of(room.use()) : HabPlanner.wingOf(plan, next);
                    return wing == Wing.WORKS ? 2 : wing == Wing.GARDENS ? 1 : 0;
                }
                break;
            case MACHINE_HALL:
                return Math.floorMod(plan.spec().seed() + room.id().hashCode(), 3) == 0 ? 1 : 0;
            case MINE_HEAD:
                return room.level() == 0 ? 1 : 0;
            // A hab's operations room is its camera wall, its observation room looks on the cameras, its cargo hold is the ore store, and the deepest infirmaries are quarantine wards.
            case OPERATIONS:
            case OBSERVATION:
            case CARGO_HOLD:
                return 1;
            case INFIRMARY:
                return room.level() == 0 && Math.floorMod(plan.spec().seed() + room.id().hashCode(), 2) == 0 ? 1 : 0;
            default:
                break;
        }
        return StationRooms.variantFor(plan.spec(), room);
    }

    private HabFurnishings()
    {
    }
}
