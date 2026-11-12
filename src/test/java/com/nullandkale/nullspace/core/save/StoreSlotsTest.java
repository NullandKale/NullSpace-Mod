package com.nullandkale.nullspace.core.save;

import java.io.IOException;
import java.nio.file.*;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class StoreSlotsTest
{
    @TempDir
    Path directory;

    private static CompoundTag store(String marker)
    {
        var tag = new CompoundTag();
        tag.putString("marker", marker);
        return tag;
    }

    @Test
    void aNewWorldStartsEmptyAtSaveNumberZero() throws IOException
    {
        var loaded = StoreSlots.load(directory);
        assertEquals(0, loaded.saveNumber());
        assertNull(loaded.slot());
        assertTrue(loaded.root().isEmpty());
        assertFalse(loaded.migrated());
    }

    @Test
    void aLargeValidStoreSurvivesValidationAndReopening() throws IOException
    {
        byte[] cargo = new byte[72 * 1024 * 1024];
        cargo[0] = 17;
        cargo[cargo.length - 1] = 91;
        var document = store("large-world");
        document.putByteArray("cargo", cargo);
        StoreSlots.write(directory, null, document, 1);
        var reopened = StoreSlots.load(directory);
        assertEquals(1, reopened.saveNumber());
        assertEquals("large-world", reopened.root().getStringOr("marker", ""));
        assertArrayEquals(cargo, reopened.root().getByteArray("cargo").orElseThrow());
    }

    @Test
    void anOversizedDeclaredArrayStillHitsABoundedReadQuota() throws IOException
    {
        var file = directory.resolve("oversized.nbt");
        try (var output = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))))
        {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(7);
            output.writeUTF("oversized");
            output.writeInt(257 * 1024 * 1024);
        }
        assertThrows(net.minecraft.nbt.NbtAccounterException.class, () -> WorldStore.read(file));
    }

    @Test
    void savesAlternateSlotsAndTheHighestNumberWins() throws IOException
    {
        var slot = StoreSlots.write(directory, null, store("one"), 1);
        assertEquals(StoreSlots.Slot.A, slot);
        slot = StoreSlots.write(directory, slot, store("two"), 2);
        assertEquals(StoreSlots.Slot.B, slot);
        var loaded = StoreSlots.load(directory);
        assertEquals(2, loaded.saveNumber());
        assertEquals("two", loaded.root().getStringOr("marker", ""));
        assertEquals(StoreSlots.Slot.B, loaded.slot());
        assertEquals(1, loaded.olderNumber());
        assertFalse(loaded.root().contains(StoreSlots.NUMBER), "The save number never reaches the repository");
        slot = StoreSlots.write(directory, slot, store("three"), 3);
        assertEquals(StoreSlots.Slot.A, slot, "The older copy is overwritten, never the newest");
        assertEquals("two", WorldStore.read(StoreSlots.file(directory, StoreSlots.Slot.B)).getStringOr("marker", ""));
    }

    @Test
    void aDamagedNewestCopyFallsBackToTheOtherAndIsKept() throws IOException
    {
        var slot = StoreSlots.write(directory, null, store("one"), 1);
        slot = StoreSlots.write(directory, slot, store("two"), 2);
        Files.write(StoreSlots.file(directory, slot), new byte[]{1, 2, 3});
        var loaded = StoreSlots.load(directory);
        assertEquals(1, loaded.saveNumber());
        assertEquals("one", loaded.root().getStringOr("marker", ""));
        assertEquals(1, loaded.damaged().size());
        assertEquals(slot, loaded.damaged().getFirst().slot());
        assertTrue(Files.exists(loaded.damaged().getFirst().keptAs()), "The damaged file is renamed, not deleted");
        assertFalse(Files.exists(StoreSlots.file(directory, slot)));
        assertEquals(-1, loaded.olderNumber());
        // The next save goes where the damaged copy was, so the good copy is never touched.
        assertEquals(slot, StoreSlots.next(loaded.slot()));
    }

    @Test
    void aSlotWithoutASaveNumberIsDamaged() throws IOException
    {
        WorldStore.write(StoreSlots.file(directory, StoreSlots.Slot.A), store("legacy-shaped"));
        var good = StoreSlots.write(directory, StoreSlots.Slot.A, store("good"), 5);
        var loaded = StoreSlots.load(directory);
        assertEquals(5, loaded.saveNumber());
        assertEquals(good, loaded.slot());
        assertEquals(1, loaded.damaged().size());
    }

    @Test
    void whenNoCopyCanBeReadTheServerRefusesToStart() throws IOException
    {
        var slot = StoreSlots.write(directory, null, store("one"), 1);
        StoreSlots.write(directory, slot, store("two"), 2);
        Files.write(StoreSlots.file(directory, StoreSlots.Slot.A), new byte[]{9});
        Files.write(StoreSlots.file(directory, StoreSlots.Slot.B), new byte[]{9});
        var error = assertThrows(StoreSlots.Unreadable.class, () -> StoreSlots.load(directory));
        assertTrue(error.getMessage().contains("ships-a.nbt") && error.getMessage().contains("backup"));
        assertTrue(Files.exists(StoreSlots.file(directory, StoreSlots.Slot.A)), "Nothing is renamed while no copy is good");
    }

    @Test
    void theOldSingleFileMigratesAsSaveNumberZero() throws IOException
    {
        WorldStore.write(directory.resolve("ships.nbt"), store("old"));
        var loaded = StoreSlots.load(directory);
        assertTrue(loaded.migrated());
        assertEquals(0, loaded.saveNumber());
        assertEquals("old", loaded.root().getStringOr("marker", ""));
        assertNull(loaded.slot());
        var slot = StoreSlots.write(directory, loaded.slot(), loaded.root(), 1);
        assertEquals(StoreSlots.Slot.A, slot);
        StoreSlots.retireLegacy(directory);
        assertTrue(Files.exists(directory.resolve("ships.nbt.pre-journals")));
        assertFalse(Files.exists(directory.resolve("ships.nbt")));
        assertEquals("old", StoreSlots.load(directory).root().getStringOr("marker", ""));
    }

    @Test
    void anUnreadableOldSingleFileRefusesToStart() throws IOException
    {
        Files.write(directory.resolve("ships.nbt"), new byte[]{1, 2});
        assertThrows(StoreSlots.Unreadable.class, () -> StoreSlots.load(directory));
    }

    @Test
    void slotsWinOverALeftoverOldFile() throws IOException
    {
        WorldStore.write(directory.resolve("ships.nbt"), store("old"));
        StoreSlots.write(directory, null, store("new"), 7);
        var loaded = StoreSlots.load(directory);
        assertEquals("new", loaded.root().getStringOr("marker", ""));
        assertFalse(loaded.migrated());
    }
}
