package com.nullandkale.nullspace.client;

import com.nullandkale.nullspace.ship.TransitFade;
import com.nullandkale.nullspace.ship.TravelPackets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class TravelScreen extends Screen {
  private static TravelPackets.Progress active;
  private static int stableTicks;
  private static boolean sent, prepared, finishing;
  private static long fadeStarted;
  private static double fadeFrom;

  public static double opacity() {
    if (active == null) return 0;
    double t =
        TransitFade.opacity(
            Math.max(0, (System.nanoTime() - fadeStarted) / 1e9), active.kind(), false);
    return fadeFrom + ((finishing ? 0 : 1) - fadeFrom) * t;
  }

  private static void fade(boolean revealing, double from) {
    fadeFrom = from;
    fadeStarted = System.nanoTime();
    finishing = revealing;
  }

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
                        if (active == null || !active.id().equals(packet.id())) return;
                        double covered = opacity();
                        active = packet;
                        fade(true, covered);
                        return;
                      }
                      boolean same = active != null && active.id().equals(packet.id());
                      double covered = opacity();
                      active = packet;
                      stableTicks = 0;
                      sent = false;
                      if (!same || finishing) {
                        prepared = false;
                        fade(false, covered);
                      }
                      context.client().gui.setScreen(new TravelScreen());
                    }));
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (active == null) return;
          if (client.getConnection() == null) {
            active = null;
            if (client.gui.screen() instanceof TravelScreen) client.gui.setScreen(null);
            return;
          }
          if (finishing) {
            if (opacity() <= .001) {
              active = null;
              if (client.gui.screen() instanceof TravelScreen) client.gui.setScreen(null);
            }
            return;
          }
          if (!prepared && active.dimension().isEmpty() && opacity() >= .999) {
            ClientPlayNetworking.send(new TravelPackets.Ready(active.id(), false));
            prepared = true;
          }
          if (!(client.gui.screen() instanceof TravelScreen))
            client.gui.setScreen(new TravelScreen());
          if (!active.dimension().isEmpty()
              && !sent
              && opacity() >= .999
              && client.level != null
              && client.player != null
              && (active.dimension().startsWith("scene:")
                  ? com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.readyForScene(
                      active.dimension().substring(6))
                  : client.level.dimension().identifier().toString().equals(active.dimension()))
              && client.level.hasChunkAt(client.player.blockPosition())
              && (active.dimension().startsWith("scene:")
                  || client.levelRenderer.isSectionCompiledAndVisible(
                      client.player.blockPosition(), 0L))) {
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
    double covered = opacity();
    int alpha = (int) Math.round(covered * 255);
    if (alpha > 0) graphics.fill(0, 0, width, height, alpha << 24);
    if (covered > .75 && active != null && !finishing) {
      int textAlpha = (int) Math.round((covered - .75) * 4 * 255);
      graphics.centeredText(
          font, active.message(), width / 2, height / 2, textAlpha << 24 | 0xeeeeee);
    }
  }
}
