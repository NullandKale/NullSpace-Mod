package com.nullandkale.nullspace.core;

import java.util.*;
import java.util.function.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.server.MinecraftServer;

/**
 * Explicit server lifetime and deterministic simulation phases; no cross-server singleton state.
 */
public final class ServerContext {
  public static final class Key<T> {}

  public enum Phase {
    SESSIONS,
    COMPUTERS,
    TRAVEL,
    SYSTEMS,
    SCENES,
    TERRAIN,
    COMBAT,
    NAVIGATION,
    SURVIVAL,
    FIXTURES,
    SAVE
  }

  private static final Map<MinecraftServer, ServerContext> ACTIVE = new IdentityHashMap<>();
  private static final EnumMap<Phase, List<Consumer<MinecraftServer>>> TICKS =
      new EnumMap<>(Phase.class);
  private final Map<Key<?>, Object> services = new IdentityHashMap<>();

  public static ServerContext get(MinecraftServer server) {
    return ACTIVE.computeIfAbsent(server, s -> new ServerContext());
  }

  public static boolean isActive(MinecraftServer server) {
    return ACTIVE.containsKey(server);
  }

  @SuppressWarnings("unchecked")
  public <T> T service(Key<T> key, Supplier<T> factory) {
    return (T) services.computeIfAbsent(key, k -> factory.get());
  }

  @SuppressWarnings("unchecked")
  public <T> T existing(Key<T> key) {
    return (T) services.get(key);
  }

  @SuppressWarnings("unchecked")
  public <T> T remove(Key<T> key) {
    return (T) services.remove(key);
  }

  public <T> void put(Key<T> key, T value) {
    services.put(key, value);
  }

  public static void tick(Phase phase, Consumer<MinecraftServer> task) {
    TICKS.computeIfAbsent(phase, p -> new ArrayList<>()).add(task);
  }

  public static void initialize() {
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          for (var phase : Phase.values())
            for (var task : TICKS.getOrDefault(phase, List.of())) task.accept(server);
        });
    // Registered after service stop hooks, so their final flush still has its owning context.
    ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.remove(server));
  }

  private ServerContext() {}
}
