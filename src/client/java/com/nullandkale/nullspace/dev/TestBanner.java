package com.nullandkale.nullspace.dev;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * A strip across the top of the window naming the graphical test that is running and for how long, drawn over the world, the HUD and every screen, so
 * someone watching a test window can see which test is running and what it was doing if it goes wrong. Only the client game tests set it
 * ({@code ClientTestEvidence}); in play there is never a name and nothing is drawn. {@code -Dnullspace.test.banner=false} turns it off, for screenshots
 * that are kept as documentation.
 */
public final class TestBanner
{
    private static String test = "", step = "";
    private static long started;

    /** A new test has started. */
    public static void test(String name)
    {
        test = name;
        step = "";
        started = System.nanoTime();
    }

    /** What the running test is doing now: a screenshot being taken, a wait, a click. */
    public static void step(String what)
    {
        step = what;
    }

    public static void clear()
    {
        test = "";
        step = "";
    }

    public static void initialize()
    {
        if (!Boolean.parseBoolean(System.getProperty("nullspace.test.banner", "true")))
            return;
        HudElementRegistry.addLast(Identifier.parse("nullspace:test_banner"), (graphics, delta) -> draw(graphics));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> draw(graphics)));
    }

    private static void draw(GuiGraphicsExtractor graphics)
    {
        if (test.isEmpty())
            return;
        var font = Minecraft.getInstance().font;
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String text = "TEST " + test + "  " + seconds / 60 + ":" + String.format("%02d", seconds % 60) + (step.isEmpty() ? "" : "  |  " + step);
        int width = graphics.guiWidth();
        graphics.fill(0, 0, width, 11, 0xcc000000);
        graphics.text(font, text.length() > width / 6 ? text.substring(0, Math.max(0, width / 6)) : text, 3, 2, 0xffffe070, false);
    }

    private TestBanner()
    {
    }
}
