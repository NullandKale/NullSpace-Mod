package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.scene.TravelVisuals;

/** Deterministic cover for a scene/dimension switch, independent of frame rate and loading time. */
public final class TransitFade {
  public static double duration(TravelPackets.Kind kind) {
    return kind == TravelPackets.Kind.BOARD ? .12 : .4;
  }

  public static double opacity(double seconds, TravelPackets.Kind kind, boolean revealing) {
    double covered = TravelVisuals.smooth(seconds / duration(kind));
    return revealing ? 1 - covered : covered;
  }

  private TransitFade() {}
}
