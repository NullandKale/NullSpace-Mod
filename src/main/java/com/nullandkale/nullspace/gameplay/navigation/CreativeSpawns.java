package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.model.galaxy.EncounterKind;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.Random;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

/**
 * What a Scanner with the Creative System Upgrade can make: a new star system on the chart, or an encounter site
 * as a destination of its own. Both appear in the Scanner's destination list, saved with the world, and are reached
 * by an ordinary jump.
 */
public final class CreativeSpawns
{
    /** Adds a generated star system within one hop of the ship's place and returns a line saying where it is. */
    public static String system(MinecraftServer server, CompoundTag ship)
    {
        var made = StarSystems.addSystem(server, LocalSpaces.address(ship), new Random().nextLong());
        var first = made.bodies().getFirst();
        return "Spawned the " + made.name() + " system (" + made.bodies().stream().filter(b -> !b.moon()).count() + " planets). Jump to " + first.id() + " from the destination list.";
    }

    /**
     * Adds an encounter of {@code kind} (an {@link EncounterKind} id) at {@code danger} (0 to 3) as a zone of its own near the
     * ship's place. The site is planned like any other and built when a ship arrives.
     */
    public static String encounter(MinecraftServer server, CompoundTag ship, int kind, int danger)
    {
        var chosen = EncounterKind.saved(kind);
        int level = Math.max(0, Math.min(3, danger));
        long seed = new Random().nextLong();
        var zone = StarSystems.addFeature(server, LocalSpaces.address(ship), GalaxyPlan.FeatureType.ASTEROID_FIELD, chosen.label + " signal", seed);
        var site = new CompoundTag();
        site.putInt("kind", chosen.id);
        site.putInt("danger", level);
        site.putLong("seed", seed);
        site.putString("name", chosen.label);
        WorldStore.get(server).section("creativeSites").put(zone.id(), site);
        WorldStore.get(server).requestSave();
        return "Spawned a " + chosen.label.toLowerCase(java.util.Locale.ROOT) + " at danger " + level + ". Jump to " + zone.id() + " from the destination list.";
    }

    private CreativeSpawns()
    {
    }
}
