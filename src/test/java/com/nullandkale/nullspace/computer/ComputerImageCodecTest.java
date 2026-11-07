package com.nullandkale.nullspace.computer;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class ComputerImageCodecTest {
  @Test
  void imagesRoundTripIncludingMultibyteText() {
    for (String image : new String[] {"", "{}", "naïve ✓ 🚀 日本語", "x".repeat(100_000)})
      assertEquals(image, ComputerImageCodec.decode(ComputerImageCodec.encode(image)));
  }

  @Test
  void aMissingImageDecodesToEmptyAndOldPlainStringImagesAreRejected() {
    assertEquals("", ComputerImageCodec.decode(null));
    assertThrows(
        IllegalArgumentException.class, () -> ComputerImageCodec.decode(StringTag.valueOf("old")));
  }

  @Test
  void aCorruptedPayloadFailsItsChecksum() {
    var tag = ComputerImageCodec.encode("important");
    var bytes = tag.getByteArray("utf8").orElseThrow().clone();
    bytes[0] ^= 1;
    tag.putByteArray("utf8", bytes);
    var error = assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.decode(tag));
    assertTrue(error.getMessage().contains("checksum"));
  }

  @Test
  void aMissingChecksumIsRejected() {
    var tag = ComputerImageCodec.encode("important");
    tag.remove("sha256");
    assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.decode(tag));
  }

  @Test
  void unknownVersionsAndShapesAreRejected() {
    var future = ComputerImageCodec.encode("x");
    future.putInt("version", 2);
    assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.decode(future));
    assertThrows(
        IllegalArgumentException.class, () -> ComputerImageCodec.decode(new CompoundTag()));
    assertThrows(
        IllegalArgumentException.class, () -> ComputerImageCodec.decode(IntTag.valueOf(1)));
    var noBytes = new CompoundTag();
    noBytes.putInt("version", 1);
    assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.decode(noBytes));
  }

  @Test
  void invalidUnicodeCannotBeEncoded() {
    assertThrows(
        IllegalArgumentException.class, () -> ComputerImageCodec.encode("lone surrogate \ud800"));
  }

  @Test
  void invalidUtf8WithAValidChecksumIsRejected() {
    var forged = ComputerImageCodec.encode("x");
    byte[] invalid = {(byte) 0xC3, (byte) 0x28};
    forged.putByteArray("utf8", invalid);
    forged.putByteArray("sha256", sha256(invalid));
    var error =
        assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.decode(forged));
    assertTrue(error.getMessage().contains("UTF-8"));
  }

  @Test
  void theByteBudgetIsEnforcedInBothDirections() {
    String exact = "a".repeat(ComputerImageCodec.MAX_BYTES);
    var tag = ComputerImageCodec.encode(exact);
    assertEquals(exact.length(), ComputerImageCodec.decode(tag).length());
    assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.encode(exact + "a"));
    // Multibyte characters count by encoded size, not by Java length.
    String wide = "é".repeat(ComputerImageCodec.MAX_BYTES / 2 + 1);
    assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.encode(wide));

    var oversized = new CompoundTag();
    oversized.putInt("version", 1);
    oversized.putByteArray("utf8", new byte[ComputerImageCodec.MAX_BYTES + 1]);
    assertThrows(IllegalArgumentException.class, () -> ComputerImageCodec.decode(oversized));
  }

  @Test
  void encodedEnvelopesSurviveACompressedNbtRoundTrip() throws Exception {
    var root = new CompoundTag();
    root.put("image", ComputerImageCodec.encode("disk ✓ " + "z".repeat(70_000)));
    var out = new java.io.ByteArrayOutputStream();
    NbtIo.writeCompressed(root, out);
    var read =
        NbtIo.readCompressed(
            new java.io.ByteArrayInputStream(out.toByteArray()), NbtAccounter.unlimitedHeap());
    assertEquals("disk ✓ " + "z".repeat(70_000), ComputerImageCodec.decode(read.get("image")));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
