package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.systems.ShipSystems;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.portal.TeleportTransition;

/** Focused coverage for chunk 2 (rescue) and chunk 3 (vessel-relative respawn). */
public final class RecoveryGameTest {
  private static boolean atHarbor(MinecraftServerAccess s, ServerPlayer p) {
    var base = ShipStore.pos(ShipStore.get(s.server()).section("harbor"), "base");
    var at = p.position();
    return p.level() == s.server().overworld()
        && at.x >= base.getX() && at.x < base.getX() + 14
        && at.z >= base.getZ() && at.z < base.getZ() + 40;
  }

  private record MinecraftServerAccess(net.minecraft.server.MinecraftServer server) {}

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 400)
  public void rescueMovesOnlyThePlayerKeepsTheirInventoryAndLeavesTheShipBehind(GameTestHelper ctx)
      throws Exception {
    var f = new ShipRecallGameTest.Fixture(ctx, 201);
    var server = f.surface.getServer();
    var access = new MinecraftServerAccess(server);
    f.player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
    var member = new CompoundTag();
    member.putString("id", f.player.getUUID().toString());
    f.ship().getListOrEmpty("crew").add(member);
    var message = PlayerRecovery.request(f.player, false);
    ctx.assertTrue(
        message.contains("Terra Harbor") && message.contains("keep what you are carrying"),
        "Rescue states its consequences before committing: " + message);
    ctx.assertTrue(
        PlayerRecovery.request(f.player, false).contains("already"),
        "A second request must not queue another arrival");
    ctx.runAfterDelay(
        200,
        () -> {
          ctx.assertTrue(atHarbor(access, f.player), "Rescued player stands on Terra Harbor");
          ctx.assertTrue(
              f.player.getInventory().countItem(Items.DIAMOND) == 5,
              "Rescue keeps carried inventory without duplicating it");
          var ship = f.ship();
          ctx.assertTrue(ship != null && !ship.getBooleanOr("stored", false), "The ship stays put");
          ctx.assertTrue(
              ship.getListOrEmpty("crew").stream()
                  .noneMatch(
                      t -> ((CompoundTag) t).getStringOr("id", "").equals(f.player.getUUID().toString())),
              "Stale crew anchor is removed so the player cannot be pulled back");
          ctx.assertTrue(
              ShipStore.get(server).section("rescues").isEmpty(), "The completed rescue is cleared");
          ctx.assertTrue(PlayerRecovery.protectedPlayer(f.player), "Arrival grants brief protection");
          f.close();
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 300)
  public void cancellingRescueLeavesThePlayerWhereTheyAre(GameTestHelper ctx) throws Exception {
    var f = new ShipRecallGameTest.Fixture(ctx, 202);
    var server = f.surface.getServer();
    var start = f.player.position();
    PlayerRecovery.request(f.player, false);
    ctx.runAfterDelay(
        10,
        () -> {
          ctx.assertTrue(
              PlayerRecovery.request(f.player, true).contains("cancelled"), "Cancel is acknowledged");
          ctx.assertTrue(
              !PlayerRecovery.request(f.player, true).contains("cancelled"),
              "Cancelling with nothing pending does not claim to cancel a rescue");
          ctx.runAfterDelay(
              120,
              () -> {
                ctx.assertTrue(
                    f.player.position().distanceTo(start) < 1 && f.player.level() == f.surface,
                    "A cancelled rescue never teleports");
                ctx.assertTrue(
                    ShipStore.get(server).section("rescues").isEmpty(), "Nothing is left queued");
                f.close();
                ctx.succeed();
              });
        });
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 400)
  public void rescueNeedsNoShipPowerFuelOrDestination(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var access = new MinecraftServerAccess(server);
    var player = ShipRecallGameTest.player(ctx, 203);
    player.teleportTo(ctx.getLevel(), ctx.absolutePos(BlockPos.ZERO).getX() + .5, ctx.absolutePos(BlockPos.ZERO).getY() + 2, ctx.absolutePos(BlockPos.ZERO).getZ() + .5, Set.of(), 0, 0, true);
    PlayerRecovery.request(player, false);
    ctx.runAfterDelay(
        200,
        () -> {
          ctx.assertTrue(atHarbor(access, player), "A player with no ship at all can still recover");
          server.getPlayerList().remove(player);
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 200)
  public void harborIsReservedOnceProtectedAndHasASafeArrivalFloor(GameTestHelper ctx) {
    var server = ctx.getLevel().getServer();
    var level = server.overworld();
    var base = TerraHarbor.ensure(server);
    ctx.assertTrue(base.equals(TerraHarbor.ensure(server)), "The harbor site is reserved once");
    var spot = TerraHarbor.arrival(server);
    var feet = BlockPos.containing(spot);
    ctx.assertTrue(level.getBlockState(feet.below()).isSolidRender(), "Arrival has a floor");
    ctx.assertTrue(level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir(),
        "Arrival has headroom");
    ctx.assertTrue(TerraHarbor.protectedAt(level, base), "The harbor floor is protected");
    ctx.assertTrue(TerraHarbor.protectedAt(level, base.offset(5, 2, 10)), "Its walls are protected");
    ctx.assertTrue(!TerraHarbor.protectedAt(level, base.offset(3, 1, 30)), "The garden stays harvestable");
    ctx.assertTrue(!TerraHarbor.protectedAt(level, base.offset(200, 1, 200)), "Unrelated terrain is not protected");
    ctx.succeed();
  }

  /** Finds floor with two clear, breathable cells in a row inside the ship. */
  private static BlockPos bedSpot(ServerLevel level, CompoundTag ship) {
    var base = ShipStore.pos(ship, "base");
    for (var at : BlockPos.betweenClosed(base, base.offset(31, 31, 31))) {
      var foot = at.immutable();
      var head = foot.east();
      if (level.getBlockState(foot).isAir() && level.getBlockState(foot.above()).isAir()
          && level.getBlockState(head).isAir() && level.getBlockState(head.above()).isAir()
          && level.getBlockState(foot.below()).isSolidRender()
          && level.getBlockState(head.below()).isSolidRender()
          && ShipSystems.breathable(level, ship, foot.above()))
        return foot;
    }
    return null;
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 100)
  public void aBedAboardTheShipRespawnsThePlayerThereUntilItIsLostOrTheShipIsStored(
      GameTestHelper ctx) throws Exception {
    var f = new ShipRecallGameTest.Fixture(ctx, 204);
    var server = f.surface.getServer();
    var ship = f.ship();
    var foot = bedSpot(f.space, ship);
    ctx.assertTrue(foot != null, "The reference ship has room for a bed");
    var head = foot.east();
    f.space.setBlock(foot, Blocks.BED.red().defaultBlockState()
        .setValue(AbstractBedBlock.PART, BedPart.FOOT).setValue(AbstractBedBlock.FACING, Direction.EAST), 18);
    f.space.setBlock(head, Blocks.BED.red().defaultBlockState()
        .setValue(AbstractBedBlock.PART, BedPart.HEAD).setValue(AbstractBedBlock.FACING, Direction.EAST), 18);
    // The player is aboard, beside the bed, as when right-clicking it in play.
    f.player.teleportTo(f.space, foot.getX() + .5, foot.getY(), foot.getZ() - .5, Set.of(), 0, 0, true);
    ctx.assertTrue(VesselRespawns.bind(f.player, foot), "Using a ship bed binds the player to it");
    var transition = VesselRespawns.resolve(f.player, TeleportTransition.DO_NOTHING);
    ctx.assertTrue(
        transition != null && transition.newLevel() == f.space
            && transition.position().distanceTo(Vec3Util.center(head)) < 3,
        "Respawn lands at the bed aboard the ship");
    ctx.assertTrue(
        ship.getListOrEmpty("crew").stream().anyMatch(t -> ((CompoundTag) t).getStringOr("id", "").equals(f.player.getUUID().toString())),
        "Respawning aboard restores the player's crew anchor");

    ship.putBoolean("stored", true);
    var stored = VesselRespawns.resolve(f.player, TeleportTransition.DO_NOTHING);
    ctx.assertTrue(stored != null && stored.newLevel() == server.overworld(),
        "A stored ship falls back to the harbor");
    ship.remove("stored");

    // Break and replace the bed: the old placement identity must not be honored.
    VesselRespawns.changed(f.space, head, true);
    var replaced = VesselRespawns.resolve(f.player, TeleportTransition.DO_NOTHING);
    ctx.assertTrue(replaced != null && replaced.newLevel() == server.overworld(),
        "A replaced bed does not keep the old respawn");
    f.close();
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 100)
  public void playersWithoutAShipBedKeepVanillaRespawn(GameTestHelper ctx) {
    var player = ShipRecallGameTest.player(ctx, 205);
    ctx.assertTrue(
        VesselRespawns.resolve(player, TeleportTransition.DO_NOTHING) == null,
        "No binding means ordinary Minecraft respawn");
    ctx.getLevel().getServer().getPlayerList().remove(player);
    ctx.succeed();
  }

  private static final class Vec3Util {
    static net.minecraft.world.phys.Vec3 center(BlockPos p) {
      return net.minecraft.world.phys.Vec3.atBottomCenterOf(p);
    }
  }
}
