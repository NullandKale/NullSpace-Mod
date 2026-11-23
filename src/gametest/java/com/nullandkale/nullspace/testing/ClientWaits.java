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
        // The banner over the window says where the test is, so a stuck wait can be found by line.
        StackWalker.getInstance().walk(frames -> frames.filter(f -> !f.getClassName().equals(ClientWaits.class.getName())).findFirst()).ifPresent(f -> com.nullandkale.nullspace.dev.TestBanner.step("waiting at " + f.getFileName() + ":" + f.getLineNumber()));
        try
        {
            return untilFloor(() -> context.waitFor(predicate, ticks), ticks);
        }
        catch (AssertionError timeout)
        {
            if (!TIMED_OUT.equals(timeout.getMessage()))
                throw timeout;
            // Say what the client showed when the wait gave up: a bare "timed out" leaves the next run to find out.
            AssertionError described = new AssertionError(TIMED_OUT + " after " + FLOOR_SECONDS + " s; the client showed " + describe(context), timeout);
            throw described;
        }
    }

    /** The screen, a terminal's status and rows, any overlay, and the player's position. */
    private static String describe(ClientGameTestContext context)
    {
        try
        {
            return context.computeOnClient(mc ->
            {
                var out = new StringBuilder();
                var screen = mc.gui.screen();
                out.append("screen ").append(screen == null ? "none" : screen.getClass().getSimpleName());
                if (screen instanceof com.nullandkale.nullspace.ui.screen.TerminalScreen terminal)
                {
                    out.append(", status \"").append(terminal.status()).append("\", rows");
                    for (int row = 0; row < com.nullandkale.nullspace.ui.terminal.TerminalBuffer.ROWS; row++)
                    {
                        String text = terminal.buffer().row(row).strip();
                        if (!text.isEmpty())
                            out.append(" | ").append(row).append(": ").append(text);
                    }
                }
                if (mc.gui.overlay() != null)
                    out.append(", overlay ").append(mc.gui.overlay().getClass().getSimpleName());
                if (mc.player != null)
                    out.append(String.format(", player at %.2f %.2f %.2f in %s", mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.level().dimension().identifier()));
                return out.toString();
            });
        }
        catch (RuntimeException | AssertionError unreadable)
        {
            return "(the client state could not be read: " + unreadable + ")";
        }
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
