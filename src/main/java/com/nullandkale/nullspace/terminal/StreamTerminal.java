package com.nullandkale.nullspace.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Byte-stream port for a future computer. All calls must run on the owning UI thread. A
 * server-hosted computer must supply its transport/session validation separately.
 */
public final class StreamTerminal implements TerminalDevice {
  private final TerminalBuffer display = new TerminalBuffer();
  private final Consumer<byte[]> keyboard;

  public StreamTerminal(Consumer<byte[]> keyboard) {
    this.keyboard = Objects.requireNonNull(keyboard);
  }

  public void write(String output) {
    display.write(output);
  }

  @Override
  public TerminalBuffer display() {
    return display;
  }

  @Override
  public void input(TerminalInput input) {
    String sequence;
    if (input instanceof TerminalInput.Text text) sequence = text.text();
    else if (input instanceof TerminalInput.Control control)
      sequence = Character.toString(control.code());
    else if (input instanceof TerminalInput.Key key)
      sequence =
          switch (key) {
            case UP -> "\033[A";
            case DOWN -> "\033[B";
            case RIGHT -> "\033[C";
            case LEFT -> "\033[D";
            case HOME -> "\033[H";
            case END -> "\033[F";
            case DELETE -> "\033[3~";
            case PAGE_UP -> "\033[5~";
            case PAGE_DOWN -> "\033[6~";
            case ENTER -> "\r";
            case TAB -> "\t";
            case BACKSPACE -> "\177";
            case ESCAPE -> "\033";
            case SELECT_ALL -> "\001";
          };
    else return; // No mouse-reporting protocol is enabled.
    keyboard.accept(sequence.getBytes(StandardCharsets.UTF_8));
  }
}
