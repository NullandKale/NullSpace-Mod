package com.nullandkale.nullspace.client;

import com.nullandkale.nullspace.NullSpace;
import net.fabricmc.api.ClientModInitializer;

public final class NullSpaceClient implements ClientModInitializer {
  private static final com.nullandkale.nullspace.scene.SceneVolumes.Cache SCENE_VOLUMES =
      new com.nullandkale.nullspace.scene.SceneVolumes.Cache();

  public static com.nullandkale.nullspace.scene.SceneVolumes.Cache sceneVolumes() {
    return SCENE_VOLUMES;
  }

  private static final com.nullandkale.nullspace.scene.SpaceDefinitionCodec.Cache DEFINITIONS =
      new com.nullandkale.nullspace.scene.SpaceDefinitionCodec.Cache();

  public static com.nullandkale.nullspace.scene.SpaceDefinitionCodec.Cache definitions() {
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
    com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.close();
    SCENE_VOLUMES.clear();
    com.nullandkale.nullspace.client.scene.TerrainClient.clear();
    DEFINITIONS.clear();
    com.nullandkale.nullspace.client.scene.SceneMotion.clear();
    PreviewRenderer.clear();
    ComputerClient.clear();
    AirFlowParticles.clear();
  }

  @Override
  public void onInitializeClient() {
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(
        client -> {
          if (com.nullandkale.nullspace.client.scene.compute.ComputeProgram.enabled()) {
            com.nullandkale.nullspace.client.scene.compute.ComputeProgram.require();
            NullSpace.LOGGER.info(
                "Space compute enabled: {}",
                com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo());
          }
        });
    com.nullandkale.nullspace.client.scene.SceneViewport.initialize();
    com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.initialize();
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        com.nullandkale.nullspace.scene.SceneService.Definition.TYPE,
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
        com.nullandkale.nullspace.terminal.ComputerTerminal.Open.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen terminal)
                        terminal.update(payload);
                      else context.client().gui.setScreen(new TerminalScreen(payload));
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        com.nullandkale.nullspace.terminal.ComputerTerminal.Rejected.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen terminal)
                        terminal.rejected(payload);
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        com.nullandkale.nullspace.systems.AirEffects.FlowPacket.TYPE,
        (packet, context) -> context.client().execute(() -> AirFlowParticles.accept(packet)));
    AirFlowParticles.initialize();
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        com.nullandkale.nullspace.scene.TerrainService.Section.TYPE,
        (p, c) ->
            c.client()
                .execute(
                    () -> {
                      try {
                        com.nullandkale.nullspace.client.scene.TerrainClient.accept(p.data());
                      } catch (IllegalArgumentException invalid) {
                        NullSpace.LOGGER.warn("Rejected invalid terrain snapshot", invalid);
                      }
                    }));
    ComputerClient.initialize();
    TravelScreen.initialize();
    PreviewRenderer.initialize();
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        com.nullandkale.nullspace.terminal.ComputerTerminal.Update.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(
                    () -> {
                      if (context.client().gui.screen() instanceof TerminalScreen terminal)
                        terminal.reading(payload);
                    }));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
        com.nullandkale.nullspace.scene.SceneService.Frame.TYPE,
        (received, context) ->
            context
                .client()
                .execute(
                    () -> {
                      final com.nullandkale.nullspace.scene.SceneService.Frame payload;
                      try {
                        payload =
                            new com.nullandkale.nullspace.scene.SceneService.Frame(
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
                                    ? com.nullandkale.nullspace.scene.PlanetSky.skyCatalog(
                                        DEFINITIONS.encoded(),
                                        payload.scene().getStringOr("surfaceBody", ""))
                                    : DEFINITIONS.encoded());
                        payload.scene().putLong("receivedNanos", System.nanoTime());
                      }
                      com.nullandkale.nullspace.client.scene.SceneMotion.track(payload.scene());
                      if (payload.scene().getBooleanOr("pilotView", false)) {
                        if (context.client().gui.screen()
                                instanceof
                                com.nullandkale.nullspace.client.scene.ShipPilotScreen pilot
                            && pilot.owns(payload.lease())) pilot.update(payload);
                        else if (payload.revision() == 1 && context.client().level != null)
                          context
                              .client()
                              .gui
                              .setScreen(
                                  new com.nullandkale.nullspace.client.scene.ShipPilotScreen(
                                      payload));
                        return;
                      }
                      if (payload.scene().getBooleanOr("panels", false)) {
                        com.nullandkale.nullspace.client.scene.MonitorRenderer.update(payload);
                        return;
                      }
                      if (payload.scene().getBooleanOr("background", false)) {
                        com.nullandkale.nullspace.client.scene.SpaceSceneRenderer.update(payload);
                        return;
                      }
                      if (context.client().gui.screen()
                          instanceof com.nullandkale.nullspace.client.scene.ViewportScreen view) {
                        if (view.owns(payload.lease())) view.update(payload);
                        else if (payload.revision() == 1)
                          context
                              .client()
                              .gui
                              .setScreen(
                                  new com.nullandkale.nullspace.client.scene.ViewportScreen(
                                      payload));
                      } else if (payload.revision() == 1 && context.client().level != null)
                        context
                            .client()
                            .gui
                            .setScreen(
                                new com.nullandkale.nullspace.client.scene.ViewportScreen(payload));
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
