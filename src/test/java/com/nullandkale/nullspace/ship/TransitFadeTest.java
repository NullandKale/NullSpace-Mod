package com.nullandkale.nullspace.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TransitFadeTest {
  @Test
  void sceneSwitchIsCoveredAndRevealedMonotonically() {
    for (var kind : TravelPackets.Kind.values()) {
      assertEquals(0, TransitFade.opacity(0, kind, false));
      assertEquals(1, TransitFade.opacity(0, kind, true));
      double previous = 0;
      for (int step = 0; step < 100; step++) {
        double time = step / 100.0;
        double opacity = TransitFade.opacity(time, kind, false);
        assertTrue(opacity >= previous && opacity <= 1);
        assertEquals(1 - opacity, TransitFade.opacity(time, kind, true), 1e-9);
        previous = opacity;
      }
      assertEquals(1, TransitFade.opacity(30, kind, false));
      assertEquals(0, TransitFade.opacity(30, kind, true));
    }
    assertTrue(
        TransitFade.duration(TravelPackets.Kind.BOARD)
            < TransitFade.duration(TravelPackets.Kind.JUMP));
  }
}
