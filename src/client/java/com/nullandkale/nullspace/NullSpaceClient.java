package com.nullandkale.nullspace;

import com.nullandkale.nullspace.content.machine.MachineMenu;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.dev.DevWorldCreation;
import com.nullandkale.nullspace.dev.PlaytestLauncher;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.scene.SceneVolumes;
import com.nullandkale.nullspace.gameplay.scene.TerrainService;
import com.nullandkale.nullspace.gameplay.systems.AirEffects;
import com.nullandkale.nullspace.model.space.PlanetSky;
import com.nullandkale.nullspace.model.space.SpaceDefinitionCodec;
import com.nullandkale.nullspace.render.compute.ComputeProgram;
import com.nullandkale.nullspace.render.effects.AirFlowParticles;
import com.nullandkale.nullspace.render.effects.PreviewRenderer;
import com.nullandkale.nullspace.render.entity.RepairBotRenderer;
import com.nullandkale.nullspace.render.scene.MonitorRenderer;
import com.nullandkale.nullspace.render.scene.SceneMotion;
import com.nullandkale.nullspace.render.scene.SceneViewport;
import com.nullandkale.nullspace.render.scene.SpaceSceneRenderer;
import com.nullandkale.nullspace.render.scene.TerrainClient;
import com.nullandkale.nullspace.ui.screen.ComputerClient;
import com.nullandkale.nullspace.ui.screen.MachineScreen;
import com.nullandkale.nullspace.ui.screen.ShipPilotScreen;
import com.nullandkale.nullspace.ui.screen.ShipyardScreen;
import com.nullandkale.nullspace.ui.screen.TerminalScreen;
import com.nullandkale.nullspace.ui.screen.TravelScreen;
import com.nullandkale.nullspace.ui.screen.ViewportScreen;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import net.fabricmc.api.ClientModInitializer;

public final class NullSpaceClient implements ClientModInitializer {
  private static final SceneVolumes.Cache SCENE_VOLUMES = new SceneVolumes.Cache();

  public static SceneVolumes.Cache sceneVolumes() {
    return SCENE_VOLUMES;
  }

  private static final SpaceDefinitionCodec.Cache DEFINITIONS = new SpaceDefinitionCodec.Cache();

  public static SpaceDefinitionCodec.Cache definitions() {
    return DEFINITIONS;
  }

  // Netty disconnect may precede Minecraft.disconnect(), which drops queued tasks.
  // Keep the token outside that queue and consume it on the render-thread tick.
  private static volatile net.minecraft.client.multiplayer.ClientPacketListener activeConnection;
  private static final java.util.concurrent.atomic.AtomicReference<
          net.minecraft.client.multiplayer.ClientPacketListener>
      DISCONNECTED = new java.util.concurrent.atomic.AtomicReference<>();

  private static void clearScenes() {
    com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
    SpaceSceneRenderer.close();
    SCENE_VOLUMES.clear();
    TerrainClient.clear();
    DEFINITIONS.clear();
    SceneMotion.clear();
    PreviewRenderer.clear();
    ComputerClient.clear();
    MachineScreen.clearReturn();
    TerminalScreen.clearReturn();
    AirFlowParticles.clear();
  }

  @Override
  public void onInitializeClient() {
    DevWorldCreation.initialize();
    RepairBotRenderer.register();
    net.minecraft.client.gui.screens.MenuScreens.register(MachineMenus.MENU, MachineScreen::new);
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        MachineMenus.View.TYPE,
        (packet, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().player != null
                          && context.client().player.containerMenu instanceof MachineMenu menu
                          && menu.containerId == packet.container()) {
                        menu.reading = packet.data();
                        menu.at = net.minecraft.core.BlockPos.of(packet.data().getLongOr("at", 0));
                        if (context.client().gui.screen() instanceof MachineScreen machine
                            && machine.getMenu() == menu) machine.readingChanged();
                      }
                    }));
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(
        client -> {
          if (ComputeProgram.enabled()) {
            ComputeProgram.require();
            NullSpace.LOGGER.info(
                "Space compute enabled: {}",
                com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo());
          }
        });
    SceneViewport.initialize();
    SpaceSceneRenderer.initialize();
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        SceneService.Definition.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      try {
                        DEFINITIONS.accept(payload.data());
                      } catch (IllegalArgumentException invalid) {
                        NullSpace.LOGGER.warn(
                            "Rejected space definition: {}", invalid.getMessage());
                      }
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        ShipControl.View.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen terminal
                          && terminal.shipControl(payload)) return;
                      if (payload.opening())
                        context.client().gui.setScreen(TerminalScreen.restore(payload));
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        ComputerTerminal.Open.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (payload.mode().equals("shipyard")) {
                        if (context.client().gui.screen() instanceof ShipyardScreen yard)
                          yard.update(payload);
                        else context.client().gui.setScreen(new ShipyardScreen(payload));
                        return;
                      }
                      if (context.client().gui.screen() instanceof TerminalScreen terminal)
                        terminal.update(payload);
                      else context.client().gui.setScreen(TerminalScreen.restore(payload));
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        ComputerTerminal.Refresh.TYPE,
        (packet, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen t)
                        t.refresh(packet.view());
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        ComputerTerminal.Ack.TYPE,
        (packet, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen t)
                        t.acknowledged(packet.session());
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        ComputerTerminal.Rejected.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen terminal)
                        terminal.rejected(payload);
                      else if (context.client().gui.screen() instanceof ShipyardScreen yard)
                        yard.rejected(payload);
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        AirEffects.FlowPacket.TYPE,
        (packet, context) -> context.client().execute(() -> AirFlowParticles.accept(packet)));
    AirFlowParticles.initialize();
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        TerrainService.Section.TYPE,
        (p, c) ->
            c.client()
                .execute(
                    () -> {
                      try {
                        TerrainClient.accept(p.data());
                      } catch (IllegalArgumentException invalid) {
                        NullSpace.LOGGER.warn("Rejected invalid terrain snapshot", invalid);
                      }
                    }));
    ComputerClient.initialize();
    TravelScreen.initialize();
    PreviewRenderer.initialize();
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        ComputerTerminal.Update.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen terminal)
                        terminal.reading(payload);
                      else if (context.client().gui.screen() instanceof ShipyardScreen yard)
                        yard.update(payload);
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        SceneService.Frame.TYPE,
        (received, context) ->
            context
                .client()
                .execute(
                    () -> {
                      final SceneService.Frame payload;
                      try {
                        payload =
                            new SceneService.Frame(
                                received.lease(),
                                received.revision(),
                                SCENE_VOLUMES.expand(received.scene()));
                      } catch (IllegalArgumentException invalid) {
                        NullSpace.LOGGER.warn(
                            "Rejected invalid scene volume: {}", invalid.getMessage());
                        return;
                      }
                      if (payload.scene().getBooleanOr("universe", false)) {
                        var definition = DEFINITIONS.scene();
                        if (definition == null
                            || !definition.id().equals(payload.scene().getStringOr("sceneId", ""))
                            || definition.revision()
                                != payload.scene().getLongOr("sceneRevision", -1)) return;
                        payload
                            .scene()
                            .put(
                                "catalog",
                                payload.scene().getBooleanOr("planetSky", false)
                                    ? PlanetSky.skyCatalog(
                                        DEFINITIONS.encoded(),
                                        payload.scene().getStringOr("surfaceBody", ""))
                                    : DEFINITIONS.encoded());
                        payload.scene().putLong("receivedNanos", System.nanoTime());
                      }
                      SceneMotion.track(payload.scene());
                      if (payload.scene().getBooleanOr("machinePreview", false)) {
                        if (context.client().gui.screen() instanceof MachineScreen machine)
                          machine.preview(payload);
                        return;
                      }
                      if (payload.scene().getBooleanOr("pilotView", false)) {
                        if (context.client().gui.screen() instanceof ShipPilotScreen pilot
                            && pilot.owns(payload.lease())) pilot.update(payload);
                        else if (payload.revision() == 1 && context.client().level != null)
                          context.client().gui.setScreen(new ShipPilotScreen(payload));
                        return;
                      }
                      if (payload.scene().getBooleanOr("panels", false)) {
                        MonitorRenderer.update(payload);
                        return;
                      }
                      if (payload.scene().getBooleanOr("background", false)) {
                        SpaceSceneRenderer.update(payload);
                        return;
                      }
                      if (context.client().gui.screen() instanceof ViewportScreen view) {
                        if (view.owns(payload.lease())) view.update(payload);
                        else if (payload.revision() == 1)
                          context.client().gui.setScreen(new ViewportScreen(payload));
                      } else if (payload.revision() == 1 && context.client().level != null)
                        context.client().gui.setScreen(new ViewportScreen(payload));
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> {
          if (activeConnection == null
              || activeConnection.getConnection() != handler.getConnection()) clearScenes();
          activeConnection = handler;
          DISCONNECTED.set(null);
        });
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          if (handler == activeConnection) DISCONNECTED.set(handler);
        });
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(
        client -> {
          var closed = DISCONNECTED.getAndSet(null);
          if (closed != null && closed == activeConnection) {
            clearScenes();
            activeConnection = null;
          }
        });
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
        .register(client -> clearScenes());
    PlaytestLauncher.initialize();
    NullSpace.LOGGER.info("NullSpace client initialized");
  }
}
