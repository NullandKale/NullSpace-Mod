package com.nullandkale.nullspace.scene;

/** CPU references for optical accumulation and per-view history decisions. */
public final class SpaceRenderMath {
  public record Optical(double radiance, double transmittance) {
    public Optical then(double density, double extinction, double source, double length) {
      if (!Double.isFinite(density + extinction + source + length)
          || density < 0
          || extinction < 0
          || source < 0
          || length < 0) throw new IllegalArgumentException("Invalid optical sample");
      double tau = density * extinction * length, t = Math.exp(-tau);
      return new Optical(radiance + transmittance * source * (1 - t), transmittance * t);
    }
  }

  public static double sliceDistance(int slice, int count) {
    if (slice < 0 || slice >= count || count < 2)
      throw new IllegalArgumentException("Invalid slice");
    return slice == 0 ? 0 : .05 * Math.pow(1e6 / .05, slice / (double) (count - 1));
  }

  public static double sliceCoordinate(double distance, int count) {
    if (!Double.isFinite(distance) || distance < 0 || count < 2)
      throw new IllegalArgumentException("Invalid distance");
    return Math.max(
        0,
        Math.min(
            count - 1,
            Math.log(Math.max(.05, distance) / .05) / Math.log(1e6 / .05) * (count - 1)));
  }

  public static boolean history(
      double displacement,
      double directionDot,
      boolean sameSource,
      boolean sameProjection,
      boolean sameContent) {
    return sameSource
        && sameProjection
        && sameContent
        && Double.isFinite(displacement)
        && displacement < 16
        && directionDot > .85;
  }

  private SpaceRenderMath() {}
}
