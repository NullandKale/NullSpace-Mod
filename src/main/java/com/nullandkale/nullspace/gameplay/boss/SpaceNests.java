package com.nullandkale.nullspace.gameplay.boss;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * The nest of the space wither (chunk A8 of {@code docs/design/rewards-work-plan.md}). The chart has one pulsar or black hole, so there is one
 * nest, in its zone. A new space wither appears <b>once a Minecraft day</b> after the last one was killed ({@link #DAY} ticks of
 * game time from the kill, whether or not anyone is there), when a ship with a player aboard is in that zone; the first appears as
 * soon as one is. It stands off the ship's hull, in the vacuum around it. Each kill drops a black hole.
 */
public final class SpaceNests
{
    public static final long DAY = 24000L;

    /** How often the nest looks, in ticks, and how far from the ship's lattice the boss appears. */
    public static final int LOOK = 100, DISTANCE = 70;

    /** Whether a new boss is due: none is alive and a day has passed since the last kill. */
    public static boolean due(long lastKill, long now, boolean alive)
    {
        return !alive && now - lastKill >= DAY;
    }

    private static CompoundTag state(MinecraftServer server)
    {
        return WorldStore.get(server).section("spaceNest");
    }

    /** The zone of the chart's pulsar or black hole, or null on a chart without one. */
    public static String zone(MinecraftServer server)
    {
        for (var feature : StarSystems.galaxy(server).features())
            if (feature.type() == GalaxyPlan.FeatureType.PULSAR || feature.type() == GalaxyPlan.FeatureType.BLACK_HOLE)
                return feature.id();
        return null;
    }

    /** Called when a space wither dies: the day to the next one starts now. */
    public static void killed(MinecraftServer server)
    {
        var state = state(server);
        state.putLong("lastKill", server.overworld().getGameTime());
        state.putString("boss", "");
        WorldStore.get(server).requestSave();
    }

    static void tick(MinecraftServer server)
    {
        if (server.getTickCount() % LOOK != 0)
            return;
        var zone = zone(server);
        var space = server.getLevel(TravelService.SPACE);
        if (zone == null || space == null)
            return;
        var state = state(server);
        String boss = state.getStringOr("boss", "");
        boolean alive = false;
        if (!boss.isEmpty())
        {
            var entity = space.getEntity(UUID.fromString(boss));
            alive = entity == null || entity.isAlive();
        }
        if (!due(state.getLongOr("lastKill", Long.MIN_VALUE / 2), server.overworld().getGameTime(), alive))
            return;
        for (var tag : WorldStore.get(server).ships())
        {
            var ship = (CompoundTag) tag;
            if (!ship.getStringOr("dimension", "").equals(space.dimension().identifier().toString()) || !LocalSpaces.address(ship).equals(zone))
                continue;
            var box = WorldStore.bounds(ship).box().inflate(2);
            if (space.players().stream().noneMatch(p -> box.contains(p.position())))
                continue;
            var at = Vec3.atLowerCornerOf(WorldStore.pos(ship, "base")).add(16, 16, DISTANCE);
            var wither = SpaceBoss.SPACE_WITHER.create(space, EntitySpawnReason.EVENT);
            wither.setPos(at);
            wither.makeInvulnerable();
            space.addFreshEntity(wither);
            state.putString("boss", wither.getUUID().toString());
            WorldStore.get(server).requestSave();
            return;
        }
    }

    private SpaceNests()
    {
    }
}
