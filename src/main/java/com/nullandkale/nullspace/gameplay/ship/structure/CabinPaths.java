package com.nullandkale.nullspace.gameplay.ship.structure;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bounded standing-player traversal; collision geometry handles full blocks, stairs and slabs.
 * Half-block nodes allow steps; one-block rises require jump headroom. Ladders connect decks and
 * roof hatches; swimming is not a walking route.
 */
public final class CabinPaths {
  private record Node(int x, int halfY, int z) {
    double y() {
      return halfY / 2.0;
    }
  }

  public static boolean reachesDoor(
      ServerLevel level, BlockPos start, HullScan.Pressure pressure, Set<BlockPos> captured) {
    return search(level, start, pressure, captured, null);
  }

  /** Player-sized route between standing positions, including stairs between decks. */
  public static boolean reaches(
      ServerLevel level, BlockPos start, BlockPos destination, HullScan.Pressure pressure) {
    return search(level, start, pressure, Set.of(), destination);
  }

  private static boolean search(
      ServerLevel level,
      BlockPos start,
      HullScan.Pressure pressure,
      Set<BlockPos> captured,
      BlockPos destination) {
    var hatches =
        captured.stream()
            .filter(
                p ->
                    level.getBlockState(p).getBlock() instanceof TrapDoorBlock
                        && ShipDoors.sealed(level, p))
            .toList();
    var queue = new ArrayDeque<Node>();
    var visited = new HashSet<Node>();
    var first = new Node(start.getX(), start.getY() * 2, start.getZ());
    if (!standing(level, first, pressure)) return false;
    queue.add(first);
    while (!queue.isEmpty()) {
      var p = queue.removeFirst();
      if (!visited.add(p)) continue;
      if (destination != null
          && p.x == destination.getX()
          && p.z == destination.getZ()
          && p.halfY == destination.getY() * 2) return true;
      if (destination == null && hatches.stream().anyMatch(h -> reachesHatch(level, p, h)))
        return true;
      // Ladders connect standing routes between decks and to ceiling hatches.
      for (int rise : new int[] {-2, -1, 1, 2}) {
        var next = new Node(p.x, p.halfY + rise, p.z);
        if (visited.contains(next)
            || !climbable(level, p) && !climbable(level, next)
            || !standing(level, next, pressure)) continue;
        boolean clear = true;
        for (int step = 0; step <= 4; step++) {
          double y = p.y() + (next.y() - p.y()) * step / 4.0;
          if (!clear(level, body(p.x + .5, y, p.z + .5))
              || !pressure.breathable(BlockPos.containing(p.x + .5, y + 1.6, p.z + .5))) {
            clear = false;
            break;
          }
        }
        if (clear) queue.add(next);
      }
      for (Direction d : Direction.Plane.HORIZONTAL) {
        int x = p.x + d.getStepX(), z = p.z + d.getStepZ();
        var at = new BlockPos(x, (int) Math.floor(p.y()), z);
        var state = level.getBlockState(at);
        if (captured.contains(at)
            && state.getBlock() instanceof DoorBlock
            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
            && Math.abs(p.y() - at.getY()) < .01) return true;
        for (int half = p.halfY - 2; half <= p.halfY + 2; half++) {
          var next = new Node(x, half, z);
          if (visited.contains(next) || !standing(level, next, pressure)) continue;
          double travelY = Math.max(p.y(), next.y());
          boolean clear = true;
          // Raising before horizontal movement models a jump/step; falling happens after it.
          for (int step = 0; step <= 4; step++) {
            double t = step / 4.0;
            var box = body(p.x + .5 + (x - p.x) * t, travelY, p.z + .5 + (z - p.z) * t);
            if (!clear(level, box)
                || !pressure.breathable(
                    BlockPos.containing(
                        (box.minX + box.maxX) / 2, travelY + 1.6, (box.minZ + box.maxZ) / 2))) {
              clear = false;
              break;
            }
          }
          if (clear) queue.add(next);
        }
      }
    }
    return false;
  }

  private static boolean climbable(ServerLevel level, Node p) {
    return level.getBlockState(BlockPos.containing(p.x, p.y(), p.z)).is(BlockTags.CLIMBABLE)
        || level.getBlockState(BlockPos.containing(p.x, p.y() + 1, p.z)).is(BlockTags.CLIMBABLE);
  }

  private static boolean reachesHatch(ServerLevel level, Node p, BlockPos hatch) {
    double feet = p.y();
    var eye = new Vec3(p.x + .5, feet + 1.6, p.z + .5);
    var target = Vec3.atCenterOf(hatch);
    if (Math.abs(target.x - eye.x) > 1.01
        || Math.abs(target.z - eye.z) > 1.01
        || eye.distanceToSqr(target) > 2.8 * 2.8) return false;
    // A floor hatch can be stepped through; a high roof hatch needs a climbable route.
    if (hatch.getY() > feet + 1.25 && !climbable(level, p)) return false;
    var hit =
        level.clip(
            new ClipContext(
                eye,
                target,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
    return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(hatch);
  }

  private static AABB body(double x, double y, double z) {
    return new AABB(x - .299, y + .001, z - .299, x + .299, y + 1.799, z + .299);
  }

  private static boolean clear(ServerLevel level, AABB box) {
    for (var shape : level.getBlockCollisions(null, box)) if (!shape.isEmpty()) return false;
    return true;
  }

  private static boolean standing(ServerLevel level, Node p, HullScan.Pressure pressure) {
    if (!pressure.bounds().contains(BlockPos.containing(p.x, p.y(), p.z))
        || !pressure.breathable(BlockPos.containing(p.x, p.y() + 1.6, p.z))) return false;
    var box = body(p.x + .5, p.y(), p.z + .5);
    if (!clear(level, box)) return false;
    // Contact with a collision surface, not a block-id approximation.
    return climbable(level, p)
        || !clear(
            level, new AABB(box.minX, p.y() - .025, box.minZ, box.maxX, p.y() + .0001, box.maxZ));
  }

  private CabinPaths() {}
}
