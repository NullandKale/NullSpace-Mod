package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.entity.RepairBot;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
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
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;

/**
 * Threats aboard derelicts: lost crew (suited zombies and skeletons) from danger 1 and repair bots
 * from danger 2. They appear once, when a player first reaches the site, and are persistent.
 */
public final class SiteThreats {
  public static final ResourceKey<EntityType<?>> REPAIR_BOT_KEY =
      ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse("nullspace:repair_bot"));
  public static final EntityType<RepairBot> REPAIR_BOT =
      Registry.register(
          BuiltInRegistries.ENTITY_TYPE,
          REPAIR_BOT_KEY,
          EntityType.Builder.of(RepairBot::new, MobCategory.MONSTER)
              .sized(0.6F, 0.6F)
              .eyeHeight(0.3F)
              .clientTrackingRange(8)
              .build(REPAIR_BOT_KEY));

  private static final String[] NAMES = {
    "Ade", "Brin", "Cass", "Dov", "Esk", "Fen", "Gale", "Hollis", "Ivo", "Jun", "Kade", "Lio",
    "Mara", "Nils", "Oda", "Pell", "Quin", "Rhee", "Sol", "Tarn", "Ulla", "Vex", "Wren", "Yara"
  };

  public static void initialize() {
    net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(
        REPAIR_BOT, RepairBot.attributes());
    ServerContext.tick(ServerContext.Phase.TRAVEL, SiteThreats::tick);
  }

  /** How many of each threat a site of this danger gets: zombies, skeletons, repair bots. */
  public static int[] counts(int danger) {
    return switch (Math.clamp(danger, 0, 3)) {
      case 0 -> new int[] {0, 0, 0};
      case 1 -> new int[] {2, 0, 0};
      case 2 -> new int[] {2, 1, 1};
      default -> new int[] {3, 2, 2};
    };
  }

  static void tick(MinecraftServer server) {
    if (server.getTickCount() % 20 != 10) return;
    var occupied = new HashSet<String>();
    for (var player : server.getPlayerList().getPlayers())
      if (player.level().dimension().equals(TravelService.SPACE)) {
        var observer = SceneService.observer(player);
        if (observer != null) occupied.add(LocalSpaces.address(observer));
      }
    if (occupied.isEmpty()) return;
    var level = server.getLevel(TravelService.SPACE);
    for (var tag : List.copyOf(WorldStore.get(server).ships())) {
      var ship = (CompoundTag) tag;
      if (!ship.getBooleanOr("salvage", false)
          || ship.getBooleanOr("threatsPlaced", false)
          || !ship.getStringOr("owner", "").isEmpty()
          || !StationAccess.ready(ship)
          || !occupied.contains(LocalSpaces.address(ship))) continue;
      populate(level, ship);
    }
  }

  /** Spawns the site's threats now. Marks the site so it happens only once per build. */
  public static List<Mob> populate(ServerLevel level, CompoundTag ship) {
    ship.putBoolean("threatsPlaced", true);
    WorldStore.get(level.getServer()).requestSave();
    String address = LocalSpaces.address(ship);
    int danger =
        ship.contains("encounterDanger")
            ? ship.getIntOr("encounterDanger", 0)
            : StarSystems.danger(level.getServer(), address);
    int[] counts = counts(danger);
    var spawned = new ArrayList<Mob>();
    if (counts[0] + counts[1] + counts[2] == 0) return spawned;
    var random =
        RandomSource.create(
            ship.getLongOr("encounterSeed", 1) ^ ship.getLongOr("resets", 0) * 31 + 7);
    var spots = spots(level, ship, random);
    if (spots.isEmpty()) return spawned;
    int next = 0;
    for (int kind = 0; kind < 3; kind++)
      for (int i = 0; i < counts[kind]; i++) {
        var at = spots.get(next++ % spots.size());
        Mob mob =
            switch (kind) {
              case 0 -> crew(level, EntityTypes.ZOMBIE, random);
              case 1 -> crew(level, EntityTypes.SKELETON, random);
              default -> REPAIR_BOT.create(level, EntitySpawnReason.STRUCTURE);
            };
        if (mob == null) continue;
        mob.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, random.nextFloat() * 360, 0);
        mob.setPersistenceRequired();
        if (level.addFreshEntity(mob)) spawned.add(mob);
      }
    if (!spawned.isEmpty())
      for (var player : level.players())
        if (SceneService.observer(player) != null
            && LocalSpaces.address(SceneService.observer(player)).equals(address))
          player.sendSystemMessage(
              Component.literal(
                  "Movement aboard " + ship.getStringOr("name", "the derelict") + ". Stay sharp."));
    return spawned;
  }

  public static Mob crew(ServerLevel level, EntityType<? extends Mob> type, RandomSource random) {
    var mob = type.create(level, EntitySpawnReason.STRUCTURE);
    if (mob == null) return null;
    mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModBlocks.HELMET));
    mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModBlocks.CHEST));
    mob.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModBlocks.LEGS));
    mob.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModBlocks.BOOTS));
    if (type == EntityTypes.SKELETON)
      mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.BOW));
    for (var slot :
        List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
      mob.setDropChance(slot, 0.08F);
    mob.setCustomName(Component.literal("Lost crew " + NAMES[random.nextInt(NAMES.length)]));
    return mob;
  }

  /**
   * Standing spots inside the site: two air blocks over a solid floor with a ceiling within six
   * blocks, so mobs start indoors rather than on the roof. Shuffled by the site's seed.
   */
  static List<BlockPos> spots(ServerLevel level, CompoundTag ship, RandomSource random) {
    var bounds = WorldStore.bounds(ship);
    var spots = new ArrayList<BlockPos>();
    for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
      if (!level.getBlockState(at).isAir()
          || !level.getBlockState(at.above()).isAir()
          || !level
              .getBlockState(at.below())
              .isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)) continue;
      boolean roofed = false;
      for (int up = 2; up <= 6 && !roofed; up++)
        roofed = !level.getBlockState(at.above(up)).isAir() && bounds.contains(at.above(up));
      if (roofed) spots.add(at.immutable());
    }
    Collections.shuffle(spots, new Random(random.nextLong()));
    return spots;
  }

  private SiteThreats() {}
}
