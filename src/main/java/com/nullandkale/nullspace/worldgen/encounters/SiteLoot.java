package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/**
 * Loot for generated sites: vanilla loot tables under {@code data/nullspace/loot_table/site/},
 * rolled when a player first opens the container. Room tables give everyday supplies; one treasure
 * container per site scales with the zone's danger (procedural generation design, "Rewards").
 */
public final class SiteLoot
{
    /** The treasure table for a zone: by danger, with black holes and pulsars rarer still. */
    public static String treasure(MinecraftServer server, String zone)
    {
        var place = StarSystems.placeOf(server, zone);
        if (place instanceof GalaxyPlan.Feature f && (f.type() == GalaxyPlan.FeatureType.BLACK_HOLE || f.type() == GalaxyPlan.FeatureType.PULSAR))
            return "treasure_singularity";
        return "treasure_" + StarSystems.danger(server, zone);
    }

    /**
     * Empties the container at {@code at} and gives it a site loot table, rolled on first opening.
     * Returns false if there is no lootable container there.
     */
    public static boolean assign(ServerLevel level, BlockPos at, String table, long seed, String name)
    {
        if (!(level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity container))
            return false;
        container.clearContent();
        container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse("nullspace:site/" + table)), seed);
        container.applyComponents(container.collectComponents(), net.minecraft.core.component.DataComponentPatch.builder().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name)).build());
        container.setChanged();
        return true;
    }

    private SiteLoot()
    {
    }
}
