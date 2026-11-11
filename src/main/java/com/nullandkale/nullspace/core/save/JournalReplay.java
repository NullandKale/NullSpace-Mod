package com.nullandkale.nullspace.core.save;

import com.nullandkale.nullspace.NullSpace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import net.minecraft.nbt.CompoundTag;

/**
 * Brings a store copy that is older than the world up to date. When the newest store copy is damaged, the other copy
 * is behind by every operation committed since it was written. Each finished journal carries the records that
 * operation changed, so applying them in order puts the copy where the world already is. The world part of a journal
 * is never touched: the blocks already hold the result.
 */
public final class JournalReplay
{
    public record Result(int applied, Set<Path> failed)
    {}

    /**
     * Applies every finished journal that the loaded copy does not include.
     *
     * @return the number of journals applied
     */
    public static int apply(Path storeDirectory, CompoundTag root, long loadedNumber) throws IOException
    {
        return replay(storeDirectory, root, loadedNumber).applied();
    }

    public static Result replay(Path storeDirectory, CompoundTag root, long loadedNumber) throws IOException
    {
        int applied = 0;
        var failed = new HashSet<Path>();
        for (var file : Journals.list(storeDirectory))
        {
            var name = Journals.parse(file);
            if (name == null)
                continue;
            if (name.finished() && name.saveNumber() <= loadedNumber)
                continue;
            try
            {
                var journal = WorldStore.read(file);
                if (journal.getIntOr("version", 0) != Journals.VERSION)
                    throw new IOException("Unsupported journal version " + journal.getIntOr("version", 0));
                String stage = journal.getStringOr("stage", "");
                if (!Journals.finishedStage(stage))
                    continue;
                long covers = name.finished() ? name.saveNumber() : journal.getLongOr("saveNumber", -1);
                if (covers <= loadedNumber)
                    continue;
                var entries = StoreRecords.decode(journal.getListOrEmpty("records"));
                StoreRecords.apply(root, entries, stage.equals("COMMITTED"));
                applied++;
                NullSpace.LOGGER.warn("Replayed {} onto the ship store copy {}", Journals.summary(journal, stage), loadedNumber);
            }
            catch (IOException | RuntimeException error)
            {
                failed.add(file);
                // The store is opened before TransferRecovery can register locks or quarantine files.
                // Leave this file intact for that pass; it must not prevent unrelated journals or the server loading.
                NullSpace.LOGGER.error("Could not replay journal {}; recovery will set it aside for an operator", file.getFileName(), error);
            }
        }
        return new Result(applied, Set.copyOf(failed));
    }

    private JournalReplay()
    {
    }
}
