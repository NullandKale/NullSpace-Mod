package com.nullandkale.nullspace.ui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
import com.nullandkale.nullspace.gameplay.navigation.Piloting;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets;
import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.scene.SceneViewport;
import com.nullandkale.nullspace.ui.terminal.TerminalTheme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Third-person render of the real ship snapshot; all movement remains server authoritative. */
public final class ShipPilotScreen extends Screen implements SceneScreen {
  private final TerminalScreen parent;
  private ScenePackets.Frame frame;
  private SceneViewport viewport;
  private int pulse, buttons, sequence, ticks, lastUpdate;
  private double orbit, elevation = 18, distance = 70;
  private boolean focused, help;

  public ShipPilotScreen(ScenePackets.Frame frame) {
    super(Component.literal("Ship controls"));
    parent =
        net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof TerminalScreen t
            ? t
            : null;
    this.frame = frame;
    distance = frame.scene().getDoubleOr("viewDistance", 70);
    orbit = 180 + frame.scene().getFloatOr("helmYaw", 180);
  }

  public boolean owns(java.util.UUID id) {
    return frame.lease().equals(id);
  }

  public void update(ScenePackets.Frame next) {
    if (owns(next.lease()) && next.revision() > frame.revision()) {
      frame = next;
      lastUpdate = ticks;
    }
  }

  public SceneViewport viewport() {
    return viewport;
  }

  public boolean canPilot() {
    return frame.scene().getBooleanOr("canPilot", false);
  }

  @Override
  protected void init() {
    resizeViewport();
    focused = minecraft.isWindowActive();
    addRenderableWidget(
        net.minecraft.client.gui.components.Button.builder(
                Component.literal("H"), b -> help = !help)
            .tooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("Controls (H)")))
            .bounds(8, 23, 24, 18)
            .build());
    if (canPilot()) {
      addRenderableWidget(
          net.minecraft.client.gui.components.Button.builder(
                  Component.literal("R"), b -> pulse |= FlightDynamics.BRAKE)
              .tooltip(
                  net.minecraft.client.gui.components.Tooltip.create(
                      Component.literal("Brake (R)")))
              .bounds(36, 23, 24, 18)
              .build());
      addRenderableWidget(
          net.minecraft.client.gui.components.Button.builder(
                  Component.literal("F"), b -> pulse |= FlightDynamics.FIRE)
              .tooltip(
                  net.minecraft.client.gui.components.Tooltip.create(Component.literal("Fire (F)")))
              .bounds(64, 23, 24, 18)
              .build());
    }
    addRenderableWidget(
        net.minecraft.client.gui.components.Button.builder(
                Component.literal("Back"), b -> onClose())
            .bounds(width - 72, 23, 64, 18)
            .build());
  }

  private void resizeViewport() {
    int w = SpaceSettings.width(), h = SpaceSettings.height();
    if ((long) w * Math.max(1, height) > (long) h * Math.max(1, width))
      w = Math.max(16, (int) Math.round(h * Math.max(1, width) / (double) Math.max(1, height)));
    else h = Math.max(16, (int) Math.round(w * Math.max(1, height) / (double) Math.max(1, width)));
    if (viewport == null || viewport.target.width != w || viewport.target.height != h) {
      if (viewport != null) viewport.close();
      viewport = new SceneViewport(w, h);
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void tick() {
    ticks++;
    if (ticks - lastUpdate > 60) {
      onClose();
      return;
    }
    boolean active = minecraft.isWindowActive();
    if (focused && !active) {
      buttons = 0;
      pulse = 0;
    }
    focused = active;
    ClientPlayNetworking.send(
        new Piloting.Input(frame.lease(), sequence++, canPilot() ? buttons | pulse : 0, false));
    buttons &= ~FlightDynamics.FIRE;
    pulse = 0;
  }

  @Override
  public void onClose() {
    if (parent != null) TerminalScreen.rememberReturn(parent);
    super.onClose();
  }

  @Override
  public void removed() {
    if (minecraft.getConnection() != null)
      ClientPlayNetworking.send(new Piloting.Input(frame.lease(), sequence++, 0, true));
    if (viewport != null) {
      viewport.close();
      viewport = null;
    }
  }

  private static int bit(int key) {
    return switch (key) {
      case InputConstants.KEY_W -> FlightDynamics.FORWARD;
      case InputConstants.KEY_S -> FlightDynamics.REVERSE;
      case InputConstants.KEY_A -> FlightDynamics.LEFT;
      case InputConstants.KEY_D -> FlightDynamics.RIGHT;
      case InputConstants.KEY_SPACE -> FlightDynamics.UP;
      case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> FlightDynamics.DOWN;
      case InputConstants.KEY_Q -> FlightDynamics.ROLL_LEFT;
      case InputConstants.KEY_E -> FlightDynamics.ROLL_RIGHT;
      case InputConstants.KEY_UP -> FlightDynamics.PITCH_UP;
      case InputConstants.KEY_DOWN -> FlightDynamics.PITCH_DOWN;
      case InputConstants.KEY_R -> FlightDynamics.BRAKE;
      case InputConstants.KEY_F -> FlightDynamics.FIRE;
      default -> 0;
    };
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.key() == InputConstants.KEY_H) {
      help = !help;
      return true;
    }
    int bit = bit(event.key());
    if (bit != 0) {
      buttons |= bit;
      return true;
    }
    return super.keyPressed(event);
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    int bit = bit(event.key());
    buttons &= ~bit;
    return bit != 0 || super.keyReleased(event);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
    return super.mouseClicked(event, twice);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    if (event.y() < 44) return false;
    orbit += dx * .4;
    elevation = Math.clamp(elevation + dy * .4, -80, 80);
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    distance = Math.clamp(distance - sy * 5, 28, 200);
    return true;
  }

  public void renderFrame() {
    resizeViewport();
    var scene = SceneViewport.cameraView(frame.scene());
    double a = Math.toRadians(orbit),
        e = Math.toRadians(elevation),
        horizontal = Math.cos(e) * distance;
    scene.putDouble("cameraX", scene.getDoubleOr("targetX", 16) - Math.sin(a) * horizontal);
    scene.putDouble("cameraY", scene.getDoubleOr("targetY", 16) + Math.sin(e) * distance);
    scene.putDouble("cameraZ", scene.getDoubleOr("targetZ", 16) + Math.cos(a) * horizontal);
    scene.putFloat("cameraYaw", (float) (180 + orbit));
    scene.putFloat("cameraPitch", (float) elevation);
    viewport.render(scene, 0, 0, 70);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    if (viewport != null) {
      var sampler =
          RenderSystem.getSamplerCache()
              .getSampler(
                  AddressMode.CLAMP_TO_EDGE,
                  AddressMode.CLAMP_TO_EDGE,
                  FilterMode.LINEAR,
                  FilterMode.LINEAR,
                  false);
      g.blit(viewport.target.getColorTextureView(), sampler, 0, 0, width, height, 0, 1, 1, 0);
    }
    g.fill(0, 0, width, 15, 0x98060d16);
    g.fill(0, height - 15, width, height, 0x98060d16);
    g.text(
        font,
        (canPilot() ? "PILOT" : "SHIP CAMERA")
            + " | "
            + frame.scene().getStringOr("location", "")
            + " | "
            + String.format(
                java.util.Locale.ROOT, "%.1f m/s", frame.scene().getDoubleOr("speed", 0)),
        8,
        5,
        TerminalTheme.CYAN,
        false);
    String hint = "H controls | Esc ship controls";
    int hintX = Math.max(8, width - font.width(hint) - 8);
    g.text(
        font,
        font.plainSubstrByWidth(
            frame.scene().getStringOr("status", "")
                + " | Power "
                + frame.scene().getIntOr("propulsion", 0),
            Math.max(0, hintX - 16)),
        8,
        height - 11,
        TerminalTheme.AMBER,
        false);
    g.text(font, hint, hintX, height - 11, TerminalTheme.MUTED, false);
    if (help) {
      g.fill(4, height - 51, Math.min(width - 4, 430), height - 17, 0xc0060d16);
      g.text(
          font,
          canPilot() ? "W/S thrust | A/D yaw | Space/Ctrl lift" : "Observer camera",
          8,
          height - 47,
          TerminalTheme.TEXT,
          false);
      g.text(
          font,
          canPilot() ? "Up/Down pitch | Q/E roll | R brake | F fire" : "",
          8,
          height - 37,
          TerminalTheme.TEXT,
          false);
      g.text(font, "Drag orbit | Wheel zoom", 8, height - 27, TerminalTheme.MUTED, false);
    }
    super.extractRenderState(g, mx, my, delta);
  }
}
