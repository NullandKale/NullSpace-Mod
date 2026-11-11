package com.nullandkale.nullspace.core.save;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import net.minecraft.nbt.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class JournalReplayTest
{
    @TempDir
    Path store;

    private static CompoundTag ship(String id, int x)
    {
        var tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("x", x);
        return tag;
    }

    private static CompoundTag root(CompoundTag... ships)
    {
        var root = new CompoundTag();
        var list = new ListTag();
        for (var ship : ships)
            list.add(ship);
        root.put("ships", list);
        return root;
    }

    private void write(String name, String stage, long saveNumber, CompoundTag before, CompoundTag after) throws IOException
    {
        var journal = new CompoundTag();
        journal.putInt("version", Journals.VERSION);
        journal.putString("stage", stage);
        journal.putLong("saveNumber", saveNumber);
        journal.putString("id", "x");
        journal.put("records", StoreRecords.encode(StoreRecords.diff(before, after)));
        Files.createDirectories(Journals.directory(store));
        WorldStore.write(Journals.directory(store).resolve(name), journal);
    }

    private static int x(CompoundTag root, String id)
    {
        return root.getListOrEmpty("ships").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(id)).findFirst().orElseThrow().getIntOr("x", -1);
    }

    @Test
    void anOlderCopyIsBroughtUpToTheWorldByTheJournalsItLacks() throws IOException
    {
        // The copy that loaded is number 4. The world has moved on: a launched ship at 10, then another at 20.
        write("00000001-aaaaaaaa-0000-0000-0000-000000000000.s3.nbt", "COMMITTED", 3, root(ship("a", 0)), root(ship("a", 99)));
        write("00000002-bbbbbbbb-0000-0000-0000-000000000000.s5.nbt", "COMMITTED", 5, root(ship("a", 0)), root(ship("a", 10)));
        write("00000003-cccccccc-0000-0000-0000-000000000000.s6.nbt", "COMMITTED", 6, root(ship("a", 10), ship("b", 0)), root(ship("a", 10), ship("b", 20)));
        var loaded = root(ship("a", 0), ship("b", 0));
        assertEquals(2, JournalReplay.apply(store, loaded, 4));
        assertEquals(10, x(loaded, "a"), "Journal 3 is already in copy 4 and is not applied; journal 5 is");
        assertEquals(20, x(loaded, "b"));
    }

    @Test
    void aRolledBackJournalAppliesItsBeforeValues() throws IOException
    {
        write("00000001-aaaaaaaa-0000-0000-0000-000000000000.s7.nbt", "ROLLED_BACK", 7, root(ship("a", 5)), root(ship("a", 50)));
        var loaded = root(ship("a", 50));
        assertEquals(1, JournalReplay.apply(store, loaded, 6));
        assertEquals(5, x(loaded, "a"), "What the rollback left in the world is what the copy must hold");
    }

    @Test
    void unfinishedJournalsAreLeftToRecovery() throws IOException
    {
        write("00000001-aaaaaaaa-0000-0000-0000-000000000000.nbt", "PREPARED", -1, root(ship("a", 1)), root(ship("a", 2)));
        var loaded = root(ship("a", 1));
        assertEquals(0, JournalReplay.apply(store, loaded, 0));
        assertEquals(1, x(loaded, "a"));
    }

    @Test
    void aCommittedJournalThatWasNotRenamedYetStillReplays() throws IOException
    {
        write("00000001-aaaaaaaa-0000-0000-0000-000000000000.nbt", "COMMITTED", 9, root(ship("a", 1)), root(ship("a", 2)));
        var loaded = root(ship("a", 1));
        assertEquals(1, JournalReplay.apply(store, loaded, 8), "A crash between writing COMMITTED and renaming the file loses nothing");
        assertEquals(2, x(loaded, "a"));
        assertEquals(0, JournalReplay.apply(store, root(ship("a", 1)), 9));
    }

    @Test
    void anUnreadableJournalDoesNotPreventStartupOrAnotherReplay() throws IOException
    {
        Files.createDirectories(Journals.directory(store));
        var broken = Journals.directory(store).resolve("00000001-aaaaaaaa-0000-0000-0000-000000000000.nbt");
        Files.writeString(broken, "damaged journal");
        write("00000002-bbbbbbbb-0000-0000-0000-000000000000.s5.nbt", "COMMITTED", 5, root(ship("a", 0)), root(ship("a", 10)));
        var loaded = root(ship("a", 0));
        assertEquals(1, JournalReplay.apply(store, loaded, 4));
        assertEquals(10, x(loaded, "a"));
        assertEquals("damaged journal", Files.readString(broken), "Recovery still has the original file to quarantine");
    }

    @Test
    void aNewerFormatIsLeftForOperatorRecoveryWithoutApplyingItsRecords() throws IOException
    {
        var name = "00000001-aaaaaaaa-0000-0000-0000-000000000000.s5.nbt";
        write(name, "COMMITTED", 5, root(ship("a", 0)), root(ship("a", 10)));
        var file = Journals.directory(store).resolve(name);
        var journal = WorldStore.read(file);
        journal.putInt("version", Journals.VERSION + 1);
        WorldStore.write(file, journal);
        var loaded = root(ship("a", 0));
        assertEquals(0, JournalReplay.apply(store, loaded, 4));
        assertEquals(0, x(loaded, "a"));
    }

    @Test
    void replayingTwiceGivesTheSameStore() throws IOException
    {
        write("00000001-aaaaaaaa-0000-0000-0000-000000000000.s5.nbt", "COMMITTED", 5, root(ship("a", 0)), root(ship("a", 1), ship("c", 3)));
        var once = root(ship("a", 0));
        JournalReplay.apply(store, once, 4);
        var twice = once.copy();
        JournalReplay.apply(store, twice, 4);
        assertEquals(once, twice);
        assertEquals(List.of("a", "c"), once.getListOrEmpty("ships").stream().map(t -> ((CompoundTag) t).getStringOr("id", "")).toList());
    }
}
