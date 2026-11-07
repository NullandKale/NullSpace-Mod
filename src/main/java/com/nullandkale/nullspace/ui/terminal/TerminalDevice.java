package com.nullandkale.nullspace.ui.terminal;

/** A display/input port. Controller programs use it today; a future computer can implement it. */
public interface TerminalDevice {
  TerminalBuffer display();

  void input(TerminalInput input);

  default boolean closeRequested() {
    return false;
  }
}
