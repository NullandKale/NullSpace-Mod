package com.nullandkale.nullspace.core;

import java.util.*;

/** Reverse-order ownership for constructor rollback and normal shutdown. Close is idempotent. */
public final class ResourceGroup implements AutoCloseable {
  private static final java.util.concurrent.atomic.AtomicInteger ENTRIES =
      new java.util.concurrent.atomic.AtomicInteger();

  public static int liveEntries() {
    return ENTRIES.get();
  }

  private final ArrayDeque<Runnable> cleanup = new ArrayDeque<>();
  private boolean closed;

  public <T extends AutoCloseable> T own(T value) {
    defer(
        () -> {
          try {
            value.close();
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    return value;
  }

  public void defer(Runnable action) {
    if (closed) throw new IllegalStateException("Resource group is closed");
    cleanup.push(action);
    ENTRIES.incrementAndGet();
  }

  public int size() {
    return cleanup.size();
  }

  public void rollback(Throwable failure) {
    try {
      close();
    } catch (RuntimeException | Error cleanupFailure) {
      if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    Throwable failure = null;
    while (!cleanup.isEmpty())
      try {
        cleanup.pop().run();
      } catch (RuntimeException | Error e) {
        if (failure == null) failure = e;
        else if (failure != e) failure.addSuppressed(e);
      } finally {
        ENTRIES.decrementAndGet();
      }
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
  }
}
