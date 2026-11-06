package com.nullandkale.nullspace.scene;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TravelVisualsTest {
  @Test
  void ftlEasesInAndOutInsteadOfPopping() {
    assertEquals(0, TravelVisuals.streaks(0, 45));
    assertEquals(.5, TravelVisuals.streaks(1, 45), 1e-9);
    assertEquals(1, TravelVisuals.streaks(2, 45));
    assertEquals(1, TravelVisuals.streaks(22, 45));
    assertEquals(.5, TravelVisuals.streaks(44, 45), 1e-9);
    assertEquals(0, TravelVisuals.streaks(45, 45));
    assertEquals(0, TravelVisuals.streaks(Double.NaN, 45));
  }
}
