package com.nullandkale.nullspace.core.save;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.StringTag;

/** Exercises the same coordinator used by the SAVE phase against the actual metadata file. */
public final class SaveCoordinatorGameTest
{
    @GameTest
    public void ordinarySavesCoalesceSkipUnchangedAndRetryFailures(GameTestHelper ctx) throws Exception
    {
        var store = WorldStore.get(ctx.getLevel().getServer());
        var file = store.directory.resolve("ships.nbt");
        var observer = WorldStore.writeObserver;
        var writes = new AtomicInteger();
        try
        {
            store.document("saveCoordinatorProbe", StringTag.valueOf("before"));
            store.save();
            WorldStore.writeObserver = p ->
            {
                if (p.file().equals(file))
                    writes.incrementAndGet();
            };
            store.document("saveCoordinatorProbe", StringTag.valueOf("after"));
            for (int i = 1; i < 20; i++)
            {
                store.requestSave();
                store.saveIfRequested(i);
            }
            ctx.assertTrue(writes.get() == 0, "Ordinary requests wait for their save interval");
            store.saveIfRequested(20);
            ctx.assertTrue(writes.get() > 0 && WorldStore.read(file).getStringOr("saveCoordinatorProbe", "").equals("after"), "Coalesced requests persist the latest state");
            int completed = writes.get();
            store.saveIfRequested(20);
            store.requestSave();
            store.saveIfRequested(40);
            ctx.assertTrue(writes.get() == completed, "Completed and unchanged requests perform no writes");
            byte[] before = Files.readAllBytes(file);
            store.document("saveCoordinatorProbe", StringTag.valueOf("retry"));
            WorldStore.writeObserver = p ->
            {
                if (p.file().equals(file))
                    throw new IllegalStateException("injected ordinary save failure");
            };
            store.saveIfRequested(60);
            ctx.assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "Failed writes preserve the last good file");
            WorldStore.writeObserver = observer;
            store.saveIfRequested(80);
            ctx.assertTrue(WorldStore.read(file).getStringOr("saveCoordinatorProbe", "").equals("retry"), "Failure retains the pending request without requiring another mutation");
            store.document("saveCoordinatorProbe", StringTag.valueOf("checkpoint"));
            WorldStore.writeObserver = p ->
            {
                if (p.file().equals(file))
                    throw new IllegalStateException("injected checkpoint failure");
            };
            boolean rejected = false;
            try
            {
                store.save();
            }
            catch (IllegalStateException expected)
            {
                rejected = true;
            }
            ctx.assertTrue(rejected && WorldStore.read(file).getStringOr("saveCoordinatorProbe", "").equals("retry"), "Synchronous checkpoints propagate failures and preserve durable state");
            WorldStore.writeObserver = observer;
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
        var file = store.directory.resolve("ships.nbt");
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
                if (!progress.file().equals(file))
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
        ctx.assertTrue(!overlapped, "Concurrent saves cannot write the same temporary file");
        ctx.assertTrue(failure.get() == null, "Concurrent saves complete without I/O failures");
        ctx.assertTrue(WorldStore.read(file).getStringOr("saveCoordinatorProbe", "").equals("concurrent-shutdown"), "The complete shutdown snapshot survives both save callers");
    }
}
