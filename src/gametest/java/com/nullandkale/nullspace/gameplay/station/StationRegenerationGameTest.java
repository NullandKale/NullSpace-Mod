package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

import com.google.gson.*;

public final class StationRegenerationGameTest
{
    @GameTest(environment = "nullspace:station_regeneration", maxTicks = 400)
    public void backedUpRebuildRestoresMissingDocksPreservesShipsAndDoesNotRepeat(GameTestHelper ctx) throws Exception
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var store = WorldStore.get(server);
        var base = new BlockPos(1830000, 80, 1830000);
        var old = StationGameTest.fixture(level, base, false);
        var id = old.getStringOr("id", "");
        for (var port : DockingPorts.ports(level, old))
            level.setBlock(port.controller(), Blocks.QUARTZ_BLOCK.defaultBlockState(), 18);
        ctx.assertTrue(DockingPorts.ports(level, old).isEmpty(), "Reproduce the saved world's missing docking bases");
        var other = new CompoundTag();
        other.putString("id", UUID.randomUUID().toString());
        other.putString("home", "Keep this ship");
        other.putInt("cargoMarker", 71);
        store.ships().add(other);
        var chest = java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(base, base.offset(31, 31, 31)).spliterator(), false).filter(at -> level.getBlockEntity(at) instanceof net.minecraft.world.Container).map(BlockPos::immutable).findFirst().orElseThrow();
        var oldCargo = (net.minecraft.world.Container) level.getBlockEntity(chest);
        oldCargo.setItem(0, new ItemStack(Items.DIAMOND, 17));
        var outside = base.offset(40, 3, 8);
        level.setBlock(outside, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        store.save();
        var backup = store.directory.resolve("regeneration-test-backup");
        Files.createDirectories(backup.resolve("nullspace"));
        Files.copy(store.storeFile(), backup.resolve("nullspace").resolve(store.storeFile().getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        var request = new JsonObject();
        request.addProperty("version", 1);
        request.addProperty("id", UUID.randomUUID().toString());
        request.addProperty("sourceSha256", StationRegeneration.sha(store.storeFile()));
        request.addProperty("backup", backup.toAbsolutePath().toString());
        var ids = new JsonArray();
        ids.add(id);
        request.add("stations", ids);
        var file = StationRegeneration.request(server);
        var original = store.snapshot();
        var invalid = request.deepCopy();
        var wrongIds = new JsonArray();
        wrongIds.add(other.getStringOr("id", ""));
        invalid.add("stations", wrongIds);
        Files.writeString(file, invalid.toString());
        boolean refused = false;
        try
        {
            StationRegeneration.applyPending(server);
        }
        catch (IllegalArgumentException expected)
        {
            refused = expected.getMessage().contains("is a ship");
        }
        ctx.assertTrue(refused && store.snapshot().equals(original), "A ship cannot be included in a regeneration request");
        Files.writeString(file, request.toString());
        ctx.assertTrue(StationRegeneration.applyPending(server) == 1, "Explicit request queues the current template");
        var station = store.repository().find(id);
        ctx.assertTrue(!StationAccess.ready(station), "Regeneration is a persisted construction job");
        ctx.assertTrue(store.repository().find(other.getStringOr("id", "")).equals(other), "Other ship records are untouched");
        StationBuilds.tickBuilds(server);
        long blocksBefore = java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(base, base.offset(31, 31, 31)).spliterator(), false).filter(at -> !level.getBlockState(at).isAir()).count();
        var visitor = ctx.makeMockServerPlayerInLevel();
        visitor.teleportTo(base.getX() + 8.5, base.getY() + 3, base.getZ() + 8.5);
        for (int pause = 0; pause < 3; pause++)
            StationBuilds.tickBuilds(server);
        long blocksAfter = java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(base, base.offset(31, 31, 31)).spliterator(), false).filter(at -> !level.getBlockState(at).isAir()).count();
        ctx.assertTrue(blocksBefore == blocksAfter, "An unexpected visitor pauses regeneration instead of being cleared with the station");
        visitor.teleportTo(base.getX() + 50, base.getY() + 3, base.getZ() + 8);
        server.getPlayerList().remove(visitor);
        for (int i = 0; i < 100 && !StationAccess.ready(station); i++)
            StationBuilds.tickBuilds(server);
        ctx.assertTrue(StationAccess.ready(station) && DockingPorts.ports(level, station).size() == 2, "Both current compact ports exist after replay");
        ctx.assertTrue(WorldStore.pos(station, "base").equals(base), "Station identity and location survive regeneration");
        ctx.assertTrue(level.getEntities(null, WorldStore.bounds(station).box()).stream().noneMatch(e -> e instanceof net.minecraft.world.entity.item.ItemEntity), "Old container contents are reset without dropping duplicate cargo into space");
        ctx.assertTrue(level.getBlockState(outside).is(Blocks.DIAMOND_BLOCK), "Blocks outside the station remain intact");
        var barrel = chest;
        var inventory = (net.minecraft.world.Container) level.getBlockEntity(barrel);
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var archive = file.resolveSibling("station-regeneration-queued-" + request.get("id").getAsString() + ".json");
        Files.copy(archive, file, StandardCopyOption.REPLACE_EXISTING);
        ctx.assertTrue(StationRegeneration.applyPending(server) == 0 && StationAccess.ready(station), "A completed request never rebuilds on revisit");
        ctx.assertTrue(inventory.getItem(0).is(Items.DIAMOND) && inventory.getItem(0).getCount() == 7, "Later station edits and cargo survive duplicate requests");
        store.ships().removeIf(t -> Set.of(id, other.getStringOr("id", "")).contains(((CompoundTag) t).getStringOr("id", "")));
        ctx.succeed();
    }
}
