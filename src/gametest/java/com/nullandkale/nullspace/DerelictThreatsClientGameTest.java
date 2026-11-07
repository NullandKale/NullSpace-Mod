package com.nullandkale.nullspace;

import com.nullandkale.nullspace.generation.SiteThreats;
import com.nullandkale.nullspace.navigation.StarSystems;
import com.nullandkale.nullspace.navigation.GalaxyPlan;
import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Visual review: the repair bot, suited lost crew and both space suits side by side, then the
 * view from orbit at each kind of galaxy feature (nebula, asteroid field, rogue planet, pulsar,
 * black hole).
 */
public final class DerelictThreatsClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    // A world whose galaxy has the pulsar, a rogue planet and an asteroid field (the black hole
    // and nebula were reviewed in an earlier world).
    long seed = 0;
    for (; seed < 100_000; seed++) {
      var types = new HashSet<GalaxyPlan.FeatureType>();
      for (var f : GalaxyPlan.generate(seed).features()) types.add(f.type());
      if (types.contains(GalaxyPlan.FeatureType.PULSAR)
          && types.contains(GalaxyPlan.FeatureType.ROGUE_PLANET)
          && types.contains(GalaxyPlan.FeatureType.ASTEROID_FIELD)) break;
    }
    String worldSeed = Long.toString(seed);
    try (var world = c.worldBuilder().adjustSettings(s -> s.setSeed(worldSeed)).create()) {
      world.getConnection().waitForChunksRender();
      world
          .getServer()
          .runOnServer(
              server -> {
                var level = server.overworld();
                var floor = new BlockPos(40, 120, 40);
                for (int x = -4; x <= 12; x++)
                  for (int z = -6; z <= 6; z++)
                    level.setBlock(floor.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState(), 18);
                var random = RandomSource.create(5);
                var lineup = new ArrayList<Mob>();
                lineup.add(SiteThreats.REPAIR_BOT.create(level, EntitySpawnReason.COMMAND));
                lineup.add(SiteThreats.crew(level, EntityTypes.ZOMBIE, random));
                lineup.add(SiteThreats.crew(level, EntityTypes.SKELETON, random));
                double x = floor.getX() - 2.5;
                for (var mob : lineup) {
                  mob.setNoAi(true);
                  mob.snapTo(x, floor.getY() + (mob instanceof com.nullandkale.nullspace.generation.RepairBot ? 1.6 : 1), floor.getZ() + 0.5, 180, 0);
                  mob.setYHeadRot(180);
                  mob.setYBodyRot(180);
                  level.addFreshEntity(mob);
                  x += 2;
                }
                for (int i = 0; i < 2; i++) {
                  var stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                  boolean armored = i == 1;
                  stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(armored ? ShipBlocks.ARMORED_HELMET : ShipBlocks.HELMET));
                  stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(armored ? ShipBlocks.ARMORED_CHEST : ShipBlocks.CHEST));
                  stand.setItemSlot(EquipmentSlot.LEGS, new ItemStack(armored ? ShipBlocks.ARMORED_LEGS : ShipBlocks.LEGS));
                  stand.setItemSlot(EquipmentSlot.FEET, new ItemStack(armored ? ShipBlocks.ARMORED_BOOTS : ShipBlocks.BOOTS));
                  stand.snapTo(x, floor.getY() + 1, floor.getZ() + 0.5, 180, 0);
                  level.addFreshEntity(stand);
                  x += 2;
                }
                var p = world.getConnection().getServerPlayer();
                p.setGameMode(GameType.SPECTATOR);
                p.teleportTo(level, floor.getX() + 2.5, floor.getY() + 2.2, floor.getZ() - 5.5, Set.of(), 0, 12, true);
              });
      world.getConnection().waitForChunksRender();
      c.waitTicks(20);
      c.takeScreenshot("nullspace-derelict-threats");
      world
          .getServer()
          .runOnServer(
              server -> {
                var p = world.getConnection().getServerPlayer();
                // Close up on the repair bot and the lost crew, from the side.
                p.teleportTo(server.overworld(), 36.5, 122.3, 37.0, Set.of(), -60, 15, true);
              });
      c.waitTicks(10);
      c.takeScreenshot("nullspace-derelict-threats-back");

      // One orbit per feature type, seen from a ship parked in that zone.
      var seen = new HashSet<GalaxyPlan.FeatureType>();
      List<GalaxyPlan.Feature> features =
          world.getServer().computeOnServer(server -> StarSystems.galaxy(server).features());
      for (var feature : features) {
        if (!seen.add(feature.type())) continue;
        String[] id = {""};
        world
            .getServer()
            .runOnServer(
                server -> {
                  var space = server.getLevel(TravelService.SPACE);
                  var ship =
                      com.nullandkale.nullspace.ship.StationGameTest.fixture(
                          space, new BlockPos(760000 + seen.size() * 4000, 96, 760000), true);
                  ship.putString("localSpace", feature.id());
                  id[0] = ship.getStringOr("id", "");
                  // Where a ship actually arrives in this zone, level and facing the scene.
                  ship.putDouble("arrivalAltitude", 0);
                  var arrival =
                      com.nullandkale.nullspace.navigation.NavigationService.arrivalOrigin(
                          server, ship);
                  ship.putDouble("simX", arrival.x);
                  ship.putDouble("simY", arrival.y);
                  ship.putDouble("simZ", arrival.z);
                  ship.putDouble("simYaw", 0);
                  ship.putDouble("simPitch", 0);
                  ship.putDouble("simRoll", 0);
                  // Above the hull, looking out at the feature.
                  var top = ShipStore.bounds(ship).max();
                  var p = world.getConnection().getServerPlayer();
                  // Crew of the parked ship, so standing outside its hull stays tethered.
                  var member = new net.minecraft.nbt.CompoundTag();
                  member.putString("id", p.getUUID().toString());
                  ship.getListOrEmpty("crew").add(member);
                  if (!ship.contains("crew")) {
                    var crew = new net.minecraft.nbt.ListTag();
                    crew.add(member);
                    ship.put("crew", crew);
                  }
                  p.setGameMode(GameType.CREATIVE);
                  p.teleportTo(space, top.getX() - 3.5, top.getY() + 3, top.getZ() - 3.5, Set.of(), 30, -8, true);
                  p.getAbilities().flying = true;
                  p.onUpdateAbilities();
                });
        world.getConnection().waitForChunksRender();
        c.waitTicks(60);
        for (int heading = 0; heading < 6; heading++) {
          // Four level headings, then straight up and straight down.
          float yaw = 30 + (heading % 4) * 90;
          float pitch = heading < 4 ? -8 : heading == 4 ? -89 : 89;
          world
              .getServer()
              .runOnServer(
                  server -> {
                    var p = world.getConnection().getServerPlayer();
                    p.teleportTo(
                        p.level(), p.getX(), p.getY(), p.getZ(), Set.of(), yaw, pitch, true);
                  });
          c.waitTicks(10);
          c.takeScreenshot(
              "nullspace-feature-orbit-"
                  + feature.type().name().toLowerCase(Locale.ROOT)
                  + "-"
                  + heading);
        }
        world
            .getServer()
            .runOnServer(
                server -> {
                  var ship = ShipStore.get(server).repository().find(id[0]);
                  if (ship != null) ShipStore.get(server).ships().remove(ship);
                });
      }
      ClientTestEvidence.passed(getClass());
    }
  }
}
