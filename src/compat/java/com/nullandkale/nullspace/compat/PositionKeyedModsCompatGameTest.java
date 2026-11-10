package com.nullandkale.nullspace.compat;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.EntityTravelGameTest;

import java.util.*;
import java.util.function.BiConsumer;

import net.blay09.mods.waystones.api.WaystoneTypes;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import com.supermartijn642.chunkloaders.capability.ChunkLoadingCapability;
import com.supermartijn642.movingelevators.elevator.ElevatorGroupCapability;

/**
 * Mods that keep a list of things by position outside the blocks themselves: Waystones' database, Chunk Loaders'
 * tickets, Moving Elevators' groups. A ship that takes its blocks away has to leave those lists as it found them,
 * and the registry sweeps cannot see a list. Each case passes at once when its mod is not loaded.
 */
public final class PositionKeyedModsCompatGameTest
{
    private record Site(EntityTravelGameTest.Bed bed, ServerLevel level, BlockPos spot)
    {}

    private static boolean loaded(String id)
    {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    /** A floor cell with three open cells above it, away from the player. */
    private static BlockPos spot(EntityTravelGameTest.Bed bed)
    {
        var level = bed.level();
        for (var floor : bed.floor())
            if (level.getBlockState(floor.above()).isAir() && level.getBlockState(floor.above(2)).isAir() && level.getBlockState(floor.above(3)).isAir() && floor.distManhattan(bed.floor().getFirst()) > 3)
                return floor.above();
        return null;
    }

    private static void withShip(GameTestHelper ctx, String mod, int slot, BiConsumer<Site, Map<String, String>> test)
    {
        if (!loaded(mod))
        {
            ctx.succeed();
            return;
        }
        var make = EntityTravelGameTest.catalogBed(ctx, slot, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var start = bed.floor().getFirst();
            bed.player().teleportTo(start.getX() + .5, start.getY(), start.getZ() + .5);
            var anomalies = new TreeMap<String, String>();
            var spot = spot(bed);
            if (spot == null)
            {
                anomalies.put("(ship)", "no open floor cell to put the block on");
                end(ctx, new Site(bed, bed.level(), start), List.of(), anomalies, mod);
                return;
            }
            test.accept(new Site(bed, bed.level(), spot), anomalies);
        });
    }

    private static void end(GameTestHelper ctx, Site site, List<String> notes, Map<String, String> anomalies, String name)
    {
        var level = site.level();
        level.getServer().getPlayerList().remove(site.bed().player());
        WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(site.bed().shipId()));
        ModCompatGameTest.conclude(ctx, "position-" + name, notes, anomalies);
    }

    private static void trip(Site site, Map<String, String> anomalies)
    {
        try
        {
            for (var problem : EntityTravelGameTest.roundTrip(site.bed(), List.of(), Set.of()))
                anomalies.put(problem.length() > 80 ? problem.substring(0, 80) : problem, problem);
        }
        catch (RuntimeException refused)
        {
            anomalies.put("(trip)", refused instanceof IllegalArgumentException ? "REFUSED: " + refused.getMessage().replaceAll("\\s+", " ") : "THREW: " + ModCompatGameTest.trace(refused));
        }
    }

    /** A waystone stays in Waystones' database, with its own identity, and the database gains nothing in space or on return. */
    @GameTest(maxTicks = 600)
    public void aWaystoneStaysOneWaystone(GameTestHelper ctx)
    {
        withShip(ctx, "waystones", 90, (site, anomalies) ->
        {
            var level = site.level();
            var server = level.getServer();
            var notes = new ArrayList<String>();
            var placed = WaystonesAPI.placeWaystone(level, site.spot(), WaystoneTypes.ANDESITE);
            if (placed.isEmpty())
            {
                anomalies.put("(placing)", "Waystones would not place one at " + site.spot());
                end(ctx, site, notes, anomalies, "waystones");
                return;
            }
            var uid = placed.get().getWaystoneUid();
            int known = WaystonesAPI.getAllWaystones(server).size();
            notes.add("database held " + known + " waystone(s) before the trip");
            ctx.runAfterDelay(20, () ->
            {
                trip(site, anomalies);
                var after = WaystonesAPI.getWaystoneAt(level, site.spot());
                int now = WaystonesAPI.getAllWaystones(server).size();
                notes.add("after the trip: " + now + " waystone(s), " + (after.isPresent() ? "one at the spot" : "none at the spot"));
                if (after.isEmpty())
                    anomalies.put("waystone", "the waystone is not in the database where it stood");
                else if (!after.get().getWaystoneUid().equals(uid))
                    anomalies.put("waystone identity", "the waystone at the spot is a different one: " + uid + " became " + after.get().getWaystoneUid());
                if (now != known)
                    anomalies.put("database size", "the database held " + known + " waystone(s) before the trip and " + now + " after");
                end(ctx, site, notes, anomalies, "waystones");
            });
        });
    }

    /** A chunk loader keeps its chunk loaded where it stands, and leaves no ticket in the dimension it passed through. */
    @GameTest(maxTicks = 600)
    public void aChunkLoaderLoadsOnlyWhereItStands(GameTestHelper ctx)
    {
        withShip(ctx, "chunkloaders", 91, (site, anomalies) ->
        {
            var level = site.level();
            var notes = new ArrayList<String>();
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse("chunkloaders:single_chunk_loader"));
            var problem = ModCompatGameTest.place(level, site.spot(), block.defaultBlockState(), site.bed().player());
            if (problem != null)
                anomalies.put("(placing)", problem);
            var chunk = ChunkPos.containing(site.spot());
            var owner = site.bed().player().getUUID();
            var loader = level.getBlockEntity(site.spot()) instanceof com.supermartijn642.chunkloaders.ChunkLoaderBlockEntity entity ? entity : null;
            notes.add("loader block entity: " + (loader == null ? "missing" : "owner " + (loader.hasOwner() ? loader.getOwner() : "none")));
            // A loader only holds its chunk for an owner who is active, as a player in the world is.
            notes.add("owner made active: " + ChunkLoadingCapability.get(level).castServer().togglePlayerActivity(owner, true));
            // The player picks the chunks to hold in the loader's screen; this is what that screen asks for.
            ChunkLoadingCapability.get(level).castServer().startLoadingChunk(owner, chunk);
            ctx.runAfterDelay(20, () ->
            {
                var before = ChunkLoadingCapability.get(level);
                notes.add("owner may load the chunk: " + before.canPlayerLoadChunk(owner, chunk) + "; active loaders " + before.getActivePlayersLoadingChunk(chunk).size() + ", inactive " + before.getInactivePlayersLoadingChunk(chunk).size() + "; chunk is " + chunk);
                notes.add("before: chunk loaded " + before.isChunkLoaded(chunk) + ", " + before.getChunksLoadedByPlayer(owner).size() + " chunk(s) for the owner");
                if (!before.isChunkLoaded(chunk))
                    anomalies.put("(setup)", "the chunk loader did not load its chunk when placed, so there is nothing to check");
                trip(site, anomalies);
                var after = ChunkLoadingCapability.get(level);
                var space = ChunkLoadingCapability.get(site.bed().space());
                notes.add("after: chunk loaded " + after.isChunkLoaded(chunk) + ", " + after.getChunksLoadedByPlayer(owner).size() + " chunk(s) for the owner; space holds " + space.getChunksLoadedByPlayer(owner).size());
                if (!after.isChunkLoaded(chunk))
                    anomalies.put("chunk", "the chunk loader's chunk is no longer loaded after the trip");
                if (after.getChunksLoadedByPlayer(owner).size() != before.getChunksLoadedByPlayer(owner).size())
                    anomalies.put("tickets", "the owner held " + before.getChunksLoadedByPlayer(owner).size() + " chunk(s) before and " + after.getChunksLoadedByPlayer(owner).size() + " after");
                if (!space.getChunksLoadedByPlayer(owner).isEmpty())
                    anomalies.put("space tickets", "the chunk loader left " + space.getChunksLoadedByPlayer(owner).size() + " ticket(s) in space");
                end(ctx, site, notes, anomalies, "chunkloaders");
            });
        });
    }

    /** An elevator controller belongs to exactly one group, and the groups of the world are what they were. */
    @GameTest(maxTicks = 600)
    public void anElevatorControllerKeepsOneGroup(GameTestHelper ctx)
    {
        withShip(ctx, "movingelevators", 92, (site, anomalies) ->
        {
            var level = site.level();
            var notes = new ArrayList<String>();
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse("movingelevators:elevator_block"));
            var problem = ModCompatGameTest.place(level, site.spot(), block.defaultBlockState(), site.bed().player());
            if (problem != null)
                anomalies.put("(placing)", problem);
            ctx.runAfterDelay(30, () ->
            {
                int before = ElevatorGroupCapability.get(level).getGroups().size();
                notes.add("before: " + before + " group(s)");
                if (before == 0)
                    anomalies.put("(setup)", "no group formed when the controller was placed, so there is nothing to check");
                trip(site, anomalies);
                ctx.runAfterDelay(30, () ->
                {
                    int after = ElevatorGroupCapability.get(level).getGroups().size();
                    int inSpace = ElevatorGroupCapability.get(site.bed().space()).getGroups().size();
                    notes.add("after: " + after + " group(s) on the surface, " + inSpace + " in space");
                    if (after != before)
                        anomalies.put("groups", "the surface held " + before + " group(s) before the trip and " + after + " after");
                    if (inSpace != 0)
                        anomalies.put("space groups", "the controller left " + inSpace + " group(s) in space");
                    end(ctx, site, notes, anomalies, "movingelevators");
                });
            });
        });
    }
}
