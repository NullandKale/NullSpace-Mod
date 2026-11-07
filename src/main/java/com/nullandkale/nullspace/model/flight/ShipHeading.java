package com.nullandkale.nullspace.model.flight;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** A side-mounted controller does not define the bow. Main engine exhaust points aft. */
public final class ShipHeading {
  public static Direction fromExhausts(Collection<Direction> exhausts) {
    var counts = new EnumMap<Direction, Integer>(Direction.class);
    for (var exhaust : exhausts)
      if (exhaust.getAxis().isHorizontal()) counts.merge(exhaust.getOpposite(), 1, Integer::sum);
    Direction best = Direction.NORTH;
    int count = 0;
    // Stable ties: machinery toggles and block iteration order cannot reverse steering.
    for (var direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST))
      if (counts.getOrDefault(direction, 0) > count) {
        best = direction;
        count = counts.get(direction);
      }
    return best;
  }

  public static Direction forward(ServerLevel level, CompoundTag ship) {
    return fromExhausts(
        ShipSystems.connected(level, ship).stream()
            .filter(p -> level.getBlockState(p).is(ModBlocks.ENGINE))
            .map(p -> level.getBlockState(p).getValue(HorizontalDirectionalBlock.FACING))
            .toList());
  }

  private ShipHeading() {}
}
