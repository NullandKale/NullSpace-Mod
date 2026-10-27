package com.nullandkale.nullspace.ship;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.StringTag;

/** Exercises the same coordinator used by the SAVE phase against the actual metadata file. */
public final class SaveCoordinatorGameTest {
  @GameTest
  public void ordinarySavesCoalesceSkipUnchangedAndRetryFailures(GameTestHelper ctx)
      throws Exception {
    var store = ShipStore.get(ctx.getLevel().getServer());
    var file = store.directory.resolve("ships.nbt");
    var observer = ShipStore.writeObserver;
    var writes = new AtomicInteger();
    try {
      store.document("saveCoordinatorProbe", StringTag.valueOf("before"));
      store.save();
      ShipStore.writeObserver =
          p -> {
            if (p.file().equals(file)) writes.incrementAndGet();
          };
      store.document("saveCoordinatorProbe", StringTag.valueOf("after"));
      for (int i = 1; i < 20; i++) {
        store.requestSave();
        store.saveIfRequested(i);
      }
      ctx.assertTrue(writes.get() == 0, "Ordinary requests wait for their save interval");
      store.saveIfRequested(20);
      ctx.assertTrue(
          writes.get() > 0
              && ShipStore.read(file).getStringOr("saveCoordinatorProbe", "").equals("after"),
          "Coalesced requests persist the latest state");
      int completed = writes.get();
      store.saveIfRequested(20);
      store.requestSave();
      store.saveIfRequested(40);
      ctx.assertTrue(
          writes.get() == completed, "Completed and unchanged requests perform no writes");
      byte[] before = Files.readAllBytes(file);
      store.document("saveCoordinatorProbe", StringTag.valueOf("retry"));
      ShipStore.writeObserver =
          p -> {
            if (p.file().equals(file))
              throw new IllegalStateException("injected ordinary save failure");
          };
      store.saveIfRequested(60);
      ctx.assertTrue(
          java.util.Arrays.equals(before, Files.readAllBytes(file)),
          "Failed writes preserve the last good file");
      ShipStore.writeObserver = observer;
      store.saveIfRequested(80);
      ctx.assertTrue(
          ShipStore.read(file).getStringOr("saveCoordinatorProbe", "").equals("retry"),
          "Failure retains the pending request without requiring another mutation");
      store.document("saveCoordinatorProbe", StringTag.valueOf("checkpoint"));
      ShipStore.writeObserver =
          p -> {
            if (p.file().equals(file))
              throw new IllegalStateException("injected checkpoint failure");
          };
      boolean rejected = false;
      try {
        store.save();
      } catch (IllegalStateException expected) {
        rejected = true;
      }
      ctx.assertTrue(
          rejected && ShipStore.read(file).getStringOr("saveCoordinatorProbe", "").equals("retry"),
          "Synchronous checkpoints propagate failures and preserve durable state");
    } finally {
      ShipStore.writeObserver = observer;
      store.document("saveCoordinatorProbe", null);
      store.save();
    }
    ctx.succeed();
  }
}
