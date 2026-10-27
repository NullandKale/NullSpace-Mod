package com.nullandkale.nullspace.scene;

import net.minecraft.world.phys.Vec3;

/**
 * Authoritative/reference procedural shapes. Visual quality settings never change gameplay queries.
 */
public final class SpaceShapes {
  public static Vec3 rotate(Vec3 p, Vec3 axis, double angle) {
    double length = axis.length();
    var n = length > 0 ? axis.scale(1 / length) : new Vec3(0, 1, 0);
    double c = Math.cos(angle), s = Math.sin(angle);
    return p.scale(c).add(n.cross(p).scale(s)).add(n.scale(n.dot(p) * (1 - c)));
  }

  public static double distance(Vec3 point, SpaceDefinition.Body body, double time) {
    var local = rotate(point.subtract(body.at(time)), body.rotationAxis(), -body.spin() * time);
    var q = local.scale(1 / body.radius());
    double seed = Math.floorMod(body.seed(), 10000);
    double wave =
        Math.sin(q.x * 3.1 + seed)
            * Math.sin(q.y * 4.3 + seed * .37)
            * Math.sin(q.z * 3.7 - seed * .21);
    return (local.length() - body.radius() * (1 + body.roughness() * wave))
        / (1 + body.roughness() * 7);
  }

  public static double intersect(
      Vec3 origin, Vec3 direction, SpaceDefinition.Body body, double time, double limit) {
    var center = body.at(time);
    if (body.roughness() == 0) return SpaceBvh.sphere(origin, direction, center, body.radius());
    var offset = origin.subtract(center);
    double projection = offset.dot(direction),
        radius = body.radius() * (1 + body.roughness()),
        h = projection * projection - offset.lengthSqr() + radius * radius;
    if (h < 0) return Double.POSITIVE_INFINITY;
    double root = Math.sqrt(h),
        t = Math.max(.001, -projection - root),
        end = Math.min(limit, -projection + root);
    for (int step = 0; step < 160 && t <= end; step++) {
      double sd = distance(origin.add(direction.scale(t)), body, time),
          epsilon = Math.max(.001, t * .000002);
      if (Math.abs(sd) < epsilon) return t;
      t += Math.max(Math.abs(sd) * .9, epsilon * .25);
    }
    return Double.POSITIVE_INFINITY;
  }

  private SpaceShapes() {}
}
