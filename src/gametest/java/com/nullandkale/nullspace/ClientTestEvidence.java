package com.nullandkale.nullspace;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Test-only receipts. A zero exit is insufficient when requested entrypoints were filtered out. */
final class ClientTestEvidence {
  private static final Set<String> COMPLETED = new LinkedHashSet<>();

  static void passed(Class<?> test) {
    if (!COMPLETED.add(test.getName()))
      throw new AssertionError("Duplicate graphical entrypoint receipt");
    var image = new JsonObject();
    image.addProperty("version", 1);
    image.add("completed", new Gson().toJsonTree(COMPLETED));
    Path file =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getGameDir()
            .resolve("nullspace-test-evidence.json");
    try {
      Files.writeString(file, image.toString());
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
    com.nullandkale.nullspace.NullSpace.LOGGER.info("NULLSPACE_TEST_PASSED {}", test.getName());
  }

  private ClientTestEvidence() {}
}
