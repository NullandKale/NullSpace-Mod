package com.nullandkale.nullspace.pad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PadScannerTest {
  @Test
  void controllerPlacementWorksFromEveryFacingIncludingCorners() {
    for (int[] at :
        new int[][] {{-1, 3}, {8, 3}, {3, -1}, {3, 8}, {-1, -1}, {8, -1}, {-1, 8}, {8, 8}})
      for (int[] facing : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        var result = PadScanner.scan(this::pad, at[0], 1, at[1], facing[0], facing[1]);
        assertTrue(
            result.valid(),
            java.util.Arrays.toString(at) + java.util.Arrays.toString(facing) + result.message());
        assertEquals(new PadScanner.Pad(0, 7, 0, 0, 7), result.pad());
      }
  }

  @Test
  void layeredPadsReadOnlyTheSupportingRingHeight() {
    var heights = new java.util.HashSet<Integer>();
    PadScanner.Grid stacked =
        (x, y, z) -> {
          heights.add(y);
          return pad(x, 0, z);
        };
    var lower = PadScanner.scan(stacked, -1, 1, 3, -1, 0);
    var upper = PadScanner.scan(stacked, -1, 41, 3, 0, 1);
    assertTrue(lower.valid());
    assertTrue(upper.valid());
    assertEquals(java.util.Set.of(0, 40), heights);
    assertFalse(lower.pad().overlaps(upper.pad()));
    assertTrue(lower.pad().overlaps(lower.pad()));
  }

  @Test
  void ambiguousSharedRingDoesNotChooseAnArbitraryPad() {
    PadScanner.Grid grid =
        (x, y, z) -> {
          if (y != 0 || x < -1 || x > 17 || z < -1 || z > 8) return PadScanner.Cell.OTHER;
          return x == -1 || x == 8 || x == 17 || z == -1 || z == 8
              ? PadScanner.Cell.BORDER
              : PadScanner.Cell.DIRT;
        };
    var result = PadScanner.scan(grid, 8, 1, 3, 1, 0);
    assertFalse(result.valid());
    assertTrue(result.message().contains("more than one pad"));
  }

  @Test
  void noRingAtControllerHeightNeverFallsThroughToALowerPad() {
    var heights = new java.util.HashSet<Integer>();
    var result =
        PadScanner.scan(
            (x, y, z) -> {
              heights.add(y);
              return pad(x, y, z);
            },
            -1,
            21,
            3,
            1,
            0);
    assertFalse(result.valid());
    assertEquals(java.util.Set.of(20), heights);
  }

  private PadScanner.Cell pad(int x, int y, int z) {
    if (y != 0) return PadScanner.Cell.OTHER;
    if (x < -1 || x > 8 || z < -1 || z > 8) return PadScanner.Cell.OTHER;
    return x == -1 || x == 8 || z == -1 || z == 8 ? PadScanner.Cell.BORDER : PadScanner.Cell.GRASS;
  }

  @Test
  void discoversWholePad() {
    var result = PadScanner.scan(this::pad, -1, 1, 3, 1, 0);
    assertTrue(result.valid(), result.message());
    assertEquals(new PadScanner.Pad(0, 7, 0, 0, 7), result.pad());
  }

  @Test
  void detectsMissingCornerAwayFromRays() {
    var result =
        PadScanner.scan(
            (x, y, z) -> x == 8 && z == 8 ? PadScanner.Cell.OTHER : pad(x, y, z), -1, 1, 3, 1, 0);
    assertFalse(result.valid());
    assertTrue(result.message().contains("8, 0, 8"));
  }

  @Test
  void detectsHoleAwayFromRays() {
    assertFalse(
        PadScanner.scan(
                (x, y, z) -> x == 5 && z == 5 ? PadScanner.Cell.OTHER : pad(x, y, z),
                -1,
                1,
                3,
                1,
                0)
            .valid());
  }

  @Test
  void unboundedFieldStops() {
    int[] reads = {0};
    assertFalse(
        PadScanner.scan(
                (x, y, z) -> {
                  reads[0]++;
                  return PadScanner.Cell.GRASS;
                },
                0,
                1,
                0,
                1,
                0)
            .valid());
    assertTrue(reads[0] <= 130);
  }

  @Test
  void unloadedAreaFails() {
    assertFalse(PadScanner.scan((x, y, z) -> PadScanner.Cell.UNLOADED, 0, 1, 0, 1, 0).valid());
  }

  @Test
  void acceptsEntirePadTurningToDirt() {
    var result =
        PadScanner.scan(
            (x, y, z) -> {
              var cell = pad(x, y, z);
              return cell == PadScanner.Cell.GRASS ? PadScanner.Cell.DIRT : cell;
            },
            -1,
            1,
            3,
            1,
            0);
    assertTrue(result.valid(), result.message());
    assertEquals(new PadScanner.Pad(0, 7, 0, 0, 7), result.pad());
  }

  @Test
  void acceptsMixedGrassAndDirtIncludingSearchColumnAndOffRayPatch() {
    var result =
        PadScanner.scan(
            (x, y, z) -> {
              var cell = pad(x, y, z);
              return cell == PadScanner.Cell.GRASS && ((x + z) % 2 == 1)
                  ? PadScanner.Cell.DIRT
                  : cell;
            },
            -1,
            1,
            3,
            1,
            0);
    assertTrue(result.valid(), result.message());
    assertEquals(new PadScanner.Pad(0, 7, 0, 0, 7), result.pad());
  }

  @Test
  void invalidDirtSurfaceDoesNotSelectDeeperValidPad() {
    int[] lowest = {0};
    var result =
        PadScanner.scan(
            (x, y, z) -> {
              lowest[0] = Math.min(lowest[0], y);
              return y == 0 ? PadScanner.Cell.DIRT : pad(x, y + 2, z);
            },
            -1,
            1,
            3,
            1,
            0);
    assertFalse(result.valid());
    assertEquals(0, lowest[0], "Discovery must stop at the first grass/dirt surface");
  }

  @Test
  void acceptsMaximumPadInAllOrientations() {
    PadScanner.Grid grid =
        (x, y, z) -> {
          if (y != 0 || x < -1 || x > 32 || z < -1 || z > 32) return PadScanner.Cell.OTHER;
          return x == -1 || x == 32 || z == -1 || z == 32
              ? PadScanner.Cell.BORDER
              : PadScanner.Cell.GRASS;
        };
    for (int[] c : new int[][] {{-1, 10, 1, 0}, {32, 10, -1, 0}, {10, -1, 0, 1}, {10, 32, 0, -1}}) {
      var result = PadScanner.scan(grid, c[0], 1, c[1], c[2], c[3]);
      assertTrue(result.valid(), result.message());
      assertEquals(32, result.pad().width());
      assertEquals(32, result.pad().depth());
    }
  }

  @Test
  void requiresComputerOnBorderAtSurfaceHeight() {
    assertFalse(PadScanner.scan(this::pad, 2, 1, 3, 1, 0).valid());
    assertFalse(PadScanner.scan(this::pad, -1, 3, 3, 1, 0).valid());
  }
}
