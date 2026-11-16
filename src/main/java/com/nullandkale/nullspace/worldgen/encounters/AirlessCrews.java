package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * The lost crews of the airless planets. Their surfaces spawn every space mob (the planet biomes' spawn list: the suited zombies,
 * villagers, skeletons, strays and wither skeletons, the stowaways, and the repair bots), and this dresses the humanoid ones in
 * the space suit as they appear, as the crews of derelicts are. Killed by a player, one drops salvage: iron and
 * copper, redstone and a little glass. Those are the things a survivor needs to rebuild a ship and cannot count on finding
 * where they land: copper has no source but ore, a lava world has no ore at all, and glass needs sand.
 */
public final class AirlessCrews
{
    private static final String SUITED = "nullspace_airless_crew";

    /** The chance, and the most, of each salvage item per kill. Tuned so about a dozen kills rebuild a small ship's machines. */
    public static final double IRON = .65, COPPER = .9, REDSTONE = .6, GLASS = .2;
    public static final int IRON_MOST = 2, COPPER_MOST = 2, REDSTONE_MOST = 2, GLASS_MOST = 1;

    public static void initialize()
    {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) ->
        {
            if (entity instanceof Mob mob && spaceMob(entity.getType()) && airless(level) && !mob.entityTags().contains(SUITED))
            {
                if (humanoid(entity.getType()))
                    SiteThreats.suit(mob, level.getCurrentDifficultyAt(mob.blockPosition()));
                mob.addTag(SUITED);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) ->
        {
            if (entity.level() instanceof ServerLevel level && entity.entityTags().contains(SUITED) && damage.getEntity() instanceof ServerPlayer)
                for (var stack : salvage(level.getRandom(), entity.getType() == SiteThreats.REPAIR_BOT))
                    level.addFreshEntity(new ItemEntity(level, entity.getX(), entity.getY() + .5, entity.getZ(), stack));
        });
    }

    /** The suited crews: the zombie and its villager, the skeleton, the stray and the wither skeleton. */
    public static boolean humanoid(net.minecraft.world.entity.EntityType<?> type)
    {
        return type == EntityTypes.ZOMBIE || type == EntityTypes.ZOMBIE_VILLAGER || type == EntityTypes.SKELETON || type == EntityTypes.STRAY || type == EntityTypes.WITHER_SKELETON;
    }

    /** Every space mob: the suited crews, the stowaways and the repair bot. */
    public static boolean spaceMob(net.minecraft.world.entity.EntityType<?> type)
    {
        return humanoid(type) || type == com.nullandkale.nullspace.content.entity.Stowaways.GHOST_CREEPER || type == com.nullandkale.nullspace.content.entity.Stowaways.PALE_SPIDER || type == com.nullandkale.nullspace.content.entity.Stowaways.PALE_CAVE_SPIDER || type == com.nullandkale.nullspace.content.entity.Stowaways.PALE_SILVERFISH || type == com.nullandkale.nullspace.content.entity.Stowaways.VIOLET_SLIME || type == com.nullandkale.nullspace.content.entity.Stowaways.VIOLET_BLAZE || type == SiteThreats.REPAIR_BOT;
    }

    /** True on the surface of a planet with no air. */
    public static boolean airless(Level level)
    {
        return level instanceof ServerLevel server && PlanetSurfaces.address(server) != null && !PlanetSurfaces.breathable(server) && !level.dimension().equals(Level.OVERWORLD);
    }

    /** What one kill drops: each item on its own chance, one up to its most. */
    public static List<ItemStack> salvage(RandomSource random, boolean machine)
    {
        var out = new ArrayList<ItemStack>();
        roll(out, random, Items.IRON_INGOT, IRON, IRON_MOST);
        roll(out, random, Items.COPPER_INGOT, COPPER, COPPER_MOST);
        if (machine)
        {
            // A repair bot is made of the very parts a ship needs: a second helping of metal.
            roll(out, random, Items.IRON_INGOT, IRON, IRON_MOST);
            roll(out, random, Items.COPPER_INGOT, COPPER, COPPER_MOST);
        }
        roll(out, random, Items.REDSTONE, REDSTONE, REDSTONE_MOST);
        roll(out, random, Items.GLASS, GLASS, GLASS_MOST);
        return out;
    }

    private static void roll(List<ItemStack> out, RandomSource random, net.minecraft.world.item.Item item, double chance, int most)
    {
        if (random.nextDouble() < chance)
            out.add(new ItemStack(item, 1 + random.nextInt(most)));
    }

    private AirlessCrews()
    {
    }
}
