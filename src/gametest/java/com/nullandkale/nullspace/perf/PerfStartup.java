package com.nullandkale.nullspace.perf;

import com.nullandkale.nullspace.core.save.*;
import com.nullandkale.nullspace.gameplay.travel.TransferRecovery;

import java.nio.file.*;
import java.util.*;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;

import com.google.gson.*;

/** A fixture JVM, a seeding JVM halted without cleanup, then a fresh measured startup JVM. */
final class PerfStartup
{
    static void fixture(GameTestHelper ctx, PerfWorld world, Path output) throws java.io.IOException
    {
        var store = WorldStore.get(world.server());
        var fixture = new CompoundTag();
        fixture.putInt("requested", world.requestedCount);
        fixture.putInt("ships", world.ships.size());
        fixture.putInt("stations", world.stations.size());
        store.document("performanceFixture", fixture);
        // Both copies include all fixture operations before seeding independent completed journals.
        store.document("performanceFixtureGeneration", StringTag.valueOf("one"));
        world.server().saveEverything(true, true, true);
        store.document("performanceFixtureGeneration", StringTag.valueOf("two"));
        world.server().saveEverything(true, true, true);
        var json = new JsonObject();
        json.addProperty("fixtureVersion", 2);
        json.addProperty("ready", true);
        Files.writeString(output, new Gson().toJson(json));
        ctx.succeed();
    }

    static void run(GameTestHelper ctx, String scenario, Path output) throws java.io.IOException
    {
        var server = ctx.getLevel().getServer();
        var store = WorldStore.get(server);
        int count = Integer.getInteger("nullspace.perf.journals", 0);
        if (scenario.equals("startup-seed"))
        {
            Journals.prune(store.directory, store.saveNumber());
            long sequence = Journals.nextSequence(store.directory);
            for (int i = 0; i < count; i++)
            {
                var journal = new CompoundTag();
                journal.putInt("version", Journals.VERSION);
                journal.putString("id", UUID.nameUUIDFromBytes(("perf-startup-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                journal.putLong("sequence", sequence + i);
                journal.putString("kind", "RECORD");
                journal.putString("operation", "startup-fixture");
                journal.putString("stage", "COMMITTED");
                journal.putLong("saveNumber", store.saveNumber());
                journal.put("records", new ListTag());
                var file = Journals.file(store.directory, journal);
                WorldStore.write(file, journal);
                Journals.finish(file, store.saveNumber());
            }
            // A graceful final save could prune the fixture. Halt only this disposable test JVM.
            Runtime.getRuntime().halt(86);
        }
        long present;
        try (var files = Files.list(Journals.directory(store.directory)))
        {
            present = files.filter(p -> p.getFileName().toString().endsWith(".nbt")).count();
        }
        ctx.assertTrue(present == count, "The fresh startup read the requested journal population: " + present + " != " + count);
        var root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("fixtureVersion", 2);
        root.addProperty("scenario", "startup");
        root.addProperty("ships", store.snapshot().getCompoundOrEmpty("performanceFixture").getIntOr("requested", 0));
        root.addProperty("journalsAtStart", count);
        double seconds = (System.currentTimeMillis() - Long.getLong("nullspace.perf.processStartedMillis", System.currentTimeMillis())) / 1000.0;
        root.addProperty("readySeconds", seconds);
        root.addProperty("recoveryMs", TransferRecovery.recoveryNanos(server) / 1e6);
        root.addProperty("javaVersion", System.getProperty("java.version"));
        root.addProperty("shipRecords", store.ships().size());
        var step = new JsonObject();
        step.addProperty("step", "startup " + count + " journals");
        step.addProperty("seconds", seconds);
        step.addProperty("operations", 1);
        step.addProperty("operationsPerSecond", 1 / Math.max(.000001, seconds));
        var times = new JsonObject();
        times.addProperty("count", 1);
        times.addProperty("median", seconds * 1000);
        times.addProperty("p95", seconds * 1000);
        times.addProperty("max", seconds * 1000);
        step.add("operationMs", times);
        step.add("tickMs", new JsonObject());
        step.addProperty("ticksOver50ms", 0);
        step.addProperty("refused", 0);
        var steps = new JsonArray();
        steps.add(step);
        root.add("steps", steps);
        var world = new JsonObject();
        world.addProperty("journals", present);
        world.addProperty("storeBytesOnDisk", Files.size(store.storeFile()));
        root.add("world", world);
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        ctx.succeed();
    }

    private PerfStartup()
    {
    }
}
