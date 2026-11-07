package com.nullandkale.nullspace.ui.apps;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class PadTerminalTest {
  @Test
  void firstRegistrationNeedsNoTypingAndCannotBeSubmittedTwiceWhileWaiting() {
    var panel =
        new PadTerminal(
            "Valid pad",
            "Home",
            List.of(new ComputerTerminal.Destination("pad_status", "5 x 5", "VALID")));
    panel.input(new TerminalInput.Pointer(4, 7));
    assertEquals(new ControllerTerminal.Action("register", "Home"), panel.pollAction());
    panel.input(new TerminalInput.Pointer(4, 7));
    assertNull(panel.pollAction());
    assertTrue(panel.display().row(21).contains("Waiting"));
  }

  @Test
  void invalidPadHasRepairWithoutRegisteringOrLosingNameDraft() {
    var panel =
        new PadTerminal(
            "Ring has a gap",
            "Home",
            List.of(new ComputerTerminal.Destination("pad_status", "Needs repair", "INVALID")));
    panel.input(new TerminalInput.Pointer(4, 7));
    assertNull(panel.pollAction());
    panel.input(new TerminalInput.Pointer(4, 13));
    assertEquals(new ControllerTerminal.Action("pad_problem", ""), panel.pollAction());
    panel.update("Gap highlighted");
    assertEquals("Home", panel.fieldValue());
  }

  @Test
  void clearedRegisteredNameCannotRenameAndCanBeRestoredWithoutTyping() {
    var panel =
        new PadTerminal(
            "Registered Home",
            "Home",
            List.of(new ComputerTerminal.Destination("pad_status", "5 x 5", "REGISTERED")));
    panel.input(new TerminalInput.Pointer(8, 5));
    panel.input(TerminalInput.Key.SELECT_ALL);
    panel.input(TerminalInput.Key.BACKSPACE);
    panel.input(new TerminalInput.Pointer(4, 7));
    assertNull(panel.pollAction(), "An empty Save must never replace a registered name");
    panel.input(new TerminalInput.Pointer(45, 5));
    assertEquals("Home", panel.fieldValue());
    assertNull(panel.pollAction(), "Restoring the draft does not change the world");
  }

  @Test
  void newSuggestionReplacesOnlyAnUntouchedDefault() {
    var panel =
        new PadTerminal(
            "Name used",
            "Home",
            List.of(new ComputerTerminal.Destination("pad_status", "5 x 5", "VALID")));
    panel.savedName("Landing pad 2");
    assertEquals("Landing pad 2", panel.fieldValue());
    panel.input(new TerminalInput.Pointer(8, 5));
    panel.input(TerminalInput.Key.SELECT_ALL);
    panel.input(new TerminalInput.Text("My place"));
    panel.savedName("Landing pad 3");
    assertEquals(
        "My place", panel.fieldValue(), "A collision cannot overwrite the player's typed name");
  }
}
