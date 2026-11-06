package com.nullandkale.nullspace.client.scene;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
import com.nullandkale.nullspace.scene.SceneService;
import com.nullandkale.nullspace.terminal.TerminalTheme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class ViewportScreen extends Screen implements SceneScreen {
  private final com.nullandkale.nullspace.client.TerminalScreen parent;
  private SceneService.Frame frame;
  private SceneViewport viewport;
  private int ticks, selection, lastUpdate;
  private float yaw, pitch, fov = 70;

  public ViewportScreen(SceneService.Frame frame) {
    super(Component.literal("Remote viewport"));
    parent =
        net.minecraft.client.Minecraft.getInstance().gui.screen()
                instanceof com.nullandkale.nullspace.client.TerminalScreen t
            ? t
            : null;
    this.frame = frame;
    this.selection = frame.scene().getIntOr("selection", 0);
  }

  public boolean owns(java.util.UUID id) {
    return frame.lease().equals(id);
  }

  public String status() {
    return frame.scene().getStringOr("status", "");
  }

  public net.minecraft.core.BlockPos sourcePosition() {
    return net.minecraft.core.BlockPos.of(frame.scene().getLongOr("source", 0));
  }

  public void update(SceneService.Frame next) {
    if (frame.lease().equals(next.lease()) && next.revision() > frame.revision()) {
      frame = next;
      lastUpdate = ticks;
    }
  }

  public SceneViewport viewport() {
    return viewport;
  }

  public void renderFrame() {
    if (viewport != null) {
      if (ticks - lastUpdate > 60) viewport.clear();
      else viewport.render(frame.scene(), yaw, pitch, fov);
    }
  }

  @Override
  protected void init() {
    String[] labels = {"Left", "Up", "Down", "Right", "Zoom +", "Zoom -"};
    Runnable[] actions = {
      () -> yaw -= 5,
      () -> pitch -= 5,
      () -> pitch += 5,
      () -> yaw += 5,
      () -> fov = Math.max(20, fov - 5),
      () -> fov = Math.min(110, fov + 5)
    };
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      addRenderableWidget(
          net.minecraft.client.gui.components.Button.builder(
                  Component.literal(labels[i]), b -> actions[index].run())
              .bounds(8 + i * 48, height - 40, 46, 18)
              .build());
    }
    if (!frame.scene().getBooleanOr("fixedCamera", false))
      addRenderableWidget(
          net.minecraft.client.gui.components.Button.builder(
                  Component.literal("Next camera"),
                  b -> {
                    selection++;
                    ClientPlayNetworking.send(
                        new SceneService.KeepAlive(frame.lease(), false, selection));
                  })
              .bounds(300, height - 40, 88, 18)
              .build());
    addRenderableWidget(
        net.minecraft.client.gui.components.Button.builder(
                Component.literal(
                    frame.scene().getBooleanOr("returnMachine", false) ? "Back" : "Close"),
                b -> onClose())
            .bounds(width - 72, height - 40, 64, 18)
            .build());
    if (viewport == null)
      viewport =
          new SceneViewport(
              com.nullandkale.nullspace.client.SpaceSettings.width(),
              com.nullandkale.nullspace.client.SpaceSettings.height());
  }

  @Override
  public void tick() {
    if (++ticks % 20 == 0)
      ClientPlayNetworking.send(new SceneService.KeepAlive(frame.lease(), false, selection));
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    if (parent != null) com.nullandkale.nullspace.client.TerminalScreen.rememberReturn(parent);
    super.onClose();
  }

  @Override
  public void removed() {
    if (minecraft.getConnection() != null)
      ClientPlayNetworking.send(new SceneService.KeepAlive(frame.lease(), true, selection));
    if (viewport != null) {
      viewport.close();
      viewport = null;
    }
  }

  @Override
  public boolean keyPressed(KeyEvent key) {
    switch (key.key()) {
      case InputConstants.KEY_LEFT -> yaw -= 5;
      case InputConstants.KEY_RIGHT -> yaw += 5;
      case InputConstants.KEY_UP -> pitch -= 5;
      case InputConstants.KEY_DOWN -> pitch += 5;
      case InputConstants.KEY_PAGEUP -> fov = Math.max(20, fov - 5);
      case InputConstants.KEY_PAGEDOWN -> fov = Math.min(110, fov + 5);
      case InputConstants.KEY_TAB -> {
        if (!frame.scene().getBooleanOr("fixedCamera", false)) {
          selection++;
          ClientPlayNetworking.send(new SceneService.KeepAlive(frame.lease(), false, selection));
        }
      }
      case InputConstants.KEY_F2 ->
          net.minecraft.client.Screenshot.takeScreenshot(
              viewport.target,
              image -> {
                try {
                  var path = minecraft.gameDirectory.toPath().resolve("screenshots");
                  java.nio.file.Files.createDirectories(path);
                  image.writeToFile(
                      path.resolve("viewport-" + System.currentTimeMillis() + ".png"));
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                } finally {
                  image.close();
                }
              });
      default -> {
        return super.keyPressed(key);
      }
    }
    return true;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
    graphics.fill(0, 0, width, height, TerminalTheme.BACKGROUND);
    graphics.fill(0, 0, width, 19, TerminalTheme.PANEL);
    int w = Math.min(width - 16, (height - 60) * 16 / 9),
        h = w * 9 / 16,
        x = (width - w) / 2,
        y = 22;
    var sampler =
        RenderSystem.getSamplerCache()
            .getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                false);
    graphics.blit(viewport.target.getColorTextureView(), sampler, x, y, x + w, y + h, 0, 1, 1, 0);
    String status =
        ticks - lastUpdate > 60
            ? "STALE - source connection lost"
            : frame.scene().getStringOr("status", "Loading");
    int color = TerminalTheme.feedStatus(status);
    graphics.fill(0, 0, 3, 19, color);
    graphics.text(font, status, 8, 5, color, false);
    graphics.text(
        font,
        "Arrows aim | PgUp/Dn zoom | "
            + (frame.scene().getBooleanOr("fixedCamera", false) ? "" : "Tab camera | ")
            + "F2 snapshot | Esc back / close",
        8,
        height - 18,
        TerminalTheme.MUTED,
        false);
    super.extractRenderState(graphics, mx, my, delta);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    fov = Math.clamp(fov - (float) sy * 5, 20, 110);
    return true;
  }
}
