package com.nullandkale.nullspace.model.docking;

import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.space.ScenePose;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Port mating and bounded route planning, independent of world reads. */
public final class DockingMotion {
  public record Obstacle(Vec3 center, double radius) {}

  public static Vec3 normal(Direction d) {
    return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
  }

  public static ScenePose mate(
      Vec3 sourcePort,
      Direction sourceFacing,
      ScenePose target,
      Vec3 targetPort,
      Direction targetFacing,
      double gap) {
    var desired = normal(targetFacing).scale(-1);
    var original = normal(sourceFacing);
    double angle = Math.atan2(desired.x, desired.z) - Math.atan2(original.x, original.z);
    var q = target.orientation().rotateY(angle);
    var outward = FlightDynamics.rotate(target.orientation(), normal(targetFacing));
    return new ScenePose(
        target
            .toUniverse(targetPort)
            .add(outward.scale(gap))
            .subtract(FlightDynamics.rotate(q, sourcePort)),
        q);
  }

  public static boolean clear(Vec3 a, Vec3 b, List<Obstacle> obstacles, double radius) {
    var delta = b.subtract(a);
    double length = delta.lengthSqr();
    for (var obstacle : obstacles) {
      double bound = obstacle.radius() + radius;
      var offset = a.subtract(obstacle.center());
      // Final docking deliberately enters conservative margins. A cancelled approach must
      // be able to retreat out of that margin; the caller also checks real hull occupancy.
      if (offset.lengthSqr() < bound * bound) {
        if (length < 1e-12
            || delta.dot(offset) < -1e-8
            || b.distanceToSqr(obstacle.center()) <= offset.lengthSqr() + 1e-8) return false;
        continue;
      }
      double t =
          length == 0 ? 0 : Math.clamp(obstacle.center().subtract(a).dot(delta) / length, 0, 1);
      if (a.add(delta.scale(t)).distanceToSqr(obstacle.center()) < bound * bound) return false;
    }
    return b.length() < FlightDynamics.LOCAL_RADIUS;
  }

  private record Cell(int x, int y, int z) {}

  private record Candidate(Cell cell, double score) {}

  public static List<Vec3> route(Vec3 start, Vec3 goal, List<Obstacle> obstacles) {
    if (clear(start, goal, obstacles, 29)) return List.of(goal);
    final double step = 64;
    var open = new PriorityQueue<Candidate>(Comparator.comparingDouble(Candidate::score));
    var costs = new HashMap<Cell, Double>();
    var parents = new HashMap<Cell, Cell>();
    var origin = new Cell(0, 0, 0);
    costs.put(origin, 0.);
    open.add(new Candidate(origin, start.distanceTo(goal)));
    Cell found = null;
    for (int attempts = 0; attempts < 4096 && !open.isEmpty(); attempts++) {
      var cell = open.remove().cell();
      var point = start.add(cell.x() * step, cell.y() * step, cell.z() * step);
      if (point.distanceTo(goal) < 128 && clear(point, goal, obstacles, 29)) {
        found = cell;
        break;
      }
      for (var d : Direction.values()) {
        var next =
            new Cell(cell.x() + d.getStepX(), cell.y() + d.getStepY(), cell.z() + d.getStepZ());
        var end = start.add(next.x() * step, next.y() * step, next.z() * step);
        double cost = costs.get(cell) + step;
        if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)
            || !clear(point, end, obstacles, 29)) continue;
        costs.put(next, cost);
        parents.put(next, cell);
        open.add(new Candidate(next, cost + end.distanceTo(goal)));
      }
    }
    if (found == null)
      throw new IllegalArgumentException(
          "No safe automatic approach was found; the berth is obstructed");
    var out = new ArrayList<Vec3>();
    out.add(goal);
    for (var at = found; !at.equals(origin); at = parents.get(at))
      out.add(start.add(at.x() * step, at.y() * step, at.z() * step));
    Collections.reverse(out);
    return List.copyOf(out);
  }

  public static ScenePose step(ScenePose old, ScenePose goal, double speed, double radians) {
    var delta = goal.position().subtract(old.position());
    var q = old.orientation();
    double angle = 2 * Math.acos(Math.min(1, Math.abs(q.dot(goal.orientation()))));
    q.slerp(goal.orientation(), angle < 1e-8 ? 1 : Math.min(1, radians / angle));
    return new ScenePose(
        old.position().add(delta.length() <= speed ? delta : delta.normalize().scale(speed)), q);
  }

  private DockingMotion() {}
}
