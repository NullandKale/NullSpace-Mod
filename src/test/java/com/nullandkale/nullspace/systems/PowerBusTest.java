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
  void reservesRequestedCapacityButChargesUnusedOutput() {
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
    assertEquals(0, result.delivered().get(PowerBus.System.AIR));
    assertEquals(0, result.consumed());
    assertEquals(4, result.charged());
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
