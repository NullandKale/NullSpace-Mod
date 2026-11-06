package com.nullandkale.nullspace.scene;

/** Shared timing for the FTL envelope. The visual never changes journey progress or fuel. */
public final class TravelVisuals {
  public static double smooth(double value) {
    double t = Math.clamp(value, 0, 1);
    return t * t * (3 - 2 * t);
  }

  public static double streaks(double elapsed, double duration) {
    if (!Double.isFinite(elapsed) || !Double.isFinite(duration) || duration <= 0) return 0;
    return smooth(elapsed / 2) * smooth((duration - elapsed) / 2);
  }

  private TravelVisuals() {}
}
