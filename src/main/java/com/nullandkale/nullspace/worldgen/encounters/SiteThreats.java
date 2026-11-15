package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.entity.RepairBot;
import com.nullandkale.nullspace.content.entity.Stowaways;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;

/**
 * Threats aboard derelicts: lost crew (suited zombies and skeletons) from danger 1 and repair bots
 * from danger 2. They appear once, when a player first reaches the site, and are persistent.
 */
public final class SiteThreats
{
    public static final ResourceKey<EntityType<?>> REPAIR_BOT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse("nullspace:repair_bot"));
    public static final EntityType<RepairBot> REPAIR_BOT = Registry.register(BuiltInRegistries.ENTITY_TYPE, REPAIR_BOT_KEY, EntityType.Builder.of(RepairBot::new, MobCategory.MONSTER).sized(0.6F, 0.6F).eyeHeight(0.3F).clientTrackingRange(8).build(REPAIR_BOT_KEY));

    public static void initialize()
    {
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(REPAIR_BOT, RepairBot.attributes());
        com.nullandkale.nullspace.content.entity.Stowaways.initialize();
        ServerContext.tick(ServerContext.Phase.TRAVEL, SiteThreats::tick);
    }

    /** How many of each threat a site of this danger gets: zombies, skeletons, repair bots. */
    public static int[] counts(int danger)
    {
        return switch (Math.clamp(danger, 0, 3))
        {
            case 0 -> new int[]{0, 0, 0};
            case 1 -> new int[]{2, 0, 0};
            case 2 -> new int[]{2, 1, 1};
            default -> new int[]{3, 2, 2};
        };
    }

    static void tick(MinecraftServer server)
    {
        if (server.getTickCount() % 20 != 10)
            return;
        var occupied = new HashSet<String>();
        for (var player : server.getPlayerList().getPlayers())
            if (player.level().dimension().equals(TravelService.SPACE))
            {
                var observer = SceneService.observer(player);
                if (observer != null)
                    occupied.add(LocalSpaces.address(observer));
            }
        if (occupied.isEmpty())
            return;
        var level = server.getLevel(TravelService.SPACE);
        for (var tag : List.copyOf(WorldStore.get(server).ships()))
        {
            var ship = (CompoundTag) tag;
            if (!ship.getBooleanOr("salvage", false) || !ship.getStringOr("owner", "").isEmpty() || !StationAccess.ready(ship) || !occupied.contains(LocalSpaces.address(ship)))
                continue;
            if (ship.getBooleanOr("threatsPlaced", false))
                reinforce(level, ship);
            else
                populate(level, ship);
        }
    }

    /**
     * Vanilla's local difficulty for a site: its upgrades (armor and enchantments, weapon enchantments, health and speed
     * bonuses, leader zombies with reinforcements) all come from this one value. The danger sets the start and time aboard
     * adds up to a quarter more. It is built from the Hard setting with the chunk time standing in for danger, so the
     * world's own difficulty and clock do not matter.
     */
    public static DifficultyInstance difficulty(int danger, int ticksAboard)
    {
        double share = switch (Math.clamp(danger, 0, 3))
        {
            case 0 -> 0;
            case 1 -> .1;
            case 2 -> .3;
            default -> .6;
        } + .25 * Math.min(1, ticksAboard / (double) FULL_HEAT);
        return new DifficultyInstance(Difficulty.HARD, 0L, (long) (3_600_000L * share), 0F);
    }

    /** The extra chance, at full difficulty, that a suit piece is the armored one (about 20 percent at danger 1, 45 at 2, 75 at 3). */
    static final float EXTRA_ARMOR = .75F;

    /** Ticks aboard after which the time bonus is complete (six minutes). */
    public static final int FULL_HEAT = 7200;
    /** Ticks aboard between one reinforcement and the next (two minutes). */
    public static final int REINFORCE_TICKS = 2400;

    /**
     * While a player is aboard a found vessel, more crew turn up in rooms away from them, up to two per danger level and
     * never beyond a few live at once. They come from the same ranks as the first crew (the hard rank half the time at danger 3), or are a pest one time in three.
     */
    public static Mob reinforce(ServerLevel level, CompoundTag ship)
    {
        String address = LocalSpaces.address(ship);
        int danger = ship.contains("encounterDanger") ? ship.getIntOr("encounterDanger", 0) : StarSystems.danger(level.getServer(), address);
        int heat = ship.getIntOr("siteHeat", 0) + 20;
        ship.putInt("siteHeat", heat);
        int done = ship.getIntOr("reinforcements", 0);
        if (danger < 1 || done >= danger * 2 || heat < (done + 1) * REINFORCE_TICKS)
            return null;
        var bounds = WorldStore.bounds(ship);
        if (level.getEntitiesOfClass(Mob.class, bounds.box(), m -> m instanceof net.minecraft.world.entity.monster.Enemy).size() >= 6 + danger)
            return null;
        var aboard = level.players().stream().filter(p -> p.isAlive() && SceneService.observer(p) != null && LocalSpaces.address(SceneService.observer(p)).equals(address)).toList();
        var random = RandomSource.create(ship.getLongOr("encounterSeed", 1) ^ (done + 1) * 0x9E3779B97F4A7C15L);
        var spots = new ArrayList<>(SitePoints.mobSpots(ship));
        if (spots.isEmpty())
            spots = new ArrayList<>(spots(level, ship, random));
        Collections.shuffle(spots, new Random(random.nextLong()));
        var pressure = new HullScan.LivePressure(level, bounds);
        for (var at : spots)
        {
            if (aboard.stream().anyMatch(p -> p.position().distanceToSqr(at.getX() + .5, at.getY(), at.getZ() + .5) < 16 * 16))
                continue;
            Mob mob = spawn(level, random.nextInt(3), danger == 3 && random.nextBoolean(), danger, at, pressure, difficulty(danger, heat));
            if (mob == null)
                continue;
            mob.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, random.nextFloat() * 360, 0);
            mob.setPersistenceRequired();
            if (!level.addFreshEntity(mob))
                return null;
            ship.putInt("reinforcements", done + 1);
            WorldStore.get(level.getServer()).requestSave();
            return mob;
        }
        return null;
    }

    /** How many pests a site of this danger gets besides its crew and bots: none, one, two, three. */
    public static int pests(int danger)
    {
        return Math.clamp(danger, 0, 3);
    }

    /**
     * One of the stowaways that came aboard as a planetside animal and adapted (see {@link Stowaways}): the white spiders and
     * silverfish, from danger 2 the ghost creeper, and at danger 3 the violet slime and blaze, which are the deepest sites' own.
     */
    public static EntityType<? extends Mob> pest(int danger, RandomSource random)
    {
        var pool = new ArrayList<EntityType<? extends Mob>>(List.of(Stowaways.PALE_SPIDER, Stowaways.PALE_CAVE_SPIDER, Stowaways.PALE_SILVERFISH));
        if (danger >= 2)
            pool.add(Stowaways.GHOST_CREEPER);
        if (danger >= 3)
        {
            pool.add(Stowaways.VIOLET_SLIME);
            pool.add(Stowaways.VIOLET_BLAZE);
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * One crew member for a spot. Kind 0 is a zombie and 1 a skeleton, and 2 is a pest; the hard rank is the zombie villager
     * and the wither skeleton (the latter only where its height fits). A skeleton in a room with no air is a stray.
     */
    static Mob spawn(ServerLevel level, int kind, boolean hard, int danger, BlockPos at, HullScan.LivePressure pressure, DifficultyInstance difficulty)
    {
        if (kind == 2)
            return special(level, pest(danger, level.getRandom()), difficulty);
        EntityType<? extends Mob> type;
        if (kind == 0)
            type = hard ? EntityTypes.ZOMBIE_VILLAGER : EntityTypes.ZOMBIE;
        else if (hard && level.getBlockState(at.above(2)).isAir() && level.getBlockState(at.above()).isAir())
            type = EntityTypes.WITHER_SKELETON;
        else
            type = pressure.breathable(at) ? EntityTypes.SKELETON : EntityTypes.STRAY;
        return crew(level, type, difficulty);
    }

    /** Spawns the site's threats now. Marks the site so it happens only once per build. */
    public static List<Mob> populate(ServerLevel level, CompoundTag ship)
    {
        ship.putBoolean("threatsPlaced", true);
        WorldStore.get(level.getServer()).requestSave();
        String address = LocalSpaces.address(ship);
        int danger = ship.contains("encounterDanger") ? ship.getIntOr("encounterDanger", 0) : StarSystems.danger(level.getServer(), address);
        int[] counts = counts(danger);
        var spawned = new ArrayList<Mob>();
        if (counts[0] + counts[1] + counts[2] == 0)
            return spawned;
        var random = RandomSource.create(ship.getLongOr("encounterSeed", 1) ^ ship.getLongOr("resets", 0) * 31 + 7);
        // Sites built from kit pieces carry the author's mob spots; hand-built sites search for floor.
        var spots = SitePoints.mobSpots(ship);
        if (spots.isEmpty())
            spots = spots(level, ship, random);
        if (spots.isEmpty())
            return spawned;
        int next = 0;
        var pressure = new HullScan.LivePressure(level, WorldStore.bounds(ship));
        var difficulty = difficulty(danger, 0);
        for (int kind = 0; kind < 3; kind++)
            for (int i = 0; i < counts[kind]; i++)
            {
                var at = spots.get(next++ % spots.size());
                // At danger 3 the first of each crew kind is the hard rank.
                Mob mob = kind == 2 ? REPAIR_BOT.create(level, EntitySpawnReason.STRUCTURE) : spawn(level, kind, danger == 3 && i == 0, danger, at, pressure, difficulty);
                if (mob == null)
                    continue;
                mob.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, random.nextFloat() * 360, 0);
                mob.setPersistenceRequired();
                if (level.addFreshEntity(mob))
                    spawned.add(mob);
            }
        for (int i = 0; i < pests(danger); i++)
        {
            var at = spots.get(next++ % spots.size());
            Mob mob = spawn(level, 2, false, danger, at, pressure, difficulty);
            if (mob == null)
                continue;
            mob.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, random.nextFloat() * 360, 0);
            mob.setPersistenceRequired();
            if (level.addFreshEntity(mob))
                spawned.add(mob);
        }
        if (!spawned.isEmpty())
            for (var player : level.players())
                if (SceneService.observer(player) != null && LocalSpaces.address(SceneService.observer(player)).equals(address))
                    player.sendSystemMessage(Component.literal("Movement aboard " + ship.getStringOr("name", "the derelict") + ". Stay sharp."));
        return spawned;
    }

    /** A special creature with vanilla's spawn upgrades for the difficulty; not yet placed. */
    public static Mob special(ServerLevel level, EntityType<? extends Mob> type, DifficultyInstance difficulty)
    {
        var mob = type.create(level, EntitySpawnReason.STRUCTURE);
        if (mob != null)
            mob.finalizeSpawn(level, difficulty, EntitySpawnReason.STRUCTURE, null);
        return mob;
    }

    public static Mob crew(ServerLevel level, EntityType<? extends Mob> type)
    {
        return crew(level, type, difficulty(3, 0));
    }

    /**
     * A crew member with every vanilla spawn upgrade for this difficulty. Wherever vanilla would hand the mob a piece of
     * armor it wears the armored suit instead, with the same enchantments; the other pieces are the plain suit. No jockeys,
     * no babies, and no door breaking, which would let a mob vent the rooms.
     */
    public static Mob crew(ServerLevel level, EntityType<? extends Mob> type, DifficultyInstance difficulty)
    {
        var mob = type.create(level, EntitySpawnReason.STRUCTURE);
        if (mob == null)
            return null;
        mob.finalizeSpawn(level, difficulty, EntitySpawnReason.STRUCTURE, mob instanceof net.minecraft.world.entity.monster.zombie.Zombie ? new net.minecraft.world.entity.monster.zombie.Zombie.ZombieGroupData(false, false) : null);
        if (mob instanceof net.minecraft.world.entity.monster.zombie.Zombie zombie)
            zombie.setCanBreakDoors(false);
        var slots = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        var plain = List.of(ModBlocks.HELMET, ModBlocks.CHEST, ModBlocks.LEGS, ModBlocks.BOOTS);
        var armored = List.of(ModBlocks.ARMORED_HELMET, ModBlocks.ARMORED_CHEST, ModBlocks.ARMORED_LEGS, ModBlocks.ARMORED_BOOTS);
        for (int i = 0; i < slots.size(); i++)
        {
            var vanilla = mob.getItemBySlot(slots.get(i));
            // Vanilla's own armor roll is low, so each piece also gets a second chance that grows with the difficulty.
            boolean armor = !vanilla.isEmpty() || mob.getRandom().nextFloat() < difficulty.getSpecialMultiplier() * EXTRA_ARMOR;
            var suit = new ItemStack(armor ? armored.get(i) : plain.get(i));
            var enchantments = vanilla.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
            if (enchantments != null && !enchantments.isEmpty())
                suit.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, enchantments);
            mob.setItemSlot(slots.get(i), suit);
            mob.setDropChance(slots.get(i), 0.08F);
        }
        if (mob instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton && mob.getMainHandItem().isEmpty())
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.BOW));
        return mob;
    }

    /**
     * Standing spots inside the site: two air blocks over a solid floor with a ceiling within six
     * blocks, so mobs start indoors rather than on the roof. Shuffled by the site's seed.
     */
    static List<BlockPos> spots(ServerLevel level, CompoundTag ship, RandomSource random)
    {
        var bounds = WorldStore.bounds(ship);
        var spots = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP))
                continue;
            boolean roofed = false;
            for (int up = 2; up <= 6 && !roofed; up++)
                roofed = !level.getBlockState(at.above(up)).isAir() && bounds.contains(at.above(up));
            if (roofed)
                spots.add(at.immutable());
        }
        Collections.shuffle(spots, new Random(random.nextLong()));
        return spots;
    }

    private SiteThreats()
    {
    }
}
