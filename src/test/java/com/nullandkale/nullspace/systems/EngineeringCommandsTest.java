package com.nullandkale.nullspace.systems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EngineeringCommandsTest {
  @Test
  void informationalCommandsAreReadsButMutationsAndUnknownCommandsAreNot() {
    for (String command :
        List.of(
            "help",
            "groups",
            "profiles",
            "rooms",
            "rooms 1",
            "room here",
            "air audit",
            "devices 2",
            "doors list",
            " HELP ")) assertTrue(EngineeringCommands.readOnly(command), command);
    for (String command :
        List.of(
            "profile emergency",
            "power air 6",
            "supply off",
            "group lock delete",
            "doors all close",
            "device 12345678 off",
            "help emergency",
            "rooms 1 extra",
            "unknown",
            "")) assertFalse(EngineeringCommands.readOnly(command), command);
  }

  @Test
  void profilesCannotBeModifiedThroughReturnedArrays() {
    var profile = EngineeringCommands.profile("cruise");
    int[] requests = profile.requests(), order = profile.order();
    requests[0] = 0;
    order[0] = 5;
    assertEquals(1, EngineeringCommands.profile("cruise").requests()[0]);
    assertEquals(0, EngineeringCommands.profile("cruise").order()[0]);
    assertThrows(UnsupportedOperationException.class, () -> EngineeringCommands.profiles().clear());
  }

  @Test
  void emergencyReservesControlsAndDoorsBeforeLifeSupportUnderLowSupply() {
    var profile = EngineeringCommands.profile("emergency");
    var loads = new ArrayList<PowerBus.Load>();
    int[] requests = profile.requests();
    for (int i : profile.order())
      loads.add(new PowerBus.Load(PowerBus.System.values()[i], requests[i], 8, 8));
    var full = PowerBus.tick(8, 0, 0, 0, 0, loads);
    assertEquals(1, full.delivered().get(PowerBus.System.CONTROLS));
    assertEquals(1, full.delivered().get(PowerBus.System.DOORS));
    assertEquals(6, full.delivered().get(PowerBus.System.AIR));
    assertEquals(0, full.delivered().get(PowerBus.System.ENGINES));
    var low = PowerBus.tick(2, 0, 0, 0, 0, loads);
    assertEquals(1, low.delivered().get(PowerBus.System.CONTROLS));
    assertEquals(1, low.delivered().get(PowerBus.System.DOORS));
    assertEquals(0, low.delivered().get(PowerBus.System.AIR));
  }

  @Test
  void combatTradesPropulsionForWeaponsWithinOneGeneratorBudget() {
    var profile = EngineeringCommands.profile("COMBAT");
    var loads = new ArrayList<PowerBus.Load>();
    int[] requests = profile.requests();
    for (int i : profile.order())
      loads.add(new PowerBus.Load(PowerBus.System.values()[i], requests[i], 8, 8));
    var result = PowerBus.tick(8, 0, 0, 0, 0, loads);
    assertEquals(8, result.consumed());
    assertEquals(2, result.delivered().get(PowerBus.System.WEAPONS));
    assertEquals(1, result.delivered().get(PowerBus.System.ENGINES));
    assertEquals(0, result.discharged());
  }

  @Test
  void invalidProfilesAreRejectedWithoutChangingTheKnownPresets() {
    assertThrows(IllegalArgumentException.class, () -> EngineeringCommands.profile("unlimited"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EngineeringCommands.Profile(
                "bad", new int[] {9, 0, 0, 0, 0, 0}, new int[] {0, 1, 2, 3, 4, 5}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EngineeringCommands.Profile(
                "bad", new int[] {1, 0, 0, 0, 0, 0}, new int[] {0, 0, 2, 3, 4, 5}));
    assertEquals(3, EngineeringCommands.profiles().size());
  }
}
