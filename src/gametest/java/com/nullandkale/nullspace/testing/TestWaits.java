package com.nullandkale.nullspace.testing;

import com.nullandkale.nullspace.core.mixin.ServerLevelEntitiesAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Waits in a test for what loads on other threads. Entities and chunks load asynchronously, and a test server never sleeps between ticks, so ten ticks can last
 * a few milliseconds: a test that waits a fixed number of ticks and then launches or lands a ship passes when the machine is idle and fails with "Ship
 * passengers are still loading" when it is busy. In a test that launches, lands or recovers a ship, hold the chunks it needs with {@link #hold} and wait with
 * {@link #afterLoaded}, not with {@code ctx.runAfterDelay} (see {@code GameTestHygieneTest}).
 *
 * <p>Only the chunks this test holds are waited for: many tests force chunks and never release them, and a chunk that an earlier test left forced says nothing
 * about this test's fixture.
 */
public final class TestWaits
{
    private record Held(ServerLevel level, long chunk)
    {}

    private static final Map<GameTestHelper, List<Held>> HELD = new WeakHashMap<>();

    /** Forces a chunk loaded for this test and remembers it, so that {@link #afterLoaded} can wait for its entities. */
    public static void hold(GameTestHelper ctx, ServerLevel level, int chunkX, int chunkZ)
    {
        level.setChunkForced(chunkX, chunkZ, true);
        synchronized (HELD)
        {
            HELD.computeIfAbsent(ctx, ignored -> new ArrayList<>()).add(new Held(level, new ChunkPos(chunkX, chunkZ).pack()));
        }
    }

    /**
     * Runs {@code then} once at least {@code minTicks} have passed and every chunk this test holds has its entities. With none held it is a plain delay. The
     * test's own tick limit ends a wait that never finishes.
     */
    public static void afterLoaded(GameTestHelper ctx, int minTicks, Runnable then)
    {
        ctx.runAfterDelay(minTicks, () -> look(ctx, then));
    }

    private static void look(GameTestHelper ctx, Runnable then)
    {
        if (held(ctx))
        {
            then.run();
            return;
        }
        // Ticks do not pass at the speed of loading: a test server spins through them while the threads that load are starved by a busy machine, and a test's
        // tick limit would run out in a few seconds. Give the loading a tick's worth of real time (a real server's tick is 50 ms) each time.
        try
        {
            Thread.sleep(PAUSE_MILLIS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
        // A new callback each time, never this one again: the framework keeps its scheduled callbacks in a map keyed by the Runnable and removes the entry of
        // the one it has just run, so a callback that schedules itself is dropped and the test waits out its tick limit.
        ctx.runAfterDelay(1, () -> look(ctx, then));
    }

    /** How long a wait for loading gives the loader between looks, in real time. */
    private static final long PAUSE_MILLIS = 100;

    /**
     * Runs {@code then} after {@code ticks} ticks, for a wait that is about time passing (a protocol timeout expiring, a countdown running out) and not about
     * anything loading. Say so by using this and not {@code ctx.runAfterDelay}; a wait for loading is {@link #afterLoaded}.
     */
    public static void afterTicks(GameTestHelper ctx, int ticks, Runnable then)
    {
        ctx.runAfterDelay(ticks, then);
    }

    private static boolean held(GameTestHelper ctx)
    {
        List<Held> chunks;
        synchronized (HELD)
        {
            chunks = List.copyOf(HELD.getOrDefault(ctx, List.of()));
        }
        for (var chunk : chunks)
            if (!((ServerLevelEntitiesAccessor) chunk.level()).nullspace$entities().areEntitiesLoaded(chunk.chunk()))
                return false;
        return true;
    }

    private TestWaits()
    {
    }
}
