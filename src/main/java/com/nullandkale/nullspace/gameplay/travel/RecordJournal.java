package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.Journals;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.StoreRecords;
import com.nullandkale.nullspace.core.save.WorldStore;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

/** One task's metadata changes, committed without forcing a world save. Nested edits share their caller's journal. */
public final class RecordJournal
{
    private static final ServerContext.Key<Boolean> ACTIVE = new ServerContext.Key<>();

    /** A MOVE or BUILD already owns its nested metadata edits. */
    static <T> T withinOperation(MinecraftServer server, Supplier<T> edit)
    {
        var context = ServerContext.get(server);
        var previous = context.existing(ACTIVE);
        context.put(ACTIVE, true);
        try
        {
            return edit.get();
        }
        finally
        {
            if (previous == null)
                context.remove(ACTIVE);
            else
                context.put(ACTIVE, previous);
        }
    }

    public static <T> T run(MinecraftServer server, String operation, Supplier<T> edit)
    {
        var context = ServerContext.get(server);
        if (Boolean.TRUE.equals(context.existing(ACTIVE)))
            return edit.get();
        var store = WorldStore.get(server);
        var before = store.snapshot();
        context.put(ACTIVE, true);
        try
        {
            T result = edit.get();
            write(store, operation, before);
            return result;
        }
        catch (java.io.IOException error)
        {
            throw new UncheckedIOException(error);
        }
        finally
        {
            context.remove(ACTIVE);
        }
    }

    /** A caller with a narrowly scoped existing snapshot can commit it without nesting another snapshot. */
    public static void commit(MinecraftServer server, String operation, CompoundTag before)
    {
        if (Boolean.TRUE.equals(ServerContext.get(server).existing(ACTIVE)))
            return;
        try
        {
            write(WorldStore.get(server), operation, before);
        }
        catch (java.io.IOException error)
        {
            throw new UncheckedIOException(error);
        }
    }

    private static void write(WorldStore store, String operation, CompoundTag before) throws java.io.IOException
    {
        var entries = StoreRecords.diff(before, store.snapshot());
        if (entries.isEmpty())
            return;
        Path file = null;
        try
        {
            var journal = Journals.create(store.directory, Journals.Kind.RECORD, operation, StoreRecords.shipIds(entries));
            journal.put("records", StoreRecords.encode(entries));
            store.requestSave();
            journal.putLong("saveNumber", store.saveNumberCovering());
            file = Journals.file(store.directory, journal);
            TransferRecovery.checkpoint(file, journal, "COMMITTED");
            TransferRecovery.finish(file, journal, "COMMITTED");
        }
        catch (java.io.IOException | RuntimeException | Error failure)
        {
            boolean committed = false;
            if (file != null && Files.exists(file))
                try
                {
                    committed = WorldStore.read(file).getStringOr("stage", "").equals("COMMITTED");
                }
                catch (java.io.IOException unreadable)
                {
                    failure.addSuppressed(unreadable);
                }
            if (!committed)
                store.repository().apply(entries, false, ShipRepository.Mutation.RECOVERY);
            else
                store.repository().changed();
            throw failure;
        }
        store.repository().changed();
    }

    private RecordJournal()
    {
    }
}
