package com.nullandkale.nullspace.perf;

import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import com.google.gson.*;

/**
 * Drives one scenario through the three phases of the performance test: a one-minute warm-up of ships moving
 * slowly, a stepped ramp of the scenario's operation, then a flat-out step. Timing is evidence and never a
 * pass/fail threshold.
 */
final class PerfRun
{
    static final int WARMUP_TICKS = Integer.getInteger("nullspace.perf.warmupSeconds", 60) * 20, STEP_TICKS = Integer.getInteger("nullspace.perf.stepSeconds", 30) * 20, FLAT_TICKS = Integer.getInteger("nullspace.perf.flatSeconds", 30) * 20, IDLE_TICKS = Integer.getInteger("nullspace.perf.idleSeconds", 120) * 20;
    static final String PENDING = "operation pending";
    static final double[] RAMP = {0.1, 0.25, 0.5, 1, 2};
    private static final Identifier LAST = Identifier.fromNamespaceAndPath("nullspace-test", "perf_last");

    /** One operation of a scenario. Returns an empty string, or why it did not run. */
    interface Operation
    {
        String run(int count);
    }

    private record Step(String name, double rate, int ticks)
    {}

    private final MinecraftServer server;
    private final PerfWorld world;
    private final String scenario;
    private final Operation operation;
    private final int flatOperations;
    private final Path output;
    private final BooleanSupplier after;
    private final List<Step> steps = new ArrayList<>();
    private final JsonArray results = new JsonArray(), events = new JsonArray(), initial = new JsonArray();
    private final Map<String, Long> stages = new LinkedHashMap<>();

    private int tick, stepIndex = -1, stepTick, operations, attempts, ordinal, background;
    private double credit;
    private long tickStart, lastStage, operationStart, stepStart, lastSchedule, backgroundAt, bytesWritten;
    private boolean pending, measuring;
    private long pendingStarted;
    private final Map<Path, Long> writeBytes = new HashMap<>();
    private java.util.function.Consumer<WorldStore.WriteProgress> previousWriter;
    private java.util.function.Consumer<String> previousStages;
    private final List<Double> tickMs = new ArrayList<>(), operationMs = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();
    private boolean warming = true, finished;
    private long runStart;

    PerfRun(MinecraftServer server, PerfWorld world, String scenario, Operation operation, double[] ramp, int flatOperations, Path output, BooleanSupplier after)
    {
        this.server = server;
        this.world = world;
        this.scenario = scenario;
        this.operation = operation;
        this.flatOperations = flatOperations;
        this.output = output;
        this.after = after;
        if (operation == null)
            steps.add(new Step("idle", 0, IDLE_TICKS));
        else
        {
            for (double rate : ramp)
                steps.add(new Step("ramp " + rate + "/s", rate, STEP_TICKS));
            steps.add(new Step("flat out", -1, FLAT_TICKS));
        }
    }

    boolean finished()
    {
        return finished;
    }

    void start()
    {
        runStart = stepStart = lastSchedule = System.nanoTime();
        backgroundAt = runStart + 15_000_000_000L;
        previousWriter = WorldStore.writeObserver;
        previousStages = TravelService.failureInjector;
        WorldStore.writeObserver = progress ->
        {
            previousWriter.accept(progress);
            long previous = writeBytes.getOrDefault(progress.file(), 0L);
            if (!warming)
                bytesWritten += progress.bytes() > previous ? progress.bytes() - previous : progress.bytes();
            writeBytes.put(progress.file(), progress.bytes());
        };
        for (var ship : world.ships)
        {
            var entry = new JsonObject();
            var record = world.record(ship);
            var at = WorldStore.pos(record, "base");
            entry.addProperty("ship", ship.index);
            entry.addProperty("padX", ship.base.getX());
            entry.addProperty("padZ", ship.base.getZ());
            entry.addProperty("inSpace", world.inSpace(ship));
            entry.addProperty("x", at.getX());
            entry.addProperty("z", at.getZ());
            initial.add(entry);
        }
        world.stageClock = nanos -> lastStage = nanos;
        world.listener = move ->
        {
            var event = new JsonObject();
            event.addProperty("tick", tick);
            event.addProperty("phase", warming ? "warm-up" : steps.get(stepIndex).name());
            event.addProperty("ship", move.ship());
            event.addProperty("kind", move.launch() ? "launch" : "land");
            event.addProperty("ok", move.completed());
            event.addProperty("ms", move.ms());
            event.addProperty("dimension", move.dimension());
            event.addProperty("x", move.baseX());
            event.addProperty("z", move.baseZ());
            events.add(event);
        };
        TravelService.failureInjector = stage ->
        {
            long now = System.nanoTime();
            previousStages.accept(stage);
            if (measuring || pending)
                stages.merge(stage, now - lastStage, Long::sum);
            lastStage = now;
        };
        ServerTickEvents.START_SERVER_TICK.register(this::begin);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, LAST);
        ServerTickEvents.END_SERVER_TICK.register(LAST, this::end);
    }

    private void begin(MinecraftServer unused)
    {
        if (finished)
            return;
        tickStart = System.nanoTime();
        tick++;
        drift();
        long now = System.nanoTime();
        if (now >= backgroundAt && !world.movers().isEmpty() && !pending)
        {
            move(world.movers().get(background++ % world.movers().size()));
            backgroundAt = now + 15_000_000_000L;
        }
        if (warming)
            return;
        var step = steps.get(stepIndex);
        if (step.rate() > 0)
            credit += step.rate() * (now - lastSchedule) / 1e9;
        lastSchedule = now;
        if (pending)
        {
            poll();
            return;
        }
        if (operation != null && (step.rate() < 0 && attempts < flatOperations || step.rate() > 0 && credit >= 1))
        {
            if (step.rate() > 0)
                credit--;
            run();
        }
    }

    private void end(MinecraftServer unused)
    {
        if (finished)
            return;
        double ms = (System.nanoTime() - tickStart) / 1e6;
        if (warming)
        {
            if (System.nanoTime() - runStart >= WARMUP_TICKS * 50_000_000L)
            {
                warming = false;
                stepIndex = 0;
                stepTick = 0;
                tickMs.clear();
                operationMs.clear();
                refusals.clear();
                stages.clear();
                bytesWritten = 0;
                stepStart = lastSchedule = System.nanoTime();
            }
            pace(ms);
            return;
        }
        tickMs.add(ms);
        stepTick++;
        boolean timedOut = System.nanoTime() - stepStart >= steps.get(stepIndex).ticks() * 50_000_000L;
        boolean capped = steps.get(stepIndex).rate() < 0 && attempts >= flatOperations;
        if ((!timedOut && !capped) || pending)
        {
            pace(ms);
            return;
        }
        record(steps.get(stepIndex));
        stepTick = 0;
        operations = attempts = 0;
        credit = 0;
        tickMs.clear();
        operationMs.clear();
        refusals.clear();
        stages.clear();
        bytesWritten = 0;
        stepStart = lastSchedule = System.nanoTime();
        if (++stepIndex >= steps.size())
            finish();
        pace(ms);
    }

    /** GameTestServer overrides vanilla pacing; the opt-in benchmark restores the normal 50 ms tick budget. */
    private static void pace(double elapsedMs)
    {
        long wait = 50_000_000L - (long) (elapsedMs * 1e6);
        if (wait > 0)
            java.util.concurrent.locks.LockSupport.parkNanos(wait);
    }

    /** Ships in space drift a little each tick, as piloted ships do, so the store changes like a live one. */
    private void drift()
    {
        var store = WorldStore.get(server);
        var ships = world.ships;
        for (int i = tick % 20; i < ships.size(); i += 20)
        {
            var ship = ships.get(i);
            var record = world.record(ship);
            if (record == null || !world.inSpace(ship))
                continue;
            store.repository().mutate(new ShipRepository.ShipId(ship.id), ShipRepository.Mutation.SIMULATION, candidate -> candidate.putDouble("simX", candidate.getDoubleOr("simX", 0) + 0.01));
        }
    }

    private void move(PerfWorld.Ship ship)
    {
        String problem = world.move(ship);
        if (!problem.isEmpty())
            refusals.add(problem);
    }

    private void run()
    {
        attempts++;
        operationStart = pendingStarted = lastStage = System.nanoTime();
        pending = true;
        poll();
    }

    private void poll()
    {
        measuring = true;
        String problem;
        try
        {
            problem = operation.run(ordinal);
        }
        finally
        {
            measuring = false;
        }
        if (problem.equals(PENDING))
        {
            if (System.nanoTime() - pendingStarted > 180_000_000_000L)
                throw new IllegalStateException("A performance operation did not complete within three minutes");
            return;
        }
        pending = false;
        ordinal++;
        if (problem.isEmpty())
        {
            operationMs.add((System.nanoTime() - operationStart) / 1e6);
            operations++;
        }
        else
            refusals.add(problem);
    }

    private void record(Step step)
    {
        var json = new JsonObject();
        json.addProperty("scenario", scenario);
        json.addProperty("ships", world.requestedCount);
        json.addProperty("step", step.name());
        json.addProperty("rate", step.rate());
        double seconds = (System.nanoTime() - stepStart) / 1e9;
        json.addProperty("seconds", seconds);
        json.addProperty("simulatedSeconds", stepTick / 20.0);
        json.addProperty("attempts", attempts);
        long backlog = step.rate() > 0 ? Math.max(0, (long) (step.rate() * seconds) - attempts) : 0;
        json.addProperty("backlog", backlog);
        json.addProperty("fellBehind", backlog > 0);
        json.addProperty("bytesWritten", bytesWritten);
        json.addProperty("operations", operations);
        json.addProperty("operationsPerSecond", operations / seconds);
        stats(json, "operationMs", operationMs);
        stats(json, "tickMs", tickMs);
        json.addProperty("ticksOver50ms", tickMs.stream().filter(v -> v > 50).count());
        json.addProperty("refused", refusals.size());
        if (!refusals.isEmpty())
            json.addProperty("firstRefusal", refusals.getFirst());
        var stageJson = new JsonObject();
        stages.forEach((name, nanos) -> stageJson.addProperty(name, nanos / 1e6));
        json.add("stageMsTotal", stageJson);
        results.add(json);
        System.getLogger(PerfRun.class.getName()).log(System.Logger.Level.INFO, scenario + " " + step.name() + ": " + operations + " completed, " + refusals.size() + " refused, longest tick " + json.getAsJsonObject("tickMs").get("max") + " ms");
    }

    private static void stats(JsonObject json, String name, List<Double> values)
    {
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        var stats = new JsonObject();
        stats.addProperty("count", sorted.size());
        if (!sorted.isEmpty())
        {
            stats.addProperty("median", sorted.get(sorted.size() / 2));
            stats.addProperty("p95", sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95))));
            stats.addProperty("max", sorted.getLast());
        }
        json.add(name, stats);
    }

    private void finish()
    {
        finished = true;
        var root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("fixtureVersion", 2);
        root.addProperty("accounting", "completed operations / monotonic elapsed seconds; paced 50 ms ticks");
        root.addProperty("operationUnit", scenario.equals("move-burst") ? "four-ship burst" : "operation");
        root.addProperty("scenario", scenario);
        root.addProperty("ships", world.requestedCount);
        root.addProperty("seconds", (System.nanoTime() - runStart) / 1e9);
        root.add("steps", results);
        root.add("fixture", initial);
        root.add("moves", events);
        root.add("world", worldStatistics());
        WorldStore.writeObserver = previousWriter;
        TravelService.failureInjector = previousStages;
        try
        {
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        }
        catch (java.io.IOException error)
        {
            throw new java.io.UncheckedIOException(error);
        }
        after.getAsBoolean();
    }

    JsonObject worldStatistics()
    {
        var json = new JsonObject();
        var store = WorldStore.get(server);
        var file = store.storeFile();
        try
        {
            json.addProperty("storeBytesOnDisk", Files.exists(file) ? Files.size(file) : -1);
            var raw = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(store.snapshot(), new java.io.DataOutputStream(raw));
            json.addProperty("storeBytesRaw", raw.size());
            json.addProperty("rawBytesPerShip", store.ships().isEmpty() ? 0 : raw.size() / store.ships().size());
            try (var journals = Files.list(store.directory.resolve("transfers")))
            {
                json.addProperty("journals", journals.count());
            }
        }
        catch (java.io.IOException error)
        {
            json.addProperty("error", error.toString());
        }
        json.addProperty("shipRecords", store.ships().size());
        json.addProperty("fixtureShips", world.ships.size());
        json.addProperty("fixtureStations", world.stations.size());
        json.addProperty("mockCrew", world.crew.size());
        json.addProperty("cargoAnimals", world.ships.size() * 2);
        int chunks = 0;
        for (var level : server.getAllLevels())
            chunks += level.getChunkSource().getLoadedChunksCount();
        json.addProperty("loadedChunks", chunks);
        json.addProperty("heapUsedMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576);
        json.addProperty("javaVersion", System.getProperty("java.version"));
        json.addProperty("processors", Runtime.getRuntime().availableProcessors());
        return json;
    }
}
