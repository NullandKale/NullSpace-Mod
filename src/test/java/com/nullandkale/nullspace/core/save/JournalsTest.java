package com.nullandkale.nullspace.core.save;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class JournalsTest
{
    @TempDir
    Path store;

    private Path journal(String name) throws IOException
    {
        Files.createDirectories(Journals.directory(store));
        var file = Journals.directory(store).resolve(name);
        WorldStore.write(file, new CompoundTag());
        return file;
    }

    @Test
    void namesCarryTheSequenceAndWhetherTheJournalIsFinished() throws IOException
    {
        var open = Journals.parse(journal("00000012-7b79fe87-6a11-4112-977f-8649768f4543.nbt"));
        assertEquals(12, open.sequence());
        assertFalse(open.finished());
        var done = Journals.parse(journal("00000013-7b79fe87-6a11-4112-977f-8649768f4543.s34.nbt"));
        assertEquals(34, done.saveNumber());
        assertTrue(done.finished());
        assertNull(Journals.parse(journal("7b79fe87-6a11-4112-977f-8649768f4543.nbt")));
        assertTrue(Journals.legacy(journal("a7642942-1c29-434f-bf47-b7b946562df1.nbt")), "A file named only by its id is a version 1 journal");
    }

    @Test
    void journalsListInTheOrderTheOperationsHappened() throws IOException
    {
        journal("00000010-bbbbbbbb-0000-0000-0000-000000000000.nbt");
        journal("00000002-cccccccc-0000-0000-0000-000000000000.s5.nbt");
        journal("a7642942-1c29-434f-bf47-b7b946562df1.nbt");
        journal("00000007-aaaaaaaa-0000-0000-0000-000000000000.nbt");
        var names = Journals.list(store).stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("a7642942-1c29-434f-bf47-b7b946562df1.nbt", "00000002-cccccccc-0000-0000-0000-000000000000.s5.nbt", "00000007-aaaaaaaa-0000-0000-0000-000000000000.nbt", "00000010-bbbbbbbb-0000-0000-0000-000000000000.nbt"), names);
    }

    @Test
    void theNextSequenceFollowsEveryFileIncludingQuarantine() throws IOException
    {
        assertEquals(1, Journals.nextSequence(store));
        journal("00000004-aaaaaaaa-0000-0000-0000-000000000000.nbt");
        Files.createDirectories(Journals.quarantine(store));
        WorldStore.write(Journals.quarantine(store).resolve("00000009-bbbbbbbb-0000-0000-0000-000000000000.nbt"), new CompoundTag());
        assertEquals(10, Journals.nextSequence(store), "A journal set aside keeps its number, so a new one never reuses it");
    }

    @Test
    void finishingRenamesTheFileWithTheSaveNumber() throws IOException
    {
        var open = journal("00000003-aaaaaaaa-0000-0000-0000-000000000000.nbt");
        var finished = Journals.finish(open, 41);
        assertEquals("00000003-aaaaaaaa-0000-0000-0000-000000000000.s41.nbt", finished.getFileName().toString());
        assertFalse(Files.exists(open));
        assertTrue(Files.exists(finished));
    }

    @Test
    void aFinishedJournalIsDeletedOnlyOnceEveryKeptCopyIncludesIt() throws IOException
    {
        var inOlder = journal("00000001-aaaaaaaa-0000-0000-0000-000000000000.s5.nbt");
        var inNewestOnly = journal("00000002-bbbbbbbb-0000-0000-0000-000000000000.s6.nbt");
        var unfinished = journal("00000003-cccccccc-0000-0000-0000-000000000000.nbt");
        assertEquals(1, Journals.prune(store, 5));
        assertFalse(Files.exists(inOlder));
        assertTrue(Files.exists(inNewestOnly), "The older copy does not include it yet");
        assertTrue(Files.exists(unfinished), "An unfinished journal is never pruned");
        assertEquals(1, Journals.prune(store, 6));
        assertFalse(Files.exists(inNewestOnly));
        assertTrue(Files.exists(unfinished));
    }

    @Test
    void newJournalsHaveAFullHeader() throws IOException
    {
        var journal = Journals.create(store, Journals.Kind.RECORD, "claim", List.of("ship-1", "ship-2"));
        assertEquals(Journals.VERSION, journal.getIntOr("version", 0));
        assertEquals("RECORD", journal.getStringOr("kind", ""));
        assertEquals("claim", journal.getStringOr("operation", ""));
        assertEquals(2, journal.getListOrEmpty("ships").size());
        assertEquals(1, journal.getLongOr("sequence", 0));
        assertEquals("OPEN", journal.getStringOr("stage", ""));
        assertTrue(journal.getLongOr("time", 0) > 0);
        assertEquals(Journals.directory(store).resolve(String.format("00000001-%s.nbt", journal.getStringOr("id", ""))), Journals.file(store, journal));
    }
}
