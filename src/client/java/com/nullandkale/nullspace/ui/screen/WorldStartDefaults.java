package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.start.WorldStartSettings;

/**
 * What Create New World offers first: the crash on Terra ({@link WorldStartSettings#NEW_WORLD}). Fabric's client test worlds are made through the same
 * screen, so under the client game tests the ordinary start is offered instead, and every other test keeps the plain world it was written for; the
 * start tests ask for the players' default to check it.
 */
public final class WorldStartDefaults
{
    private static WorldStartSettings offered = System.getProperty("fabric.client.gametest") != null ? WorldStartSettings.DEFAULT : WorldStartSettings.NEW_WORLD;

    public static WorldStartSettings offered()
    {
        return offered;
    }

    /** For the start tests: offer what players are offered (true), or the plain start the other tests use (false). */
    public static void playersDefault(boolean players)
    {
        offered = players ? WorldStartSettings.NEW_WORLD : WorldStartSettings.DEFAULT;
    }

    private WorldStartDefaults()
    {
    }
}
