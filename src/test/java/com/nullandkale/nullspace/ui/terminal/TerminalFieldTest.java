package com.nullandkale.nullspace.ui.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TerminalFieldTest {
  @Test
  void selectionDoesNotEraseUntilReplacementAndCaretEditsInPlace() {
    var field = new TerminalField("Home", 32);
    field.input(TerminalInput.Key.SELECT_ALL);
    assertEquals("Home", field.value());
    field.input(new TerminalInput.Text("Earth dock"));
    assertEquals("Earth dock", field.value());
    field.click(5);
    field.input(new TerminalInput.Text(" II"));
    assertEquals("Earth II dock", field.value());
    field.input(TerminalInput.Key.BACKSPACE);
    assertEquals("Earth I dock", field.value());
  }

  @Test
  void pasteRespectsLimitAndRejectsControlCharacters() {
    var field = new TerminalField("", 4);
    field.input(new TerminalInput.Text("ab\ncdxyz"));
    assertEquals("abcd", field.value());
    field.input(TerminalInput.Key.HOME);
    field.input(TerminalInput.Key.DELETE);
    assertEquals("bcd", field.value());
  }
}
