package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.Journals;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.StoreRecords;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/**
 * The transfer journal after a crash: replaying or rolling back a move and putting players where
 * they belong.
 */
public final class TransferRecovery
{
    private static final ServerContext.Key<Long> RECOVERY_NANOS = new ServerContext.Key<>();

    public static long recoveryNanos(MinecraftServer server)
    {
        var value = ServerContext.get(server).existing(RECOVERY_NANOS);
        return value == null ? 0 : value;
    }

    /** A journaled entity and every rider saved inside it. */
    static Set<UUID> journaled(CompoundTag entry)
    {
        var ids = new HashSet<UUID>();
        ids.add(UUID.fromString(entry.getStringOr("id", "")));
        for (Tag rider : entry.getListOrEmpty("riders"))
            ids.add(UUID.fromString(rider.asString().orElse("")));
        return ids;
    }

    static void checkpoint(Path file, CompoundTag journal, String stage)
    {
        journal.putString("stage", stage);
        WorldStore.write(file, journal);
        TravelService.failureInjector.accept(stage);
    }

    /** A committed journal is marked finished by its name. If this fails, recovery renames it at the next start. */
    static void finish(Path file, CompoundTag journal, String outcome)
    {
        try
        {
            Journals.finish(file, journal.getLongOr("saveNumber", -1));
            NullSpace.LOGGER.info(Journals.summary(journal, outcome));
        }
        catch (java.io.IOException error)
        {
            NullSpace.LOGGER.warn("Could not mark {} finished; recovery marks it at the next start", file.getFileName(), error);
        }
    }

    static void safeOfflineArrival(ServerLevel source, HullScan.Result scan, BlockPos base, CompoundTag member)
    {
        var feet = BlockPos.containing(base.getX() + member.getDoubleOr("x", 0), base.getY() + member.getDoubleOr("y", 0), base.getZ() + member.getDoubleOr("z", 0));
        if (scan.pressure().breathable(feet) && scan.pressure().breathable(feet.above()) && scan.blocks().contains(feet.below()) && source.getBlockState(feet.below()).isCollisionShapeFullBlock(source, feet.below()))
            return;
        // An offline passenger's old room may be in the abandoned component. Reconnect in the
        // validated cabin, never at an offset that has become empty space in the departing ship.
        var safe = scan.boarding().subtract(base);
        member.putDouble("x", safe.getX() + .5);
        member.putDouble("y", safe.getY());
        member.putDouble("z", safe.getZ() + .5);
    }

    public static void place(ServerLevel level, StructureTemplate template, BlockPos min)
    {
        TransferReplay.place(level, template, min);
    }

    public static void clear(ServerLevel level, BlockPos min, BlockPos max)
    {
        TransferReplay.clear(level, min, max);
    }

    /** How long a journal waits for the saved entities of its chunks before it counts as stuck. */
    static final int WAIT_LIMIT_TICKS = 600;

    /** Recovery cannot finish this journal and waiting will not help. The whole journal is set aside. */
    static final class Unrecoverable extends RuntimeException
    {
        Unrecoverable(String message)
        {
            super(message);
        }
    }

    public static void initialize()
    {
        // Entities load from disk on level ticks, so a journal that has to wait is retried here until it can run.
        ServerContext.tick(ServerContext.Phase.TRAVEL, server ->
        {
            if (server.getTickCount() % 5 == 0)
                retryWaiting(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> notifyOperator(handler.player));
    }

    private static boolean operator(ServerPlayer player)
    {
        return player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    private static void notifyOperator(ServerPlayer player)
    {
        if (!operator(player))
            return;
        var server = player.level().getServer();
        var unresolved = UnresolvedJournals.all(server);
        if (!unresolved.isEmpty())
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(unresolved.size() + " ship journal(s) need attention. Run /nullspace recovery list"));
        var store = WorldStore.existing(server);
        if (store != null)
            for (var damaged : store.damagedCopies())
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("""
                    Ship store copy %s was damaged at start (kept as %s). The other copy was loaded and committed \
                    journals were replayed; changes since the last world save may be missing.""".formatted(damaged.slot(), damaged.keptAs().getFileName())));
    }

    private static void notifyOperators(MinecraftServer server, String message)
    {
        for (var player : server.getPlayerList().getPlayers())
            if (operator(player))
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }

    /**
     * Recovers every unfinished journal that can run now. A journal that has to wait is tracked in
     * {@link UnresolvedJournals} and retried every few ticks; one that cannot be recovered is set aside whole.
     * Each journal is handled in its own try block, so one failure never stops the others or the server.
     */
    public static void recover(MinecraftServer server)
    {
        long started = System.nanoTime();
        boolean opensStore = WorldStore.existing(server) == null;
        var store = WorldStore.get(server);
        List<Path> files;
        try
        {
            files = Journals.list(store.directory);
        }
        catch (java.io.IOException error)
        {
            throw new IllegalStateException("Cannot list the transfer journals", error);
        }
        for (Path file : files)
            handle(server, store, file);
        ServerContext.get(server).put(RECOVERY_NANOS, recoveryNanos(server) + System.nanoTime() - started - (opensStore ? store.loadNanos() : 0));
    }

    /** At start, journals set aside earlier lock their areas again. */
    public static void registerQuarantined(MinecraftServer server)
    {
        var store = WorldStore.get(server);
        var folder = Journals.quarantine(store.directory);
        if (!Files.isDirectory(folder))
            return;
        try (var files = Files.list(folder))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".nbt") || p.toString().endsWith(".nbt.restoring")).toList())
            {
                String reason = "set aside by an earlier run";
                var note = file.resolveSibling(file.getFileName() + ".reason.txt");
                try
                {
                    if (Files.exists(note))
                        reason = Files.readAllLines(note).stream().findFirst().orElse(reason);
                    var journal = WorldStore.read(file);
                    UnresolvedJournals.put(server, UnresolvedJournals.describe(journal, UnresolvedJournals.State.SET_ASIDE, reason, server.getTickCount(), file));
                }
                catch (java.io.IOException | RuntimeException unreadable)
                {
                    UnresolvedJournals.put(server, unreadableEntry(file, reason, server.getTickCount()));
                }
            }
        }
        catch (java.io.IOException error)
        {
            NullSpace.LOGGER.error("Cannot list quarantined journals", error);
        }
    }

    private static UnresolvedJournals.Entry unreadableEntry(Path file, String reason, int tick)
    {
        var name = Journals.parse(file);
        String id = name == null ? file.getFileName().toString() : name.id();
        return new UnresolvedJournals.Entry(id, name == null ? 0 : name.sequence(), "?", "unreadable", List.of(), "", "", null, null, BlockPos.ZERO, UnresolvedJournals.State.SET_ASIDE, reason + "; the file cannot be read", tick, file);
    }

    private static void retryWaiting(MinecraftServer server)
    {
        var waiting = UnresolvedJournals.all(server).stream().filter(e -> e.state() == UnresolvedJournals.State.WAITING).toList();
        if (waiting.isEmpty())
            return;
        var store = WorldStore.get(server);
        for (var entry : waiting)
            if (Files.exists(entry.file()))
                handle(server, store, entry.file());
            else
                UnresolvedJournals.remove(server, entry.id());
    }

    /** One journal, start to finish. Never throws: anything unexpected sets the journal aside. */
    private static void handle(MinecraftServer server, WorldStore store, Path file)
    {
        if (BuildJournal.running(server, file))
            return;
        var name = Journals.parse(file);
        if (name != null && name.finished() && name.saveNumber() <= store.saveNumber() && !store.replayFailed(file))
            return; // A completed journal already in the loaded copy needs no decompression or recovery work.
        try
        {
            if (Journals.legacy(file))
            {
                handleLegacy(server, store, file);
                return;
            }
            var journal = WorldStore.read(file);
            int version = journal.getIntOr("version", 0);
            if (version != Journals.VERSION)
                throw new Unrecoverable("Unsupported journal version " + version + "; this mod reads version " + Journals.VERSION + ". It was probably written by a newer NullSpace.");
            String stage = journal.getStringOr("stage", "");
            if (Journals.finishedStage(stage))
            {
                StoreRecords.decode(journal.getListOrEmpty("records"));
                if (journal.contains("rescuedOriginal"))
                    JournalRestore.finishOriginal(server, journal, stage.equals("COMMITTED"));
                long covers = journal.getLongOr("saveNumber", -1);
                Journals.finish(file, covers >= 0 ? covers : store.saveNumberCovering());
                UnresolvedJournals.remove(server, journal.getStringOr("id", ""));
                return;
            }
            if (!ready(server, file, journal))
                return;
            rollback(server, store, file, journal);
            UnresolvedJournals.remove(server, journal.getStringOr("id", ""));
        }
        catch (Throwable error)
        {
            // An error that is not known to be recoverable is treated as unrecoverable.
            setAside(server, file, error);
        }
    }

    private static ServerLevel levelOrNull(MinecraftServer server, String name)
    {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(name)));
    }

    /**
     * True when both ends of the move are loaded and their saved entities are in. Otherwise the journal is tracked as
     * waiting or as missing a dimension, keeps its areas locked, and runs again later. Waiting too long is unrecoverable.
     */
    private static boolean ready(MinecraftServer server, Path file, CompoundTag journal)
    {
        int tick = server.getTickCount();
        String id = journal.getStringOr("id", "");
        if (!journal.contains("min"))
            return true; // A RECORD journal has no world part.
        for (var tag : journal.getListOrEmpty("regions"))
        {
            var region = (CompoundTag) tag;
            if (levelOrNull(server, region.getStringOr("source", "")) == null)
            {
                UnresolvedJournals.put(server, UnresolvedJournals.describe(journal, UnresolvedJournals.State.MISSING_DIMENSION, "a build region's dimension is not loaded", tick, file));
                return false;
            }
        }
        var source = levelOrNull(server, journal.getStringOr("source", ""));
        var target = levelOrNull(server, journal.getStringOr("target", ""));
        if (source == null || target == null)
        {
            String missing = source == null ? journal.getStringOr("source", "") : journal.getStringOr("target", "");
            UnresolvedJournals.put(server, UnresolvedJournals.describe(journal, UnresolvedJournals.State.MISSING_DIMENSION, "dimension " + missing + " is not loaded; it is retried on every start", tick, file));
            return false;
        }
        // A build has no entities or players to restore, so it never waits for saved entities to load.
        if (journal.getStringOr("kind", "MOVE").equals("BUILD") && !journal.contains("entityChanges"))
            return true;
        BlockPos min = WorldStore.pos(journal, "min"), max = WorldStore.pos(journal, "max"), delta = WorldStore.pos(journal, "delta");
        // Entities load from disk after their chunk does. Restoring a passenger before that finishes leaves it
        // unregistered, so a journal waits (and is retried every few ticks) until both ends of the move are ready.
        boolean extraReady = true;
        for (var tag : journal.getListOrEmpty("regions"))
        {
            var region = (CompoundTag) tag;
            extraReady &= entitiesReady(WorldStore.level(server, region.getStringOr("source", "")), new HullScan.Bounds(WorldStore.pos(region, "min"), WorldStore.pos(region, "max")));
        }
        if (!entitiesReady(source, new HullScan.Bounds(min, max)) | !entitiesReady(target, new HullScan.Bounds(min.offset(delta), max.offset(delta))) | !extraReady)
        {
            var prior = UnresolvedJournals.get(server, id);
            int since = prior != null && prior.state() == UnresolvedJournals.State.WAITING ? prior.sinceTick() : tick;
            if (tick - since > WAIT_LIMIT_TICKS)
                throw new Unrecoverable("The saved entities of the chunks did not finish loading within " + WAIT_LIMIT_TICKS + " ticks");
            UnresolvedJournals.put(server, UnresolvedJournals.describe(journal, UnresolvedJournals.State.WAITING, "waiting for saved entities to load", since, file));
            return false;
        }
        return true;
    }

    /** Rolls an unfinished journal back: its records, its hull, its entities and its players. Nothing else is touched. */
    private static void rollback(MinecraftServer server, WorldStore store, Path file, CompoundTag journal) throws java.io.IOException
    {
        var records = StoreRecords.decode(journal.getListOrEmpty("records"));
        store.repository().apply(records, false, ShipRepository.Mutation.RECOVERY);
        if (journal.contains("min"))
        {
            var source = WorldStore.level(server, journal.getStringOr("source", ""));
            var target = WorldStore.level(server, journal.getStringOr("target", ""));
            BlockPos min = WorldStore.pos(journal, "min"), max = WorldStore.pos(journal, "max"), delta = WorldStore.pos(journal, "delta");
            for (Tag tag : journal.getListOrEmpty("entities"))
                for (UUID moved : journaled((CompoundTag) tag))
                    authorityStore(store, moved, source.dimension().identifier().toString(), target.dimension().identifier().toString(), min.offset(delta), max.offset(delta));
            var moved = new HashSet<BlockPos>();
            for (var at : TransferReplay.positions(journal.getCompoundOrEmpty("template"), min))
                moved.add(at.offset(delta));
            TransferReplay.clear(target, moved);
            var template = new StructureTemplate();
            template.load(source.registryAccess().lookupOrThrow(Registries.BLOCK), journal.getCompoundOrEmpty("template"));
            place(source, template, min);
            JournalTicks.restore(source, journal.getCompoundOrEmpty("ticks"), BlockPos.ZERO);
            restoreEntitiesAndPlayers(store, journal, source, target, min, max, delta);
        }
        for (var tag : journal.getListOrEmpty("regions"))
        {
            var region = (CompoundTag) tag;
            var level = WorldStore.level(server, region.getStringOr("source", ""));
            var template = new StructureTemplate();
            template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), region.getCompoundOrEmpty("template"));
            place(level, template, WorldStore.pos(region, "min"));
            JournalTicks.restore(level, region.getCompoundOrEmpty("ticks"), BlockPos.ZERO);
        }
        if (journal.contains("entityChanges"))
            restoreBuildEntities(server, journal);
        store.requestSave();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            resolveArrival(player);
        server.saveEverything(true, true, true);
        journal.putString("stage", "ROLLED_BACK");
        long covers = store.saveNumberCovering();
        journal.putLong("saveNumber", covers);
        WorldStore.write(file, journal);
        NullSpace.LOGGER.warn("Recovered {}", Journals.summary(journal, "ROLLED_BACK"));
        if (journal.contains("rescuedOriginal"))
            JournalRestore.finishOriginal(server, journal, false);
        Journals.finish(file, covers);
    }

    private static void restoreBuildEntities(MinecraftServer server, CompoundTag journal)
    {
        var changes = journal.getCompoundOrEmpty("entityChanges");
        var ids = new HashSet<UUID>();
        changes.getListOrEmpty("ids").forEach(t -> ids.add(UUID.fromString(t.asString().orElseThrow())));
        for (var level : server.getAllLevels())
            for (var id : ids)
            {
                var entity = level.getEntity(id);
                if (entity != null)
                    entity.discard();
            }
        for (var tag : changes.getListOrEmpty("before"))
        {
            var entry = (CompoundTag) tag;
            var level = WorldStore.level(server, entry.getStringOr("dimension", ""));
            var entity = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), entry.getCompoundOrEmpty("data")), level, EntitySpawnReason.LOAD, e -> e);
            if (entity == null)
                throw new Unrecoverable("Could not restore a build's passenger");
            level.addFreshEntityWithPassengers(entity);
            if (entity.isRemoved() || level.getEntity(entity.getUUID()) != entity)
                throw new Unrecoverable("Could not register a build's restored passenger");
        }
    }

    private static void restoreEntitiesAndPlayers(WorldStore store, CompoundTag journal, ServerLevel source, ServerLevel target, BlockPos min, BlockPos max, BlockPos delta)
    {
        for (Tag tag : journal.getListOrEmpty("entities"))
        {
            var entry = (CompoundTag) tag;
            UUID id = UUID.fromString(entry.getStringOr("id", ""));
            var ids = journaled(entry);
            for (var old : HullEntities.inBounds(source, new HullScan.Bounds(min, max)))
                if (ids.contains(old.getUUID()))
                    old.discard();
            for (var old : HullEntities.inBounds(target, new HullScan.Bounds(min.offset(delta), max.offset(delta))))
                if (ids.contains(old.getUUID()))
                    old.discard();
            var entity = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, source.registryAccess(), entry.getCompoundOrEmpty("data")), source, EntitySpawnReason.LOAD, e -> e);
            if (entity == null)
                throw new Unrecoverable("Could not restore passenger " + id);
            source.addFreshEntityWithPassengers(entity);
            if (entity.isRemoved() || source.getEntity(entity.getUUID()) != entity)
                throw new Unrecoverable("Could not establish authoritative passenger " + id + " (" + entity.getType() + ", removed " + entity.isRemoved() + ", reason " + entity.getRemovalReason() + ")");
        }
        // A fence-post knot is never journaled: an animal leaves one at the destination when it is tied there. With the
        // animals gone it would only block the next ship, so it goes with them.
        for (var stray : HullEntities.inBounds(target, new HullScan.Bounds(min.offset(delta), max.offset(delta))))
            if (stray instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity)
                stray.discard();
        for (Tag tag : journal.getListOrEmpty("players"))
        {
            var p = (CompoundTag) tag;
            targetStore(store, UUID.fromString(p.getStringOr("id", "")), source.dimension().identifier().toString(), p.getDoubleOr("x", 0), p.getDoubleOr("y", 0), p.getDoubleOr("z", 0), p.getFloatOr("yaw", 0), p.getFloatOr("pitch", 0));
        }
    }

    /** Moves the whole journal to quarantine with a reason, keeps its areas and ships locked, and tells operators. */
    static void setAside(MinecraftServer server, Path file, Throwable error)
    {
        NullSpace.LOGGER.error("Journal {} cannot be recovered and was set aside; its areas stay locked", file.getFileName(), error);
        var store = WorldStore.get(server);
        var folder = Journals.quarantine(store.directory);
        Path kept = file;
        String reason = error.getMessage() == null ? error.toString() : error.getMessage();
        try
        {
            Files.createDirectories(folder);
            if (Files.exists(file))
            {
                kept = folder.resolve(file.getFileName());
                Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING);
            }
            var text = new StringBuilder(reason.replace('\n', ' ')).append('\n').append("Set aside at ").append(java.time.Instant.now()).append('\n');
            var trace = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            text.append(trace);
            Files.writeString(kept.resolveSibling(kept.getFileName() + ".reason.txt"), text);
        }
        catch (java.io.IOException io)
        {
            NullSpace.LOGGER.error("Could not move journal {} to quarantine; it stays where it is and stays locked", file, io);
        }
        try
        {
            var journal = WorldStore.read(kept);
            UnresolvedJournals.put(server, UnresolvedJournals.describe(journal, UnresolvedJournals.State.SET_ASIDE, reason, server.getTickCount(), kept));
        }
        catch (java.io.IOException | RuntimeException unreadable)
        {
            UnresolvedJournals.put(server, unreadableEntry(kept, reason, server.getTickCount()));
        }
        notifyOperators(server, "A ship journal could not be recovered and was set aside: " + reason + ". Run /nullspace recovery list");
    }

    /** A version 1 journal holds whole-store copies and the whole box. It is rolled back with the rules it was written under. */
    private static void handleLegacy(MinecraftServer server, WorldStore store, Path file) throws java.io.IOException
    {
        var journal = WorldStore.read(file);
        if (journal.getIntOr("version", 0) != 1)
            throw new Unrecoverable("Unsupported transfer journal version " + journal.getIntOr("version", 0));
        String stage = journal.getStringOr("stage", "");
        if (Journals.finishedStage(stage))
        {
            Files.delete(file); // A finished version 1 journal carries nothing replayable: the store was saved before it committed.
            return;
        }
        if (!ready(server, file, journal))
            return;
        var source = WorldStore.level(server, journal.getStringOr("source", ""));
        var target = WorldStore.level(server, journal.getStringOr("target", ""));
        BlockPos min = WorldStore.pos(journal, "min"), max = WorldStore.pos(journal, "max"), delta = WorldStore.pos(journal, "delta");
        store.replace(journal.getCompoundOrEmpty("before").copy(), ShipRepository.Mutation.RECOVERY);
        for (Tag tag : journal.getListOrEmpty("entities"))
            for (UUID moved : journaled((CompoundTag) tag))
                authorityStore(store, moved, source.dimension().identifier().toString(), target.dimension().identifier().toString(), min.offset(delta), max.offset(delta));
        clear(target, min.offset(delta), max.offset(delta));
        var template = new StructureTemplate();
        template.load(source.registryAccess().lookupOrThrow(Registries.BLOCK), journal.getCompoundOrEmpty("template"));
        TransferReplay.placeInBox(source, template, min);
        restoreEntitiesAndPlayers(store, journal, source, target, min, max, delta);
        store.requestSave();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            resolveArrival(player);
        server.saveEverything(true, true, true);
        NullSpace.LOGGER.warn("Recovered version 1 journal {}", file.getFileName());
        Files.delete(file);
    }

    /** Asks for the chunks around a hull and says whether their saved entities are loaded yet. */
    private static boolean entitiesReady(ServerLevel level, HullScan.Bounds bounds)
    {
        for (int x = (bounds.min().getX() - 2) >> 4; x <= (bounds.max().getX() + 2) >> 4; x++)
            for (int z = (bounds.min().getZ() - 2) >> 4; z <= (bounds.max().getZ() + 2) >> 4; z++)
                level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(x, z), 2);
        return HullEntities.loaded(level, bounds);
    }

    public static void resolveArrival(ServerPlayer player)
    {
        var store = WorldStore.get(player.level().getServer());
        for (Tag item : store.list("targets"))
        {
            var target = (CompoundTag) item;
            if (!target.getStringOr("id", "").equals(player.getUUID().toString()))
                continue;
            var level = WorldStore.level(player.level().getServer(), target.getStringOr("dimension", ""));
            player.teleport(new TeleportTransition(level, new Vec3(target.getDoubleOr("x", 0), target.getDoubleOr("y", 0), target.getDoubleOr("z", 0)), Vec3.ZERO, target.getFloatOr("yaw", 0), target.getFloatOr("pitch", 0), TeleportTransition.PLACE_PORTAL_TICKET));
            clearTarget(player);
            break;
        }
    }

    static void clearTarget(ServerPlayer player)
    {
        var store = WorldStore.get(player.level().getServer());
        store.list("targets").removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(player.getUUID().toString()));
        store.requestSave();
    }

    static void targetStore(WorldStore store, UUID id, String dimension, double x, double y, double z, float yaw, float pitch)
    {
        var targets = store.list("targets");
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

    static void authorityStore(WorldStore store, UUID id, String dimension, String obsolete, BlockPos min, BlockPos max)
    {
        var root = new CompoundTag();
        root.put("entityAuthority", store.list("entityAuthority"));
        authority(root, id, dimension, obsolete, min, max);
    }

    static void authority(CompoundTag root, UUID id, String dimension, String obsolete, BlockPos min, BlockPos max)
    {
        if (!root.contains("entityAuthority"))
            root.put("entityAuthority", new ListTag());
        var list = root.getListOrEmpty("entityAuthority");
        list.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id.toString()));
        var tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putString("dimension", dimension);
        tag.putString("obsoleteDimension", obsolete);
        tag.putLong("min", min.asLong());
        tag.putLong("max", max.asLong());
        list.add(tag);
    }

    private TransferRecovery()
    {
    }
}
