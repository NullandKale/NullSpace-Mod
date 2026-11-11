package com.nullandkale.nullspace.core.save;

import java.util.List;

import net.minecraft.nbt.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoreRecordsTest
{
    private static CompoundTag ship(String id, String name, int x)
    {
        var tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("name", name);
        tag.putInt("x", x);
        return tag;
    }

    private static CompoundTag store(CompoundTag... ships)
    {
        var root = new CompoundTag();
        var list = new ListTag();
        for (var ship : ships)
            list.add(ship);
        root.put("ships", list);
        root.putInt("version", 1);
        return root;
    }

    @Test
    void identicalStoresHaveNoEntries()
    {
        assertTrue(StoreRecords.diff(store(ship("a", "A", 1)), store(ship("a", "A", 1))).isEmpty());
    }

    @Test
    void onlyTheChangedShipIsRecordedAsAWholeRecord()
    {
        var before = store(ship("a", "A", 1), ship("b", "B", 2));
        var after = store(ship("a", "A", 1), ship("b", "B", 9));
        var entries = StoreRecords.diff(before, after);
        assertEquals(1, entries.size());
        var entry = entries.getFirst();
        assertEquals(StoreRecords.Kind.KEYED, entry.kind());
        assertEquals("b", entry.key());
        assertEquals(2, ((CompoundTag) entry.before()).getIntOr("x", 0));
        assertEquals(9, ((CompoundTag) entry.after()).getIntOr("x", 0));
        assertEquals(List.of("b"), StoreRecords.shipIds(entries));
    }

    @Test
    void addedAndRemovedShipsHaveOneAbsentSide()
    {
        var before = store(ship("a", "A", 1));
        var after = store(ship("b", "B", 2));
        var entries = StoreRecords.diff(before, after);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.key().equals("a") && e.after() == null));
        assertTrue(entries.stream().anyMatch(e -> e.key().equals("b") && e.before() == null));
    }

    @Test
    void applyingAfterReplaysAndApplyingBeforeRollsBack()
    {
        var before = store(ship("a", "A", 1), ship("b", "B", 2));
        var after = store(ship("b", "B", 9), ship("c", "C", 3));
        var entries = StoreRecords.diff(before, after);
        var forward = before.copy();
        StoreRecords.apply(forward, entries, true);
        assertEquals(ids(after), ids(forward));
        assertEquals(9, find(forward, "b").getIntOr("x", 0));
        var back = after.copy();
        StoreRecords.apply(back, entries, false);
        assertEquals(ids(before), ids(back));
        assertEquals(2, find(back, "b").getIntOr("x", 0));
    }

    @Test
    void applyingTwiceChangesNothingMore()
    {
        var before = store(ship("a", "A", 1));
        var after = store(ship("a", "A", 5), ship("b", "B", 2));
        var entries = StoreRecords.diff(before, after);
        var once = before.copy();
        StoreRecords.apply(once, entries, true);
        var twice = once.copy();
        StoreRecords.apply(twice, entries, true);
        assertEquals(once, twice);
    }

    @Test
    void rollbackLeavesUnrelatedChangesAlone()
    {
        var before = store(ship("a", "A", 1), ship("b", "B", 2));
        var after = store(ship("a", "A", 1), ship("b", "B", 9));
        var entries = StoreRecords.diff(before, after);
        // Something else changed ship a after the operation committed.
        var live = after.copy();
        find(live, "a").putInt("x", 77);
        StoreRecords.apply(live, entries, false);
        assertEquals(77, find(live, "a").getIntOr("x", 0), "Ship a was not part of the operation");
        assertEquals(2, find(live, "b").getIntOr("x", 0));
    }

    @Test
    void sectionsAreRecordedPerChild()
    {
        var before = store();
        var beforeSection = new CompoundTag();
        beforeSection.putInt("one", 1);
        beforeSection.putInt("two", 2);
        before.put("oxygen", beforeSection);
        var after = before.copy();
        after.getCompoundOrEmpty("oxygen").putInt("two", 20);
        after.getCompoundOrEmpty("oxygen").putInt("three", 3);
        var entries = StoreRecords.diff(before, after);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> e.kind() == StoreRecords.Kind.CHILD && e.top().equals("oxygen")));
        var back = after.copy();
        StoreRecords.apply(back, entries, false);
        assertEquals(before, back);
    }

    @Test
    void listsWithoutUniqueIdsAreRecordedWhole()
    {
        var before = store();
        var after = before.copy();
        var plain = new ListTag();
        plain.add(StringTag.valueOf("x"));
        after.put("log", plain);
        var entries = StoreRecords.diff(before, after);
        assertEquals(1, entries.size());
        assertEquals(StoreRecords.Kind.VALUE, entries.getFirst().kind());
        var back = after.copy();
        StoreRecords.apply(back, entries, false);
        assertFalse(back.contains("log"));
    }

    @Test
    void changingASectionToAnotherTypeDoesNotLoseTheReplacement()
    {
        var before = store();
        var section = new CompoundTag();
        section.putString("a", "old");
        before.put("probe", section);
        var after = before.copy();
        after.putString("probe", "replacement");
        var entries = StoreRecords.diff(before, after);
        var replayed = before.copy();
        StoreRecords.apply(replayed, entries, true);
        assertEquals(after, replayed);
        StoreRecords.apply(replayed, entries, false);
        assertEquals(before, replayed);
    }

    @Test
    void aMalformedKeyedRecordIsRejectedBeforeAnyReplayCanMutateTheStore()
    {
        var list = StoreRecords.encode(StoreRecords.diff(store(ship("a", "A", 1)), store(ship("a", "A", 2), ship("b", "B", 3))));
        ((CompoundTag) list.getLast()).getCompoundOrEmpty("after").putString("id", "wrong");
        assertThrows(IllegalArgumentException.class, () -> StoreRecords.decode(list));
    }

    @Test
    void entriesSurviveEncoding()
    {
        var before = store(ship("a", "A", 1));
        var after = store(ship("a", "A", 2), ship("b", "B", 3));
        var entries = StoreRecords.diff(before, after);
        var decoded = StoreRecords.decode(StoreRecords.encode(entries));
        assertEquals(entries, decoded);
    }

    private static List<String> ids(CompoundTag store)
    {
        return store.getListOrEmpty("ships").stream().map(t -> ((CompoundTag) t).getStringOr("id", "")).sorted().toList();
    }

    private static CompoundTag find(CompoundTag store, String id)
    {
        return store.getListOrEmpty("ships").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(id)).findFirst().orElseThrow();
    }
}
