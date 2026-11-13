package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Adapter for current Waypoint rooms, before the manufacturer-specific planners are built. */
public final class ExistingStationPlans
{
    public static StationSpec encounter(long seed)
    {
        var makers = Maker.values();
        return new StationSpec(makers[(int) Math.floorMod(seed >>> 32, makers.length)], StationSpec.Purpose.HARBOR, StationSpec.Size.COMPACT, StationSpec.Condition.DERELICT, seed);
    }

    public static StationBlueprint.Plan build(StationSpec spec)
    {
        if (spec.purpose() != StationSpec.Purpose.HARBOR || spec.size() != StationSpec.Size.COMPACT)
            throw new IllegalArgumentException("Existing station previews support harbor / compact; other layouts need the new station generator");
        var layout = StationLayout.generate(spec.seed());
        var plan = StationBlueprint.build(layout, spec.maker());
        var points = new ArrayList<Piece.Point>();
        // Bound supply stocking to one existing container per room, not every decorative barrel.
        for (var room : layout.rooms())
        {
            var origin = room.origin();
            plan.blocks().entrySet().stream().filter(e -> e.getKey().getX() >= origin.getX() && e.getKey().getX() < origin.getX() + StationLayout.TILE && e.getKey().getZ() >= origin.getZ() && e.getKey().getZ() < origin.getZ() + StationLayout.TILE && (e.getValue().is(Blocks.BARREL) || e.getValue().is(Blocks.CHEST))).map(Map.Entry::getKey).sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingLong(BlockPos::asLong)).findFirst().ifPresent(at -> points.add(new Piece.Point(at, "loot", switch (room.role())
            {
                case ENGINEERING -> "engineering";
                case WORKSHOP -> "workshop";
                case HABITATION -> "crew";
                default -> "cargo";
            })));
        }
        return new StationBlueprint.Plan(plan.blocks(), plan.core(), plan.docks(), points);
    }

    private ExistingStationPlans()
    {
    }
}
