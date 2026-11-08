package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TransferRecovery;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.testing.TestZones;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public final class ShipStorageGameTest {
  @GameTest(environment = "nullspace:ship_recall")
  public void unclaimedShipsAreNotListedForAnyone(GameTestHelper ctx) throws Exception {
    var f = new Fixture(ctx, 7);
    f.ship().remove("owner");
    f.ship().remove("recallUsers");
    ctx.assertTrue(
        ShipStorage.candidates(f.player, f.computer).isEmpty(),
        "Unclaimed ships are not free for any player to take");
    f.close();
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_recall")
  public void deathAboardAVisitedMobileVesselPreservesHomeRecallWithoutClaimingTheVisitedShip(
      GameTestHelper ctx) throws Exception {
    var f = new Fixture(ctx, 5);
    f.ship().remove("owner");
    f.ship().remove("recallUsers");
    var visited = f.ship().copy();
    visited.putString("id", UUID.randomUUID().toString());
    visited.putString("owner", UUID.randomUUID().toString());
    visited.putLong("base", f.base.offset(512, 0, 0).asLong());
    var visitor = new CompoundTag();
    visitor.putString("id", f.player.getUUID().toString());
    visitor.putString("returnShip", f.id);
    var crew = new ListTag();
    crew.add(visitor);
    visited.put("crew", crew);
    WorldStore.get(f.surface.getServer()).ships().add(visited);
    CrewRelease.rememberDeath(f.player);
    ctx.assertTrue(
        f.ship()
            .getCompoundOrEmpty("recallUsers")
            .getBooleanOr(f.player.getUUID().toString(), false),
        "A visiting crew member retains their own home ship");
    ctx.assertTrue(
        !visited
            .getCompoundOrEmpty("recallUsers")
            .getBooleanOr(f.player.getUUID().toString(), false),
        "Visiting another mobile ship never grants ownership by dying there");
    f.close();
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_recall_rollback", maxTicks = 160)
  public void interruptedRecallRollsBackWithoutDuplicatingShipOrTeleportingRequester(
      GameTestHelper ctx) throws Exception {
    var f = new Fixture(ctx, 6);
    int[] ticks = {0};
    ctx.onEachTick(
        () -> {
          if (++ticks[0] < 20) return;
          TravelService.failureInjector =
              stage -> {
                if (stage.equals("DESTINATION_STAGED")) throw new TravelService.SimulatedCrash();
              };
          boolean interrupted = false;
          try {
            var result = ShipStorage.move(f.player, f.computer, f.ship());
            if (result.outcome() == TravelService.Outcome.WAITING) return;
          } catch (TravelService.SimulatedCrash crash) {
            interrupted = true;
          } finally {
            TravelService.failureInjector = stage -> {};
          }
          ctx.assertTrue(interrupted, "The real recall journal reaches destination staging");
          TransferRecovery.recover(f.surface.getServer());
          ctx.assertTrue(
              f.ship()
                      .getStringOr("dimension", "")
                      .equals(TravelService.SPACE.identifier().toString())
                  && WorldStore.pos(f.ship(), "base").equals(f.base),
              "Recovery restores the authoritative original ship");
          for (var pos : BlockPos.betweenClosed(f.padBase.above(), f.padBase.offset(11, 10, 11)))
            ctx.assertTrue(
                f.surface.getBlockState(pos).isAir(), "Rollback removes every destination copy");
          ctx.assertTrue(
              f.player.level() == f.surface
                  && f.player
                          .position()
                          .distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(f.computer))
                      < .01,
              "Rollback never relocates the requester");
          f.close();
          ctx.succeed();
        });
  }

  public static ServerPlayer player(GameTestHelper ctx, int index) {
    var server = ctx.getLevel().getServer();
    var cookie =
        net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), "recall-" + index), false);
    var p =
        new ServerPlayer(server, ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
    var connection =
        new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
    new io.netty.channel.embedded.EmbeddedChannel(connection);
    server.getPlayerList().placeNewPlayer(connection, p, cookie);
    p.setGameMode(GameType.SURVIVAL);
    return p;
  }

  public static final class Fixture {
    public final ServerLevel surface, space;
    public final BlockPos base, padBase, computer;
    public final String id;
    public ServerPlayer player;

    public Fixture(GameTestHelper ctx, int index) throws Exception {
      surface = ctx.getLevel();
      space = surface.getServer().getLevel(TravelService.SPACE);
      base = ctx.absolutePos(new BlockPos(180000 + index * 4096, 96, 180000));
      padBase = ctx.absolutePos(new BlockPos(120000 + index * 4096, 70, 120000));
      computer = padBase.offset(-1, 1, 3);
      for (int x = -1; x <= 12; x++)
        for (int z = -1; z <= 12; z++)
          surface.setBlock(
              padBase.offset(x, 0, z),
              (x == -1 || z == -1 || x == 12 || z == 12 ? Blocks.COBBLESTONE : Blocks.DIRT)
                  .defaultBlockState(),
              18);
      surface.setBlock(computer, PadComputers.BLOCK.defaultBlockState(), 18);
      PadComputers.register(
          surface,
          computer,
          new PadScanner.Pad(
              padBase.getX(),
              padBase.getX() + 11,
              padBase.getY(),
              padBase.getZ(),
              padBase.getZ() + 11),
          "Recall " + index);
      var ship = StationGameTest.fixture(space, base, true);
      ship.putDouble("simX", 10000000 + index * 1000);
      ship.putDouble("simZ", 10000000);
      id = ship.getStringOr("id", "");
      player = player(ctx, index);
      CrewRelease.authorize(ship, player);
      home();
      for (var level : List.of(surface, space)) {
        var at = level == surface ? padBase : base;
        for (int x = -16; x < 48; x += 16)
          for (int z = -16; z < 48; z += 16)
            level.setChunkForced(
                at.offset(x, 0, z).getX() >> 4, at.offset(x, 0, z).getZ() >> 4, true);
      }
    }

    public CompoundTag ship() {
      return WorldStore.get(surface.getServer()).repository().find(id);
    }

    public void home() {
      player.teleportTo(
          surface,
          computer.getX() + .5,
          computer.getY(),
          computer.getZ() + .5,
          Set.of(),
          0,
          0,
          true);
    }

    public String selection() {
      return ShipStorage.candidates(player, computer).getFirst().id().replace("-", "");
    }

    public void close() {
      surface.getServer().getPlayerList().remove(player);
    }
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 180)
  public void damagedFuelEmptyShipReturnsWithCargoModulesAndIdentityWhilePlayerStaysOnPlanet(
      GameTestHelper ctx) throws Exception {
    var f = new Fixture(ctx, 1);
    f.ship().putString("localSpace", TestZones.otherSystem(ctx.getLevel().getServer()));
    var generator =
        ShipSystems.connected(f.space, f.ship()).stream()
            .filter(p -> f.space.getBlockState(p).is(ModBlocks.GENERATOR))
            .findFirst()
            .orElseThrow();
    for (var at : ShipSystems.connected(f.space, f.ship()))
      if (f.space.getBlockEntity(at) instanceof MachineBlockEntity machine && machine.generator()) {
        machine.clearContent();
        machine.draw(machine.burn());
      }
    ((MachineBlockEntity) f.space.getBlockEntity(generator))
        .setItem(0, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
    var cargo = f.base.offset(3, 1, 7);
    f.space.setBlock(cargo, Blocks.CHEST.defaultBlockState(), 18);
    ((ChestBlockEntity) f.space.getBlockEntity(cargo)).setItem(0, new ItemStack(Items.DIAMOND, 7));
    var controller = WorldStore.pos(f.ship(), "controller");
    f.space.setBlock(controller, Blocks.AIR.defaultBlockState(), 18);
    var origin = f.player.position();
    int[] ticks = {0};
    ctx.onEachTick(
        () -> {
          if (++ticks[0] < 20) return;
          var result = ShipStorage.move(f.player, f.computer, f.ship());
          if (result.outcome() == TravelService.Outcome.WAITING) return;
          ctx.assertTrue(result.outcome() == TravelService.Outcome.COMPLETED, result.message());
          ctx.assertTrue(
              f.ship().getStringOr("dimension", "").equals("minecraft:overworld")
                  && f.ship().getStringOr("id", "").equals(f.id),
              "The existing ship identity is moved, never replaced by a new ship");
          ctx.assertTrue(
              f.ship().getStringOr("localSpace", "").equals("sol_terra")
                  && !f.ship().contains("simX"),
              "Recovery from a distant region resets future launch position to this planet");
          var delta = WorldStore.pos(f.ship(), "base").subtract(f.base);
          var moved = (ChestBlockEntity) f.surface.getBlockEntity(cargo.offset(delta));
          ctx.assertTrue(
              moved != null && moved.getItem(0).getCount() == 7, "Stored cargo is preserved");
          ctx.assertTrue(
              ((MachineBlockEntity) f.surface.getBlockEntity(generator.offset(delta))).tier() == 1
                  && FuelSystem.available(f.surface, f.ship()) == 0,
              "Recovery neither spends nor creates fuel or upgrades");
          ctx.assertTrue(
              f.surface.getBlockState(controller.offset(delta)).isAir()
                  && f.space.getBlockState(cargo).isAir(),
              "Damage remains, and the source copy is removed");
          ctx.assertTrue(
              f.player.level() == f.surface && f.player.position().distanceToSqr(origin) < .01,
              "The requesting player stays at the surface computer");
          f.close();
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 120)
  public void blockedPadAndOtherPlayersShipAreRefusedWithoutSpendingOrReplacingAnything(
      GameTestHelper ctx) throws Exception {
    var f = new Fixture(ctx, 2);
    var obstacle = f.padBase.offset(2, 4, 2);
    f.surface.setBlock(obstacle, Blocks.COBBLESTONE.defaultBlockState(), 18);
    int fuel = FuelSystem.available(f.space, f.ship());
    int[] ticks = {0};
    ctx.onEachTick(
        () -> {
          if (++ticks[0] < 20) return;
          var result = ShipStorage.move(f.player, f.computer, f.ship());
          if (result.outcome() == TravelService.Outcome.WAITING) return;
          ctx.assertTrue(
              result.outcome() == TravelService.Outcome.REJECTED
                  && result.message().contains("Cobblestone")
                  && result.message().contains(obstacle.toShortString()),
              "Obstruction names its block and coordinates: " + result.message());
          ctx.assertTrue(
              f.ship()
                      .getStringOr("dimension", "")
                      .equals(TravelService.SPACE.identifier().toString())
                  && f.surface.getBlockState(obstacle).is(Blocks.COBBLESTONE)
                  && FuelSystem.available(f.space, f.ship()) == fuel,
              "Blocked recovery changes neither ship, pad nor fuel");
          f.ship().putString("owner", UUID.randomUUID().toString());
          f.ship().remove("recallUsers");
          ctx.assertTrue(
              ShipStorage.candidates(f.player, f.computer).isEmpty()
                  && ShipStorage.move(f.player, f.computer, f.ship()).outcome()
                      == TravelService.Outcome.REJECTED,
              "A player cannot recall someone else's ship");
          f.close();
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 120)
  public void aboardOfflineAndDockedCrewCannotBeDraggedAway(GameTestHelper ctx) throws Exception {
    var f = new Fixture(ctx, 3);
    var visitor = player(ctx, 30);
    var at = StationGameTest.boardingPoint(f.space, f.ship());
    visitor.teleportTo(f.space, at.x, at.y, at.z, Set.of(), 0, 0, true);
    ctx.assertTrue(
        ShipStorage.move(f.player, f.computer, f.ship()).message().contains("Crew aboard"),
        "An online passenger blocks recall");
    f.surface.getServer().getPlayerList().remove(visitor);
    var member = new CompoundTag();
    member.putString("id", visitor.getUUID().toString());
    f.ship().getListOrEmpty("crew").add(member);
    // Offline passengers no longer block: they are taken off and log in at Terra Harbor.
    CrewRelease.releaseOfflineCrew(f.surface.getServer(), f.ship(), f.player.getUUID());
    ctx.assertTrue(
        f.ship().getListOrEmpty("crew").isEmpty()
            && WorldStore.get(f.surface.getServer()).list("targets").stream()
                .anyMatch(
                    t ->
                        ((CompoundTag) t)
                            .getStringOr("id", "")
                            .equals(visitor.getUUID().toString())),
        "Offline passengers are moved to Terra Harbor instead of blocking recall");
    f.ship().put("crew", new ListTag());
    f.ship().put("dock", new CompoundTag());
    ctx.assertTrue(
        ShipStorage.move(f.player, f.computer, f.ship()).message().contains("Undock"),
        "A dock connection is never torn apart");
    f.close();
    ctx.succeed();
  }

  @GameTest(environment = "nullspace:ship_recall", maxTicks = 180)
  public void actualDeathAndRespawnKeepRecallRightsButNeverReboardTheDeadPlayer(GameTestHelper ctx)
      throws Exception {
    var f = new Fixture(ctx, 4);
    f.ship().remove("owner");
    f.ship().remove("recallUsers");
    var member = new CompoundTag();
    member.putString("id", f.player.getUUID().toString());
    f.ship().getListOrEmpty("crew").add(member);
    var at = StationGameTest.boardingPoint(f.space, f.ship());
    f.player.teleportTo(f.space, at.x, at.y, at.z, Set.of(), 0, 0, true);
    var snapshot = WorldStore.get(f.surface.getServer()).snapshot();
    WorldStore.target(
        snapshot,
        f.player.getUUID(),
        TravelService.SPACE.identifier().toString(),
        at.x,
        at.y,
        at.z,
        0,
        0);
    WorldStore.get(f.surface.getServer()).document("targets", snapshot.getListOrEmpty("targets"));
    // Embedded connections have no client-loaded acknowledgment, so invoke real death directly.
    f.player.setHealth(0);
    f.player.die(f.player.damageSources().genericKill());
    ctx.assertTrue(
        !f.player.isAlive() && f.ship().getListOrEmpty("crew").isEmpty(),
        "Death removes crew assignment");
    ctx.assertTrue(
        WorldStore.get(f.surface.getServer()).list("targets").stream()
            .noneMatch(
                t -> ((CompoundTag) t).getStringOr("id", "").equals(f.player.getUUID().toString())),
        "Death cannot retain a forced boarding target");
    f.player =
        f.surface.getServer().getPlayerList().respawn(f.player, false, Entity.RemovalReason.KILLED);
    f.player.connection.player = f.player;
    f.home();
    ctx.assertTrue(
        !ShipStorage.candidates(f.player, f.computer).isEmpty(),
        "The respawned player still has recovery rights");
    String notice = ShipStorage.request(f.player, f.computer, f.selection(), null);
    ctx.assertTrue(notice.startsWith("Recall requested"), notice);
    ctx.onEachTick(
        () -> {
          if (ShipStorage.active(f.surface.getServer())) return;
          ctx.assertTrue(
              f.ship().getStringOr("dimension", "").equals("minecraft:overworld")
                  && f.player.level() == f.surface,
              "Remote recovery completes after real death and respawn");
          ctx.assertTrue(
              f.ship().getListOrEmpty("crew").isEmpty(), "Recall does not fabricate passengers");
          f.close();
          ctx.succeed();
        });
  }
}
