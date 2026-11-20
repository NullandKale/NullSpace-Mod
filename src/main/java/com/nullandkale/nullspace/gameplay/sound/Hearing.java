package com.nullandkale.nullspace.gameplay.sound;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.systems.RoomAcoustics;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Who hears a sound and how muffled: sound needs air to carry. In space and on airless worlds a listener inside a hull hears only that hull, and nothing outside
 * any hull is heard at all; on worlds with air a sound outside the listener's hull comes through it muffled. Inside a hull the rooms muffle by their doors and
 * walls ({@link RoomAcoustics}), and two docked vessels carry sound to each other like a room through a door.
 */
public final class Hearing
{
    /** What {@link #muffle} answers for a sound that is not heard. */
    public static final float SILENT = -1;

    /** How muffled a sound at {@code source} is for a listener whose head is at {@code ear}: 0 clear to 1 almost gone, or {@link #SILENT}. */
    public static float muffle(ServerLevel level, Vec3 ear, Vec3 source)
    {
        var store = WorldStore.get(level.getServer());
        var at = BlockPos.containing(ear);
        var from = BlockPos.containing(source);
        var listenerHull = store.at(level, at);
        var sourceHull = store.at(level, from);
        boolean air = !PlanetSurfaces.vacuum(level);
        if (listenerHull != null && sourceHull != null && id(listenerHull).equals(id(sourceHull)))
            return rooms(RoomAcoustics.cost(level, listenerHull, from, at));
        if (listenerHull != null && sourceHull != null && docked(listenerHull, sourceHull))
            return rooms(RoomAcoustics.DOOR);
        if (!air)
            return SILENT;
        return listenerHull == null && sourceHull == null ? 0 : rooms(RoomAcoustics.WALL);
    }

    /** The muffling of a path between rooms: a closed door darkens a sound, two more so, a wall leaves a faint thud, and much more is not heard. */
    public static float rooms(int cost)
    {
        return switch (cost)
        {
            case 0 -> 0;
            case 1 -> .45f;
            case 2 -> .65f;
            case 3 -> .8f;
            case 4 -> .88f;
            default -> SILENT;
        };
    }

    private static String id(CompoundTag ship)
    {
        return ship.getStringOr("id", "");
    }

    private static boolean docked(CompoundTag a, CompoundTag b)
    {
        for (var pair : new CompoundTag[][]{{a, b}, {b, a}})
        {
            var dock = pair[0].getCompoundOrEmpty("dock");
            if (dock.getStringOr("state", "").equals("CLAMPED") && (dock.getStringOr("target", "").equals(id(pair[1])) || dock.getStringOr("source", "").equals(id(pair[1]))))
                return true;
        }
        return false;
    }

    private Hearing()
    {
    }
}
