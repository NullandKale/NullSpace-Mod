package com.nullandkale.nullspace.terminal;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.navigation.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ShipControlTerminalTest {
  /** Clicks the first control showing this label, wherever the layout puts it. */
  private static boolean click(ShipControlTerminal t, String label) {
    var display = t.display();
    for (int row = 0; row < TerminalBuffer.ROWS; row++) {
      int column = display.row(row).indexOf(label);
      if (column >= 0) {
        t.input(new TerminalInput.Pointer(column + 1, row));
        return true;
      }
    }
    return false;
  }

  private static boolean shows(ShipControlTerminal t, String label) {
    var display = t.display();
    for (int row = 0; row < TerminalBuffer.ROWS; row++)
      if (display.row(row).contains(label)) return true;
    return false;
  }

  @Test
  void scannerSeparatesStationsFromShipsAndClearsASelectionHiddenByAnotherCategory() {
    var original = state(true, "");
    var entries =
        List.of(
            new ShipControl.Entry(
                "station", ShipDestination.Kind.VESSEL, "Outpost", "sol_luna", "Station: 2/2 free"),
            new ShipControl.Entry(
                "ship", ShipDestination.Kind.VESSEL, "Courier", "sol_luna", "Ship: 1/1 free"));
    var panel =
        new ShipControlTerminal(
            new ShipControl.View(
                original.session(),
                BlockPos.ZERO,
                true,
                true,
                "Ship",
                "Ready",
                "",
                "None",
                "",
                "",
                entries,
                original.buttons()));
    panel.input(new TerminalInput.Pointer(12, 5));
    assertTrue(panel.display().row(7).contains("Outpost"));
    assertFalse(panel.display().row(8).contains("Courier"));
    panel.input(new TerminalInput.Pointer(3, 7));
    panel.input(new TerminalInput.Pointer(26, 5));
    assertTrue(panel.display().row(7).contains("Courier"));
    assertFalse(panel.display().row(8).contains("Outpost"));
    panel.input(new TerminalInput.Pointer(3, 22));
    assertNull(
        panel.pollAction(), "Changing category cannot set the now-hidden station destination");
    panel.input(new TerminalInput.Pointer(3, 7));
    panel.input(new TerminalInput.Pointer(3, 22));
    assertEquals("ship", panel.pollAction().target());
  }

  @Test
  void leavingLooseBlocksRequiresASeparateVisibleConfirmationAndWaitsForTheServer() {
    var original = state(false, "pad");
    var panel = new ShipControlTerminal(original);
    assertFalse(shows(panel, "Confirm leave blocks"));
    assertTrue(click(panel, "[ More ]"));
    assertTrue(click(panel, "[ Details ]"));
    assertTrue(click(panel, "[ Loose blocks ]"));
    assertEquals(ShipControl.Action.REVIEW_LOOSE_BLOCKS, panel.pollAction().action());
    assertNull(panel.pollAction());
    assertFalse(
        click(panel, "[ Confirm leave blocks ]"),
        "Confirmation is not offered before the server's review");
    assertNull(panel.pollAction());
    var reviewed =
        new ShipControl.View(
            original.session(),
            original.position(),
            false,
            false,
            original.ship(),
            original.summary(),
            original.selected(),
            original.destination(),
            original.location(),
            "3 loose blocks and their contents will stay here",
            original.entries(),
            List.of(
                new ShipControl.Button(ShipControl.Action.REVIEW_LOOSE_BLOCKS, ""),
                new ShipControl.Button(ShipControl.Action.CONFIRM_LEAVE_BLOCKS, "")),
            1);
    panel.update(reviewed);
    assertEquals(
        reviewed.message(), panel.status(), "Details mode cannot hide the leave-behind warning");
    assertTrue(click(panel, "[ Confirm leave blocks ]"));
    assertEquals(ShipControl.Action.CONFIRM_LEAVE_BLOCKS, panel.pollAction().action());
  }

  @Test
  void investigateAndResumeAreDirectActionsWithoutAnotherDestinationMenu() {
    for (var action : List.of(ShipControl.Action.INVESTIGATE, ShipControl.Action.RESUME)) {
      var original = state(false, "port");
      var buttons =
          new ArrayList<>(
              original.buttons().stream()
                  .filter(
                      b ->
                          b.action() != ShipControl.Action.INVESTIGATE
                              && b.action() != ShipControl.Action.RESUME)
                  .toList());
      buttons.add(new ShipControl.Button(action, ""));
      var view =
          new ShipControl.View(
              original.session(),
              original.position(),
              false,
              true,
              original.ship(),
              original.summary(),
              original.selected(),
              original.destination(),
              original.location(),
              "FTL journey",
              original.entries(),
              buttons);
      var panel = new ShipControlTerminal(view);
      assertTrue(
          panel
              .display()
              .row(13)
              .contains(
                  action == ShipControl.Action.RESUME ? "Resume journey" : "Investigate signal"));
      panel.input(new TerminalInput.Pointer(3, 13));
      assertEquals(action, panel.pollAction().action());
      assertFalse(panel.picker());
    }
  }

  @Test
  void portsPickerOnlySelectsDiscoveredStationsAndKeepsSelectionAcrossUpdates() {
    var original = state(false, "pad");
    var t = new ShipControlTerminal(original);
    assertTrue(click(t, "[ Change ]"));
    t.input(new TerminalInput.Pointer(60, 19));
    t.input(new TerminalInput.Pointer(3, 19));
    assertNull(t.pollAction(), "The old Find control cannot create a station");
    t.input(new TerminalInput.Pointer(3, 7));
    var next = state(false, "port");
    t.update(next);
    t.input(new TerminalInput.Pointer(3, 22));
    var selected = t.pollAction();
    assertEquals(ShipControl.Action.SELECT, selected.action());
    assertEquals("port", selected.target());
  }

  @Test
  void longFaultDetailsCanBeReadAndLiveUpdatesKeepTheScrollPosition() {
    var original = state(false, "pad");
    var next =
        new ShipControl.View(
            original.session(),
            original.position(),
            false,
            false,
            original.ship(),
            original.summary(),
            original.selected(),
            original.destination(),
            original.location(),
            "Fault details ".repeat(30) + " Location: 100, 65, -30",
            original.entries(),
            original.buttons());
    var t = new ShipControlTerminal(next);
    t.input(TerminalInput.Key.PAGE_DOWN);
    t.input(TerminalInput.Key.PAGE_DOWN);
    String lastPage = t.display().row(20) + t.display().row(21) + t.display().row(22);
    assertTrue(lastPage.contains("Location:"));
    assertTrue(lastPage.contains("100, 65, -30"));
    t.update(next);
    assertEquals(lastPage, t.display().row(20) + t.display().row(21) + t.display().row(22));
  }

  private static final UUID SESSION = UUID.randomUUID();

  @Test
  void browserGroupsSystemsAndPlanetsAndShowsEachStationOnce() {
    var original = state(true, "");
    var entries =
        List.of(
            new ShipControl.Entry(
                "terra", ShipDestination.Kind.REGION, "Sol / Terra", "sol_terra", "Orbit"),
            new ShipControl.Entry(
                "luna", ShipDestination.Kind.REGION, "Sol / Luna", "sol_luna", "Orbit"),
            new ShipControl.Entry(
                "vega", ShipDestination.Kind.REGION, "Vega / Pelagos", "vega_pelagos", "Orbit"),
            new ShipControl.Entry(
                "station", ShipDestination.Kind.VESSEL, "Outpost", "sol_luna", "Station: 2/2 free"),
            new ShipControl.Entry(
                "berth1", ShipDestination.Kind.PORT, "Outpost A", "sol_luna", "Available"),
            new ShipControl.Entry(
                "berth2", ShipDestination.Kind.PORT, "Outpost B", "sol_luna", "Available"));
    var panel =
        new ShipControlTerminal(
            new ShipControl.View(
                original.session(),
                BlockPos.ZERO,
                true,
                true,
                "Ship",
                "Ready",
                "",
                "None",
                "",
                "",
                entries,
                original.buttons(),
                0,
                "Sol / Terra",
                "",
                ""));
    assertFalse(
        panel.display().row(7).contains("Outpost"),
        "Start in the current planet, not a global port list");
    panel.input(new TerminalInput.Pointer(3, 2));
    assertTrue(panel.display().row(7).contains("Terra"));
    assertTrue(panel.display().row(8).contains("Luna"));
    panel.input(new TerminalInput.Pointer(3, 8));
    assertTrue(panel.display().row(8).contains("Outpost"));
    assertFalse(
        panel.display().row(9).contains("Outpost B"), "Ordinary browsing hides berth details");
    panel.input(new TerminalInput.Pointer(3, 8));
    panel.input(new TerminalInput.Pointer(3, 22));
    assertEquals(
        new ShipControl.Input(SESSION, ShipControl.Action.SELECT, "station"), panel.pollAction());
    assertTrue(panel.picker());
  }

  @Test
  void starMapPlotsEveryPlaceAndSelectingOneSetsItsZone() {
    var original = state(true, "");
    var entries =
        List.of(
            new ShipControl.Entry(
                "terra", ShipDestination.Kind.REGION, "Sol / Terra", "sol_terra", "Orbit"),
            new ShipControl.Entry(
                "corion", ShipDestination.Kind.REGION, "Corion / Corion I", "sys_d_1", "Orbit"),
            new ShipControl.Entry(
                "nebula",
                ShipDestination.Kind.REGION,
                "Kel Nebula / Kel Nebula",
                "feature_a",
                "Orbit"));
    var chart =
        List.of(
            new ShipControl.ChartPlace(
                "sol", "Sol", "System", 100, 100, "sol_terra", 0, 0, true, true, "G star", ""),
            new ShipControl.ChartPlace(
                "feature_a", "Kel Nebula", "Nebula", 300, 100, "feature_a", 18.2, 2.82, true,
                false, "Nebula | stepping stone", ""),
            new ShipControl.ChartPlace(
                "sys_d", "Corion", "System", 800, 900, "sys_d_1", 61.0, 9.4, false, false,
                "A star | 7 planets, 2 moons, 2 landable", "3 hops via Kel Nebula, Tal Drift"));
    var panel =
        new ShipControlTerminal(
            new ShipControl.View(
                original.session(), BlockPos.ZERO, true, true, "Ship", "Ready", "", "None", "",
                "", entries, original.buttons(), 0, "Sol / Terra", "", "", chart, 25));
    // Back out of the current planet to the top level: the star map.
    panel.input(new TerminalInput.Pointer(3, 2));
    panel.input(new TerminalInput.Pointer(3, 2));
    var screen = new StringBuilder();
    for (int y = 0; y < 25; y++) screen.append(panel.display().row(y)).append('\n');
    assertTrue(screen.toString().contains("STAR MAP"), screen.toString());
    assertTrue(screen.toString().contains("engine range 25 ly"));
    assertTrue(panel.display().row(20).contains("# black hole"), "Legend fits on screen");
    assertTrue(panel.display().row(8).contains("@ Sol"), "You are marked at Sol");
    assertTrue(panel.display().row(8).contains("~ Kel Nebula"));
    assertTrue(panel.display().row(16).contains("* Corion"));
    int nebula = panel.display().row(8).indexOf("~ Kel Nebula");
    panel.input(new TerminalInput.Pointer(nebula, 8));
    assertTrue(panel.display().row(19).contains("18.2 ly | jump 2.82 fuel | in range"));
    panel.input(new TerminalInput.Pointer(3, 22));
    assertEquals(
        new ShipControl.Input(SESSION, ShipControl.Action.SELECT, "nebula"), panel.pollAction());
    panel.update(panel.state());
    int corion = panel.display().row(16).indexOf("* Corion");
    panel.input(new TerminalInput.Pointer(corion, 16));
    assertTrue(
        panel.display().row(19).contains("61.0 ly | 9.40 fuel total | 3 hops via Kel Nebula, Tal Drift"),
        panel.display().row(19));
    panel.input(new TerminalInput.Pointer(corion, 16));
    assertTrue(
        panel.display().row(7).contains("Corion I"), "Selecting a system again opens it");
  }

  private static ShipControl.View state(boolean scanner, String selected) {
    var entries =
        List.of(
            new ShipControl.Entry(
                "region", ShipDestination.Kind.REGION, "Sol / Terra", "sol_terra", "Orbit"),
            new ShipControl.Entry("pad", ShipDestination.Kind.PAD, "My yard", "sol_terra", "READY"),
            new ShipControl.Entry(
                "port", ShipDestination.Kind.PORT, "Waypoint berth", "sol_luna", "Available"));
    var buttons =
        Arrays.stream(ShipControl.Action.values())
            .filter(
                a ->
                    a != ShipControl.Action.CONFIRM_LEAVE_BLOCKS
                        && a != ShipControl.Action.MARK
                        && a != ShipControl.Action.RETURN_TO_CONTROLLER)
            .map(
                a ->
                    new ShipControl.Button(
                        a,
                        a == ShipControl.Action.LAND
                            ? "Jump to the destination's region first"
                            : ""))
            .toList();
    return new ShipControl.View(
        SESSION,
        BlockPos.ZERO,
        scanner,
        true,
        "Ship",
        "Power: online",
        selected,
        "Waypoint berth",
        "Sol / Luna | port",
        "Ready",
        entries,
        buttons);
  }

  @Test
  void engageIsPrimaryAndManualStepsRemainAvailable() {
    var t = new ShipControlTerminal(state(false, "port"));
    assertTrue(t.display().row(10).contains("Engage"));
    assertFalse(t.display().row(9).contains("Jump"));
    assertTrue(click(t, "[ Manual steps ]"));
    String[] labels = {"Launch", "Jump", "Land", "Dock", "Undock"};
    for (int i = 0; i < labels.length; i++) assertTrue(t.display().row(8 + i).contains(labels[i]));
    assertTrue(
        t.display().row(10).contains("Jump to the destination"),
        "Blocker is readable before clicking");
    t.input(new TerminalInput.Pointer(3, 8));
    assertEquals(ShipControl.Action.LAUNCH, t.pollAction().action());
    t.input(new TerminalInput.Pointer(3, 8));
    assertNull(t.pollAction(), "Pending movement cannot be submitted twice");
  }

  @Test
  void disabledTravelRequestsAnExplanationWithoutRequestingMovement() {
    var t = new ShipControlTerminal(state(false, "port"));
    assertTrue(click(t, "[ Manual steps ]"));
    t.input(new TerminalInput.Pointer(3, 10));
    assertEquals(
        new ShipControl.Input(SESSION, ShipControl.Action.EXPLAIN, "LAND"), t.pollAction());
    assertTrue(t.status().contains("Jump to the destination"));
    assertNull(t.pollAction());
  }

  @Test
  void selectingAPortSetsOneDestinationAndDoesNotMoveTheShip() {
    var t = new ShipControlTerminal(state(false, "pad"));
    assertTrue(click(t, "[ Change ]"));
    t.input(new TerminalInput.Text("Waypoint"));
    t.input(new TerminalInput.Pointer(3, 7));
    assertNull(t.pollAction());
    t.input(new TerminalInput.Pointer(3, 22));
    assertEquals(new ShipControl.Input(SESSION, ShipControl.Action.SELECT, "port"), t.pollAction());
    assertNull(t.pollAction());
    assertTrue(t.picker(), "Wait for the server to save the destination");
    var accepted = state(false, "port");
    t.update(
        new ShipControl.View(
            accepted.session(),
            accepted.position(),
            false,
            false,
            accepted.ship(),
            accepted.summary(),
            accepted.selected(),
            accepted.destination(),
            accepted.location(),
            "Destination set",
            accepted.entries(),
            accepted.buttons(),
            1));
    assertFalse(t.picker());
  }

  @Test
  void liveUpdatesPreserveSearchAndDoNotReopenThePicker() {
    var t = new ShipControlTerminal(state(false, "pad"));
    assertTrue(click(t, "[ Change ]"));
    t.input(new TerminalInput.Text("Waypoint"));
    t.update(state(false, "port"));
    assertTrue(t.display().row(3).contains("Waypoint"));
    assertTrue(t.display().row(7).contains("Waypoint berth"));
    t.input(TerminalInput.Key.ESCAPE);
    t.update(state(false, "port"));
    assertFalse(t.picker());
  }

  @Test
  void scannerSetsDestinationWithoutStartingMovement() {
    var t = new ShipControlTerminal(state(true, "pad"));
    assertTrue(t.picker());
    assertFalse(t.display().row(8).contains("[ Jump ]"));
    t.input(new TerminalInput.Pointer(3, 9));
    t.input(new TerminalInput.Pointer(3, 22));
    assertEquals(new ShipControl.Input(SESSION, ShipControl.Action.SELECT, "port"), t.pollAction());
    assertTrue(t.picker());
  }

  @Test
  void paginationAndEmptyFiltersRemainUsable() {
    var t = new ShipControlTerminal(state(true, "pad"));
    t.input(new TerminalInput.Text("does not exist"));
    for (int i = 0; i < 40; i++) {
      t.input(TerminalInput.Key.DOWN);
      t.input(TerminalInput.Key.PAGE_DOWN);
      t.display();
    }
    assertTrue(t.display().row(8).contains("No matching destinations"));
    assertNull(t.pollAction());
  }

  @Test
  void savedDestinationKeepsPortIdentityAcrossRenames() {
    var a =
        new ShipDestination(
            ShipDestination.Kind.PORT,
            "vessel",
            new BlockPos(1, 2, 3),
            "sol_terra",
            "First name",
            "Available");
    var b =
        new ShipDestination(
            ShipDestination.Kind.PORT,
            "vessel",
            new BlockPos(1, 2, 3),
            "sol_terra",
            "Renamed station",
            "Unavailable");
    assertEquals(a.id(), b.id());
    var ship = new net.minecraft.nbt.CompoundTag();
    ship.put("destination", a.save());
    assertEquals(a.id(), ShipDestination.selected(ship).id());
    assertEquals(a.region(), ShipDestination.selected(ship).region());
  }

  @Test
  void liveReadingCannotHideARequestThatIsStillWaitingForAcknowledgment() {
    var original = state(false, "pad");
    var panel = new ShipControlTerminal(original);
    panel.input(new TerminalInput.Pointer(3, 10));
    panel.pollAction();
    panel.update(
        new ShipControl.View(
            original.session(),
            original.position(),
            false,
            false,
            original.ship(),
            "Power changed",
            original.selected(),
            original.destination(),
            original.location(),
            "New reading",
            original.entries(),
            original.buttons(),
            0));
    assertTrue(panel.status().contains("Waiting"));
    panel.input(new TerminalInput.Pointer(3, 10));
    assertNull(panel.pollAction(), "A live reading is not an action acknowledgment");
  }

  @Test
  void controllerSeparatesLocationDestinationAndTripWithoutDuplicatingThePlanet() {
    var original = state(false, "region");
    var panel =
        new ShipControlTerminal(
            new ShipControl.View(
                SESSION,
                BlockPos.ZERO,
                false,
                true,
                "Ship",
                "Fuel: loaded",
                "region",
                "Sol / Luna",
                "Sol / Luna | Planet/moon | Orbit",
                "Ready",
                original.entries(),
                original.buttons(),
                0,
                "Sol / Terra / Orbit",
                "",
                "Trip: Local travel -> Hold in orbit"));
    var display = panel.display();
    assertTrue(display.row(2).contains("Here") && display.row(2).contains("Terra"));
    assertTrue(display.row(5).contains("Going to") && display.row(5).contains("Sol / Luna"));
    assertFalse(display.row(6).contains("Sol / Luna"));
    assertTrue(display.row(8).contains("Local travel"));
    assertNotEquals(display.cell(20, 2).foreground(), display.cell(2, 5).foreground());
    assertFalse(display.row(18).contains("Build station"));
    assertTrue(click(panel, "[ Manual steps ]"));
    assertTrue(panel.display().row(8).contains("Launch"));
    assertTrue(click(panel, "[ Trip ]"));
    assertTrue(panel.display().row(10).contains("Engage"));
    panel.input(new TerminalInput.Pointer(3, 5));
    assertEquals(' ', panel.display().cell(64, 24).glyph(), "Footer leaves space before Close");
    assertTrue(panel.display().row(24).contains("[ Close ]"));
  }
}
