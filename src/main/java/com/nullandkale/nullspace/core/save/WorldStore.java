package com.nullandkale.nullspace.core.save;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.StandardOpenOption;
import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** World-scoped metadata and durable write-ahead records. No player inventories are stored here. */
public final class WorldStore
{
    public record WriteProgress(Path file, String stage, long bytes)
    {}

    /** Fault-injection seam used by the opt-in local process-crash tests. */
    public static java.util.function.Consumer<WriteProgress> writeObserver = progress ->
    {};

    private static final ServerContext.Key<WorldStore> KEY = new ServerContext.Key<>();
    private final ShipRepository repository;
    private CompoundTag lastSaved;
    private boolean requested;
    private StoreSlots.Slot slot;
    private long newest, older;
    private final List<StoreSlots.Damaged> damaged;
    private final Set<Path> failedReplays;
    private boolean migrated;
    private java.util.function.Consumer<CompoundTag> saveCapture = snapshot ->
    {};
    public final Path directory;

    private WorldStore(MinecraftServer server)
    {
        directory = server.getWorldPath(LevelResource.ROOT).resolve("nullspace");
        StoreSlots.Loaded loaded;
        boolean staleOnDisk = false;
        try
        {
            Files.createDirectories(Journals.directory(directory));
            loaded = StoreSlots.load(directory);
            // A copy older than the world is brought up to date from the journals committed since it was saved.
            var replay = JournalReplay.replay(directory, loaded.root(), loaded.saveNumber());
            int replayed = replay.applied();
            failedReplays = replay.failed();
            repository = new ShipRepository(loaded.root());
            slot = loaded.slot();
            newest = loaded.saveNumber();
            older = loaded.olderNumber();
            damaged = loaded.damaged();
            migrated = loaded.migrated();
            for (var bad : damaged)
                NullSpace.LOGGER.error("Ship store copy {} was damaged ({}); it was kept as {} and copy {} was loaded with {} journals replayed", bad.slot(), bad.reason(), bad.keptAs().getFileName(), loaded.saveNumber(), replayed);
            // The newest copy on disk is behind the store in memory after a replay or a damaged copy, so it must be written.
            staleOnDisk = replayed > 0 || !damaged.isEmpty();
            if (replayed > 0)
                requested = true;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot open ship metadata: " + e.getMessage(), e);
        }
        if (repository.encoded().getIntOr("version", 1) != 1)
            throw new IllegalStateException("Unsupported NullSpace save version");
        repository.encoded().putInt("version", 1);
        lastSaved = staleOnDisk ? null : repository.snapshot();
        repository.onChange(change ->
        {
            ShipSystems.edited(server, change);
            requestSave();
        });
        repository.onReplace(kind ->
        {
            ShipSystems.replaced(server);
            ComputerService.replaced(server, kind);
            requestSave();
        });
        // A damaged copy is replaced at once, so the world is never left with a single good copy for long.
        if (!damaged.isEmpty())
            save();
    }

    /** The file holding the newest saved copy of the store. */
    public synchronized Path storeFile()
    {
        return StoreSlots.file(directory, slot == null ? StoreSlots.Slot.A : slot);
    }

    /** The store copies that were damaged when this server started, for operators. */
    public List<StoreSlots.Damaged> damagedCopies()
    {
        return damaged;
    }

    public boolean replayFailed(Path file)
    {
        return failedReplays.contains(file);
    }

    public static WorldStore get(MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, () -> new WorldStore(server));
    }

    /** The store of a running server, or null before it has been opened. */
    public static WorldStore existing(MinecraftServer server)
    {
        return ServerContext.isActive(server) ? ServerContext.get(server).existing(KEY) : null;
    }

    public static void forget(MinecraftServer server)
    {
        ServerContext.get(server).remove(KEY);
    }

    public ShipRepository repository()
    {
        return repository;
    }

    public CompoundTag snapshot()
    {
        return repository.snapshot();
    }

    public CompoundTag section(String name)
    {
        return repository.section(name);
    }

    public ListTag list(String name)
    {
        return repository.list(name);
    }

    public void replace(CompoundTag snapshot, ShipRepository.Mutation kind)
    {
        repository.replace(snapshot, kind);
    }

    public void document(String name, Tag value)
    {
        repository.document(name, value);
        requestSave();
    }

    /** Marks the store changed. It is written with the next world save, so it matches the chunks written with it. */
    public void requestSave()
    {
        requested = true;
    }

    /**
     * Called at the end of every world save (autosave, save-all, shutdown and the forced saves of transfers). The store is
     * written with the world, so after a crash both come from the same moment. Honours save-off like the world does.
     */
    public static void worldSaved(MinecraftServer server, boolean force)
    {
        var store = existing(server);
        if (store == null)
            return;
        if (!force && server.getAllLevels() != null && allSavingOff(server))
            return;
        if (force)
            store.save();
        else
            try
            {
                store.save();
            }
            catch (RuntimeException error)
            {
                NullSpace.LOGGER.error("Ordinary ship store save failed; it stays pending and retries with the next world save", error);
            }
    }

    private static boolean allSavingOff(MinecraftServer server)
    {
        boolean any = false;
        for (var level : server.getAllLevels())
        {
            any = true;
            if (!level.noSave)
                return false;
        }
        return any;
    }

    /** The save number of the first store save that will include a change made now. */
    public synchronized long saveNumberCovering()
    {
        return requested ? newest + 1 : newest;
    }

    public synchronized long saveNumber()
    {
        return newest;
    }

    public synchronized long olderSaveNumber()
    {
        return older;
    }

    /** Captures an operation's rollback records before an intermediate metadata save can reach disk. */
    public SaveCapture captureSaves(java.util.function.Consumer<CompoundTag> capture)
    {
        return new SaveCapture(capture);
    }

    public final class SaveCapture implements AutoCloseable
    {
        private final java.util.function.Consumer<CompoundTag> previous;

        private SaveCapture(java.util.function.Consumer<CompoundTag> capture)
        {
            previous = saveCapture;
            saveCapture = snapshot ->
            {
                previous.accept(snapshot);
                capture.accept(snapshot);
            };
        }

        @Override
        public void close()
        {
            saveCapture = previous;
        }
    }

    public synchronized void save()
    {
        // Disconnect and shutdown callbacks can overlap. One store owns one temporary file per slot.
        // Write and validate one frozen document. Live metadata can change through callbacks or an
        // integrated client's shared payloads while IO is in progress.
        var encoded = repository.snapshot();
        saveCapture.accept(encoded);
        if (lastSaved != null && lastSaved.equals(encoded) && slot != null && !Journals.needsRotation(directory, older, newest))
        {
            requested = false;
            return;
        }
        long number = newest + 1;
        var written = StoreSlots.write(directory, slot, encoded, number);
        older = newest;
        newest = number;
        slot = written;
        lastSaved = encoded;
        requested = !repository.encoded().equals(encoded);
        if (migrated)
        {
            try
            {
                StoreSlots.retireLegacy(directory);
                migrated = false;
            }
            catch (java.io.IOException error)
            {
                NullSpace.LOGGER.warn("Could not rename the old ships.nbt; the new store copies take precedence", error);
            }
        }
        Journals.prune(directory, older);
    }

    public static CompoundTag read(Path file) throws java.io.IOException
    {
        return Files.exists(file) ? NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024)) : new CompoundTag();
    }

    public static void write(Path file, CompoundTag tag)
    {
        try
        {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var output = Files.newOutputStream(temp))
            {
                NbtIo.writeCompressed(tag, new java.io.FilterOutputStream(output)
                {
                    private long written;

                    @Override
                    public void write(int value) throws java.io.IOException
                    {
                        out.write(value);
                        written++;
                        writeObserver.accept(new WriteProgress(file, tag.getStringOr("stage", "metadata"), written));
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws java.io.IOException
                    {
                        out.write(bytes, offset, length);
                        written += length;
                        writeObserver.accept(new WriteProgress(file, tag.getStringOr("stage", "metadata"), written));
                    }
                });
            }
            // Reject a snapshot that recovery could not read, before promoting it or touching world
            // blocks.
            var verified = read(temp);
            if (!tag.equals(verified))
                throw new java.io.IOException("Saved NBT differs at " + difference(tag, verified, "root") + "; refusing to replace the previous file");
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }
            AtomicFiles.promote(temp, file);
        }
        catch (java.io.IOException error)
        {
            throw new IllegalStateException("Could not durably save " + file, error);
        }
    }

    public ListTag ships()
    {
        return repository.ships();
    }

    private static String difference(Tag expected, Tag actual, String path)
    {
        if (expected instanceof CompoundTag a && actual instanceof CompoundTag b)
        {
            var keys = new java.util.TreeSet<>(a.keySet());
            keys.addAll(b.keySet());
            for (var key : keys)
                if (!java.util.Objects.equals(a.get(key), b.get(key)))
                    return difference(a.get(key), b.get(key), path + "." + key);
        }
        else if (expected instanceof ListTag a && actual instanceof ListTag b)
        {
            if (a.size() != b.size())
                return path + ".length";
            for (int i = 0; i < a.size(); i++)
                if (!java.util.Objects.equals(a.get(i), b.get(i)))
                    return difference(a.get(i), b.get(i), path + "[" + i + "]");
        }
        return path + " (" + (expected == null ? "missing" : expected.getClass().getSimpleName()) + "/" + (actual == null ? "missing" : actual.getClass().getSimpleName()) + ")";
    }

    /**
     * The ship whose region holds the position, the highest base winning. Most code edits ship records directly, so no
     * cached index can be trusted here; this is a loop over the records that allocates nothing.
     */
    public CompoundTag at(ServerLevel level, BlockPos pos)
    {
        String dimension = level.dimension().identifier().toString();
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        CompoundTag best = null;
        int bestY = Integer.MIN_VALUE;
        var list = ships();
        for (int i = 0; i < list.size(); i++)
        {
            if (!(list.get(i) instanceof CompoundTag ship) || !ship.getStringOr("dimension", "").equals(dimension))
                continue;
            long base = ship.getLongOr("base", 0);
            int baseX = BlockPos.getX(base), baseY = BlockPos.getY(base), baseZ = BlockPos.getZ(base);
            int height = Math.clamp(ship.getIntOr("height", 32), 0, 32);
            if (x >= baseX && x <= baseX + ship.getIntOr("width", 32) - 1 && y >= baseY && y <= baseY + height - 1 && z >= baseZ && z <= baseZ + ship.getIntOr("depth", 32) - 1 && baseY > bestY)
            {
                best = ship;
                bestY = baseY;
            }
        }
        return best;
    }

    public static BlockPos pos(CompoundTag tag, String key)
    {
        return BlockPos.of(tag.getLongOr(key, 0));
    }

    public static ServerLevel level(MinecraftServer server, String name)
    {
        var result = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(name)));
        if (result == null)
            throw new IllegalArgumentException("Dimension unavailable: " + name);
        return result;
    }

    public static HullScan.Bounds bounds(CompoundTag ship)
    {
        BlockPos base = pos(ship, "base");
        return new HullScan.Bounds(base, base.offset(ship.getIntOr("width", 32) - 1, Math.clamp(ship.getIntOr("height", 32), 0, 32) - 1, ship.getIntOr("depth", 32) - 1));
    }

    public static ListTag targets(CompoundTag root)
    {
        if (!root.contains("targets"))
            root.put("targets", new ListTag());
        return root.getListOrEmpty("targets");
    }

    public static void target(CompoundTag root, UUID id, String dimension, double x, double y, double z, float yaw, float pitch)
    {
        var targets = targets(root);
        targets.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id.toString()));
        var target = new CompoundTag();
        target.putString("id", id.toString());
        target.putString("dimension", dimension);
        target.putDouble("x", x);
        target.putDouble("y", y);
        target.putDouble("z", z);
        target.putFloat("yaw", yaw);
        target.putFloat("pitch", pitch);
        targets.add(target);
    }

    private WorldStore()
    {
        throw new AssertionError();
    }
}
