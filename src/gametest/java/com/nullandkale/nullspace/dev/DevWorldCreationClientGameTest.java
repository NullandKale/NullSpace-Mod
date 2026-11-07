package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.ui.screen.WorldStartAccess;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;

/** The restart route uses the real creation screen, honors edits and remembers a renamed save. */
public final class DevWorldCreationClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext c) {
    try {
      check(c);
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private void check(ClientGameTestContext c) throws java.io.IOException {
    ClientTestEvidence.started(getClass());
    if (System.getProperty("nullspace.local.role") != null) return;
    var marker = new AtomicReference<Path>();
    c.runOnClient(mc -> marker.set(mc.gameDirectory.toPath().resolve("nullspace-dev-world.txt")));
    String previous = Files.exists(marker.get()) ? Files.readString(marker.get()) : null;
    try {
      c.runOnClient(mc -> DevWorldCreation.open(mc, "World 5", "halcyon_horizon"));
      c.waitFor(mc -> mc.gui.screen() instanceof CreateWorldScreen, 1200);
      c.runOnClient(
          mc -> {
            var create = (CreateWorldScreen) mc.gui.screen();
            if (!create.getUiState().getName().equals("World 5")
                || !((WorldStartAccess) create).nullspace$start().space()
                || !((WorldStartAccess) create).nullspace$start().ship().equals("halcyon_horizon"))
              throw new AssertionError("Restart must prefill World 5 and the preferred ship");
            var state = create.getUiState();
            state.setName("Renamed dev world");
            state.setSeed("813579");
            state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            state.setWorldType(
                state.getNormalPresetList().stream()
                    .filter(p -> p.preset() != null && p.preset().is(WorldPresets.FLAT))
                    .findFirst()
                    .orElseThrow());
            create.children().stream()
                .filter(MenuTabBar.class::isInstance)
                .map(MenuTabBar.class::cast)
                .findFirst()
                .orElseThrow()
                .selectTab(3, false);
            click(create, "Start: Aboard a ship");
            if (((WorldStartAccess) create).nullspace$start().space())
              throw new AssertionError("The UI must allow choosing an ordinary planet start");
          });
      c.takeScreenshot("nullspace-dev-restart-creation");
      c.runOnClient(mc -> click(mc.gui.screen(), "Create New World"));
      c.waitFor(
          mc -> {
            if (mc.gui.screen() instanceof ConfirmScreen confirm)
              confirm.children().stream()
                  .filter(Button.class::isInstance)
                  .map(Button.class::cast)
                  .filter(
                      b -> java.util.Set.of("Proceed", "Yes").contains(b.getMessage().getString()))
                  .findFirst()
                  .ifPresent(b -> click(confirm, b.getMessage().getString()));
            return mc.level != null
                && mc.player != null
                && mc.getSingleplayerServer() != null
                && mc.player.level() == mc.level
                && mc.gui.screen() == null;
          },
          6000);
      var checked = new AtomicBoolean();
      var failure = new AtomicReference<Throwable>();
      c.runOnClient(
          mc ->
              mc.getSingleplayerServer()
                  .execute(
                      () -> {
                        try {
                          var server = mc.getSingleplayerServer();
                          var player = server.getPlayerList().getPlayers().getFirst();
                          String folder =
                              server
                                  .getWorldPath(LevelResource.ROOT)
                                  .toAbsolutePath()
                                  .normalize()
                                  .getFileName()
                                  .toString();
                          if (!Files.readString(marker.get()).equals(folder)
                              || !folder.startsWith("Renamed dev world"))
                            throw new AssertionError(
                                "Normal dev launches must reopen the folder chosen by the creation"
                                    + " UI");
                          if (server.overworld().getSeed() != 813579L
                              || !server.overworld().isFlat()
                              || !player.isCreative()
                              || !player
                                  .level()
                                  .dimension()
                                  .equals(net.minecraft.world.level.Level.OVERWORLD))
                            throw new AssertionError(
                                "Creation must honor seed, world type, game mode and planet start");
                          var pad =
                              PadRegistry.read(server).stream()
                                  .filter(
                                      p ->
                                          p.dimension().equals("minecraft:overworld")
                                              && p.name().startsWith("Terra Landing"))
                                  .findFirst()
                                  .orElseThrow(
                                      () ->
                                          new AssertionError(
                                              "Flat world has no Terra landing destination"));
                          var floor = pad.bounds();
                          // Load the sampled column first: an unloaded chunk reports the
                          // minimum build height, which made this check fail under load.
                          server
                              .overworld()
                              .getChunk((floor.minX() - 2) >> 4, (floor.minZ() - 2) >> 4);
                          int terrain =
                              server
                                  .overworld()
                                  .getHeight(
                                      net.minecraft.world.level.levelgen.Heightmap.Types
                                          .WORLD_SURFACE,
                                      floor.minX() - 2,
                                      floor.minZ() - 2);
                          if (terrain >= 0 || floor.y() != terrain)
                            throw new AssertionError(
                                "Flat-world landing pad must meet the negative-Y ground instead of"
                                    + " floating at Y=64");
                          checked.set(true);
                        } catch (Throwable error) {
                          failure.set(error);
                        }
                      }));
      c.waitFor(mc -> checked.get() || failure.get() != null, 300);
      if (failure.get() != null)
        throw new AssertionError("Dev creation options failed", failure.get());
      c.waitTicks(30);
      var closing = new AtomicReference<net.minecraft.client.server.IntegratedServer>();
      c.runOnClient(
          mc -> {
            closing.set(mc.getSingleplayerServer());
            mc.disconnectFromWorld(
                net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
          });
      c.waitFor(
          mc ->
              mc.level == null
                  && mc.player == null
                  && mc.getSingleplayerServer() == null
                  && closing.get().isShutdown(),
          100);
      c.waitTicks(10);
      c.runOnClient(
          mc -> {
            mc.clearDownloadedResourcePacks();
            mc.gui.setScreen(new TitleScreen());
          });
      ClientTestEvidence.passed(getClass());
    } finally {
      if (previous == null) Files.deleteIfExists(marker.get());
      else Files.writeString(marker.get(), previous);
    }
  }

  private static void click(Screen screen, String label) {
    var button =
        screen.children().stream()
            .filter(AbstractButton.class::isInstance)
            .map(AbstractButton.class::cast)
            .filter(b -> b.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing control: " + label));
    if (!button.active) throw new AssertionError("Disabled control: " + label);
    screen.mouseClicked(
        new net.minecraft.client.input.MouseButtonEvent(
            button.getX() + button.getWidth() / 2.0,
            button.getY() + button.getHeight() / 2.0,
            new net.minecraft.client.input.MouseButtonInfo(
                com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)),
        false);
  }
}
