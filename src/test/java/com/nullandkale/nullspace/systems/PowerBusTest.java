package com.nullandkale.nullspace.systems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PowerBusTest {
  @Test
  void conservesEnergyAcrossBrownoutsAndFullBatteries() {
    var random = new Random(41);
    for (int n = 0; n < 5000; n++) {
      int energy = random.nextInt(1201), generation = random.nextInt(15);
      var result =
          PowerBus.tick(
              generation,
              energy,
              1200,
              4,
              4,
              List.of(
                  new PowerBus.Load(PowerBus.System.AIR, random.nextInt(9), 8, random.nextInt(9)),
                  new PowerBus.Load(PowerBus.System.ENGINES, 8, 8, 8)));
      assertEquals(
          result.generated() + result.discharged(),
          result.consumed() + result.charged() + result.curtailed());
      assertEquals(energy - result.discharged() + result.charged(), result.energy());
      assertTrue(result.energy() >= 0 && result.energy() <= 1200);
      assertFalse(result.charged() > 0 && result.discharged() > 0);
    }
  }

  @Test
  void idleReservationsDoNotStarveWorkingEquipment() {
    var result =
        PowerBus.tick(
            8,
            0,
            1200,
            4,
            4,
            List.of(
                new PowerBus.Load(PowerBus.System.DOORS, 8, 8, 0),
                new PowerBus.Load(PowerBus.System.AIR, 2, 2, 2)));
    assertEquals(8, result.delivered().get(PowerBus.System.DOORS));
    assertEquals(2, result.delivered().get(PowerBus.System.AIR));
    assertEquals(2, result.consumed());
    assertEquals(4, result.charged());
  }

  @Test
  void occupiedExplorerKeepsSensorsPoweredAndLimitsThrustToItsActualSupply() {
    for (int thrust : new int[] {0, 3}) {
      var result =
          PowerBus.tick(
              16,
              0,
              2400,
              8,
              8,
              List.of(
                  new PowerBus.Load(PowerBus.System.CONTROLS, 1, 1, 1),
                  new PowerBus.Load(PowerBus.System.AIR, 12, 12, 12),
                  new PowerBus.Load(PowerBus.System.DOORS, 1, 1, 0),
                  new PowerBus.Load(PowerBus.System.CAMERAS, 2, 2, 2),
                  new PowerBus.Load(PowerBus.System.ENGINES, 3, 6, thrust)));
      assertEquals(2, result.delivered().get(PowerBus.System.CAMERAS));
      assertEquals(1, result.delivered().get(PowerBus.System.ENGINES));
      assertEquals(15 + Math.min(thrust, 1), result.consumed());
      assertEquals(16 - result.consumed(), result.charged());
      assertEquals(0, result.discharged());
    }
  }

  @Test
  void priorityAndStoredEnergyBoundDeliveredPower() {
    var result =
        PowerBus.tick(
            0,
            1,
            1200,
            4,
            4,
            List.of(
                new PowerBus.Load(PowerBus.System.AIR, 2, 2, 2),
                new PowerBus.Load(PowerBus.System.ENGINES, 3, 3, 3)));
    assertEquals(1, result.delivered().get(PowerBus.System.AIR));
    assertEquals(0, result.delivered().get(PowerBus.System.ENGINES));
    assertEquals(0, result.energy());
  }
}
