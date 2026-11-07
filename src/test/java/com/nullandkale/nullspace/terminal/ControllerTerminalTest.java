package com.nullandkale.nullspace.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ControllerTerminalTest {
  @Test
  void legacyNavigationUsesTheServerChartAndPaginatesBeyondTheOriginalPlanets() {
    var terminal = new ControllerTerminal("navigation", "Ready", "sol_terra");
    var routes =
        java.util.stream.IntStream.range(0, 17)
            .mapToObj(
                i -> new ComputerTerminal.Destination("route_" + i, "Saved planet " + i, "READY"))
            .toList();
    terminal.catalog(routes);
    for (int i = 0; i < 8; i++) terminal.input(TerminalInput.Key.DOWN);
    assertTrue(terminal.display().row(14).contains("Saved planet 8"));
    terminal.input(new TerminalInput.Pointer(8, 14));
    assertEquals(new ControllerTerminal.Action("fast_travel", "route_8"), terminal.pollAction());
    assertFalse(terminal.display().row(23).isBlank());
  }

  @Test
  void padPreviewsAreIndependentActionsAndRetainTheNameEditor() {
    var terminal = new ControllerTerminal("pad", "Ready", "My pad");
    for (var entry : java.util.Map.of(15, "preview_pad", 16, "preview_ship").entrySet()) {
      terminal.input(new TerminalInput.Pointer(10, entry.getKey()));
      assertEquals(entry.getValue(), terminal.pollAction().verb());
      terminal.update("Preview ON");
      assertEquals("My pad", terminal.fieldValue());
    }
    assertEquals(1, terminal.field());
  }

  @Test
  void helmFlightAndNavigationActionsAreVisibleWithoutOverlappingFooter() {
    var t = new ControllerTerminal("ship", "Ready", "");
    String[] verbs = {"pilot", "chase", "navigation"};
    for (int i = 0; i < 3; i++) {
      assertFalse(t.display().row(19 + i).isBlank());
      t.input(new TerminalInput.Pointer(10, 19 + i));
      assertEquals(verbs[i], t.pollAction().verb());
      t.update("Ready");
    }
  }

  @Test
  void navigationIsAKeyboardMenuWithStableRouteIds() {
    var t = new ControllerTerminal("navigation", "Ready", "sol_terra");
    assertEquals(-1, t.field());
    t.input(TerminalInput.Key.DOWN);
    t.input(TerminalInput.Key.ENTER);
    assertEquals(new ControllerTerminal.Action("fast_travel", "sol_luna"), t.pollAction());
    t.update("Ready");
    for (int i = 0; i < 2; i++) t.input(TerminalInput.Key.DOWN);
    t.input(TerminalInput.Key.ENTER);
    assertEquals(new ControllerTerminal.Action("helm", ""), t.pollAction());
  }

  @Test
  void everyEquipmentMenuHasWorkingKeyboardActionsAndNoEditableRevisionField() {
    for (String mode : EquipmentMenus.MODES) {
      var terminal = new ControllerTerminal(mode, "CONNECTED\nSwitch ON\nCharge 1200", "42");
      assertEquals(-1, terminal.field());
      terminal.input(new TerminalInput.Text("garbage"));
      assertEquals(0, terminal.selection());
      assertEquals("", terminal.fieldValue());
      for (var entry : EquipmentMenus.entries(mode)) {
        if (entry.verb().equals("device_allocate")) {
          terminal.input(TerminalInput.Key.RIGHT);
          assertEquals(
              new ControllerTerminal.Action("device_allocate", "1"), terminal.pollAction());
        } else {
          terminal.input(TerminalInput.Key.ENTER);
          assertEquals(new ControllerTerminal.Action(entry.verb(), ""), terminal.pollAction());
        }
        terminal.update("Reply");
        terminal.input(TerminalInput.Key.DOWN);
      }
      assertEquals(0, terminal.selection());
      assertFalse(terminal.display().cursorVisible());
    }
  }

  @Test
  void equipmentReadingsUseSeparateLinesAndScrollWithoutHidingActions() {
    var terminal =
        new ControllerTerminal(
            "battery",
            java.util.stream.IntStream.range(0, 15)
                .mapToObj(n -> "Line " + n)
                .collect(java.util.stream.Collectors.joining("\n")),
            "");
    assertTrue(terminal.display().row(4).contains("Line 0"));
    assertTrue(terminal.display().row(9).contains("Line 5"));
    terminal.input(TerminalInput.Key.PAGE_DOWN);
    assertTrue(terminal.display().row(4).contains("Line 4"));
    assertTrue(terminal.display().row(12).contains("refresh"));
    terminal.input(new TerminalInput.Pointer(12, 15));
    assertEquals(new ControllerTerminal.Action("device_reset", ""), terminal.pollAction());
  }

  @Test
  void equipmentControlsAreSpecificToTheHardware() {
    assertTrue(
        EquipmentMenus.entries("generator").stream()
            .anyMatch(e -> e.verb().equals("device_reset")));
    assertFalse(
        EquipmentMenus.entries("engine").stream().anyMatch(e -> e.verb().equals("device_reset")));
    for (String mode : EquipmentMenus.MODES)
      assertTrue(
          EquipmentMenus.entries(mode).stream()
              .noneMatch(
                  e ->
                      java.util.Set.of("fire", "launch", "upgrade", "configure")
                          .contains(e.verb())));
  }

  @Test
  void liveReadingsPreserveEditingScrollAndStaticDiagnosticPages() {
    var terminal = new ControllerTerminal("systems", "A".repeat(304) + "B".repeat(456), "");
    terminal.input(new TerminalInput.Text("power air 2"));
    terminal.input(TerminalInput.Key.PAGE_DOWN);
    terminal.refresh("C".repeat(304) + "D".repeat(456));
    assertEquals("power air 2", terminal.fieldValue());
    assertEquals('D', terminal.display().cell(2, 6).glyph());
    terminal.input(TerminalInput.Key.SELECT_ALL);
    terminal.input(new TerminalInput.Text("help"));
    terminal.input(TerminalInput.Key.ENTER);
    terminal.input(TerminalInput.Key.ENTER);
    terminal.update("Help page");
    for (int n = 0; n < 40; n++) terminal.tick();
    terminal.refresh("New live state");
    assertEquals("Help page", terminal.status());
    terminal.input(new TerminalInput.Pointer(10, 12));
    terminal.update("Refreshed overview");
    for (int n = 0; n < 40; n++) terminal.tick();
    terminal.refresh("Generation 0");
    assertEquals("Generation 0", terminal.status());
  }

  @Test
  void engineeringCommandsUseTheSameEditableTerminalCells() {
    var terminal = new ControllerTerminal("systems", "Ready", "17");
    terminal.input(new TerminalInput.Text("power cameras 2"));
    assertEquals(8, terminal.selection());
    assertEquals(20, terminal.display().cursorRow());
    terminal.input(TerminalInput.Key.ENTER);
    terminal.input(TerminalInput.Key.ENTER);
    assertEquals(
        new ControllerTerminal.Action("configure", "power cameras 2"), terminal.pollAction());
    terminal.update("CAMERAS requested 2");
    assertEquals("power cameras 2", terminal.fieldValue());
  }

  @Test
  void typingFromInitialSelectionFocusesNameAndRegisters() {
    var p = new ControllerTerminal(false, "Ready", "");
    p.input(new TerminalInput.Text("Plot 1"));
    assertEquals(1, p.selection());
    p.input(TerminalInput.Key.ENTER);
    p.input(TerminalInput.Key.ENTER);
    assertEquals(new ControllerTerminal.Action("register", "Plot 1"), p.pollAction());
  }

  @Test
  void supportsSelectionPasteCursorAndDeletion() {
    var p = new ControllerTerminal(false, "", "Old");
    p.input(TerminalInput.Key.DOWN);
    p.input(TerminalInput.Key.SELECT_ALL);
    p.input(new TerminalInput.Text("New Name"));
    p.input(TerminalInput.Key.HOME);
    p.input(TerminalInput.Key.DELETE);
    p.input(TerminalInput.Key.END);
    p.input(TerminalInput.Key.BACKSPACE);
    assertEquals("ew Nam", p.fieldValue());
  }

  @Test
  void editsSurviveServerRepliesAndNameLimitIsEnforced() {
    var p = new ControllerTerminal(false, "", "");
    p.input(new TerminalInput.Text("a".repeat(40) + "/\n"));
    p.update("Scan complete");
    assertEquals("a".repeat(32), p.fieldValue());
  }

  @Test
  void blankNameAndDestinationFocusFieldInsteadOfSendingAction() {
    for (boolean ship : new boolean[] {false, true}) {
      var p = new ControllerTerminal(ship, "", "");
      p.input(new TerminalInput.Pointer(10, ship ? 15 : 14));
      assertEquals(p.field(), p.selection());
      assertNull(p.pollAction());
      assertTrue(p.status().startsWith("Enter a"));
    }
  }

  @Test
  void waitsForReplyAndReportsTimeout() {
    var p = new ControllerTerminal(false, "Ready", "Plot");
    p.input(TerminalInput.Key.ENTER);
    assertNotNull(p.pollAction());
    p.input(TerminalInput.Key.ENTER);
    assertNull(p.pollAction());
    for (int i = 0; i < 100; i++) p.tick();
    assertTrue(p.status().contains("No response"));
    p.input(TerminalInput.Key.ENTER);
    assertNotNull(p.pollAction());
  }

  @Test
  void fieldCursorUsesActualCellsAndStaysWithinEightyColumns() {
    var p = new ControllerTerminal(false, "", "");
    p.input(new TerminalInput.Text("Example"));
    var t = p.display();
    assertEquals(29, t.cursorColumn());
    assertEquals(13, t.cursorRow());
    assertTrue(t.cursorVisible());
    assertEquals('E', t.cell(22, 13).glyph());
  }
}
