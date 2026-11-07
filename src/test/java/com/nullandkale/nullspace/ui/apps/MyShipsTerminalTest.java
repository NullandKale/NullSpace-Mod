package com.nullandkale.nullspace.ui.apps;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyShipsTerminalTest {
  private final String a = "00000000-0000-0000-0000-000000000001",
      b = "00000000-0000-0000-0000-000000000002";

  @Test
  void selectionDoesNotMoveAShipAndDockedShipsCannotBeRecalled() {
    var t =
        new MyShipsTerminal(
            "Ready",
            List.of(
                new ComputerTerminal.Destination(a, "Home ship", "AVAILABLE"),
                new ComputerTerminal.Destination(
                    b, "Docked ship", "DOCKED", "Release docking before recall")));
    t.input(new TerminalInput.Pointer(4, 7));
    assertNull(t.pollAction());
    t.input(new TerminalInput.Pointer(4, 18));
    assertNull(t.pollAction());
    assertTrue(t.status().contains("docking"));
    t.input(new TerminalInput.Pointer(4, 6));
    assertNull(t.pollAction());
    t.input(new TerminalInput.Pointer(4, 18));
    assertEquals(new ControllerTerminal.Action("recall_ship", a.replace("-", "")), t.pollAction());
  }

  @Test
  void removingACompletedShipCannotLeaveAStaleRecallSelection() {
    var t =
        new MyShipsTerminal(
            "Ready",
            List.of(
                new ComputerTerminal.Destination(a, "Home ship", "AVAILABLE"),
                new ComputerTerminal.Destination(b, "Other ship", "AVAILABLE")));
    t.input(new TerminalInput.Pointer(4, 7));
    t.catalog(List.of(new ComputerTerminal.Destination(a, "Home ship", "AVAILABLE")));
    assertNull(t.pollAction());
    t.input(new TerminalInput.Pointer(4, 18));
    assertNull(t.pollAction(), "Removal must not select a different ship automatically");
    t.input(new TerminalInput.Pointer(58, 16));
    assertNull(t.pollAction(), "Cancel is absent without an active recovery");
    assertFalse(t.closeRequested());
    t.input(TerminalInput.Key.ESCAPE);
    assertTrue(t.closeRequested(), "Escape closes My ships locally, without the server");
    t.input(new TerminalInput.Pointer(4, 24));
    assertEquals("recall_back", t.pollAction().verb());
  }

  @Test
  void recallIsDirectlyAvailableOnThePadWithoutChangingItsName() {
    var t = new ControllerTerminal(false, "Ready", "My pad");
    t.input(new TerminalInput.Pointer(4, 19));
    assertEquals(new ControllerTerminal.Action("recall_list", ""), t.pollAction());
    assertEquals("My pad", t.fieldValue());
  }
}
