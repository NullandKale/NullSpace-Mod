package com.nullandkale.nullspace.content.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;

/**
 * The stowaways: planetside animals that came aboard in cargo and adapted to the ship. Each is the vanilla creature with its
 * own entity type, so its look belongs to the ship and planets keep their vanilla creatures. The ghost creeper is grey and
 * half transparent, the spiders and silverfish are white, and the slime and blaze are violet. Their behavior is the vanilla
 * creature's; only the type, and so the texture, differs.
 */
public final class Stowaways
{
    public static final class GhostCreeper extends Creeper
    {
        public GhostCreeper(EntityType<? extends Creeper> type, Level level)
        {
            super(type, level);
        }
    }

    public static final class PaleSpider extends Spider
    {
        public PaleSpider(EntityType<? extends Spider> type, Level level)
        {
            super(type, level);
        }
    }

    public static final class PaleCaveSpider extends CaveSpider
    {
        public PaleCaveSpider(EntityType<? extends CaveSpider> type, Level level)
        {
            super(type, level);
        }
    }

    public static final class PaleSilverfish extends Silverfish
    {
        public PaleSilverfish(EntityType<? extends Silverfish> type, Level level)
        {
            super(type, level);
        }
    }

    public static final class VioletSlime extends Slime
    {
        public VioletSlime(EntityType<? extends Slime> type, Level level)
        {
            super(type, level);
        }
    }

    public static final class VioletBlaze extends Blaze
    {
        public VioletBlaze(EntityType<? extends Blaze> type, Level level)
        {
            super(type, level);
        }
    }

    private static <T extends Entity> EntityType<T> register(String id, EntityType.EntityFactory<T> factory, EntityType<?> like, int tracking, boolean fire)
    {
        var key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse("nullspace:" + id));
        var size = like.getDimensions();
        var builder = EntityType.Builder.of(factory, MobCategory.MONSTER).sized(size.width(), size.height()).eyeHeight(size.eyeHeight()).clientTrackingRange(tracking);
        if (fire)
            builder = builder.fireImmune();
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }

    public static final EntityType<GhostCreeper> GHOST_CREEPER = register("ghost_creeper", GhostCreeper::new, EntityTypes.CREEPER, 8, false);
    public static final EntityType<PaleSpider> PALE_SPIDER = register("pale_spider", PaleSpider::new, EntityTypes.SPIDER, 8, false);
    public static final EntityType<PaleCaveSpider> PALE_CAVE_SPIDER = register("pale_cave_spider", PaleCaveSpider::new, EntityTypes.CAVE_SPIDER, 8, false);
    public static final EntityType<PaleSilverfish> PALE_SILVERFISH = register("pale_silverfish", PaleSilverfish::new, EntityTypes.SILVERFISH, 8, false);
    public static final EntityType<VioletSlime> VIOLET_SLIME = register("violet_slime", VioletSlime::new, EntityTypes.SLIME, 10, false);
    public static final EntityType<VioletBlaze> VIOLET_BLAZE = register("violet_blaze", VioletBlaze::new, EntityTypes.BLAZE, 8, true);

    public static void initialize()
    {
        // Where they may spawn on a planet: on the ground, in the dark, like any monster; the blaze, which burns, in any light.
        for (var type : java.util.List.of(GHOST_CREEPER, PALE_SPIDER, PALE_CAVE_SPIDER, PALE_SILVERFISH, VIOLET_SLIME))
            place(type, false);
        place(VIOLET_BLAZE, true);
        FabricDefaultAttributeRegistry.register(GHOST_CREEPER, Creeper.createAttributes());
        FabricDefaultAttributeRegistry.register(PALE_SPIDER, Spider.createAttributes());
        FabricDefaultAttributeRegistry.register(PALE_CAVE_SPIDER, CaveSpider.createCaveSpider());
        FabricDefaultAttributeRegistry.register(PALE_SILVERFISH, Silverfish.createAttributes());
        FabricDefaultAttributeRegistry.register(VIOLET_SLIME, Monster.createMonsterAttributes());
        FabricDefaultAttributeRegistry.register(VIOLET_BLAZE, Blaze.createAttributes());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void place(EntityType<? extends Mob> type, boolean anyLight)
    {
        com.nullandkale.nullspace.core.mixin.SpawnPlacementsInvoker.nullspace$register((EntityType) type, net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, anyLight ? (net.minecraft.world.entity.SpawnPlacements.SpawnPredicate) (t, level, reason, pos, random) -> Monster.checkAnyLightMonsterSpawnRules((EntityType) t, level, reason, pos, random) : (net.minecraft.world.entity.SpawnPlacements.SpawnPredicate) (t, level, reason, pos, random) -> Monster.checkMonsterSpawnRules((EntityType) t, level, reason, pos, random));
    }

    private Stowaways()
    {
    }
}
