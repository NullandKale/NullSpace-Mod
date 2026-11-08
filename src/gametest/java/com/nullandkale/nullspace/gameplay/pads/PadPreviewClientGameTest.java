package com.nullandkale.nullspace.gameplay.pads;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.content.machine.PadComputerBlock;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ReferenceShip;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelPackets;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.render.effects.PreviewRenderer;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.testing.ShipControlTestUi;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import com.nullandkale.nullspace.ui.screen.TravelScreen;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Place from outside the ring, exercise real typed preview UI, then carry live furnaces through
 * quarantine.
 */
public final class PadPreviewClientGameTest implements FabricClientGameTest {
  private static void choose(ClientGameTestContext ctx, int index) {
    if (ctx.computeOnClient(
        c -> c.gui.screen() instanceof TerminalScreen t && t.shipController())) {
      ShipControlTestUi.action(
          ctx, index == 1 ? ShipControl.Action.LAUNCH : ShipControl.Action.LAND);
      return;
    }
    ctx.runOnClient(
        c -> {
          var t = (TerminalScreen) c.gui.screen();
          if (t.shipController())
            throw new AssertionError("Controller actions are handled before this callback");
          int target = new int[] {5, 0, 1, 2, 3, 4, 7, 6}[index];
          for (int i = 0; i < 12 && t.selection() != target; i++)
            t.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
          if (t.selection() != target) throw new AssertionError("Menu entry missing: " + index);
          t.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
  }

  private static CompoundTag furnace(net.minecraft.server.level.ServerLevel level, BlockPos at) {
    return ((AbstractFurnaceBlockEntity) level.getBlockEntity(at))
        .saveWithoutMetadata(level.registryAccess());
  }

  @Override
  public void runTest(ClientGameTestContext ctx) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    try (var world = ctx.worldBuilder().create()) {
      var base = new BlockPos(0, -60, 0);
      var control = base.offset(-1, 1, 3);
      var support = control.below();
      var helm = base.offset(6, 3, 3);
      var failureHook = TravelService.failureInjector;
      var guard = new net.minecraft.server.level.ServerPlayer[1];
      var channel = new io.netty.channel.embedded.EmbeddedChannel[1];
      var connection = new net.minecraft.network.Connection[1];
      try {
        world
            .getServer()
            .runOnServer(
                server -> {
                  var level = server.overworld();
                  ReferenceShip.build(level, base);
                  level.setBlock(control, Blocks.AIR.defaultBlockState(), 18);
                  level.setBlock(base.offset(-3, 0, 3), Blocks.COBBLESTONE.defaultBlockState(), 18);
                  var p = world.getConnection().getServerPlayer();
                  p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                  p.teleportTo(
                      level,
                      base.getX() - 2.5,
                      base.getY() + 1,
                      base.getZ() + 3.5,
                      Set.of(),
                      -90,
                      40,
                      true);
                  p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(PadComputers.BLOCK));
                  p.inventoryMenu.broadcastChanges();
                });
        world.getConnection().waitForChunksRender();
        ctx.waitFor(
            c -> c.player != null && c.player.getMainHandItem().is(PadComputers.BLOCK.asItem()),
            200);
        ctx.waitTicks(10);
        ctx.getInput().lookAt(support);
        ctx.waitTicks(3);
        ctx.waitFor(
            c ->
                c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                    && hit.getBlockPos().equals(support)
                    && hit.getDirection() == Direction.UP,
            100);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        world
            .getServer()
            .waitFor(
                server -> server.overworld().getBlockState(control).is(PadComputers.BLOCK), 100);
        world
            .getServer()
            .runOnServer(
                server -> {
                  var state = server.overworld().getBlockState(control);
                  if (state.getValue(PadComputerBlock.FACING) != Direction.WEST)
                    throw new AssertionError(
                        "Outside placement must exercise an outward-facing controller");
                  var p = world.getConnection().getServerPlayer();
                  p.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                  p.inventoryMenu.broadcastChanges();
                });
        ctx.waitFor(c -> c.player.getMainHandItem().isEmpty(), 100);
        ctx.getInput().lookAt(control);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 100);
        choose(ctx, 3);
        ctx.waitFor(c -> PreviewRenderer.count(false) == 100, 100);
        ctx.waitTicks(3);
        choose(ctx, 4);
        ctx.waitFor(c -> PreviewRenderer.count(true) > 100, 100);
        ctx.runOnClient(c -> c.gui.screen().onClose());
        ctx.getInput().lookAt(base.offset(3, 3, 3));
        ctx.waitTicks(3);
        var visible = new java.util.concurrent.atomic.AtomicInteger(-1);
        ctx.runOnClient(
            c ->
                net.minecraft.client.Screenshot.takeScreenshot(
                    c.gameRenderer.mainRenderTarget(),
                    image -> {
                      try {
                        int cyan = 0;
                        for (int y = 0; y < image.getHeight(); y++)
                          for (int x = 0; x < image.getWidth(); x++) {
                            int color = image.getPixel(x, y);
                            if ((color >> 16 & 255) < 110
                                && (color >> 8 & 255) > 150
                                && (color & 255) > 160) cyan++;
                          }
                        visible.set(cyan);
                      } finally {
                        image.close();
                      }
                    }));
        ctx.waitFor(c -> visible.get() >= 0, 100);
        if (visible.get() < 30)
          throw new AssertionError(
              "Pad preview must draw visible cyan geometry, got " + visible.get() + " pixels");
        ctx.takeScreenshot("nullspace-pad-and-ship-preview");
        var count = new int[1];
        var alpha = new double[1];
        ctx.runOnClient(
            c -> {
              count[0] = PreviewRenderer.count(true);
              alpha[0] = PreviewRenderer.alpha(true);
            });
        world
            .getServer()
            .runOnServer(
                server ->
                    server
                        .overworld()
                        .setBlock(base.offset(7, 3, 4), Blocks.GOLD_BLOCK.defaultBlockState(), 18));
        ctx.waitFor(c -> PreviewRenderer.count(true) == count[0] + 1, 100);
        ctx.waitTicks(10);
        ctx.runOnClient(
            c -> {
              if (PreviewRenderer.alpha(true) >= alpha[0])
                throw new AssertionError("Refreshing geometry must not restart the 30-second fade");
            });
        world
            .getServer()
            .runOnServer(
                server ->
                    server
                        .overworld()
                        .setBlock(base.offset(7, 3, 4), Blocks.AIR.defaultBlockState(), 18));
        ctx.waitFor(c -> PreviewRenderer.count(true) == count[0], 100);
        ctx.getInput().lookAt(control);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 100);
        choose(ctx, 3);
        ctx.waitFor(c -> PreviewRenderer.count(false) == 0 && PreviewRenderer.count(true) > 0, 100);
        ctx.waitTicks(3);
        choose(ctx, 4);
        ctx.waitFor(c -> PreviewRenderer.count(true) == 0, 100);
        ctx.runOnClient(
            c -> {
              PreviewRenderer.accept(
                  new BuildPreviews.Preview(
                      UUID.randomUUID(),
                      control,
                      c.level.dimension().identifier().toString(),
                      true,
                      2,
                      new long[] {helm.asLong()}));
              if (PreviewRenderer.count(true) != 1
                  || PreviewRenderer.alpha(true) <= 0
                  || PreviewRenderer.alpha(true) > .004)
                throw new AssertionError(
                    "A near-expiry preview must retain only its remaining fade");
            });
        ctx.waitTicks(5);
        ctx.runOnClient(
            c -> {
              if (PreviewRenderer.count(true) != 0 || PreviewRenderer.alpha(true) != 0)
                throw new AssertionError(
                    "Expired overlays must disappear without another server packet");
            });
        ctx.runOnClient(
            c -> {
              var t = (TerminalScreen) c.gui.screen();
              ShipControlTestUi.click(t, 8, 5);
              t.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL));
              c.keyboardHandler.textInput(c.getWindow().handle(), "Survival Pad");
            });
        choose(ctx, 2);
        ctx.waitFor(
            c ->
                c.gui.screen() instanceof TerminalScreen t
                    && t.status().startsWith("Registered Survival Pad"),
            100);
        ctx.runOnClient(c -> c.gui.screen().onClose());
        var furnacePositions =
            List.of(base.offset(1, 3, 6), base.offset(2, 3, 6), base.offset(4, 3, 6));
        var snapshots = new CompoundTag[3];
        world
            .getServer()
            .runOnServer(
                server -> {
                  var level = server.overworld();
                  var blocks = List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
                  for (int i = 0; i < 3; i++) {
                    var pos = furnacePositions.get(i);
                    level.setBlock(
                        pos,
                        blocks
                            .get(i)
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                        18);
                    var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
                    furnace.setItem(0, new ItemStack(i == 2 ? Items.BEEF : Items.IRON_ORE, 32));
                    furnace.setItem(1, new ItemStack(Items.COAL, 4));
                  }
                  var p = world.getConnection().getServerPlayer();
                  p.teleportTo(
                      level,
                      base.getX() + 4.5,
                      base.getY() + 3,
                      base.getZ() + 3.5,
                      Set.of(),
                      0,
                      0,
                      true);
                  var cookie =
                      net.minecraft.server.network.CommonListenerCookie.createInitial(
                          new com.mojang.authlib.GameProfile(UUID.randomUUID(), "furnace-guard"),
                          false);
                  guard[0] =
                      new net.minecraft.server.level.ServerPlayer(
                          server, level, cookie.gameProfile(), cookie.clientInformation());
                  connection[0] =
                      new net.minecraft.network.Connection(
                          net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                  channel[0] = new io.netty.channel.embedded.EmbeddedChannel(connection[0]);
                  server.getPlayerList().placeNewPlayer(connection[0], guard[0], cookie);
                  guard[0].teleportTo(base.getX() + 3.5, base.getY() + 3, base.getZ() + 4.5);
                  TravelService.failureInjector =
                      stage -> {
                        failureHook.accept(stage);
                        if (stage.equals("PREPARED"))
                          for (int i = 0; i < 3; i++)
                            snapshots[i] = furnace(level, furnacePositions.get(i));
                      };
                });
        world.getConnection().waitForChunksRender();
        ctx.waitTicks(15);
        world
            .getServer()
            .waitFor(
                server -> {
                  try {
                    ShipService.preflight(server.overworld(), helm);
                    return furnace(server.overworld(), furnacePositions.getFirst())
                            .getIntOr("lit_time_remaining", 0)
                        > 0;
                  } catch (IllegalArgumentException loading) {
                    return false;
                  }
                },
                200);
        world
            .getServer()
            .runOnServer(
                server ->
                    ShipService.open(
                        world.getConnection().getServerPlayer(),
                        helm,
                        "Live furnace transfer test"));
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 100);
        ctx.waitTicks(12);
        choose(ctx, 1);
        ctx.waitFor(c -> c.gui.screen() instanceof TravelScreen, 100);
        var token = new UUID[1];
        world
            .getServer()
            .runOnServer(
                server -> {
                  connection[0].flushChannel();
                  channel[0].runPendingTasks();
                  Object packet;
                  while ((packet = channel[0].readOutbound()) != null)
                    if (packet
                            instanceof
                            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
                                custom
                        && custom.payload() instanceof TravelPackets.Progress progress
                        && !progress.done()) token[0] = progress.id();
                  if (token[0] == null)
                    throw new AssertionError("Guard did not receive travel readiness token");
                  TravelService.ready(guard[0], new TravelPackets.Ready(token[0], false));
                });
        ctx.waitFor(c -> c.level != null && c.level.dimension().equals(TravelService.SPACE), 300);
        world
            .getServer()
            .waitFor(
                server ->
                    world
                        .getConnection()
                        .getServerPlayer()
                        .level()
                        .dimension()
                        .equals(TravelService.SPACE),
                100);
        var target = new BlockPos[3];
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  var ship = WorldStore.get(server).at(p.level(), p.blockPosition());
                  var delta = WorldStore.pos(ship, "base").subtract(base.above());
                  for (int i = 0; i < 3; i++) {
                    target[i] = furnacePositions.get(i).offset(delta);
                    if (snapshots[i] == null || !snapshots[i].equals(furnace(p.level(), target[i])))
                      throw new AssertionError(
                          "Furnace inventory/cooking/fuel state changed during transfer: " + i);
                  }
                  TravelService.failureInjector = failureHook;
                });
        ctx.waitTicks(25);
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  if (!TransferLocks.busy(p))
                    throw new AssertionError("Guard must keep arrival quarantined");
                  for (int i = 0; i < 3; i++)
                    if (!snapshots[i].equals(furnace(p.level(), target[i])))
                      throw new AssertionError("A quarantined furnace advanced: " + i);
                  TravelService.ready(guard[0], new TravelPackets.Ready(token[0], true));
                });
        ctx.waitFor(c -> c.gui.screen() == null, 200);
        world
            .getServer()
            .runOnServer(
                server -> {
                  server.getPlayerList().remove(guard[0]);
                  guard[0] = null;
                  channel[0].close();
                });
        ctx.waitTicks(12);
        world
            .getServer()
            .runOnServer(
                server -> {
                  var p = world.getConnection().getServerPlayer();
                  for (int i = 0; i < 3; i++)
                    if (furnace(p.level(), target[i]).getIntOr("lit_time_remaining", 0)
                        >= snapshots[i].getIntOr("lit_time_remaining", 0))
                      throw new AssertionError("Furnaces must resume after arrival: " + i);
                  ShipService.open(
                      p,
                      WorldStore.pos(
                          WorldStore.get(server).at(p.level(), p.blockPosition()), "controller"),
                      "Return furnace test");
                });
        ctx.waitFor(c -> c.gui.screen() instanceof TerminalScreen, 100);
        ctx.waitTicks(12);
        choose(ctx, 3);
        ctx.waitFor(
            c ->
                c.level != null
                    && c.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
                    && c.gui.screen() == null,
            500);
        world
            .getServer()
            .runOnServer(
                server -> {
                  for (int i = 0; i < 3; i++)
                    if (!(server.overworld().getBlockEntity(furnacePositions.get(i))
                        instanceof AbstractFurnaceBlockEntity))
                      throw new AssertionError("Furnace missing after round trip");
                });
      } finally {
        world
            .getServer()
            .runOnServer(
                server -> {
                  TravelService.failureInjector = failureHook;
                  if (guard[0] != null) {
                    server.getPlayerList().remove(guard[0]);
                    guard[0] = null;
                  }
                  if (channel[0] != null) channel[0].close();
                });
      }
    }
    ClientTestEvidence.passed(getClass());
  }
}
