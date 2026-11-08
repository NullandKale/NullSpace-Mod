package com.nullandkale.nullspace.core.save;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import net.minecraft.nbt.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorldStoreDurabilityTest
{
    @TempDir
    Path directory;

    @AfterEach
    void restoreObserver()
    {
        WorldStore.writeObserver = progress ->
        {};
    }

    private static CompoundTag sample(String marker)
    {
        var tag = new CompoundTag();
        tag.putString("marker", marker);
        tag.putByteArray("blob", new byte[50_000]);
        var list = new ListTag();
        for (int i = 0; i < 50; i++)
        {
            var entry = new CompoundTag();
            entry.putInt("i", i);
            entry.putString("text", "ship ✓ " + i);
            list.add(entry);
        }
        tag.put("ships", list);
        return tag;
    }

    @Test
    void writtenSnapshotsReadBackIdentically() throws IOException
    {
        var file = directory.resolve("ships.nbt");
        var tag = sample("first");
        WorldStore.write(file, tag);
        assertEquals(tag, WorldStore.read(file));
    }

    @Test
    void aSuccessfulWriteLeavesNoTemporaryFile() throws IOException
    {
        var file = directory.resolve("ships.nbt");
        WorldStore.write(file, sample("x"));
        try (var files = Files.list(directory))
        {
            assertEquals(List.of("ships.nbt"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void overwritingReplacesThePreviousSnapshot() throws IOException
    {
        var file = directory.resolve("ships.nbt");
        WorldStore.write(file, sample("old"));
        WorldStore.write(file, sample("new"));
        assertEquals("new", WorldStore.read(file).getStringOr("marker", ""));
    }

    @Test
    void aFailureDuringTheWriteKeepsThePreviousSnapshotAndRecovers() throws IOException
    {
        var file = directory.resolve("ships.nbt");
        WorldStore.write(file, sample("good"));
        WorldStore.writeObserver = progress ->
        {
            throw new UncheckedIOException(new IOException("disk full"));
        };
        assertThrows(RuntimeException.class, () -> WorldStore.write(file, sample("lost")));
        assertEquals("good", WorldStore.read(file).getStringOr("marker", ""));
        WorldStore.writeObserver = progress ->
        {};
        WorldStore.write(file, sample("retry"));
        assertEquals("retry", WorldStore.read(file).getStringOr("marker", ""));
    }

    @Test
    void aCrashAfterPartOfTheDataWasWrittenKeepsThePreviousSnapshot() throws IOException
    {
        var file = directory.resolve("ships.nbt");
        WorldStore.write(file, sample("good"));
        var written = new long[1];
        WorldStore.writeObserver = progress ->
        {
            written[0] = progress.bytes();
            if (progress.bytes() > 200)
                throw new UncheckedIOException(new IOException("killed"));
        };
        var large = sample("partial");
        var noise = new Random(7);
        var bytes = new byte[400_000];
        noise.nextBytes(bytes);
        large.putByteArray("noise", bytes);
        assertThrows(RuntimeException.class, () -> WorldStore.write(file, large));
        assertTrue(written[0] > 200, "The failure must land mid-stream");
        assertEquals("good", WorldStore.read(file).getStringOr("marker", ""));
    }

    @Test
    void readingAMissingFileGivesAnEmptyRoot() throws IOException
    {
        assertTrue(WorldStore.read(directory.resolve("absent.nbt")).isEmpty());
    }

    @Test
    void readingGarbageIsAnIoErrorRatherThanAnEmptyWorld() throws IOException
    {
        var file = directory.resolve("ships.nbt");
        Files.write(file, "definitely not nbt".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> WorldStore.read(file));
    }

    @Test
    void progressReportsTheTargetFileAndIncreases() throws IOException
    {
        var file = directory.resolve("ships.nbt");
        var seen = new ArrayList<WorldStore.WriteProgress>();
        WorldStore.writeObserver = seen::add;
        var tag = sample("p");
        tag.putString("stage", "recovery");
        WorldStore.write(file, tag);
        assertFalse(seen.isEmpty());
        assertTrue(seen.stream().allMatch(p -> p.file().equals(file)));
        assertTrue(seen.stream().allMatch(p -> p.stage().equals("recovery")));
        long previous = 0;
        for (var progress : seen)
        {
            assertTrue(progress.bytes() >= previous);
            previous = progress.bytes();
        }
    }

    // --- AtomicFiles ---------------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aWindowsSharingLockLongerThanFiftyMillisecondsCanClearWithoutLosingState() throws Exception
    {
        var temp = directory.resolve("sharing.tmp");
        var destination = directory.resolve("sharing");
        Files.writeString(destination, "old");
        Files.writeString(temp, "new");
        try (var locked = java.nio.channels.FileChannel.open(destination, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE); var release = java.util.concurrent.Executors.newSingleThreadScheduledExecutor())
        {
            var closed = release.schedule(() ->
            {
                locked.close();
                return true;
            }, 150, java.util.concurrent.TimeUnit.MILLISECONDS);
            AtomicFiles.promote(temp, destination);
            assertTrue(closed.get(2, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals("new", Files.readString(destination));
        assertFalse(Files.exists(temp));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aPersistentSharingLockFailsWithBothOriginalAndTemporaryContentsIntact() throws Exception
    {
        var temp = directory.resolve("blocked.tmp");
        var destination = directory.resolve("blocked");
        Files.writeString(destination, "old");
        Files.writeString(temp, "new");
        try (var locked = java.nio.channels.FileChannel.open(destination, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE))
        {
            assertThrows(AccessDeniedException.class, () -> AtomicFiles.promote(temp, destination));
        }
        assertEquals("old", Files.readString(destination));
        assertEquals("new", Files.readString(temp));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void interruptionCancelsSharingRetriesAndKeepsTheInterruptFlag() throws Exception
    {
        var temp = directory.resolve("interrupt.tmp");
        var destination = directory.resolve("interrupt");
        Files.writeString(destination, "old");
        Files.writeString(temp, "new");
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try (var locked = java.nio.channels.FileChannel.open(destination, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE))
        {
            var worker = Thread.ofPlatform().start(() ->
            {
                Thread.currentThread().interrupt();
                try
                {
                    AtomicFiles.promote(temp, destination);
                }
                catch (IOException error)
                {
                    failure.set(error);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            worker.join(2000);
            assertFalse(worker.isAlive());
        }
        assertInstanceOf(IOException.class, failure.get());
        assertTrue(interrupted.get());
        assertEquals("old", Files.readString(destination));
        assertEquals("new", Files.readString(temp));
    }

    @Test
    void promoteReplacesAnExistingDestination() throws IOException
    {
        var temp = directory.resolve("a.tmp");
        var destination = directory.resolve("a");
        Files.writeString(destination, "old");
        Files.writeString(temp, "new");
        AtomicFiles.promote(temp, destination);
        assertEquals("new", Files.readString(destination));
        assertFalse(Files.exists(temp));
    }

    @Test
    void promoteCreatesAMissingDestination() throws IOException
    {
        var temp = directory.resolve("b.tmp");
        Files.writeString(temp, "fresh");
        AtomicFiles.promote(temp, directory.resolve("b"));
        assertEquals("fresh", Files.readString(directory.resolve("b")));
    }

    @Test
    void promotingAMissingSourceFailsImmediately()
    {
        assertThrows(NoSuchFileException.class, () -> AtomicFiles.promote(directory.resolve("none.tmp"), directory.resolve("none")));
    }

    @Test
    void promoteDoesNotDisturbTheDestinationWhenTheSourceIsMissing() throws IOException
    {
        var destination = directory.resolve("keep");
        Files.writeString(destination, "intact");
        assertThrows(IOException.class, () -> AtomicFiles.promote(directory.resolve("none.tmp"), destination));
        assertEquals("intact", Files.readString(destination));
    }
}
