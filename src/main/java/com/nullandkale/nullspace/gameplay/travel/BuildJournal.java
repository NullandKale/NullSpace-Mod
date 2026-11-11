package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.Journals;
import com.nullandkale.nullspace.core.save.StoreRecords;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueOutput;

/** A build owns only its footprint and the records changed by its synchronous steps. */
public final class BuildJournal
{
    private static final ServerContext.Key<Set<Path>> ACTIVE = new ServerContext.Key<>();
    private static final ServerContext.Key<Job> CURRENT = new ServerContext.Key<>();

    static boolean running(MinecraftServer server, Path file)
    {
        var active = ServerContext.get(server).existing(ACTIVE);
        return active != null && active.contains(file);
    }

    public static Job begin(ServerLevel level, String operation, Collection<String> ships, HullScan.Bounds region)
    {
        return begin(level, operation, ships, region, null);
    }

    /** Sparse footprints restore only listed positions, including their original air. */
    public static Job begin(ServerLevel level, String operation, Collection<String> ships, Collection<BlockPos> positions)
    {
        if (positions.isEmpty())
            throw new IllegalArgumentException("A build needs at least one position");
        var min = new BlockPos(positions.stream().mapToInt(BlockPos::getX).min().orElseThrow(), positions.stream().mapToInt(BlockPos::getY).min().orElseThrow(), positions.stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        var max = new BlockPos(positions.stream().mapToInt(BlockPos::getX).max().orElseThrow(), positions.stream().mapToInt(BlockPos::getY).max().orElseThrow(), positions.stream().mapToInt(BlockPos::getZ).max().orElseThrow());
        return begin(level, operation, ships, new HullScan.Bounds(min, max), new HashSet<>(positions));
    }

    private static Job begin(ServerLevel level, String operation, Collection<String> ships, HullScan.Bounds region, Set<BlockPos> positions)
    {
        var store = WorldStore.get(level.getServer());
        if (TransferLocks.areaBusy(level, region))
            throw new IllegalArgumentException("A journal or travel operation reserves this build area");
        try
        {
            var journal = Journals.create(store.directory, Journals.Kind.BUILD, operation, ships);
            var min = region.min();
            var max = region.max();
            var template = new StructureTemplate();
            template.fillFromWorld(level, min, new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1), false, List.of());
            var saved = template.save(new CompoundTag());
            if (positions != null)
                saved.getListOrEmpty("blocks").removeIf(entry ->
                {
                    var coords = ((CompoundTag) entry).getListOrEmpty("pos");
                    return !positions.contains(min.offset(coords.getIntOr(0, 0), coords.getIntOr(1, 0), coords.getIntOr(2, 0)));
                });
            String dimension = level.dimension().identifier().toString();
            journal.putString("source", dimension);
            journal.putString("target", dimension);
            journal.putLong("min", min.asLong());
            journal.putLong("max", max.asLong());
            journal.putLong("delta", BlockPos.ZERO.asLong());
            journal.put("template", saved);
            journal.put("ticks", JournalTicks.capture(level, TransferReplay.positions(saved, min)));
            journal.put("records", new ListTag());
            var job = new Job(level, Journals.file(store.directory, journal), journal);
            TransferRecovery.checkpoint(job.file, journal, "PREPARED");
            ServerContext.get(level.getServer()).service(ACTIVE, HashSet::new).add(job.file);
            job.lock();
            return job;
        }
        catch (java.io.IOException error)
        {
            throw new UncheckedIOException(error);
        }
    }

    public static Job resume(ServerLevel level, String fileName)
    {
        if (!Path.of(fileName).getFileName().toString().equals(fileName))
            throw new IllegalArgumentException("Invalid build journal name");
        var file = Journals.directory(WorldStore.get(level.getServer()).directory).resolve(fileName);
        if (!running(level.getServer(), file))
            throw new IllegalStateException("Build recovery must finish before construction resumes");
        try
        {
            return new Job(level, file, WorldStore.read(file));
        }
        catch (java.io.IOException error)
        {
            throw new UncheckedIOException(error);
        }
    }

    public static <T> T run(ServerLevel level, String operation, Collection<String> ships, HullScan.Bounds region, Supplier<T> build)
    {
        var parent = ServerContext.get(level.getServer()).existing(CURRENT);
        if (parent != null)
        {
            parent.include(level, BlockPos.betweenClosedStream(region.min(), region.max()).map(BlockPos::immutable).toList());
            return parent.step(build);
        }
        var job = begin(level, operation, ships, region);
        T result = job.step(build);
        job.commit();
        return result;
    }

    public static <T> T run(ServerLevel level, String operation, Collection<String> ships, Collection<BlockPos> positions, Supplier<T> build)
    {
        return runRegions(level, operation, ships, List.of(positions), build);
    }

    public static <T> T runRegions(ServerLevel level, String operation, Collection<String> ships, List<? extends Collection<BlockPos>> regions, Supplier<T> edit)
    {
        var parent = ServerContext.get(level.getServer()).existing(CURRENT);
        if (parent != null)
        {
            for (var region : regions)
                parent.include(level, region);
            return parent.step(edit);
        }
        var job = begin(level, operation, ships, regions.getFirst());
        try
        {
            for (int i = 1; i < regions.size(); i++)
                job.include(level, regions.get(i));
            T result = job.step(edit);
            job.commit();
            return result;
        }
        catch (RuntimeException | Error failure)
        {
            job.abort();
            throw failure;
        }
    }

    /** A capture scope lasts one step, so unrelated work between construction ticks is never rolled back. */
    public static final class Job
    {
        private final MinecraftServer server;
        private final WorldStore store;
        private final Path file;
        private final CompoundTag journal;

        private Job(ServerLevel level, Path file, CompoundTag journal)
        {
            server = level.getServer();
            store = WorldStore.get(server);
            this.file = file;
            this.journal = journal;
        }

        public String fileName()
        {
            return file.getFileName().toString();
        }

        public void annotate(String key, Tag value)
        {
            journal.put(key, value.copy());
            WorldStore.write(file, journal);
        }

        /** Saves actual entities before a rescue or removal changes their authority or creates replacements. */
        public void captureEntityChanges(Collection<UUID> affected)
        {
            var ids = new LinkedHashSet<>(affected);
            var saved = new ListTag();
            var regions = new ArrayList<CompoundTag>();
            regions.add(journal);
            journal.getListOrEmpty("regions").forEach(t -> regions.add((CompoundTag) t));
            var captured = new HashSet<UUID>();
            for (var region : regions)
            {
                var level = WorldStore.level(server, region.getStringOr("source", ""));
                var bounds = new HullScan.Bounds(WorldStore.pos(region, "min"), WorldStore.pos(region, "max"));
                HullEntities.requireLoaded(level, bounds);
                for (var entity : HullEntities.inBounds(level, bounds))
                {
                    if (entity instanceof Player || !ids.contains(entity.getUUID()) || !captured.add(entity.getUUID()) || !entity.getType().canSerialize())
                        continue;
                    if (entity.getVehicle() != null && ids.contains(entity.getVehicle().getUUID()))
                        continue;
                    var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                    if (!entity.save(output))
                        throw new IllegalArgumentException("A passenger cannot be saved for recovery");
                    var entry = new CompoundTag();
                    entry.putString("dimension", level.dimension().identifier().toString());
                    entry.put("data", output.buildResult());
                    saved.add(entry);
                }
            }
            var changes = new CompoundTag();
            var affectedIds = new ListTag();
            ids.forEach(id -> affectedIds.add(StringTag.valueOf(id.toString())));
            changes.put("ids", affectedIds);
            changes.put("before", saved);
            journal.put("entityChanges", changes);
            WorldStore.write(file, journal);
        }

        /** Adds a second, separately captured footprint without scanning the empty distance between storage slots. */
        public void include(ServerLevel level, Collection<BlockPos> positions)
        {
            var unique = new HashSet<>(positions);
            var existing = new ArrayList<CompoundTag>();
            existing.add(journal);
            journal.getListOrEmpty("regions").forEach(t -> existing.add((CompoundTag) t));
            for (var region : existing)
                if (region.getStringOr("source", "").equals(level.dimension().identifier().toString()))
                    unique.removeAll(TransferReplay.positions(region.getCompoundOrEmpty("template"), WorldStore.pos(region, "min")));
            if (unique.isEmpty())
                return;
            var min = new BlockPos(unique.stream().mapToInt(BlockPos::getX).min().orElseThrow(), unique.stream().mapToInt(BlockPos::getY).min().orElseThrow(), unique.stream().mapToInt(BlockPos::getZ).min().orElseThrow());
            var max = new BlockPos(unique.stream().mapToInt(BlockPos::getX).max().orElseThrow(), unique.stream().mapToInt(BlockPos::getY).max().orElseThrow(), unique.stream().mapToInt(BlockPos::getZ).max().orElseThrow());
            UnresolvedJournals.remove(server, journal.getStringOr("id", ""));
            try
            {
                if (TransferLocks.areaBusy(level, new HullScan.Bounds(min, max)))
                    throw new IllegalArgumentException("A journal or travel operation reserves this build area");
                var template = new StructureTemplate();
                template.fillFromWorld(level, min, new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1), false, List.of());
                var saved = template.save(new CompoundTag());
                var captured = unique;
                saved.getListOrEmpty("blocks").removeIf(t ->
                {
                    var coords = ((CompoundTag) t).getListOrEmpty("pos");
                    return !captured.contains(min.offset(coords.getIntOr(0, 0), coords.getIntOr(1, 0), coords.getIntOr(2, 0)));
                });
                var region = new CompoundTag();
                region.putString("source", level.dimension().identifier().toString());
                region.putLong("min", min.asLong());
                region.putLong("max", max.asLong());
                region.put("template", saved);
                region.put("ticks", JournalTicks.capture(level, TransferReplay.positions(saved, min)));
                if (!journal.contains("regions"))
                    journal.put("regions", new ListTag());
                journal.getListOrEmpty("regions").add(region);
                WorldStore.write(file, journal);
            }
            finally
            {
                lock();
            }
        }

        private void lock()
        {
            UnresolvedJournals.put(server, UnresolvedJournals.describe(journal, UnresolvedJournals.State.BUILDING, "construction in progress", server.getTickCount(), file));
        }

        private record Address(String top, StoreRecords.Kind kind, String key)
        {
            Address(StoreRecords.Entry entry)
            {
                this(entry.top(), entry.kind(), entry.key());
            }

            Tag value(CompoundTag root)
            {
                return switch (kind)
                {
                    case VALUE -> root.get(top);
                    case CHILD -> root.getCompoundOrEmpty(top).get(key);
                    case KEYED -> root.getListOrEmpty(top).stream().filter(t -> t instanceof CompoundTag c && c.getStringOr("id", "").equals(key)).findFirst().orElse(null);
                };
            }
        }

        private void capture(CompoundTag before, CompoundTag after, Set<Address> touched)
        {
            var entries = new ArrayList<>(StoreRecords.decode(journal.getListOrEmpty("records")));
            for (var changed : StoreRecords.diff(before, after))
            {
                touched.add(new Address(changed));
                int previous = -1;
                for (int i = 0; i < entries.size(); i++)
                {
                    var entry = entries.get(i);
                    if (entry.top().equals(changed.top()) && entry.kind() == changed.kind() && entry.key().equals(changed.key()))
                        previous = i;
                }
                if (previous >= 0)
                {
                    var old = entries.remove(previous);
                    changed = new StoreRecords.Entry(changed.top(), changed.kind(), changed.key(), old.before(), changed.after());
                }
                entries.add(changed);
            }
            // An intermediate save may contain a provisional record that the same step later removes.
            // Keep its address even when the final value equals the step's original value: replay must
            // overwrite that intermediate copy too. Do not refresh entries untouched by this step.
            entries.replaceAll(entry ->
            {
                var address = new Address(entry);
                if (!touched.contains(address))
                    return entry;
                var latest = address.value(after);
                return new StoreRecords.Entry(entry.top(), entry.kind(), entry.key(), entry.before(), latest == null ? null : latest.copy());
            });
            journal.put("records", StoreRecords.encode(entries));
            var shipIds = new LinkedHashSet<String>();
            journal.getListOrEmpty("ships").forEach(t -> t.asString().ifPresent(shipIds::add));
            shipIds.addAll(StoreRecords.shipIds(entries));
            var ships = new ListTag();
            shipIds.forEach(id -> ships.add(StringTag.valueOf(id)));
            journal.put("ships", ships);
            WorldStore.write(file, journal);
        }

        public <T> T step(Supplier<T> build)
        {
            var context = ServerContext.get(server);
            var parent = context.existing(CURRENT);
            context.put(CURRENT, this);
            var before = store.snapshot();
            var touched = new HashSet<Address>();
            UnresolvedJournals.remove(server, journal.getStringOr("id", ""));
            try (var capture = store.captureSaves(snapshot -> capture(before, snapshot, touched)))
            {
                T result = RecordJournal.withinOperation(server, build);
                capture(before, store.snapshot(), touched);
                if (parent != this)
                    lock();
                return result;
            }
            catch (RuntimeException | Error failure)
            {
                if (parent == this)
                    throw failure;
                capture(before, store.snapshot(), touched);
                ServerContext.get(server).service(ACTIVE, HashSet::new).remove(file);
                TransferRecovery.recover(server);
                throw failure;
            }
            finally
            {
                if (parent == null)
                    context.remove(CURRENT);
                else
                    context.put(CURRENT, parent);
            }
        }

        public void commit()
        {
            UnresolvedJournals.remove(server, journal.getStringOr("id", ""));
            try
            {
                TransferRecovery.checkpoint(file, journal, "PLACED");
                server.saveEverything(true, true, true);
                journal.putLong("saveNumber", store.saveNumberCovering());
                TransferRecovery.checkpoint(file, journal, "COMMITTED");
                if (journal.contains("rescuedOriginal"))
                    try
                    {
                        JournalRestore.finishOriginal(server, journal, true);
                    }
                    catch (java.io.IOException error)
                    {
                        throw new UncheckedIOException(error);
                    }
                TransferRecovery.finish(file, journal, "COMMITTED");
                ServerContext.get(server).service(ACTIVE, HashSet::new).remove(file);
            }
            catch (RuntimeException | Error failure)
            {
                ServerContext.get(server).service(ACTIVE, HashSet::new).remove(file);
                TransferRecovery.recover(server);
                throw failure;
            }
        }

        public void abort()
        {
            ServerContext.get(server).service(ACTIVE, HashSet::new).remove(file);
            TransferRecovery.recover(server);
        }
    }

    private BuildJournal()
    {
    }
}
