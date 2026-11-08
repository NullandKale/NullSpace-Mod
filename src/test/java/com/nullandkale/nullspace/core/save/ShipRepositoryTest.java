package com.nullandkale.nullspace.core.save;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShipRepositoryTest
{
    private static CompoundTag ship(String id)
    {
        var tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("name", "name-" + id);
        tag.putLong("base", new BlockPos(10, 64, 20).asLong());
        tag.putInt("width", 5);
        tag.putInt("height", 3);
        tag.putInt("depth", 7);
        return tag;
    }

    private static ShipRepository repository(String... ids)
    {
        var root = new CompoundTag();
        var list = new ListTag();
        for (String id : ids)
            list.add(ship(id));
        root.put("ships", list);
        return new ShipRepository(root);
    }

    private static List<ShipRepository.Change> changes(ShipRepository repository)
    {
        var events = new ArrayList<ShipRepository.Change>();
        repository.onChange(events::add);
        return events;
    }

    @Test
    void shipIdentitiesAreValidated()
    {
        for (String bad : new String[]{null, "", "   ", "x".repeat(81)})
            assertThrows(IllegalArgumentException.class, () -> new ShipRepository.ShipId(bad));
        new ShipRepository.ShipId("x".repeat(80));
    }

    @Test
    void findLocatesShipsByIdAndMissingOnesAreNull()
    {
        var repo = repository("a", "b");
        assertEquals("name-b", repo.find("b").getStringOr("name", ""));
        assertNull(repo.find("c"));
        assertNull(repo.find(""));
    }

    @Test
    void sectionsAndListsAreCreatedOnceAndShared()
    {
        var repo = new ShipRepository(new CompoundTag());
        repo.section("things").putInt("n", 1);
        assertEquals(1, repo.section("things").getIntOr("n", 0));
        repo.list("rows").add(StringTag.valueOf("x"));
        assertEquals(1, repo.list("rows").size());
        assertTrue(repo.ships().isEmpty());
    }

    @Test
    void snapshotsAreIndependentCopies()
    {
        var repo = repository("a");
        var snapshot = repo.snapshot();
        repo.find("a").putString("name", "changed");
        assertEquals("name-a", ((CompoundTag) snapshot.getListOrEmpty("ships").getFirst()).getStringOr("name", ""));
        ((CompoundTag) snapshot.getListOrEmpty("ships").getFirst()).putString("name", "tampered");
        assertEquals("changed", repo.find("a").getStringOr("name", ""));
    }

    @Test
    void mutationsPublishOnlyTheFieldsThatChanged()
    {
        var repo = repository("a");
        var events = changes(repo);
        long generation = repo.generation();
        repo.mutate(new ShipRepository.ShipId("a"), ShipRepository.Mutation.CONFIGURATION, ship ->
        {
            ship.putString("name", "renamed");
            ship.putInt("extra", 9);
            ship.remove("depth");
        });
        assertEquals(1, events.size());
        assertEquals(Set.of("name", "extra", "depth"), events.getFirst().fields());
        assertEquals(ShipRepository.Mutation.CONFIGURATION, events.getFirst().kind());
        assertEquals("a", events.getFirst().ship().value());
        assertEquals(generation + 1, repo.generation());
        var stored = repo.find("a");
        assertEquals("renamed", stored.getStringOr("name", ""));
        assertEquals(9, stored.getIntOr("extra", 0));
        assertFalse(stored.contains("depth"));
    }

    @Test
    void mutationsKeepTheLiveRecordIdentityForAdapters()
    {
        var repo = repository("a");
        var before = repo.find("a");
        repo.mutate(new ShipRepository.ShipId("a"), ShipRepository.Mutation.SIMULATION, ship -> ship.putDouble("simX", 1.5));
        assertSame(before, repo.find("a"));
        assertEquals(1.5, before.getDoubleOr("simX", 0));
    }

    @Test
    void noOpEditsDoNotNotifyOrAdvanceTheGeneration()
    {
        var repo = repository("a");
        var events = changes(repo);
        long generation = repo.generation();
        repo.mutate(new ShipRepository.ShipId("a"), ShipRepository.Mutation.DAMAGE, ship ->
        {});
        repo.mutate(new ShipRepository.ShipId("a"), ShipRepository.Mutation.DAMAGE, ship -> ship.putString("name", "name-a"));
        assertTrue(events.isEmpty());
        assertEquals(generation, repo.generation());
    }

    @Test
    void invalidEditsAreRejectedWithoutPartialEffects()
    {
        var repo = repository("a", "b");
        var events = changes(repo);
        var id = new ShipRepository.ShipId("a");
        assertThrows(IllegalArgumentException.class, () -> repo.mutate(id, ShipRepository.Mutation.CONFIGURATION, ship -> ship.putString("id", "b")));
        assertThrows(IllegalArgumentException.class, () -> repo.mutate(id, ShipRepository.Mutation.CONFIGURATION, ship -> ship.remove("id")));
        assertThrows(IllegalArgumentException.class, () -> repo.mutate(new ShipRepository.ShipId("zzz"), ShipRepository.Mutation.CONFIGURATION, s ->
        {}));
        assertThrows(IllegalStateException.class, () -> repo.mutate(id, ShipRepository.Mutation.CONFIGURATION, ship ->
        {
            ship.putString("name", "half-applied");
            throw new IllegalStateException("boom");
        }));
        assertEquals("name-a", repo.find("a").getStringOr("name", ""));
        assertTrue(events.isEmpty());
    }

    @Test
    void transferAndRecoveryMustUseTheirOwnBoundary()
    {
        var repo = repository("a");
        var id = new ShipRepository.ShipId("a");
        for (var kind : List.of(ShipRepository.Mutation.TRANSFER, ShipRepository.Mutation.RECOVERY))
            assertThrows(IllegalArgumentException.class, () -> repo.mutate(id, kind, s ->
            {}));
        for (var kind : List.of(ShipRepository.Mutation.CONFIGURATION, ShipRepository.Mutation.SIMULATION, ShipRepository.Mutation.DAMAGE))
            assertThrows(IllegalArgumentException.class, () -> repo.replace(new CompoundTag(), kind), "" + kind);
    }

    @Test
    void replacingTheRootNotifiesListenersOnceAndIgnoresTheSameRoot()
    {
        var repo = repository("a");
        var seen = new ArrayList<ShipRepository.Mutation>();
        repo.onReplace(seen::add);
        long generation = repo.generation();
        var replacement = new CompoundTag();
        replacement.put("ships", new ListTag());
        repo.replace(replacement, ShipRepository.Mutation.RECOVERY);
        assertEquals(List.of(ShipRepository.Mutation.RECOVERY), seen);
        assertEquals(generation + 1, repo.generation());
        assertNull(repo.find("a"));
        repo.replace(replacement, ShipRepository.Mutation.RECOVERY);
        assertEquals(1, seen.size(), "Replacing with the identical root is a no-op");
    }

    @Test
    void documentsAreStoredAndRemovedByName()
    {
        var repo = new ShipRepository(new CompoundTag());
        long generation = repo.generation();
        repo.document("doc", StringTag.valueOf("v"));
        assertEquals(generation + 1, repo.generation());
        assertEquals("v", repo.snapshot().getStringOr("doc", ""));
        repo.document("doc", null);
        assertFalse(repo.snapshot().contains("doc"));
    }

    @Test
    void locationReadsDimensionBaseAndBounds()
    {
        var tag = ship("a");
        tag.putString("dimension", "nullspace:space");
        var location = ShipRepository.location(tag);
        assertEquals("nullspace:space", location.dimension());
        assertEquals(new BlockPos(10, 64, 20), location.base());
        assertEquals(new BlockPos(10, 64, 20), location.bounds().min());
        assertEquals(new BlockPos(14, 66, 26), location.bounds().max());
    }

    @Test
    void boundsDefaultToAThirtyTwoCubeAndClampHeight()
    {
        var bare = new CompoundTag();
        bare.putLong("base", BlockPos.ZERO.asLong());
        var bounds = WorldStore.bounds(bare);
        assertEquals(new BlockPos(31, 31, 31), bounds.max());
        bare.putInt("height", 500);
        assertEquals(31, WorldStore.bounds(bare).max().getY(), "Height is capped at 32 blocks");
        assertTrue(WorldStore.bounds(bare).contains(new BlockPos(31, 31, 31)));
        assertFalse(WorldStore.bounds(bare).contains(new BlockPos(32, 0, 0)));
    }

    @Test
    void positionsDefaultToTheOriginWhenMissing()
    {
        assertEquals(BlockPos.ZERO, WorldStore.pos(new CompoundTag(), "base"));
        var tag = new CompoundTag();
        tag.putLong("base", new BlockPos(-5, 70, 9).asLong());
        assertEquals(new BlockPos(-5, 70, 9), WorldStore.pos(tag, "base"));
    }

    @Test
    void targetsAreReplacedByIdentityNotDuplicated()
    {
        var root = new CompoundTag();
        var id = UUID.randomUUID();
        WorldStore.target(root, id, "dim", 1, 2, 3, 4f, 5f);
        WorldStore.target(root, id, "dim", 9, 8, 7, 6f, 5f);
        WorldStore.target(root, UUID.randomUUID(), "dim", 0, 0, 0, 0f, 0f);
        var targets = WorldStore.targets(root);
        assertEquals(2, targets.size());
        var mine = targets.stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(id.toString())).findFirst().orElseThrow();
        assertEquals(9.0, mine.getDoubleOr("x", 0));
    }
}
