package com.nullandkale.nullspace.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/**
 * One action at a time per player at a computer. An action is refused while both fewer than the limit's server ticks and less than that many ticks' worth of
 * real time (50 ms a tick) have passed since the player's last request. On a healthy server the two agree. On a lagging server the real time decides, so a
 * slow server never makes a player wait longer than a healthy one would (counted in ticks alone, a server at ten ticks a second refused clicks for twice as
 * long); and a test server, whose ticks pass faster than real time, is held by the ticks. A refused request counts as the last one, so clicking repeatedly
 * keeps being refused until the player pauses.
 */
public final class ActionLimit
{
    private final Map<UUID, long[]> last = new HashMap<>();

    /** Records this request and says whether it comes too soon after the player's previous one. */
    public boolean refuse(MinecraftServer server, UUID player, int ticks)
    {
        long tick = server.getTickCount(), now = System.nanoTime();
        long[] previous = last.put(player, new long[]{tick, now});
        return previous != null && tick - previous[0] < ticks && now - previous[1] < ticks * 50_000_000L;
    }
}
