package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.model.flight.JumpFlash;
import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * The flash over the world while a jump changes the sky (see {@link JumpFlash}). It is drawn under the hotbar and chat and is not a screen, so the player keeps
 * moving and using the ship through it; it holds while the new sky is not ready yet, so the old one never shows after the flash.
 */
public final class JumpFlashOverlay
{
    private static long started;
    private static String scene = "";
    private static double ready = Double.POSITIVE_INFINITY;
    private static boolean active;

    /** Starts a flash for a jump to the scene a server's progress packet names ({@code scene:<id>}, or empty). */
    public static void start(String target)
    {
        started = System.nanoTime();
        scene = target.startsWith("scene:") ? target.substring(6) : "";
        ready = scene.isEmpty() ? 0 : Double.POSITIVE_INFINITY;
        active = true;
    }

    /** Whether a flash is showing, for tests. */
    public static boolean active()
    {
        return active;
    }

    /** How strongly the flash covers the screen now, for tests. */
    public static double opacity()
    {
        return active ? JumpFlash.opacity((System.nanoTime() - started) / 1e9, ready) : 0;
    }

    public static void initialize()
    {
        HudElementRegistry.addFirst(Identifier.parse("nullspace:jump_flash"), (graphics, delta) ->
        {
            if (!active)
                return;
            double age = (System.nanoTime() - started) / 1e9;
            if (ready == Double.POSITIVE_INFINITY && SpaceSceneRenderer.readyForScene(scene))
                ready = age;
            if (JumpFlash.over(age) || age > ready + JumpFlash.FADE)
            {
                active = false;
                return;
            }
            int alpha = (int) Math.round(JumpFlash.opacity(age, ready) * 255);
            if (alpha > 0)
                graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | 0xe6efff);
        });
    }

    private JumpFlashOverlay()
    {
    }
}
