package com.nullandkale.nullspace.pad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PadMutationTest {
  private static PadScanner.Cell cell(int x, int y, int z, int ox, int oz, int width, int depth) {
    x -= ox;
    z -= oz;
    if (y != 64 || x < -1 || x > width || z < -1 || z > depth) return PadScanner.Cell.OTHER;
    if (x == -1 || x == width || z == -1 || z == depth) return PadScanner.Cell.BORDER;
    return (x + z) % 2 == 0 ? PadScanner.Cell.GRASS : PadScanner.Cell.DIRT;
  }

  @TestFactory
  Stream<DynamicTest> rectangularPadsWorkAcrossNegativeAndPositiveChunks() {
    return Stream.of(
            new int[] {1, 1},
            new int[] {1, 32},
            new int[] {32, 1},
            new int[] {7, 19},
            new int[] {32, 32})
        .flatMap(
            size ->
                Stream.of(-65537, -17, 0, 65537)
                    .map(
                        origin ->
                            DynamicTest.dynamicTest(
                                size[0] + "x" + size[1] + " at " + origin,
                                () -> {
                                  var reads = new int[1];
                                  var result =
                                      PadScanner.scan(
                                          (x, y, z) -> {
                                            reads[0]++;
                                            return cell(x, y, z, origin, origin, size[0], size[1]);
                                          },
                                          origin - 1,
                                          65,
                                          origin - 1,
                                          1,
                                          0);
                                  assertTrue(result.valid(), result.message());
                                  assertEquals(
                                      new PadScanner.Pad(
                                          origin,
                                          origin + size[0] - 1,
                                          64,
                                          origin,
                                          origin + size[1] - 1),
                                      result.pad());
                                  assertTrue(
                                      reads[0] < 2000,
                                      "Discovery must remain bounded by a small number of pad"
                                          + " scans");
                                })));
  }

  @Test
  void everySingleMissingRingBlockIsDetected() {
    for (int x = -1; x <= 8; x++)
      for (int z = -1; z <= 8; z++) {
        if (x != -1 && x != 8 && z != -1 && z != 8) continue;
        int holeX = x, holeZ = z;
        var result =
            PadScanner.scan(
                (px, y, pz) ->
                    px == holeX && pz == holeZ
                        ? PadScanner.Cell.OTHER
                        : cell(px, y, pz, 0, 0, 8, 8),
                -1,
                65,
                -1,
                1,
                0);
        assertFalse(result.valid(), "Broken ring " + x + "," + z + " must reject");
      }
  }

  @Test
  void unloadedCellsNeverBecomeValidFloorOrRing() {
    for (var point :
        List.of(new int[] {3, 4}, new int[] {8, 8}, new int[] {0, 0}, new int[] {-1, -1})) {
      var result =
          PadScanner.scan(
              (x, y, z) ->
                  x == point[0] && z == point[1]
                      ? PadScanner.Cell.UNLOADED
                      : cell(x, y, z, 0, 0, 8, 8),
              -1,
              65,
              -1,
              1,
              0);
      assertFalse(result.valid());
      assertTrue(result.message().contains("loading"), result.message());
    }
  }

  @Test
  void overlapIsSymmetricAndIncludesSharedRingOnlyOnSameLevel() {
    var random = new Random(20261025);
    for (int i = 0; i < 1000; i++) {
      int x = random.nextInt(80) - 40, z = random.nextInt(80) - 40;
      var a = new PadScanner.Pad(0, 7, 64, 0, 7);
      var b = new PadScanner.Pad(x, x + 7, 64, z, z + 7);
      assertEquals(a.overlaps(b), b.overlaps(a));
      assertFalse(a.overlaps(new PadScanner.Pad(x, x + 7, 65, z, z + 7)));
    }
  }
}
