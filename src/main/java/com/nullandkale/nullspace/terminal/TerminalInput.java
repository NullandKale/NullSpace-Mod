package com.nullandkale.nullspace.terminal;

/** Physical keys and committed text are separate; never infer text from key codes. */
public sealed interface TerminalInput {
  enum Key implements TerminalInput {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    HOME,
    END,
    BACKSPACE,
    DELETE,
    ENTER,
    PAGE_UP,
    PAGE_DOWN,
    TAB,
    SELECT_ALL,
    ESCAPE
  }

  record Control(int code) implements TerminalInput {
    public Control {
      if (code < 0 || code > 31) throw new IllegalArgumentException("Control code must be 0..31");
    }
  }

  record Text(String text) implements TerminalInput {}

  record Pointer(int column, int row) implements TerminalInput {}
}
