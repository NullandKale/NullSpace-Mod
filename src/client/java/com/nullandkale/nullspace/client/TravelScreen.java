package com.nullandkale.nullspace.client;

import com.nullandkale.nullspace.ship.TravelPackets;
import com.nullandkale.nullspace.terminal.TerminalTheme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class TravelScreen extends Screen {
  private static TravelPackets.Progress active;
  private static int stableTicks;
  private static boolean sent;

  private TravelScreen() {
    super(Component.literal("Ship transit"));
  }

  public static void initialize() {
    ClientPlayNetworking.registerGlobalReceiver(
        TravelPackets.Progress.TYPE,
        (packet, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (packet.done()) {
                        active = null;
                        if (context.client().gui.screen() instanceof TravelScreen)
                          context.client().gui.setScreen(null);
                        return;
                      }
                      active = packet;
                      stableTicks = 0;
                      sent = false;
                      context.client().gui.setScreen(new TravelScreen());
                      if (packet.dimension().isEmpty())
                        ClientPlayNetworking.send(new TravelPackets.Ready(packet.id(), false));
                    }));
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (active == null) return;
          if (client.getConnection() == null) {
            active = null;
            return;
          }
          if (!(client.gui.screen() instanceof TravelScreen))
            client.gui.setScreen(new TravelScreen());
          if (!active.dimension().isEmpty()
              && !sent
              && client.level != null
              && client.player != null
              && (active.dimension().startsWith("scene:")
                  ? com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.readyForScene(
                      active.dimension().substring(6))
                  : client.level.dimension().identifier().toString().equals(active.dimension()))
              && client.level.hasChunkAt(client.player.blockPosition())) {
            if (++stableTicks >= 10) {
              ClientPlayNetworking.send(new TravelPackets.Ready(active.id(), true));
              sent = true;
            }
          } else if (!sent) stableTicks = 0;
        });
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public boolean keyPressed(KeyEvent key) {
    return true;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    graphics.fill(0, 0, width, height, TerminalTheme.BACKGROUND);
    graphics.fill(16, height / 2 - 38, width - 16, height / 2 + 42, TerminalTheme.PANEL);
    graphics.fill(16, height / 2 - 38, width - 16, height / 2 - 36, TerminalTheme.CYAN);
    graphics.centeredText(
        font, "NULLSPACE // SHIP TRANSIT", width / 2, height / 2 - 24, TerminalTheme.CYAN);
    graphics.centeredText(
        font,
        active == null ? "Arriving" : active.message(),
        width / 2,
        height / 2,
        TerminalTheme.TEXT);
    graphics.centeredText(
        font, "Loading ship and passengers...", width / 2, height / 2 + 24, TerminalTheme.AMBER);
  }
}
