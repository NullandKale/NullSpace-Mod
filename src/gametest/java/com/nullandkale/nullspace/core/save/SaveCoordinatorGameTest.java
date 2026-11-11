package com.nullandkale.nullspace.core.save;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.StringTag;

/**
 * Each test runs in its own environment, so its own batch: they change global state (the write observer, save-off) that
 * other tests running at the same time would feel.
 *
 * <p>Exercises the real store files: it is written with the world, keeps two copies, survives failed writes and honours
 * save-off.
 */
public final class SaveCoordinatorGameTest
{
    private static boolean storeCopy(Path file)
    {
        return file.getFileName().toString().startsWith("ships-");
    }

    private static String probe(WorldStore store) throws java.io.IOException
    {
        return WorldStore.read(store.storeFile()).getStringOr("saveCoordinatorProbe", "");
    }

    @GameTest(environment = "nullspace:save_with_world")
    public void theStoreIsWrittenWithTheWorldNotOnItsOwnSchedule(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var store = WorldStore.get(server);
        var observer = WorldStore.writeObserver;
        var writes = new AtomicInteger();
        try
        {
            store.document("saveCoordinatorProbe", StringTag.valueOf("before"));
            store.save();
            WorldStore.writeObserver = p ->
            {
                if (storeCopy(p.file()))
                    writes.incrementAndGet();
            };
            store.document("saveCoordinatorProbe", StringTag.valueOf("after"));
            for (int i = 0; i < 100; i++)
                store.requestSave();
            ctx.assertTrue(writes.get() == 0, "Ordinary requests only mark the store changed; nothing is written until the world saves");
            WorldStore.worldSaved(server, false);
            ctx.assertTrue(writes.get() > 0 && probe(store).equals("after"), "A world save writes the latest state");
            int completed = writes.get();
            WorldStore.worldSaved(server, false);
            store.requestSave();
            WorldStore.worldSaved(server, true);
            ctx.assertTrue(writes.get() == completed, "An unchanged store is not written again");
        }
        finally
        {
            WorldStore.writeObserver = observer;
            store.document("saveCoordinatorProbe", null);
            store.save();
        }
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:save_two_copies")
    public void twoCopiesAlternateAndTheNewestIsNeverTheOneBeingWritten(GameTestHelper ctx) throws Exception
    {
        var store = WorldStore.get(ctx.getLevel().getServer());
        try
        {
            store.document("saveCoordinatorProbe", StringTag.valueOf("one"));
            store.save();
            var first = store.storeFile();
            long firstNumber = store.saveNumber();
            store.document("saveCoordinatorProbe", StringTag.valueOf("two"));
            store.save();
            var second = store.storeFile();
            ctx.assertTrue(!first.equals(second), "The next save goes to the other slot");
            ctx.assertTrue(store.saveNumber() == firstNumber + 1, "Every save raises the save number");
            ctx.assertTrue(WorldStore.read(first).getStringOr("saveCoordinatorProbe", "").equals("one"), "The previous copy is untouched by the newer save");
            var loaded = StoreSlots.load(store.directory);
            ctx.assertTrue(loaded.saveNumber() == store.saveNumber() && loaded.root().getStringOr("saveCoordinatorProbe", "").equals("two"), "A restart loads the newest copy");
        }
        finally
        {
            store.document("saveCoordinatorProbe", null);
            store.save();
        }
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:save_failed_writes")
    public void failedWritesKeepTheLastGoodCopyAndRetry(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var store = WorldStore.get(server);
        var observer = WorldStore.writeObserver;
        try
        {
            store.document("saveCoordinatorProbe", StringTag.valueOf("good"));
            store.save();
            byte[] good = Files.readAllBytes(store.storeFile());
            var goodFile = store.storeFile();
            store.document("saveCoordinatorProbe", StringTag.valueOf("retry"));
            WorldStore.writeObserver = p ->
            {
                if (storeCopy(p.file()))
                    throw new IllegalStateException("injected ordinary save failure");
            };
            WorldStore.worldSaved(server, false);
            ctx.assertTrue(java.util.Arrays.equals(good, Files.readAllBytes(goodFile)) && goodFile.equals(store.storeFile()), "A failed ordinary save leaves the newest good copy untouched");
            WorldStore.writeObserver = observer;
            WorldStore.worldSaved(server, false);
            ctx.assertTrue(probe(store).equals("retry"), "The failure kept the change pending, so the next world save writes it");
            store.document("saveCoordinatorProbe", StringTag.valueOf("checkpoint"));
            WorldStore.writeObserver = p ->
            {
                if (storeCopy(p.file()))
                    throw new IllegalStateException("injected checkpoint failure");
            };
            boolean rejected = false;
            try
            {
                WorldStore.worldSaved(server, true);
            }
            catch (IllegalStateException expected)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected && probe(store).equals("retry"), "A forced save (a transfer checkpoint) propagates the failure and preserves durable state");
        }
        finally
        {
            WorldStore.writeObserver = observer;
            store.document("saveCoordinatorProbe", null);
            store.save();
        }
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:save_off")
    public void saveOffKeepsTheStoreWithTheWorld(GameTestHelper ctx) throws Exception
    {
        var server = ctx.getLevel().getServer();
        var store = WorldStore.get(server);
        var previous = new java.util.IdentityHashMap<net.minecraft.server.level.ServerLevel, Boolean>();
        try
        {
            store.document("saveCoordinatorProbe", StringTag.valueOf("before-save-off"));
            store.save();
            for (var level : server.getAllLevels())
            {
                previous.put(level, level.noSave);
                level.noSave = true;
            }
            store.document("saveCoordinatorProbe", StringTag.valueOf("during-save-off"));
            WorldStore.worldSaved(server, false);
            ctx.assertTrue(probe(store).equals("before-save-off"), "With saving off the store is not written, so a backup of the world stays consistent");
            WorldStore.worldSaved(server, true);
            ctx.assertTrue(probe(store).equals("during-save-off"), "A forced save still writes");
        }
        finally
        {
            previous.forEach((level, value) -> level.noSave = value);
            store.document("saveCoordinatorProbe", null);
            store.save();
        }
        ctx.succeed();
    }

    @GameTest(environment = "nullspace:save_concurrent")
    public void concurrentSavesNeverShareAFile(GameTestHelper ctx) throws Exception
    {
        var store = WorldStore.get(ctx.getLevel().getServer());
        var observer = WorldStore.writeObserver;
        try
        {
            concurrentSaves(ctx, store);
        }
        finally
        {
            WorldStore.writeObserver = observer;
            store.document("saveCoordinatorProbe", null);
            store.save();
        }
        ctx.succeed();
    }

    private static void concurrentSaves(GameTestHelper ctx, WorldStore store) throws Exception
    {
        store.document("saveCoordinatorProbe", StringTag.valueOf("concurrent-shutdown"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAttempt = new CountDownLatch(1);
        var secondWrite = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var firstProgress = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable save = () ->
        {
            try
            {
                store.save();
            }
            catch (Throwable error)
            {
                failure.compareAndSet(null, error);
            }
        };
        var first = new Thread(save, "first-shutdown-save");
        var second = new Thread(() ->
        {
            secondAttempt.countDown();
            save.run();
        }, "second-shutdown-save");
        var observer = WorldStore.writeObserver;
        boolean overlapped;
        try
        {
            WorldStore.writeObserver = progress ->
            {
                if (!storeCopy(progress.file()))
                    return;
                if (Thread.currentThread() == second)
                    secondWrite.countDown();
                if (Thread.currentThread() == first && firstProgress.compareAndSet(false, true))
                {
                    entered.countDown();
                    try
                    {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("First save was not released");
                    }
                    catch (InterruptedException interrupted)
                    {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
            };
            first.start();
            ctx.assertTrue(entered.await(5, TimeUnit.SECONDS), "First save reached the actual file write");
            second.start();
            ctx.assertTrue(secondAttempt.await(5, TimeUnit.SECONDS), "Second save attempted concurrently");
            overlapped = secondWrite.await(200, TimeUnit.MILLISECONDS);
        }
        finally
        {
            release.countDown();
            first.join(5000);
            second.join(5000);
            WorldStore.writeObserver = observer;
        }
        ctx.assertTrue(!first.isAlive() && !second.isAlive(), "Both save callers finish");
        ctx.assertTrue(!overlapped, "Concurrent saves cannot write at the same time");
        ctx.assertTrue(failure.get() == null, "Concurrent saves complete without I/O failures");
        ctx.assertTrue(probe(store).equals("concurrent-shutdown"), "The complete shutdown snapshot survives both save callers");
    }
}
