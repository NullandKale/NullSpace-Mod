package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.computer.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;

public final class PersistenceBoundaryGameTest {
  private static void reject(GameTestHelper ctx, Runnable action, String why) {
    boolean failed = false;
    try {
      action.run();
    } catch (RuntimeException expected) {
      failed = true;
    }
    ctx.assertTrue(failed, why);
  }

  @GameTest
  public void maximumDiskAndEightJobsSurviveActualCompressedStorage(GameTestHelper ctx)
      throws Exception {
    var computer = new VirtualComputer((n, a) -> 0);
    for (String f : computer.files.files().keySet()) computer.files.remove(f);
    String body = "\nwhile True:\n    sleep(1)\n";
    String source = "#" + "x".repeat(VirtualFiles.MAX_FILE - 1 - body.length()) + body;
    computer.files.write("/home/crew/task.py", source);
    for (int i = 0; i < 7; i++)
      computer.files.write("/home/crew/f" + i, "\u661f".repeat(VirtualFiles.MAX_FILE));
    for (int i = 0; i < 8; i++)
      computer.run("/home/crew/task.py", ScriptLanguage.Syntax.PYTHON, true);
    computer.tick(false, true);
    String image = computer.save();
    var tag = new CompoundTag();
    tag.put("image", ComputerImageCodec.encode(image));
    Path file =
        ShipStore.get(ctx.getLevel().getServer()).directory.resolve("maximum-computer-test.nbt");
    ShipStore.write(file, tag);
    var restored = new VirtualComputer((n, a) -> 0);
    restored.restore(ComputerImageCodec.decode(ShipStore.read(file).get("image")));
    ctx.assertTrue(
        computer.files.files().equals(restored.files.files()) && restored.processes.size() == 8,
        "Complete multi-byte disk and detached jobs survive the real NBT boundary");
    Files.delete(file);
    ctx.succeed();
  }

  @GameTest
  public void unicodeImagesDecodeIdentically(GameTestHelper ctx) {
    String text = "crew \u0000 \u661f \uD83D\uDE80\n";
    ctx.assertTrue(
        text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > text.length(),
        "Fixture must exercise multibyte UTF-8 and supplementary characters");
    ctx.assertTrue(
        text.equals(ComputerImageCodec.decode(ComputerImageCodec.encode(text))),
        "New UTF-8 images preserve Unicode and NUL");
    ctx.succeed();
  }

  @GameTest
  public void corruptedAndFutureImagesCannotBecomeFreshDisks(GameTestHelper ctx) {
    var encoded = ComputerImageCodec.encode("precious");
    encoded.putByteArray("utf8", new byte[] {1, 2});
    reject(ctx, () -> ComputerImageCodec.decode(encoded), "Reject digest mismatch");
    var future = ComputerImageCodec.encode("precious");
    future.putInt("version", 999);
    reject(ctx, () -> ComputerImageCodec.decode(future), "Reject future codec");
    reject(
        ctx,
        () -> ComputerImageCodec.encode("x".repeat(ComputerImageCodec.MAX_BYTES + 1)),
        "Bound encoded byte count");
    ctx.succeed();
  }

  @GameTest
  public void utf8ByteLimitsAndMalformedTextAreCheckedIndependently(GameTestHelper ctx)
      throws Exception {
    String atLimit = "\u661f".repeat(ComputerImageCodec.MAX_BYTES / 3) + "xx";
    var encoded = ComputerImageCodec.encode(atLimit);
    ctx.assertTrue(
        encoded.getByteArray("utf8").orElseThrow().length == ComputerImageCodec.MAX_BYTES
            && atLimit.equals(ComputerImageCodec.decode(encoded)),
        "Exact UTF-8 byte limit is accepted");
    reject(
        ctx, () -> ComputerImageCodec.encode(atLimit + "x"), "One byte over the limit is rejected");
    reject(
        ctx,
        () -> ComputerImageCodec.encode("\uD800"),
        "Unpaired surrogates must not silently become question marks");
    for (byte[] invalid :
        List.of(
            new byte[] {(byte) 0xc3, 0x28},
            new byte[] {(byte) 0xf0, (byte) 0x9f},
            new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
            new byte[] {(byte) 0xc0, (byte) 0x80})) {
      var tag = ComputerImageCodec.encode("");
      tag.putByteArray("utf8", invalid);
      tag.putByteArray(
          "sha256", java.security.MessageDigest.getInstance("SHA-256").digest(invalid));
      boolean rejected = false;
      try {
        ComputerImageCodec.decode(tag);
      } catch (IllegalArgumentException expected) {
        rejected = expected.getMessage().contains("UTF-8");
      }
      ctx.assertTrue(rejected, "Valid checksums must reach strict malformed UTF-8 validation");
    }
    ctx.succeed();
  }

  @GameTest
  public void legacyWriteUtfBoundaryAndByteEnvelopeAreDistinct(GameTestHelper ctx)
      throws Exception {
    var file = ShipStore.get(ctx.getLevel().getServer()).directory.resolve("utf-boundary.nbt");
    try {
      var tag = new CompoundTag();
      tag.putString("legacy", "x".repeat(65535));
      ShipStore.write(file, tag);
      ctx.assertTrue(
          tag.equals(ShipStore.read(file)), "Maximum legacy modified UTF string survives");
      var tooLarge = new CompoundTag();
      tooLarge.putString("legacy", "\u661f".repeat(21846));
      reject(
          ctx,
          () -> ShipStore.write(file, tooLarge),
          "Modified UTF byte count matters, not Java length");
      ctx.assertTrue(
          tag.equals(ShipStore.read(file)),
          "Failed multibyte legacy write preserves previous data");
      tag.put("image", ComputerImageCodec.encode("\u661f".repeat(65536)));
      ShipStore.write(file, tag);
      ctx.assertTrue(
          tag.equals(ShipStore.read(file)), "Byte images cross the old string ceiling losslessly");
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
    }
    ctx.succeed();
  }

  @GameTest
  public void nativeStringFallbackCannotReplaceTheLastGoodSave(GameTestHelper ctx)
      throws Exception {
    Path file =
        ShipStore.get(ctx.getLevel().getServer()).directory.resolve("strict-writer-test.nbt");
    var good = new CompoundTag();
    good.putString("precious", "keep");
    ShipStore.write(file, good);
    var invalid = good.copy();
    invalid.putString("oversized", "x".repeat(65536));
    reject(ctx, () -> ShipStore.write(file, invalid), "Reject silent native writeUTF fallback");
    ctx.assertTrue(good.equals(ShipStore.read(file)), "The last good file remains intact");
    Files.delete(file);
    Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
    ctx.succeed();
  }

  @GameTest
  public void repositorySnapshotsAndTransactionBoundariesAreExplicit(GameTestHelper ctx) {
    var root = new CompoundTag();
    var repository = new ShipRepository(root);
    var calls = new ArrayList<ShipRepository.Mutation>();
    repository.onReplace(calls::add);
    var snapshot = repository.snapshot();
    snapshot.putString("new", "value");
    ctx.assertTrue(!repository.snapshot().contains("new"), "Snapshots cannot mutate live roots");
    reject(
        ctx,
        () -> repository.replace(snapshot, ShipRepository.Mutation.DAMAGE),
        "Root replacement requires transfer or recovery");
    repository.replace(snapshot, ShipRepository.Mutation.RECOVERY);
    ctx.assertTrue(
        repository.generation() == 1 && calls.equals(List.of(ShipRepository.Mutation.RECOVERY)),
        "One explicit invalidation after recovery");
    ctx.succeed();
  }

  @GameTest
  public void failedRepositoryEditsCannotPublishPartialState(GameTestHelper ctx) {
    var root = new CompoundTag();
    var ships = new ListTag();
    var ship = new CompoundTag();
    ship.putString("id", "fixture");
    ship.putString("name", "before");
    ships.add(ship);
    root.put("ships", ships);
    var repository = new ShipRepository(root);
    reject(
        ctx,
        () ->
            repository.mutate(
                new ShipRepository.ShipId("fixture"),
                ShipRepository.Mutation.CONFIGURATION,
                c -> {
                  c.putString("name", "partial");
                  throw new IllegalArgumentException("invalid edit");
                }),
        "Reject failed edits");
    ctx.assertTrue(
        repository.find("fixture").getStringOr("name", "").equals("before")
            && repository.generation() == 0,
        "A failed edit cannot alter live state or revisions");
    repository.mutate(
        new ShipRepository.ShipId("fixture"),
        ShipRepository.Mutation.CONFIGURATION,
        c -> c.putString("name", "after"));
    ctx.assertTrue(
        repository.find("fixture") == ship
            && ship.getStringOr("name", "").equals("after")
            && repository.generation() == 1,
        "Successful edits publish atomically while borrowed domain adapters retain record"
            + " identity");
    ctx.succeed();
  }
}
