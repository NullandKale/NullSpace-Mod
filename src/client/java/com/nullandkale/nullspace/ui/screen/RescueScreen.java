package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.crew.PlayerRescue;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Always reachable; has no dependency on ship equipment or a destination list. */
public final class RescueScreen extends Screen {
  private final Screen parent;
  private boolean called;
  private int ticks;
  private net.minecraft.world.phys.Vec3 start;
  private Object startLevel;

  public RescueScreen(Screen parent) {
    super(Component.literal("Rescue"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    int x = width / 2 - 120, y = height / 2;
    addRenderableWidget(
        Button.builder(
                Component.literal("Call rescue"),
                b -> {
                  if (called) return;
                  called = true;
                  ticks = 0;
                  b.active = false;
                  start = minecraft.player.position();
                  startLevel = minecraft.player.level();
                  ClientPlayNetworking.send(new PlayerRescue.Request(false));
                })
            .bounds(x, y + 38, 116, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(x + 124, y + 38, 116, 20)
            .build());
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void tick() {
    if (!called) return;
    ticks++;
    var p = minecraft.player;
    // Arrived: the server moved us. Close without sending a cancel for a finished rescue.
    if (p != null && (p.level() != startLevel || p.position().distanceTo(start) > 8)) {
      called = false;
      minecraft.gui.setScreen(null);
    }
  }

  @Override
  public void onClose() {
    if (called) ClientPlayNetworking.send(new PlayerRescue.Request(true));
    minecraft.gui.setScreen(parent);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    g.fill(width / 2 - 132, height / 2 - 76, width / 2 + 132, height / 2 + 70, 0xee222222);
    g.text(font, title, width / 2 - 120, height / 2 - 64, 0xffffffff, false);
    int y = height / 2 - 42;
    for (var line :
        font.split(
            Component.literal(
                "Rescue takes you to Terra Harbor. You keep what you are carrying. Your ship and"
                    + " its cargo stay here."),
            240)) {
      g.text(font, line, width / 2 - 120, y, 0xffdddddd, false);
      y += 12;
    }
    if (called)
      g.text(
          font,
          "Rescue: " + Math.max(0, 3 - ticks / 20) + "...",
          width / 2 - 120,
          height / 2 + 20,
          0xffffd878,
          false);
    super.extractRenderState(g, mx, my, delta);
  }
}
