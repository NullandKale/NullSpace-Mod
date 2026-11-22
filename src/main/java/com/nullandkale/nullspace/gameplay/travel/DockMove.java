package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.nbt.CompoundTag;

/**
 * A hull moved within space for docking: joining a visitor to its berth, so the two hulls stand side by side and the crew walk between them, or separating
 * it into an area of its own again before it backs away. It runs as an ordinary journaled ship move, with the crew aboard moved with it behind a short fade.
 *
 * @param operation the journal's name for the move
 * @param place where the scanned hull goes; throws {@link IllegalArgumentException} with the reason when it cannot
 * @param after changes the moved ship's saved record, given the transform its blocks moved by
 * @param failed told why, when the move is refused or fails
 */
public record DockMove(String operation, Function<HullScan.Result, Placement> place, BiConsumer<CompoundTag, ShipTransform> after, Consumer<String> failed)
{
    /** Where the hull goes: how its blocks move, and the size of its area there. */
    public record Placement(ShipTransform transform, int width, int depth, int height)
    {}
}
