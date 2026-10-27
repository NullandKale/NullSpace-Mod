package com.nullandkale.nullspace.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class TerminalTransportTest {
  private static void sameDisplay(TerminalBuffer expected, TerminalBuffer actual) {
    for (int y = 0; y < 25; y++)
      for (int x = 0; x < 80; x++)
        assertEquals(expected.cell(x, y), actual.cell(x, y), "cell " + x + "," + y);
    assertEquals(expected.cursorColumn(), actual.cursorColumn());
    assertEquals(expected.cursorRow(), actual.cursorRow());
    assertEquals(expected.cursorVisible(), actual.cursorVisible());
  }

  @TestFactory
  Stream<DynamicTest> arbitraryPacketBoundariesPreserveAnsiState() {
    String stream =
        "boot\r\n"
            + "\033[1;91;44mALERT\033[0m\033[25;80HX\r\n"
            + "scroll\033[s\033[?25l\033[3;5Hnew\033[K\033[u";
    return IntStream.rangeClosed(1, 16)
        .mapToObj(
            size ->
                DynamicTest.dynamicTest(
                    "ANSI output in " + size + " character packets",
                    () -> {
                      var whole = new TerminalBuffer();
                      whole.write(stream);
                      var fragmented = new TerminalBuffer();
                      for (int i = 0; i < stream.length(); i += size)
                        fragmented.write(stream.substring(i, Math.min(stream.length(), i + size)));
                      sameDisplay(whole, fragmented);
                      assertEquals(whole.scrollback(), fragmented.scrollback());
                    }));
  }

  @TestFactory
  Stream<DynamicTest> snapshotsPreserveAllAnsiColors() {
    return IntStream.range(0, 16)
        .mapToObj(
            color ->
                DynamicTest.dynamicTest(
                    "late viewer color " + color,
                    () -> {
                      var source = new TerminalBuffer();
                      int fg = color < 8 ? 30 + color : 90 + color - 8,
                          bg = color < 8 ? 40 + color : 100 + color - 8;
                      source.write(
                          "\033[1;"
                              + fg
                              + "mABC\033[0;"
                              + bg
                              + "mDEF\033[7mXYZ\033[0m\033[12;37H\033[?25l");
                      var target = new TerminalBuffer();
                      target.write("old viewer data");
                      target.write(source.snapshot());
                      sameDisplay(source, target);
                    }));
  }

  @Test
  void malformedEscapeFloodRecoversAtNextValidSequence() {
    var t = new TerminalBuffer();
    var random = new Random(101);
    for (int i = 0; i < 1000; i++) {
      t.write("\033[" + "9".repeat(random.nextInt(200)) + "H");
      t.write("\033[1;1HOK");
      assertTrue(t.row(0).startsWith("OK"));
      assertTrue(t.cursorColumn() >= 0 && t.cursorColumn() < 80);
      assertTrue(t.cursorRow() >= 0 && t.cursorRow() < 25);
    }
  }

  @Test
  void screenResetClearsHistoryStylesAndPartialEscapes() {
    var t = new TerminalBuffer();
    for (int i = 0; i < 30; i++) t.write("line\r\n");
    t.write("\033[1;31m\033[?25l\033[");
    t.reset();
    t.write("Z");
    assertTrue(t.scrollback().isEmpty());
    assertTrue(t.cursorVisible());
    assertEquals('Z', t.cell(0, 0).glyph());
    assertEquals(TerminalBuffer.DEFAULT_FOREGROUND, t.cell(0, 0).foreground());
    assertFalse(t.cell(0, 0).bold());
  }
}
