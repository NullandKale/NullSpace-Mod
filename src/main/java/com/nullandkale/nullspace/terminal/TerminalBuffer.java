package com.nullandkale.nullspace.terminal;

import java.util.*;

/**
 * Fixed 80-column terminal. Coordinates are zero-based; ANSI coordinates are one-based. Printable
 * ASCII occupies one cell. Unsupported glyphs become '?'. Not a complete VT emulator. Output can be
 * fed in arbitrary chunks, including split escape sequences.
 */
public final class TerminalBuffer {
  public static final int COLUMNS = 80, ROWS = 25;
  public static final int DEFAULT_FOREGROUND = 0xff7bdfa0, DEFAULT_BACKGROUND = 0xff050908;
  private static final int[] COLORS = {
    0xff000000,
    0xffaa0000,
    0xff00aa00,
    0xffaa5500,
    0xff0000aa,
    0xffaa00aa,
    0xff00aaaa,
    0xffaaaaaa,
    0xff555555,
    0xffff5555,
    0xff55ff55,
    0xffffff55,
    0xff5555ff,
    0xffff55ff,
    0xff55ffff,
    0xffffffff
  };

  public record Cell(char glyph, int foreground, int background, boolean bold) {}

  private final Cell[][] cells = new Cell[ROWS][COLUMNS];
  private final ArrayDeque<List<Cell>> history = new ArrayDeque<>();
  private int x,
      y,
      savedX,
      savedY,
      foreground = DEFAULT_FOREGROUND,
      background = DEFAULT_BACKGROUND;
  private boolean visible = true, bold, inverse, wrap;
  private int escape;
  private final StringBuilder sequence = new StringBuilder();

  public TerminalBuffer() {
    clear();
  }

  public Cell cell(int column, int row) {
    return cells[row][column];
  }

  public String row(int row) {
    var text = new StringBuilder(COLUMNS);
    for (var c : cells[row]) text.append(c.glyph());
    return text.toString();
  }

  public int cursorColumn() {
    return x;
  }

  public int cursorRow() {
    return y;
  }

  public boolean cursorVisible() {
    return visible;
  }

  public void cursorVisible(boolean visible) {
    this.visible = visible;
  }

  public List<List<Cell>> scrollback() {
    return List.copyOf(history);
  }

  private Cell blank() {
    return new Cell(' ', foreground, background, bold);
  }

  public void clear() {
    for (var row : cells) Arrays.fill(row, blank());
    x = 0;
    y = 0;
    wrap = false;
  }

  public void reset() {
    foreground = DEFAULT_FOREGROUND;
    background = DEFAULT_BACKGROUND;
    bold = false;
    inverse = false;
    visible = true;
    escape = 0;
    sequence.setLength(0);
    history.clear();
    clear();
  }

  public void cursor(int column, int row) {
    x = Math.clamp(column, 0, COLUMNS - 1);
    y = Math.clamp(row, 0, ROWS - 1);
    wrap = false;
  }

  public void style(int foreground, int background) {
    this.foreground = foreground;
    this.background = background;
  }

  /** Absolute writes clip at the right margin and never change the stream cursor. */
  public void put(int column, int row, String text, int fg, int bg) {
    if (row < 0 || row >= ROWS) return;
    for (int i = 0; i < text.length(); i++) {
      int c = column + i;
      if (c >= COLUMNS) break;
      if (c < 0) continue;
      char ch = text.charAt(i);
      cells[row][c] = new Cell(ch >= 32 && ch <= 126 ? ch : '?', fg, bg, false);
    }
  }

  private void newline() {
    wrap = false;
    if (y < ROWS - 1) {
      y++;
      return;
    }
    history.addLast(List.copyOf(Arrays.asList(cells[0].clone())));
    if (history.size() > 200) history.removeFirst();
    for (int row = 1; row < ROWS; row++)
      System.arraycopy(cells[row], 0, cells[row - 1], 0, COLUMNS);
    Arrays.fill(cells[ROWS - 1], blank());
  }

  /** Self-contained screen state for a late or rejoining console viewer. */
  public String snapshot() {
    var out = new StringBuilder("\033[0m\033[2J\033[H");
    int lastFg = DEFAULT_FOREGROUND, lastBg = DEFAULT_BACKGROUND;
    boolean lastBold = false;
    for (int row = 0; row < ROWS; row++) {
      out.append("\033[").append(row + 1).append(";1H");
      for (int col = 0; col < COLUMNS; col++) {
        var c = cells[row][col];
        if (c.foreground() != lastFg || c.background() != lastBg || c.bold() != lastBold) {
          out.append("\033[0;38;2;")
              .append(c.foreground() >> 16 & 255)
              .append(';')
              .append(c.foreground() >> 8 & 255)
              .append(';')
              .append(c.foreground() & 255)
              .append(";48;2;")
              .append(c.background() >> 16 & 255)
              .append(';')
              .append(c.background() >> 8 & 255)
              .append(';')
              .append(c.background() & 255);
          if (c.bold()) out.append(";1");
          out.append('m');
          lastFg = c.foreground();
          lastBg = c.background();
          lastBold = c.bold();
        }
        out.append(c.glyph());
      }
    }
    return out.append("\033[")
        .append(y + 1)
        .append(';')
        .append(x + 1)
        .append("H\033[?25")
        .append(visible ? 'h' : 'l')
        .toString();
  }

  public void write(String output) {
    output.codePoints().forEach(this::accept);
  }

  private void accept(int ch) {
    if (ch == 27) {
      escape = 1;
      sequence.setLength(0);
      return;
    }
    if (escape == 1) {
      escape = 0;
      if (ch == '[') escape = 2;
      else if (ch == '7') {
        savedX = x;
        savedY = y;
      } else if (ch == '8') cursor(savedX, savedY);
      else if (ch == 'c') reset();
      return;
    }
    if (escape == 2) {
      if (ch >= 0x40 && ch <= 0x7e) {
        escape = 0;
        csi((char) ch);
        sequence.setLength(0);
      } else if (sequence.length() < 128 && ch >= 0x20 && ch <= 0x3f) sequence.append((char) ch);
      else {
        escape = 0;
        sequence.setLength(0);
      }
      return;
    }
    if (ch == '\r') {
      x = 0;
      wrap = false;
      return;
    }
    if (ch == '\n') {
      newline();
      return;
    }
    if (ch == '\b') {
      x = Math.max(0, x - 1);
      wrap = false;
      return;
    }
    if (ch == '\t') {
      int count = 8 - x % 8;
      for (int i = 0; i < count; i++) accept(' ');
      return;
    }
    if (ch < 32 || ch == 127) return;
    if (wrap) {
      x = 0;
      newline();
    }
    cells[y][x] =
        new Cell(
            ch <= 126 ? (char) ch : '?',
            inverse ? background : foreground,
            inverse ? foreground : background,
            bold);
    if (x == COLUMNS - 1) wrap = true;
    else x++;
  }

  private void csi(char command) {
    String raw = sequence.toString();
    if (raw.equals("?25")) {
      if (command == 'h') visible = true;
      else if (command == 'l') visible = false;
      return;
    }
    if (!raw.matches("[0-9;]*")) return;
    int[] p;
    try {
      p =
          Arrays.stream(raw.split(";", -1))
              .mapToInt(s -> s.isEmpty() ? 0 : Integer.parseInt(s))
              .toArray();
    } catch (NumberFormatException ignored) {
      return;
    }
    int n = p[0] == 0 ? 1 : p[0];
    n = Math.min(n, 10000);
    switch (command) {
      case 'A' -> cursor(x, y - n);
      case 'B' -> cursor(x, y + n);
      case 'C' -> cursor(x + n, y);
      case 'D' -> cursor(x - n, y);
      case 'G' -> cursor(n - 1, y);
      case 'H', 'f' -> cursor(p.length < 2 || p[1] == 0 ? 0 : p[1] - 1, n - 1);
      case 'J' -> {
        int mode = p[0];
        if (mode <= 2)
          for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLUMNS; c++)
              if (mode == 2
                  || mode == 0 && (r > y || r == y && c >= x)
                  || mode == 1 && (r < y || r == y && c <= x)) cells[r][c] = blank();
        if (mode == 3) history.clear();
        wrap = false;
      }
      case 'K' -> {
        for (int c = 0; c < COLUMNS; c++)
          if (p[0] == 2 || p[0] == 0 && c >= x || p[0] == 1 && c <= x) cells[y][c] = blank();
        wrap = false;
      }
      case 'm' -> {
        for (int param = 0; param < p.length; param++) {
          int code = p[param];
          if ((code == 38 || code == 48) && param + 4 < p.length && p[param + 1] == 2) {
            int red = p[param + 2], green = p[param + 3], blue = p[param + 4];
            param += 4;
            if (red <= 255 && green <= 255 && blue <= 255) {
              int color = 0xff000000 | red << 16 | green << 8 | blue;
              if (code == 38) foreground = color;
              else background = color;
            }
            continue;
          }
          if (code == 0) {
            foreground = DEFAULT_FOREGROUND;
            background = DEFAULT_BACKGROUND;
            bold = false;
            inverse = false;
          } else if (code == 1) bold = true;
          else if (code == 22) bold = false;
          else if (code == 7) inverse = true;
          else if (code == 27) inverse = false;
          else if (code == 39) foreground = DEFAULT_FOREGROUND;
          else if (code == 49) background = DEFAULT_BACKGROUND;
          else if (code >= 30 && code <= 37) foreground = COLORS[code - 30];
          else if (code >= 90 && code <= 97) foreground = COLORS[code - 90 + 8];
          else if (code >= 40 && code <= 47) background = COLORS[code - 40];
          else if (code >= 100 && code <= 107) background = COLORS[code - 100 + 8];
        }
      }
      case 's' -> {
        savedX = x;
        savedY = y;
      }
      case 'u' -> cursor(savedX, savedY);
      default -> {}
    }
  }
}
