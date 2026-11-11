package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.worldgen.sites.TerraHarbor;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;

/**
 * Ships whose owners are away: deciding a ship is abandoned and sending offline crew to Terra
 * Harbor.
 */
public final class CrewRelease
{

    /** A ship left on a pad counts as abandoned once its owner has been away this long. */
    public static final long ABANDONED_TICKS = 24000;

    static void seen(MinecraftServer server, UUID player)
    {
        WorldStore.get(server).section("lastSeen").putLong(player.toString(), server.overworld().getGameTime());
        WorldStore.get(server).requestSave();
    }

    /**
     * Whether a ship's owner has been away for an in-game day and none of its crew are online. Anyone
     * at a pad may then store it, so it no longer blocks the pad; it stays the owner's ship.
     */
    public static boolean abandoned(MinecraftServer server, CompoundTag ship)
    {
        String owner = ship.getStringOr("owner", "");
        if (owner.isEmpty())
            return false;
        var online = server.getPlayerList().getPlayers().stream().map(p -> p.getUUID().toString()).collect(java.util.stream.Collectors.toSet());
        if (online.contains(owner))
            return false;
        for (var item : ship.getListOrEmpty("crew"))
            if (online.contains(((CompoundTag) item).getStringOr("id", "")))
                return false;
        long last = WorldStore.get(server).section("lastSeen").getLongOr(owner, -1);
        return last < 0 || server.overworld().getGameTime() - last >= ABANDONED_TICKS;
    }

    /**
     * Offline crew no longer hold a ship in place: they are taken off its crew list and will log in
     * at Terra Harbor instead of inside a ship that has moved.
     */
    static void releaseOfflineCrew(MinecraftServer server, CompoundTag ship, UUID requester)
    {
        com.nullandkale.nullspace.gameplay.travel.RecordJournal.run(server, "release-crew", () ->
        {
            releaseOfflineCrewRecord(server, ship, requester);
            return null;
        });
    }

    private static void releaseOfflineCrewRecord(MinecraftServer server, CompoundTag ship, UUID requester)
    {
        var online = server.getPlayerList().getPlayers().stream().map(p -> p.getUUID().toString()).collect(java.util.stream.Collectors.toSet());
        var crew = ship.getListOrEmpty("crew");
        var store = WorldStore.get(server);
        for (var item : List.copyOf(crew))
        {
            String id = ((CompoundTag) item).getStringOr("id", "");
            if (id.isEmpty() || online.contains(id) || id.equals(requester.toString()))
                continue;
            net.minecraft.world.phys.Vec3 at;
            try
            {
                at = TerraHarbor.arrival(server);
            }
            catch (IllegalStateException busy)
            {
                at = net.minecraft.world.phys.Vec3.atBottomCenterOf(TerraHarbor.ensure(server).offset(8, 1, 12));
            }
            var targets = store.list("targets");
            targets.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id));
            var target = new CompoundTag();
            target.putString("id", id);
            target.putString("dimension", "minecraft:overworld");
            target.putDouble("x", at.x);
            target.putDouble("y", at.y);
            target.putDouble("z", at.z);
            targets.add(target);
            crew.remove(item);
        }
        store.requestSave();
    }

    /** Crew death must remove respawn targets without erasing the right to recover their ship. */
    public static void authorize(CompoundTag ship, ServerPlayer p)
    {
        if (ship.getStringOr("owner", "").isEmpty() || !ship.getCompoundOrEmpty("recallUsers").getBooleanOr(p.getUUID().toString(), false))
            com.nullandkale.nullspace.gameplay.travel.RecordJournal.run(p.level().getServer(), "authorize-crew", () ->
            {
                authorizeRecord(ship, p);
                return null;
            });
    }

    private static void authorizeRecord(CompoundTag ship, ServerPlayer p)
    {
        if (Set.of("station", "debris").contains(ship.getStringOr("kind", "")))
            return;
        if (ship.getStringOr("owner", "").isEmpty())
            ship.putString("owner", p.getUUID().toString());
        var users = ship.getCompoundOrEmpty("recallUsers");
        users.putBoolean(p.getUUID().toString(), true);
        ship.put("recallUsers", users);
    }

    public static void rememberDeath(ServerPlayer p)
    {
        var store = WorldStore.get(p.level().getServer());
        for (var tag : store.ships())
        {
            var ship = (CompoundTag) tag;
            for (var member : ship.getListOrEmpty("crew"))
            {
                var crew = (CompoundTag) member;
                if (!crew.getStringOr("id", "").equals(p.getUUID().toString()))
                    continue;
                String returnShip = crew.getStringOr("returnShip", "");
                var home = !returnShip.isEmpty() ? store.repository().find(returnShip) : Set.of("station", "debris").contains(ship.getStringOr("kind", "")) ? null : ship;
                if (home != null)
                    authorize(home, p);
            }
        }
        store.requestSave();
    }

    private CrewRelease()
    {
    }
}
