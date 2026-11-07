package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.dev.SphereLab;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.systems.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

public final class CombatGameTest {
  @GameTest(maxTicks = 160)
  public void nativeTntDamagesSimulatedNeighbor(GameTestHelper ctx) {
    var level = ctx.getLevel().getServer().getLevel(TravelService.SPACE);
    var anchor = ctx.absolutePos(new BlockPos(24576, 0, 24576));
    var base = new BlockPos(anchor.getX(), 96, anchor.getZ());
    var target = base.offset(8192, 0, 0);
    double simY = base.getX() * 64.0, simZ = base.getZ() * 64.0;
    build(level, base, 2000000, simY, simZ, 0);
    build(level, target, 2000032, simY, simZ, 0);
    var isolatedBase = target.offset(8192, 0, 0);
    var isolated = build(level, isolatedBase, 2000032, simY, simZ, 0);
    isolated.putString(
        "localSpace",
        com.nullandkale.nullspace.navigation.TestZones.otherSystem(level.getServer()));
    for (var center : List.of(base, target, isolatedBase))
      for (int x = center.getX() >> 4; x <= (center.getX() + 33) >> 4; x++)
        for (int z = center.getZ() >> 4; z <= (center.getZ() + 31) >> 4; z++)
          level.setChunkForced(x, z, true);
    var hull = target.offset(0, 15, 16);
    ctx.assertTrue(!level.getBlockState(hull).isAir(), "TNT target must start with a real hull");
    var bomb = new net.minecraft.world.entity.item.PrimedTnt[1];
    int[] ticks = {0};
    ctx.onEachTick(
        () -> {
          if (++ticks[0] < 20) return;
          if (bomb[0] == null) {
            var tnt =
                net.minecraft.world.entity.EntityTypes.TNT.create(
                    level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            tnt.setPos(base.getX() + 32.5, base.getY() + 15.5, base.getZ() + 16.5);
            tnt.setDeltaMovement(Vec3.ZERO);
            tnt.setNoGravity(true);
            tnt.setFuse(2);
            level.addFreshEntity(tnt);
            bomb[0] = tnt;
            return;
          }
          if (!bomb[0].isRemoved()) return;
          ctx.assertTrue(
              level.getBlockState(hull).isAir(),
              "Real ticking TNT must damage a simulated neighbor whose physical hull is 8192 blocks"
                  + " away");
          ctx.assertTrue(
              !level.getBlockState(isolatedBase.offset(0, 15, 16)).isAir(),
              "Native TNT cannot damage a ship at identical simulated coordinates in a different"
                  + " region");
          ctx.succeed();
        });
  }

  @GameTest(maxTicks = 100)
  public void rotatedRemoteHullHitAndBlastIsolation(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.getLevel(TravelService.SPACE);
    var anchor = ctx.absolutePos(new BlockPos(8192, 0, 8192));
    var base = new BlockPos(anchor.getX(), 96, anchor.getZ());
    var target = base.offset(8192, 0, 0);
    double simY = base.getX() * 64.0, simZ = base.getZ() * 64.0;
    var a = build(level, base, 1000000, simY, simZ, 0);
    var b = build(level, target, 1000180, simY, simZ + 32, 90);
    var gun = base.offset(31, 15, 16);
    level.setBlock(
        gun,
        ShipBlocks.WEAPON
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST),
        18);
    level.setBlock(base.offset(14, 5, 21), ShipBlocks.GENERATOR.defaultBlockState(), 18);
    com.nullandkale.nullspace.systems.FuelSystem.provision(level, base.offset(14, 5, 21), 64);
    level.setBlock(base.offset(15, 5, 21), ShipBlocks.BATTERY.defaultBlockState(), 18);
    level.setBlock(
        base.offset(16, 5, 21),
        ShipBlocks.AIR_SUPPLY
            .defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
        18);
    ShipSystems.upgrade(level, a);
    a.getCompoundOrEmpty("systems").putIntArray("requests", new int[] {1, 2, 1, 0, 0, 2});
    var player = ctx.makeMockServerPlayerInLevel();
    player.teleportTo(
        level, base.getX() + 16.5, base.getY() + 15, base.getZ() + 23.5, Set.of(), 0, 0, true);
    for (var center : List.of(base, target))
      for (int x = center.getX() >> 4; x <= (center.getX() + 31) >> 4; x++)
        for (int z = center.getZ() >> 4; z <= (center.getZ() + 31) >> 4; z++)
          level.setChunkForced(x, z, true);
    var unrelated = target.offset(15, 15, -1);
    level.setBlock(unrelated, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
    ctx.runAfterDelay(
        20,
        () -> {
          var origin = new Vec3(1000032.1, simY + 15.5, simZ + 16.5);
          var hit = ShipCombat.trace(level, b, origin, new Vec3(1, 0, 0), 1000);
          ctx.assertTrue(
              hit != null && Math.abs(hit.local().z) < .01,
              "Virtual ray must reach rotated target's actual front hull");
          var block =
              BlockPos.containing(Vec3.atLowerCornerOf(target).add(hit.local()).add(0, 0, .01));
          ctx.assertTrue(
              !level.getBlockState(block).isAir(),
              "Ray must resolve an actual block before impact");
          var distant = new ArrayList<CompoundTag>();
          for (int n = 0; n < 130; n++) {
            var tag = new CompoundTag();
            tag.putString("id", UUID.randomUUID().toString());
            tag.putString("dimension", TravelService.SPACE.identifier().toString());
            tag.putLong("base", base.offset(16384 + n * 64, 0, 0).asLong());
            tag.putDouble("simX", 1000100);
            tag.putDouble("simY", simY + 100000 + n * 64);
            tag.putDouble("simZ", simZ);
            distant.add(tag);
            ShipStore.get(server).ships().add(tag);
          }
          String result;
          try {
            result = ShipCombat.fire(player, ShipStore.pos(a, "controller"));
          } finally {
            ShipStore.get(server).ships().removeAll(distant);
          }
          ctx.assertTrue(result.startsWith("Impact"), "Powered cannon must report a real impact");
          ctx.assertTrue(
              level.getBlockState(block).isAir(), "Mapped blast must remove target hull blocks");
          ctx.assertTrue(
              level.getBlockState(unrelated).is(Blocks.DIAMOND_BLOCK),
              "Blast must not mutate outside target ownership");
          boolean cooldown = false;
          try {
            ShipCombat.fire(player, ShipStore.pos(a, "controller"));
          } catch (IllegalArgumentException e) {
            cooldown = e.getMessage().contains("recharging");
          }
          ctx.assertTrue(cooldown, "Repeated requests must not fire a second shot");
          var physical = player.position();
          var storage = ShipStore.pos(a, "base");
          try {
            int poseResult =
                server
                    .getCommands()
                    .getDispatcher()
                    .execute(
                        "nullspace scene pose 1000000 "
                            + String.format(Locale.ROOT, "%.0f %.0f 90 0 0", simY, simZ),
                        server
                            .createCommandSourceStack()
                            .withEntity(player)
                            .withLevel(level)
                            .withPosition(player.position()));
            ctx.assertTrue(poseResult == 1, "Operator pose command must execute");
          } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw new AssertionError(error);
          }

          var updated = ShipStore.get(server).at(level, player.blockPosition());
          ctx.assertTrue(
              updated != null
                  && updated.getStringOr("id", "").equals(a.getStringOr("id", ""))
                  && updated.getDoubleOr("simYaw", 0) == 90
                  && ShipStore.pos(updated, "base").equals(storage)
                  && player.position().equals(physical),
              "Operator pose controls must rotate the simulated scene without moving storage blocks"
                  + " or crew; pose="
                  + (updated == null ? "missing" : updated.getDoubleOr("simYaw", 0))
                  + " position="
                  + player.position());
          var store = ShipStore.get(server);
          var previous = store.snapshot().get("pendingImpacts");
          var full = new ListTag();
          for (int n = 0; n < 128; n++) full.add(new CompoundTag());
          store.document("pendingImpacts", full);
          boolean rejected = false;
          try {
            ShipCombat.fire(player, ShipStore.pos(updated, "controller"));
          } catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("queue is full");
          } finally {
            if (previous == null) store.document("pendingImpacts", null);
            else store.document("pendingImpacts", previous);
          }
          ctx.assertTrue(
              rejected,
              "A full arrival queue must reject new cannon fire before accepting an impact");
          server.getPlayerList().remove(player);
          ctx.succeed();
        });
  }

  private static CompoundTag build(
      net.minecraft.server.level.ServerLevel level,
      BlockPos base,
      double x,
      double y,
      double z,
      double yaw) {
    var store = ShipStore.get(level.getServer());
    store
        .ships()
        .removeIf(
            t ->
                ((CompoundTag) t)
                        .getStringOr("dimension", "")
                        .equals(TravelService.SPACE.identifier().toString())
                    && ShipStore.pos((CompoundTag) t, "base").equals(base));
    SphereLab.buildShip(level, base.below());
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", TravelService.SPACE.identifier().toString());
    ship.putLong("base", base.asLong());
    ship.putLong("controller", SphereLab.controller(base.below()).asLong());
    ship.putDouble("simX", x);
    ship.putDouble("simY", y);
    ship.putDouble("simZ", z);
    ship.putDouble("simYaw", yaw);
    ship.put("crew", new ListTag());
    store.ships().add(ship);
    return ship;
  }
}
