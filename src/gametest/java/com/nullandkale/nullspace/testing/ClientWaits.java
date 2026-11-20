package com.nullandkale.nullspace.testing;

import java.util.function.IntSupplier;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * The waits of a graphical test. Fabric's waits count ticks, and a client test's ticks pass as fast as frames are drawn: on an idle machine a limit of 200
 * ticks is a second or two, and on a busy one the threads that load chunks, build meshes, generate terrain or work out the sky are starved while the ticks
 * race on, so a wait for their work times out though nothing is wrong. These are Fabric's own waits, checked the same way each tick and failing with the
 * same message, except that a timeout is only accepted once {@link #FLOOR_SECONDS} of real time have also passed. On an idle machine nothing changes.
 * Use these in a graphical test, not the context's {@code waitFor} or {@code waitForChunksRender} (see {@code GameTestHygieneTest}).
 */
public final class ClientWaits
{
    /** The real time a wait is given before its tick limit may end it. */
    public static final int FLOOR_SECONDS = 60;
    private static final String TIMED_OUT = "Timed out waiting for predicate";

    public static int waitFor(ClientGameTestContext context, Predicate<Minecraft> predicate)
    {
        return waitFor(context, predicate, ClientGameTestContext.DEFAULT_TIMEOUT);
    }

    public static int waitFor(ClientGameTestContext context, Predicate<Minecraft> predicate, int ticks)
    {
        return untilFloor(() -> context.waitFor(predicate, ticks), ticks);
    }

    public static int waitFor(TestServerContext server, Predicate<MinecraftServer> predicate)
    {
        return waitFor(server, predicate, ClientGameTestContext.DEFAULT_TIMEOUT);
    }

    public static int waitFor(TestServerContext server, Predicate<MinecraftServer> predicate, int ticks)
    {
        return untilFloor(() -> server.waitFor(predicate, ticks), ticks);
    }

    /** Waits for the chunks round the player to be built into meshes, as {@link TestServerConnection#waitForChunksRender()} does. */
    public static int chunksRendered(TestServerConnection connection)
    {
        return untilFloor(connection::waitForChunksRender, ClientGameTestContext.DEFAULT_TIMEOUT);
    }

    /**
     * Waits until no overlay covers the screen. The keyboard handler drops typed characters while one does, without a word: text typed with
     * {@code keyboardHandler.textInput} under the loading overlay (which fades out over a second of real time) is simply lost, and the test fails later,
     * somewhere else. Call this before every block that types that way (see {@code GameTestHygieneTest}).
     */
    public static void withoutOverlay(ClientGameTestContext context)
    {
        waitFor(context, mc -> mc.gui.overlay() == null);
    }

    private static int untilFloor(IntSupplier wait, int ticks)
    {
        long deadline = System.nanoTime() + FLOOR_SECONDS * 1_000_000_000L;
        int waited = 0;
        while (true)
        {
            try
            {
                return waited + wait.getAsInt();
            }
            catch (AssertionError timeout)
            {
                // A predicate's own failure is not a timeout: it is thrown at once.
                if (!TIMED_OUT.equals(timeout.getMessage()) || System.nanoTime() > deadline)
                    throw timeout;
                waited += ticks;
            }
        }
    }

    private ClientWaits()
    {
    }
}
