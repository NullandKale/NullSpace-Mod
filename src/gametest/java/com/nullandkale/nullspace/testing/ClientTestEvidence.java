package com.nullandkale.nullspace.testing;

import com.nullandkale.nullspace.NullSpace;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import com.google.gson.*;

/** Test-only receipts. A zero exit is insufficient when requested entrypoints were filtered out. */
public final class ClientTestEvidence
{
    private static final Set<String> COMPLETED = new LinkedHashSet<>();
    private static final Map<String, Long> STARTED = new HashMap<>();
    private static final Map<String, String> START_TIMES = new HashMap<>();
    private static final JsonObject TIMINGS = new JsonObject();
    private static String running = "";

    public static void started(Class<?> test)
    {
        String name = test.getName();
        if (STARTED.putIfAbsent(name, System.nanoTime()) != null || COMPLETED.contains(name))
            throw new AssertionError("Duplicate graphical entrypoint start: " + name);
        START_TIMES.put(name, Instant.now().toString());
        running = name;
        save();
        NullSpace.LOGGER.info("NULLSPACE_TEST_STARTED {}", name);
    }

    public static void passed(Class<?> test)
    {
        if (!COMPLETED.add(test.getName()))
            throw new AssertionError("Duplicate graphical entrypoint receipt");
        var start = STARTED.get(test.getName());
        if (start != null)
        {
            var timing = new JsonObject();
            timing.addProperty("startedUtc", START_TIMES.get(test.getName()));
            timing.addProperty("finishedUtc", Instant.now().toString());
            timing.addProperty("milliseconds", Math.max(1, (System.nanoTime() - start) / 1_000_000));
            TIMINGS.add(test.getName(), timing);
        }
        running = "";
        save();
        NullSpace.LOGGER.info("NULLSPACE_TEST_PASSED {}", test.getName());
    }

    private static void save()
    {
        var image = new JsonObject();
        image.addProperty("version", 2);
        image.add("completed", new Gson().toJsonTree(COMPLETED));
        image.add("timings", TIMINGS.deepCopy());
        image.addProperty("running", running);
        Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("nullspace-test-evidence.json");
        try
        {
            publishReceipt(file, image.toString());
        }
        catch (java.io.IOException error)
        {
            throw new java.io.UncheckedIOException(error);
        }
    }

    public static void publishReceipt(Path file, String contents) throws java.io.IOException
    {
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, contents);
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (true)
        {
            try
            {
                try
                {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                catch (AtomicMoveNotSupportedException unsupported)
                {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
            catch (AccessDeniedException locked)
            {
                // Windows scanners/readers can briefly hold a handle without FILE_SHARE_DELETE.
                // Keep the last complete receipt and fail if the lock persists.
                if (System.nanoTime() >= deadline)
                    throw locked;
                try
                {
                    Thread.sleep(50);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Interrupted while publishing test evidence", interrupted);
                }
            }
        }
    }

    private ClientTestEvidence()
    {
    }
}
