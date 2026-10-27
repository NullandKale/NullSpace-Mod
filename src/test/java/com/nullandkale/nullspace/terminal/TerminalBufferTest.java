package com.nullandkale.nullspace.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TerminalBufferTest {
  @Test
  void exactlyEightyCellsAndDeferredWrap() {
    var t = new TerminalBuffer();
    t.write("x".repeat(80));
    assertEquals(80, t.row(0).length());
    assertEquals(0, t.cursorRow());
    assertEquals(79, t.cursorColumn());
    t.write("Y");
    assertEquals('Y', t.cell(0, 1).glyph());
    assertEquals(1, t.cursorColumn());
  }

  @Test
  void preservesSplitEscapeSequencesAndAddressesLastCell() {
    var t = new TerminalBuffer();
    t.write("\033[");
    t.write("25;80");
    t.write("HZ");
    assertEquals('Z', t.cell(79, 24).glyph());
    assertEquals(' ', t.cell(0, 0).glyph());
  }

  @Test
  void carriageReturnAndLineFeedAreIndependent() {
    var t = new TerminalBuffer();
    t.write("abc\rX\nY");
    assertTrue(t.row(0).startsWith("Xbc"));
    assertEquals('Y', t.cell(1, 1).glyph());
  }

  @Test
  void scrollingRetainsBoundedImmutableHistory() {
    var t = new TerminalBuffer();
    for (int i = 0; i < 300; i++) t.write(i + "\r\n");
    assertEquals(200, t.scrollback().size());
    assertThrows(UnsupportedOperationException.class, () -> t.scrollback().getFirst().clear());
    assertTrue(t.row(23).startsWith("299"));
  }

  @Test
  void colorsBoldAndInverseSurviveInCells() {
    var t = new TerminalBuffer();
    t.write("\033[1;31;44mX\033[7mY\033[0mZ");
    assertTrue(t.cell(0, 0).bold());
    assertEquals(0xffaa0000, t.cell(0, 0).foreground());
    assertEquals(t.cell(0, 0).background(), t.cell(1, 0).foreground());
    assertEquals(TerminalBuffer.DEFAULT_FOREGROUND, t.cell(2, 0).foreground());
  }

  @Test
  void eraseDoesNotMoveCursor() {
    var t = new TerminalBuffer();
    t.write("abcdef\033[1;4H\033[K");
    assertTrue(t.row(0).startsWith("abc   "));
    assertEquals(3, t.cursorColumn());
    t.write("\033[2J");
    assertTrue(t.row(0).isBlank());
    assertEquals(3, t.cursorColumn());
  }

  @Test
  void saveRestoreVisibilityAndClamping() {
    var t = new TerminalBuffer();
    t.write("\033[5;6H\033[s\033[999B\033[999C\033[?25l");
    assertEquals(24, t.cursorRow());
    assertEquals(79, t.cursorColumn());
    assertFalse(t.cursorVisible());
    t.write("\033[u\033[?25h");
    assertEquals(4, t.cursorRow());
    assertEquals(5, t.cursorColumn());
    assertTrue(t.cursorVisible());
  }

  @Test
  void malformedAndOversizedParametersCannotCrashOrGrowState() {
    var t = new TerminalBuffer();
    t.write("\033[999999999999999999999999H");
    t.write("\033[" + "1".repeat(200) + "m");
    t.write("\033[1;1HOK");
    assertTrue(t.row(0).startsWith("OK"));
  }

  @Test
  void absoluteWritesClipWithoutMovingStreamCursor() {
    var t = new TerminalBuffer();
    t.cursor(2, 3);
    t.put(79, 24, "abc", 1, 2);
    assertEquals('a', t.cell(79, 24).glyph());
    assertEquals(2, t.cursorColumn());
    assertEquals(3, t.cursorRow());
  }
}
