package com.nullandkale.nullspace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

@SuppressWarnings("UnstableApiUsage")
public final class NullSpaceClientGameTest implements FabricClientGameTest {
  static void shiftEscape(ClientGameTestContext context, int modifiers) {
    // Fabric 26.3 TestInputImpl.pressOrReleaseKey hard-codes modifiers=0. Exercise the
    // real Minecraft callback with the left/right Shift flags supplied by native SDL events.
    context.runOnClient(
        client -> {
          var event =
              new net.minecraft.client.input.KeyEvent(
                  com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 27, modifiers);
          client.keyboardHandler.keyPress(
              client.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.PRESS, event);
          client.keyboardHandler.keyPress(
              client.getWindow().handle(),
              com.mojang.blaze3d.platform.InputConstants.RELEASE,
              event);
        });
  }

  private static void checkStreamExit(ClientGameTestContext context) {
    for (int modifiers : new int[] {1, 2}) { // SDL left/right Shift modifier bits.
      var bytes = new java.util.ArrayList<String>();
      int[] closed = {0};
      context.runOnClient(
          client -> {
            var stream =
                new com.nullandkale.nullspace.terminal.StreamTerminal(
                    value -> bytes.add(new String(value, java.nio.charset.StandardCharsets.UTF_8)));
            client.gui.setScreen(
                new com.nullandkale.nullspace.client.TerminalScreen(
                    stream, () -> {}, () -> closed[0]++));
          });
      shiftEscape(context, modifiers);
      context.waitFor(client -> client.gui.screen() == null, 100);
      context.runOnClient(
          client -> {
            if (closed[0] != 1 || !bytes.isEmpty())
              throw new AssertionError(
                  "Shift+Escape closes exactly once without sending guest input");
            if (org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(client.getWindow().handle()))
              throw new AssertionError("Shift+Escape must release native text input");
          });
    }
  }

  @Override
  public void runTest(ClientGameTestContext context) {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) {
      LocalMultiplayerTest.run(context);
      return;
    }
    try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
      singleplayer.getConnection().waitForChunksRender();
      context.runOnClient(
          client -> {
            var terminal =
                new com.nullandkale.nullspace.client.TerminalScreen(
                    new com.nullandkale.nullspace.terminal.ComputerTerminal.Open(
                        new net.minecraft.core.BlockPos(0, 0, 0), ""));
            client.gui.setScreen(terminal);

            var bytes = new java.util.ArrayList<String>();
            var stream =
                new com.nullandkale.nullspace.terminal.StreamTerminal(
                    value -> bytes.add(new String(value, java.nio.charset.StandardCharsets.UTF_8)));
            stream.write("boot\r\n\033[25;80H!");
            var computerScreen = new com.nullandkale.nullspace.client.TerminalScreen(stream);
            client.gui.setScreen(computerScreen);
            client.keyboardHandler.textInput(client.getWindow().handle(), "native input");
            computerScreen.keyPressed(
                new net.minecraft.client.input.KeyEvent(
                    com.mojang.blaze3d.platform.InputConstants.KEY_UP, 0, 0));
            computerScreen.keyPressed(
                new net.minecraft.client.input.KeyEvent(
                    com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0));
            if (!String.join("", bytes).equals("native input\033[A\033")
                || client.gui.screen() != computerScreen)
              throw new AssertionError(
                  "Generic terminal must forward text/arrows/Escape to its device");
            if (computerScreen.buffer().cell(79, 24).glyph() != '!')
              throw new AssertionError("Generic computer output must reach column 80, row 25");
            client.gui.setScreen(terminal);
            var emptyShip =
                new com.nullandkale.nullspace.client.TerminalScreen(
                    new com.nullandkale.nullspace.terminal.ComputerTerminal.Open(
                        new net.minecraft.core.BlockPos(0, 0, 0), "", "ship", ""));
            client.gui.setScreen(emptyShip);
            click(emptyShip, 10, 15);
            if (!emptyShip.status().contains("Enter a destination") || emptyShip.selection() != 2)
              throw new AssertionError("Blank landing destination must explain how to proceed");
            client.gui.setScreen(terminal);

            client.gui.setScreen(null);
          });
      checkStreamExit(context);
      singleplayer
          .getServer()
          .runOnServer(
              server -> {
                var player = singleplayer.getConnection().getServerPlayer();
                var level = player.level();
                for (int x = -1; x <= 8; x++)
                  for (int z = -1; z <= 8; z++) {
                    var block =
                        x == -1 || x == 8 || z == -1 || z == 8
                            ? net.minecraft.world.level.block.Blocks.COBBLESTONE
                            : net.minecraft.world.level.block.Blocks.GRASS_BLOCK;
                    level.setBlockAndUpdate(
                        new net.minecraft.core.BlockPos(x, -60, z), block.defaultBlockState());
                  }
                var pos = new net.minecraft.core.BlockPos(-1, -59, 3);
                level.setBlockAndUpdate(
                    pos,
                    com.nullandkale.nullspace.pad.PadComputers.BLOCK
                        .defaultBlockState()
                        .setValue(
                            com.nullandkale.nullspace.pad.PadComputerBlock.FACING,
                            net.minecraft.core.Direction.EAST));
                player.teleportTo(-1, -58, 3);
                server
                    .getCommands()
                    .performPrefixedCommand(
                        player.createCommandSourceStack(),
                        "nullspace pad -1 -59 3 register Test Pad");
                var file =
                    server
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("nullspace-pads.json");
                String saved;
                try {
                  saved = java.nio.file.Files.readString(file);
                } catch (java.io.IOException error) {
                  throw new java.io.UncheckedIOException(error);
                }
                var records = com.google.gson.JsonParser.parseString(saved).getAsJsonArray();
                long named =
                    java.util.stream.StreamSupport.stream(records.spliterator(), false)
                        .filter(
                            t -> t.getAsJsonObject().get("name").getAsString().equals("Test Pad"))
                        .count();
                if (named != 1) throw new AssertionError("Pad registration was not persisted");
              });
      context.waitFor(
          client -> client.gui.screen() instanceof com.nullandkale.nullspace.client.TerminalScreen);
      long ready = System.nanoTime();
      context.waitFor(client -> System.nanoTime() - ready > 600_000_000L);
      context.runOnClient(
          client -> {
            var terminal = (com.nullandkale.nullspace.client.TerminalScreen) client.gui.screen();
            if (!org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(client.getWindow().handle()))
              throw new AssertionError("Terminal must activate native text input");
            click(terminal, 10, 5);
            terminal.keyPressed(
                new net.minecraft.client.input.KeyEvent(
                    com.mojang.blaze3d.platform.InputConstants.KEY_A,
                    0,
                    com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL));
            client.keyboardHandler.textInput(client.getWindow().handle(), "Terminal Pad");
            click(terminal, 4, 7);
          });
      singleplayer
          .getServer()
          .waitFor(
              server -> {
                try {
                  var file =
                      server
                          .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                          .resolve("nullspace-pads.json");
                  return java.nio.file.Files.readString(file).contains("Terminal Pad");
                } catch (java.io.IOException error) {
                  throw new java.io.UncheckedIOException(error);
                }
              });
      context.takeScreenshot("nullspace-pad-computer");
      context.runOnClient(
          client -> {
            client.gui.setScreen(null);
            if (org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(client.getWindow().handle()))
              throw new AssertionError("Closing a terminal must release native text input");
          });
      singleplayer
          .getServer()
          .runOnServer(
              server ->
                  com.nullandkale.nullspace.pad.PadComputers.open(
                      singleplayer.getConnection().getServerPlayer(),
                      new net.minecraft.core.BlockPos(-1, -59, 3),
                      "Reopened registered pad"));
      context.waitFor(
          client ->
              client.gui.screen() instanceof com.nullandkale.nullspace.client.TerminalScreen t
                  && t.fieldValue().equals("Terminal Pad"));

      long padSaved = System.nanoTime();
      context.waitFor(client -> System.nanoTime() - padSaved > 600_000_000L);
      singleplayer
          .getServer()
          .runOnServer(
              server -> {
                var player = singleplayer.getConnection().getServerPlayer();
                var level = player.level();
                net.minecraft.core.BlockPos base;
                try {
                  base = com.nullandkale.nullspace.dev.ShipLab.ensure(server);
                } catch (java.io.IOException error) {
                  throw new java.io.UncheckedIOException(error);
                }
                var computer = base.offset(6, 3, 3);
                player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
                com.nullandkale.nullspace.ship.ShipService.open(
                    player, computer, "Ready for launch test");
                com.nullandkale.nullspace.ship.ShipService.inspect(level, computer);
              });
      context.waitFor(
          client -> client.gui.screen() instanceof com.nullandkale.nullspace.client.TerminalScreen);
      long shipReady = System.nanoTime();
      context.waitFor(client -> System.nanoTime() - shipReady > 600_000_000L);
      context.waitFor(
          client -> client.gui.screen() instanceof com.nullandkale.nullspace.client.TerminalScreen t
              && t.shipController());
      ShipControlTestUi.action(context, com.nullandkale.nullspace.navigation.ShipControl.Action.LAUNCH);
      context.takeScreenshot("nullspace-launch-request");
      context.waitFor(
          client -> client.gui.screen() instanceof com.nullandkale.nullspace.client.TravelScreen);
      context.takeScreenshot("nullspace-loading");
      singleplayer
          .getServer()
          .waitFor(
              server ->
                  singleplayer
                      .getConnection()
                      .getServerPlayer()
                      .level()
                      .dimension()
                      .equals(com.nullandkale.nullspace.ship.TravelService.SPACE));
      singleplayer
          .getServer()
          .runOnServer(
              server -> {
                var player = singleplayer.getConnection().getServerPlayer();
                var vessel =
                    com.nullandkale.nullspace.ship.ShipStore.get(server)
                        .at(player.level(), player.blockPosition());
                var computer = com.nullandkale.nullspace.ship.ShipStore.pos(vessel, "controller");
                if (!com.nullandkale.nullspace.ship.TravelService.busy(player))
                  throw new AssertionError("Client must still be loading for quarantine check");
                if (player
                    .level()
                    .setBlock(
                        computer,
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        18))
                  throw new AssertionError("Arrival must reject environmental block mutation");
              });
      context.waitFor(
          client ->
              client.gui.screen() == null
                  && client.level != null
                  && client
                      .level
                      .dimension()
                      .equals(com.nullandkale.nullspace.ship.TravelService.SPACE));
      singleplayer.getConnection().waitForChunksRender();
      context.takeScreenshot("nullspace-space-cabin");
      singleplayer
          .getServer()
          .runOnServer(
              server -> {
                var player = singleplayer.getConnection().getServerPlayer();
                var vessel =
                    com.nullandkale.nullspace.ship.ShipStore.get(server)
                        .at(player.level(), player.blockPosition());
                var computer = com.nullandkale.nullspace.ship.ShipStore.pos(vessel, "controller");
                server
                    .getCommands()
                    .performPrefixedCommand(
                        player.createCommandSourceStack(),
                        "nullspace ship "
                            + computer.getX()
                            + " "
                            + computer.getY()
                            + " "
                            + computer.getZ()
                            + " land "
                            + vessel.getStringOr("home", ""));
              });
      singleplayer
          .getServer()
          .waitFor(
              server ->
                  singleplayer
                      .getConnection()
                      .getServerPlayer()
                      .level()
                      .dimension()
                      .equals(net.minecraft.world.level.Level.OVERWORLD));
      context.waitFor(
          client ->
              client.gui.screen() == null
                  && client.level != null
                  && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD));
      singleplayer
          .getServer()
          .runOnServer(
              server -> {
                var player = singleplayer.getConnection().getServerPlayer();
                var vessel =
                    com.nullandkale.nullspace.ship.ShipStore.get(server)
                        .at(player.level(), player.blockPosition());
                if (vessel == null
                    || !player
                        .level()
                        .getBlockState(
                            com.nullandkale.nullspace.ship.ShipStore.pos(vessel, "controller"))
                        .is(com.nullandkale.nullspace.ship.ShipBlocks.COMPUTER))
                  throw new AssertionError("Ship must return to registered pad");
              });
      context.takeScreenshot("nullspace-returned");
    }
    ClientTestEvidence.passed(getClass());
  }

  private static void click(
      com.nullandkale.nullspace.client.TerminalScreen terminal, int column, int row) {
    double scale = Math.min((terminal.width - 16) / 480.0, (terminal.height - 16) / 250.0);
    double x = (terminal.width - 480 * scale) / 2 + (column * 6 + 3) * scale;
    double y = (terminal.height - 250 * scale) / 2 + (row * 10 + 5) * scale;
    if (!terminal.mouseClicked(
        new net.minecraft.client.input.MouseButtonEvent(
            x,
            y,
            new net.minecraft.client.input.MouseButtonInfo(
                com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)),
        false)) throw new AssertionError("Terminal click must be handled");
  }
}
