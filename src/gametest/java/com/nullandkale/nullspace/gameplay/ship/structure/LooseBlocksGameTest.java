package com.nullandkale.nullspace.gameplay.ship.structure;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.station.StationGameTest;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.ConcurrentShipActionsGameTest;
import com.nullandkale.nullspace.gameplay.travel.TransferReplay;
import com.nullandkale.nullspace.gameplay.travel.TravelPackets;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public final class LooseBlocksGameTest {
  @GameTest(environment = "nullspace:ship_control", maxTicks = 100)
  public void debrisChangedDuringLoadingCancelsNormallyBeforeAnyWorldMutation(GameTestHelper ctx)
      throws Exception {
    var client = ConcurrentShipActionsGameTest.client(ctx, 94);
    var f = client.fixture();
    client.board();
    var controller = WorldStore.pos(f.ship(), "controller");
    var loose = f.base.offset(30, 10, 30);
    f.space.setBlock(loose, Blocks.IRON_BLOCK.defaultBlockState(), 18);
    int[] ticks = {0};
    boolean[] started = {false};
    ctx.onEachTick(
        () -> {
          if (++ticks[0] < 20) return;
          if (!started[0]) {
            LooseBlocks.confirm(f.player, LooseBlocks.review(f.space, controller));
            var handshake = token(client);
            // Ordinary edits are quarantined during loading. Exercise a real replay mutation,
            // which can invalidate a reviewed snapshot and must still cancel before transfer.
            var seed = f.base.offset(40, 0, 0);
            f.space.setBlock(seed, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
            var replacement =
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
            replacement.fillFromWorld(f.space, seed, new Vec3i(1, 1, 1), false, List.of());
            TransferReplay.place(f.space, replacement, loose);
            client.ready(handshake, false);
            started[0] = true;
            return;
          }
          if (TravelService.busy(f.player)) return;
          client.connection().flushChannel();
          client.channel().runPendingTasks();
          String message = "";
          Object packet;
          while ((packet = client.channel().readOutbound()) != null) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                && custom.payload() instanceof TravelPackets.Progress progress
                && progress.done()) message = progress.message();
            io.netty.util.ReferenceCountUtil.release(packet);
          }
          ctx.assertTrue(
              message.startsWith("Travel cancelled:") && message.contains("disconnected"),
              "Changed loose blocks are an ordinary preflight refusal, not a failed"
                  + " mutation/recovery");
          ctx.assertTrue(
              WorldStore.pos(f.ship(), "base").equals(f.base)
                  && f.space.getBlockState(loose).is(Blocks.GOLD_BLOCK),
              "Neither hull nor changed debris was moved or deleted");
          client.close();
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:ship_control", maxTicks = 180)
  public void surfaceLaunchLeavesAcknowledgedContainersButStillRejectsNewDebrisAndSkyObstructions(
      GameTestHelper ctx) throws Exception {
    var client = ConcurrentShipActionsGameTest.client(ctx, 93);
    var f = client.fixture();
    var origin = f.padBase.above();
    var preset = ShipBlueprints.get("sparrow");
    for (var e : ShipBlueprints.blocks(preset).entrySet()) {
      f.surface.setBlock(origin.offset(e.getKey()), e.getValue(), 18);
      if (e.getValue().is(ModBlocks.GENERATOR))
        FuelSystem.provision(f.surface, origin.offset(e.getKey()), 64);
    }
    var controller = origin.offset(preset.helm());
    var ship = ShipTopology.resolve(f.surface, controller);
    String id = ship.getStringOr("id", "");
    ShipSystems.upgrade(f.surface, ship);
    ShipSystems.tick(f.surface, ship);
    var boarding = StationGameTest.boardingPoint(f.surface, ship);
    f.player.teleportTo(f.surface, boarding.x, boarding.y, boarding.z, Set.of(), 0, 0, true);
    var loose = origin.offset(10, 2, 10);
    var extra = origin.offset(11, 2, 11);
    var obstruction = controller.above(33);
    f.surface.setBlock(loose, Blocks.CHEST.defaultBlockState(), 18);
    ((ChestBlockEntity) f.surface.getBlockEntity(loose))
        .setItem(0, new ItemStack(Items.EMERALD, 9));
    int[] ticks = {0};
    boolean[] acknowledged = {false};
    ctx.onEachTick(
        () -> {
          if (++ticks[0] < 20) return;
          if (!acknowledged[0]) {
            f.surface.setBlock(obstruction, Blocks.GLASS.defaultBlockState(), 18);
            boolean refused = false;
            try {
              LooseBlocks.review(f.surface, controller);
            } catch (HullScan.Failure expected) {
              refused = expected.getMessage().contains("Launch path obstruction");
            }
            ctx.assertTrue(
                refused, "Even transparent high obstructions cannot be acknowledged away");
            f.surface.setBlock(obstruction, Blocks.AIR.defaultBlockState(), 18);
            var review = LooseBlocks.review(f.surface, controller);
            ctx.assertTrue(
                review.loose().size() == 1, "Only the detached container is proposed for leaving");
            LooseBlocks.confirm(f.player, review);
            f.surface.setBlock(extra, Blocks.IRON_BLOCK.defaultBlockState(), 18);
            refused = false;
            try {
              ShipService.preflight(f.surface, controller);
            } catch (HullScan.Failure expected) {
              refused = expected.getMessage().contains("disconnected");
            }
            ctx.assertTrue(
                refused, "Acknowledgement cannot silently include later disconnected pieces");
            f.surface.setBlock(extra, Blocks.AIR.defaultBlockState(), 18);
            acknowledged[0] = true;
          }
          var result =
              TravelService.transfer(
                  f.player, controller, "", ShipService.preflight(f.surface, controller));
          if (result.outcome() == TravelService.Outcome.WAITING) return;
          ctx.assertTrue(result.outcome() == TravelService.Outcome.COMPLETED, result.message());
          var moved = WorldStore.get(f.space.getServer()).repository().find(id);
          var delta = WorldStore.pos(moved, "base").subtract(origin);
          ctx.assertTrue(
              f.player.level() == f.space
                  && f.space.getBlockState(loose.offset(delta)).isAir()
                  && ((ChestBlockEntity) f.surface.getBlockEntity(loose)).getItem(0).getCount()
                      == 9,
              "Launch leaves the exact container and inventory on the surface and moves only the"
                  + " hull");
          client.close();
          ctx.succeed();
        });
  }

  private static UUID token(ConcurrentShipActionsGameTest.Client client) {
    client.connection().flushChannel();
    client.channel().runPendingTasks();
    TravelPackets.Progress latest = null;
    Object packet;
    while ((packet = client.channel().readOutbound()) != null) {
      if (packet instanceof ClientboundCustomPayloadPacket custom
          && custom.payload() instanceof TravelPackets.Progress progress
          && !progress.done()) latest = progress;
      io.netty.util.ReferenceCountUtil.release(packet);
    }
    if (latest == null)
      throw new AssertionError("Confirmation must use the real passenger handshake");
    return latest.id();
  }

  @GameTest(environment = "nullspace:ship_control", maxTicks = 180)
  public void confirmedDebrisSurvivesEveryJournalBoundaryAndStaysBehindAfterRealBoarding(
      GameTestHelper ctx) throws Exception {
    var client = ConcurrentShipActionsGameTest.client(ctx, 90);
    var f = client.fixture();
    client.board();
    var controller = WorldStore.pos(f.ship(), "controller");
    for (int x = 7; x <= 28; x++)
      f.space.setBlock(f.base.offset(x, 0, 5), Blocks.IRON_BLOCK.defaultBlockState(), 18);
    var chest = f.base.offset(24, 2, 5);
    var outside = f.base.offset(30, 20, 30);
    var offline = UUID.randomUUID();
    var member = new net.minecraft.nbt.CompoundTag();
    member.putString("id", offline.toString());
    member.putDouble("x", 30.5);
    member.putDouble("y", 20);
    member.putDouble("z", 30.5);
    f.ship().getListOrEmpty("crew").add(member);
    f.space.setBlock(chest, Blocks.CHEST.defaultBlockState(), 18);
    ((ChestBlockEntity) f.space.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 7));
    f.space.setBlock(outside, Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 18);
    String[] stages = {"PREPARED", "DESTINATION_STAGED", "SOURCE_CLEARED", "PASSENGERS_MOVED"};
    int[] stage = {0};
    UUID[] handshake = {null};
    boolean[] ready = {false};
    int[] ticks = {0};
    ctx.onEachTick(
        () -> {
          if (++ticks[0] < 20) return;
          if (handshake[0] == null) {
            var review = LooseBlocks.review(f.space, controller);
            ctx.assertTrue(
                review.loose().size() == 2 && review.containers() == 1,
                "Review counts the disconnected container inside the hull bounds and the high"
                    + " exterior piece");
            ctx.assertTrue(
                LooseBlocks.approved(f.space, controller).isEmpty(),
                "Review alone cannot authorize travel");

            LooseBlocks.confirm(f.player, review);
            handshake[0] = token(client);
            return;
          }
          if (stage[0] < stages.length) {
            TravelService.failureInjector =
                point -> {
                  if (point.equals(stages[stage[0]])) throw new TravelService.SimulatedCrash();
                };
            boolean interrupted = false;
            try {
              var result =
                  TravelService.transfer(
                      f.player,
                      controller,
                      "@leave-loose-blocks",
                      ShipService.preflight(f.space, controller));
              if (result.outcome() == TravelService.Outcome.WAITING) return;
              ctx.assertTrue(false, "Expected journal interruption, got " + result.message());
            } catch (TravelService.SimulatedCrash expected) {
              interrupted = true;
            } finally {
              TravelService.failureInjector = point -> {};
            }
            ctx.assertTrue(
                interrupted, "The actual transfer reaches the selected journal boundary");
            TravelService.recover(f.space.getServer());
            ctx.assertTrue(
                WorldStore.pos(f.ship(), "base").equals(f.base)
                    && ((ChestBlockEntity) f.space.getBlockEntity(chest)).getItem(0).getCount() == 7
                    && f.space.getBlockState(outside).is(Blocks.POLISHED_DEEPSLATE),
                "Rollback preserves hull identity and both loose pieces, including" + " inventory");
            stage[0]++;
            return;
          }
          if (!ready[0]) {
            client.ready(handshake[0], false);
            ready[0] = true;
            return;
          }
          if (WorldStore.pos(f.ship(), "base").equals(f.base)) return;
          var delta = WorldStore.pos(f.ship(), "base").subtract(f.base);
          var arrival =
              WorldStore.get(f.space.getServer()).list("targets").stream()
                  .map(t -> (net.minecraft.nbt.CompoundTag) t)
                  .filter(t -> t.getStringOr("id", "").equals(offline.toString()))
                  .findFirst()
                  .orElseThrow();
          var feet =
              BlockPos.containing(
                  arrival.getDoubleOr("x", 0),
                  arrival.getDoubleOr("y", 0),
                  arrival.getDoubleOr("z", 0));
          var cabin = ShipService.inspect(f.space, WorldStore.pos(f.ship(), "controller")).scan();
          ctx.assertTrue(
              cabin.pressure().breathable(feet)
                  && cabin.pressure().breathable(feet.above())
                  && cabin.blocks().contains(feet.below()),
              "An offline passenger reconnects inside the surviving cabin, not at the abandoned"
                  + " room's offset");
          ctx.assertTrue(
              f.space.getBlockState(chest.offset(delta)).isAir()
                  && f.space.getBlockState(outside.offset(delta)).isAir(),
              "Neither loose piece is copied into the new ship");
          ctx.assertTrue(
              ((ChestBlockEntity) f.space.getBlockEntity(chest)).getItem(0).getCount() == 7,
              "The original container and all its contents remain in space");
          var debris = WorldStore.get(f.space.getServer()).at(f.space, chest);
          ctx.assertTrue(
              debris != null
                  && debris.getStringOr("kind", "").equals("debris")
                  && !debris.getStringOr("id", "").equals(f.id),
              "The wreck has an independent physical frame and identity");
          var pose = SceneService.pose(debris);
          f.ship().putDouble("simX", f.ship().getDoubleOr("simX", 0) + 500);
          f.ship().putString("localSpace", "sol_luna");
          ctx.assertTrue(
              SceneService.pose(debris).equals(pose)
                  && debris.getStringOr("localSpace", "").equals("sol_terra"),
              "Later ship motion and region changes cannot drag debris along");
          client.ready(handshake[0], true);
          client.close();
          ctx.succeed();
        });
  }

  @GameTest(environment = "nullspace:ship_control", maxTicks = 100)
  public void staleOrForgedConfirmationDoesNotAuthorizeNewDebrisOrRepairAnUnsafeHull(
      GameTestHelper ctx) throws Exception {
    var client = ConcurrentShipActionsGameTest.client(ctx, 91);
    var f = client.fixture();
    client.board();
    ctx.runAfterDelay(
        20,
        () -> {
          var controller = WorldStore.pos(f.ship(), "controller");
          var loose = f.base.offset(30, 10, 30);
          f.space.setBlock(loose, Blocks.IRON_BLOCK.defaultBlockState(), 18);
          var review = LooseBlocks.review(f.space, controller);
          f.space.setBlock(loose, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
          ctx.assertTrue(
              LooseBlocks.confirm(f.player, review).contains("changed")
                  && LooseBlocks.approved(f.space, controller).isEmpty(),
              "Changed debris invalidates the review");
          var view = ShipControl.open(f.player, controller, "");
          ctx.assertTrue(
              ShipControl.input(
                      f.player,
                      new ShipControl.Input(
                          view.session(), ShipControl.Action.CONFIRM_LEAVE_BLOCKS, ""))
                  .contains("Review"),
              "A forged confirmation packet has no reviewed set and cannot launch");
          // A glass block that borders the cabin air in front of the controller. (Taking any glass
          // block from the unordered scan could pick a window that does not open the cabin.)
          var blocks = ShipService.inspect(f.space, controller, true).scan().blocks();
          var facing =
              f.space
                  .getBlockState(controller)
                  .getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
          var cabin = new java.util.HashSet<BlockPos>();
          var queue = new java.util.ArrayDeque<BlockPos>(List.of(controller.relative(facing)));
          BlockPos shell = null;
          while (!queue.isEmpty() && shell == null && cabin.size() < 4000) {
            var at = queue.poll();
            if (!cabin.add(at)) continue;
            for (var d : net.minecraft.core.Direction.values()) {
              var next = at.relative(d);
              if (blocks.contains(next) && f.space.getBlockState(next).is(Blocks.GLASS)) {
                shell = next;
                break;
              }
              if (!HullScan.seals(f.space, next)
                  && blocks.stream().anyMatch(b -> b.distManhattan(next) <= 2)) queue.add(next);
            }
          }
          if (shell == null) throw new AssertionError("The fixture cabin has a glass wall");
          f.space.setBlock(shell, Blocks.AIR.defaultBlockState(), 18);
          boolean refused = false;
          try {
            LooseBlocks.review(f.space, controller);
          } catch (HullScan.Failure expected) {
            refused = expected.getMessage().contains("sealed");
          }
          ctx.assertTrue(
              refused && !TravelService.busy(f.player),
              "Acknowledgement cannot bypass a real cabin leak");
          client.close();
          ctx.succeed();
        });
  }
}
