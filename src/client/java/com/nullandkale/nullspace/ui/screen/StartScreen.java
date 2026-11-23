package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.start.StartPackets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/**
 * Covers the world while a new player's start is built (see {@link StartPackets}). It replaces the screen whenever the game would show the world, takes no
 * keys, and leaves only once the player stands where they start and the ground under them has been drawn. A start that keeps failing offers Disconnect.
 */
public final class StartScreen extends Screen
{
    /** How long the cover waits before offering a way out, in ticks. */
    private static final int ESCAPE_TICKS = 600;

    private static boolean waiting, placed;
    /** How many times the cover has gone up, for tests: a start that showed the world first would leave it at zero. */
    private static int covers, received;
    private static String message = "";
    private static int stableTicks, waitedTicks;

    private StartScreen()
    {
        super(Component.literal("Preparing your start"));
    }

    public static int covers()
    {
        return covers;
    }

    /** What the cover knows, for a test that times out waiting for it. */
    public static String state()
    {
        var screen = Minecraft.getInstance().gui.screen();
        return "received " + received + ", covers " + covers + ", waiting " + waiting + ", placed " + placed + ", screen " + (screen == null ? "none" : screen.getClass().getSimpleName());
    }

    /** Whether the cover is up or due, for tests. */
    public static boolean waiting()
    {
        return waiting;
    }

    public static void initialize()
    {
        ClientPlayNetworking.registerGlobalReceiver(StartPackets.Preparing.TYPE, (packet, context) -> context.client().execute(() ->
        {
            received++;
            if (!packet.done())
            {
                waiting = true;
                placed = false;
                stableTicks = 0;
                message = packet.message();
                cover(context.client());
            }
            else if (waiting)
            {
                placed = true;
                stableTicks = 0;
            }
        }));
        ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            if (!waiting)
                return;
            if (client.getConnection() == null)
            {
                reset();
                return;
            }
            waitedTicks++;
            cover(client);
            if (!placed)
                return;
            var feet = client.player == null ? null : client.player.blockPosition();
            boolean drawn = feet != null && client.level != null && client.level.hasChunkAt(feet) && client.levelRenderer.isSectionCompiledAndVisible(feet, 0L);
            // Counted only while the cover is up, so it is shown at least once after vanilla's own terrain screen, and never lifts early behind it.
            stableTicks = drawn && client.gui.screen() instanceof StartScreen ? stableTicks + 1 : 0;
            if (stableTicks >= 10)
            {
                reset();
                if (client.gui.screen() instanceof StartScreen)
                    client.gui.setScreen(null);
            }
        });
    }

    private static void reset()
    {
        waiting = false;
        placed = false;
        message = "";
        stableTicks = 0;
        waitedTicks = 0;
    }

    /** Shows the cover unless vanilla's own terrain loading screen is up: that one closes itself, and the next tick covers again. */
    private static void cover(Minecraft client)
    {
        var screen = client.gui.screen();
        if (screen instanceof StartScreen || screen instanceof LevelLoadingScreen || screen instanceof TravelScreen)
            return;
        covers++;
        client.gui.setScreen(new StartScreen());
    }

    @Override
    protected void init()
    {
        if (waitedTicks >= ESCAPE_TICKS)
            addDisconnect();
    }

    private void addDisconnect()
    {
        addRenderableWidget(Button.builder(Component.literal("Disconnect"), b ->
        {
            reset();
            minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }).bounds(width / 2 - 60, height / 2 + 40, 120, 20).build());
    }

    @Override
    public void tick()
    {
        if (waitedTicks == ESCAPE_TICKS)
            addDisconnect();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc()
    {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)
    {
        graphics.fill(0, 0, width, height, 0xff05080f);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)
    {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String dots = ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
        String line = message.isEmpty() ? "Preparing your start" : message;
        graphics.centeredText(font, placed ? "Almost there" + dots : line.endsWith("...") ? line.substring(0, line.length() - 3) + dots : line, width / 2, height / 2 - 4, 0xffdce8f5);
    }
}
