package com.nullandkale.nullspace.ui.terminal;

/** Small editable field shared by character-based operating panels. */
public final class TerminalField {
  private String value;
  private int caret;
  private final int limit;
  private boolean selected;

  public TerminalField(String value, int limit) {
    this.value = value;
    this.limit = limit;
    caret = value.length();
  }

  public String value() {
    return value;
  }

  public void clear() {
    value = "";
    caret = 0;
    selected = false;
  }

  public void click(int column) {
    caret = Math.clamp(column, 0, value.length());
    selected = false;
  }

  public void input(TerminalInput input) {
    if (input instanceof TerminalInput.Text text) {
      String added = text.text().replaceAll("[^ -~]", "");
      if (added.isEmpty()) return;
      if (selected) clear();
      added = added.substring(0, Math.min(added.length(), limit - value.length()));
      value = value.substring(0, caret) + added + value.substring(caret);
      caret += added.length();
    } else if (input instanceof TerminalInput.Key key) {
      switch (key) {
        case SELECT_ALL -> selected = true;
        case HOME -> {
          caret = 0;
          selected = false;
        }
        case END -> {
          caret = value.length();
          selected = false;
        }
        case LEFT -> {
          caret = selected ? 0 : Math.max(0, caret - 1);
          selected = false;
        }
        case RIGHT -> {
          caret = selected ? value.length() : Math.min(value.length(), caret + 1);
          selected = false;
        }
        case BACKSPACE, DELETE -> {
          if (selected) clear();
          else if (key == TerminalInput.Key.BACKSPACE && caret > 0) {
            value = value.substring(0, caret - 1) + value.substring(caret);
            caret--;
          } else if (key == TerminalInput.Key.DELETE && caret < value.length())
            value = value.substring(0, caret) + value.substring(caret + 1);
        }
        default -> {}
      }
    }
  }

  public void draw(TerminalBuffer buffer, int x, int y, int width, boolean focused) {
    int start = Math.max(0, caret - width + 1);
    String shown = value.substring(start, Math.min(value.length(), start + width));
    buffer.put(x, y, " ".repeat(width), TerminalTheme.TEXT, TerminalTheme.PANEL);
    buffer.put(
        x,
        y,
        shown,
        selected && focused ? TerminalTheme.BACKGROUND : TerminalTheme.TEXT,
        selected && focused ? TerminalTheme.GREEN : TerminalTheme.PANEL);
    if (focused) {
      buffer.cursor(x + caret - start, y);
      buffer.cursorVisible(true);
    }
  }
}
