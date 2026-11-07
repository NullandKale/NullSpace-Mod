package com.nullandkale.nullspace.model.space;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;

/** Bounded, lossless wire representation; independent of worlds, registries, and render state. */
public final class VoxelVolume {
  public static final int MAX_CELLS = 32768;

  public record Packed(int size, int bits, int[] palette, long[] words) {
    public Packed {
      checkSize(size);
      if (palette.length < 1
          || palette.length > size
          || bits != bitsFor(palette.length)
          || words.length != wordCount(size, bits))
        throw new IllegalArgumentException("Invalid voxel palette shape");
      for (int value : palette)
        if (value < 0) throw new IllegalArgumentException("Negative block-state ID");
    }

    public int bytes() {
      return 12 + palette.length * 4 + words.length * 8;
    }
  }

  public static Packed pack(int[] cells) {
    checkSize(cells.length);
    var lookup = new Int2IntOpenHashMap();
    lookup.defaultReturnValue(-1);
    var palette = new IntArrayList();
    int[] indices = new int[cells.length];
    for (int i = 0; i < cells.length; i++) {
      int value = cells[i];
      if (value < 0) throw new IllegalArgumentException("Negative block-state ID");
      int index = lookup.get(value);
      if (index < 0) {
        index = palette.size();
        palette.add(value);
        lookup.put(value, index);
      }
      indices[i] = index;
    }
    int bits = bitsFor(palette.size());
    long[] words = new long[wordCount(cells.length, bits)];
    if (bits > 0) {
      int perWord = 64 / bits;
      for (int i = 0; i < indices.length; i++)
        words[i / perWord] |= (long) indices[i] << ((i % perWord) * bits);
    }
    return new Packed(cells.length, bits, palette.toIntArray(), words);
  }

  public static int[] unpack(Packed packed) {
    int[] result = new int[packed.size];
    if (packed.bits == 0) {
      Arrays.fill(result, packed.palette[0]);
      return result;
    }
    int perWord = 64 / packed.bits;
    long mask = (1L << packed.bits) - 1;
    for (int i = 0; i < result.length; i++) {
      int index = (int) ((packed.words[i / perWord] >>> ((i % perWord) * packed.bits)) & mask);
      if (index >= packed.palette.length)
        throw new IllegalArgumentException("Voxel index outside palette");
      result[i] = packed.palette[index];
    }
    return result;
  }

  /** Uniform light costs two bytes; otherwise store two four-bit light levels per byte. */
  public static byte[] packLight(byte[] values) {
    checkSize(values.length);
    boolean uniform = true;
    int first = Byte.toUnsignedInt(values[0]);
    for (byte value : values) {
      int light = Byte.toUnsignedInt(value);
      if (light > 15) throw new IllegalArgumentException("Invalid light level");
      if (light != first) uniform = false;
    }
    if (uniform) return new byte[] {0, (byte) first};
    byte[] packed = new byte[1 + (values.length + 1) / 2];
    packed[0] = 1;
    for (int i = 0; i < values.length; i++)
      packed[1 + i / 2] |= (byte) (Byte.toUnsignedInt(values[i]) << ((i % 2) * 4));
    return packed;
  }

  public static byte[] unpackLight(byte[] packed, int size) {
    checkSize(size);
    byte[] result = new byte[size];
    if (packed.length == 2 && packed[0] == 0 && Byte.toUnsignedInt(packed[1]) <= 15) {
      Arrays.fill(result, packed[1]);
      return result;
    }
    if (packed.length != 1 + (size + 1) / 2 || packed[0] != 1)
      throw new IllegalArgumentException("Invalid packed light volume");
    for (int i = 0; i < size; i++)
      result[i] = (byte) ((Byte.toUnsignedInt(packed[1 + i / 2]) >>> ((i % 2) * 4)) & 15);
    return result;
  }

  private static int bitsFor(int count) {
    return count <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
  }

  private static int wordCount(int size, int bits) {
    return bits == 0 ? 0 : (size + (64 / bits) - 1) / (64 / bits);
  }

  private static void checkSize(int size) {
    if (size < 1 || size > MAX_CELLS)
      throw new IllegalArgumentException("Voxel volume outside budget");
  }

  private VoxelVolume() {}
}
