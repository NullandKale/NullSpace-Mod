package com.nullandkale.nullspace.scene;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class VoxelVolumeTest {
  @Test
  void uniformBlocksNeedNoIndexWords() {
    int[] blocks = new int[32768];
    Arrays.fill(blocks, 4137);
    var packed = VoxelVolume.pack(blocks);
    assertEquals(0, packed.bits());
    assertEquals(0, packed.words().length);
    assertEquals(16, packed.bytes());
    assertArrayEquals(blocks, VoxelVolume.unpack(packed));
  }

  @Test
  void arbitraryPaletteSizesAndNonAlignedWordBoundariesRoundTrip() {
    var random = new Random(21489052);
    for (int palette : new int[] {2, 3, 5, 17, 65, 513, 32768}) {
      int[] input = new int[32768];
      for (int i = 0; i < input.length; i++)
        input[i] = i < palette ? i * 11 : random.nextInt(palette) * 11;
      assertArrayEquals(input, VoxelVolume.unpack(VoxelVolume.pack(input)), "Palette " + palette);
    }
  }

  @Test
  void shortAndOddVolumesRoundTrip() {
    for (int size : new int[] {1, 3, 17, 31, 65, 999, 32767}) {
      int[] input = new int[size];
      for (int i = 0; i < size; i++) input[i] = i % 7;
      assertArrayEquals(input, VoxelVolume.unpack(VoxelVolume.pack(input)));
    }
  }

  @Test
  void largeStateIdsStayLossless() {
    int[] input = {0, Integer.MAX_VALUE, 897123, Integer.MAX_VALUE, 0};
    assertArrayEquals(input, VoxelVolume.unpack(VoxelVolume.pack(input)));
  }

  @Test
  void commonShipPaletteIsSmallerThanRawStateIds() {
    int[] input = new int[32768];
    for (int i = 0; i < input.length; i++) input[i] = i % 32 == 0 ? 791 : i % 64 == 1 ? 317 : 0;
    assertTrue(VoxelVolume.pack(input).bytes() < input.length * 4 / 4);
  }

  @Test
  void blockVolumeBoundsAndInvalidPaletteShapesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> VoxelVolume.pack(new int[0]));
    assertThrows(IllegalArgumentException.class, () -> VoxelVolume.pack(new int[32769]));
    assertThrows(IllegalArgumentException.class, () -> VoxelVolume.pack(new int[] {-1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VoxelVolume.Packed(3, 1, new int[] {1}, new long[1]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VoxelVolume.Packed(3, 2, new int[] {1, 2, 3}, new long[0]));
  }

  @Test
  void paletteIndexOverrunsFailClosed() {
    var packed = new VoxelVolume.Packed(3, 2, new int[] {1, 2, 3}, new long[] {3});
    assertThrows(IllegalArgumentException.class, () -> VoxelVolume.unpack(packed));
  }

  @Test
  void everyUniformLightLevelUsesTwoBytes() {
    for (int level = 0; level < 16; level++) {
      byte[] input = new byte[32768];
      Arrays.fill(input, (byte) level);
      byte[] packed = VoxelVolume.packLight(input);
      assertEquals(2, packed.length);
      assertArrayEquals(input, VoxelVolume.unpackLight(packed, input.length));
    }
  }

  @Test
  void mixedAndOddLightVolumesRoundTrip() {
    var random = new Random(12521);
    for (int size : new int[] {1, 3, 19, 512, 32767, 32768}) {
      byte[] input = new byte[size];
      for (int i = 0; i < size; i++) input[i] = (byte) random.nextInt(16);
      byte[] packed = VoxelVolume.packLight(input);
      assertTrue(packed.length <= 1 + (size + 1) / 2 || packed.length == 2);
      assertArrayEquals(input, VoxelVolume.unpackLight(packed, size));
    }
  }

  @Test
  void invalidLightLevelsAndPayloadShapesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> VoxelVolume.packLight(new byte[] {16}));
    assertThrows(IllegalArgumentException.class, () -> VoxelVolume.packLight(new byte[] {-1}));
    assertThrows(
        IllegalArgumentException.class, () -> VoxelVolume.unpackLight(new byte[] {0, 16}, 4));
    assertThrows(
        IllegalArgumentException.class, () -> VoxelVolume.unpackLight(new byte[] {2, 0}, 2));
    assertThrows(IllegalArgumentException.class, () -> VoxelVolume.unpackLight(new byte[0], 4));
    assertThrows(
        IllegalArgumentException.class, () -> VoxelVolume.unpackLight(new byte[] {1, 0}, 32768));
  }
}
