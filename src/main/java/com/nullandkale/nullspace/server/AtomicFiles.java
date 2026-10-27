package com.nullandkale.nullspace.server;

import java.io.IOException;
import java.nio.file.*;

/** Brief Windows sharing violations must not turn an additive gallery into a partial build. */
public final class AtomicFiles {
  public static void promote(Path temporary, Path destination) throws IOException {
    for (int attempt = 0; ; attempt++)
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        return;
      } catch (AccessDeniedException sharing) {
        if (attempt >= 5) throw sharing;
        try {
          Thread.sleep(10);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while promoting durable state", interrupted);
        }
      }
  }

  private AtomicFiles() {}
}
