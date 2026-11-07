package com.nullandkale.nullspace.model.space;

import net.minecraft.world.phys.Vec3;

public final class SpaceRay {
  public static double[] box(Vec3 origin, Vec3 direction, Vec3 max, double range) {
    double entry = 0, exit = range;
    double[] o = {origin.x, origin.y, origin.z},
        d = {direction.x, direction.y, direction.z},
        m = {max.x, max.y, max.z};
    for (int a = 0; a < 3; a++) {
      if (Math.abs(d[a]) < 1e-12) {
        if (o[a] < 0 || o[a] > m[a]) return null;
        continue;
      }
      double lo = -o[a] / d[a], hi = (m[a] - o[a]) / d[a];
      entry = Math.max(entry, Math.min(lo, hi));
      exit = Math.min(exit, Math.max(lo, hi));
      if (entry > exit) return null;
    }
    return new double[] {entry, exit};
  }

  public static double sphere(Vec3 origin, Vec3 direction, Vec3 center, double radius) {
    var delta = origin.subtract(center);
    double b = delta.dot(direction), c = delta.lengthSqr() - radius * radius, h = b * b - c;
    if (h < 0) return Double.POSITIVE_INFINITY;
    double t = -b - Math.sqrt(h);
    return t >= 0 ? t : c <= 0 ? 0 : Double.POSITIVE_INFINITY;
  }

  private SpaceRay() {}
}
