package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.shipyard.RoomEntities;
import com.nullandkale.nullspace.gameplay.systems.SealScan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.HabStructure;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Natural surface dungeons. Existing inhabited terrain is never retrofitted. A dungeon is a fixed set of blocks at fixed places, so building it is idempotent:
 * there is no journal and no save of its own. Progress is a count of finished chunks kept in the world store, which is saved together with the chunks, so after
 * a crash both come back from the same moment and the build goes on from there, setting a few blocks again that are already right.
 */
public final class NaturalHabs
{
    public static final String SECTION = "naturalHabs";
    private static final int VERSION = 1, MAX_CELLS_PER_TICK = 4096, RESUME_REWIND = 8, MAX_TERRAIN_FILL = 8;
    /** How long a tick may spend placing blocks, after its first 64: a few milliseconds, so that a build never takes a tick over its 50. */
    private static final long EDIT_NANOS = 6_000_000L;
    /** How long a tick may spend finding the seals of a finished dungeon (see SealScan); a large one takes some seconds in all. */
    private static final long SCAN_NANOS = 5_000_000L;
    private static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.parse("nullspace:hab_generation"), new TicketType(1200, TicketType.FLAG_LOADING));
    private static final ThreadLocal<Boolean> PLACING = ThreadLocal.withInitial(() -> false);
    private static final ServerContext.Key<State> KEY = new ServerContext.Key<>();

    private static final class State
    {
        Active active;
        long retry;
    }

    private static State state(MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, State::new);
    }

    private static CompoundTag records(MinecraftServer server)
    {
        return WorldStore.get(server).section(SECTION);
    }

    /** Records one exploration candidate. The ordinary terrain tick does the planning and construction. */
    public static CompoundTag consider(ServerLevel level, int cellX, int cellZ)
    {
        var map = records(level.getServer());
        String key = level.dimension().identifier() + "/" + cellX + "/" + cellZ;
        if (!map.contains(key))
        {
            NullSpace.LOGGER.info("Natural hab candidate in {} region {}, {}", level.dimension().identifier(), cellX, cellZ);
            var site = HabSites.site(level.getSeed(), cellX, cellZ);
            var record = new CompoundTag();
            record.putInt("version", VERSION);
            record.putInt("cellX", cellX);
            record.putInt("cellZ", cellZ);
            record.putString("dimension", level.dimension().identifier().toString());
            record.putString("state", "planned");
            record.putInt("x", site.x());
            record.putInt("z", site.z());
            record.put("spec", site.spec().save());
            map.put(key, record);
            WorldStore.get(level.getServer()).requestSave();
        }
        return map.getCompoundOrEmpty(key);
    }

    /** Drops the build in progress as a restart would, keeping only what the world and its store hold; the next terrain tick takes it up again. For tests. */
    public static void interrupt(MinecraftServer server)
    {
        var runtime = state(server);
        if (runtime.active != null)
        {
            runtime.active.release();
            runtime.active = null;
        }
    }

    private static boolean surface(ServerLevel level)
    {
        return PlanetSurfaces.address(level) != null && !(level.getChunkSource().getGenerator() instanceof FlatLevelSource);
    }

    private static void discover(MinecraftServer server)
    {
        if (!Boolean.parseBoolean(System.getProperty("nullspace.habs.enabled", "true")) || !server.getWorldGenSettings().options().generateStructures())
            return;
        for (var player : server.getPlayerList().getPlayers())
        {
            var level = player.level();
            if (!surface(level))
                continue;
            int cx = Math.floorDiv(player.blockPosition().getX(), HabSites.SPACING), cz = Math.floorDiv(player.blockPosition().getZ(), HabSites.SPACING);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                {
                    var site = HabSites.site(level.getSeed(), cx + dx, cz + dz);
                    double distance = Math.hypot(site.x() - player.getX(), site.z() - player.getZ());
                    if (distance < 384 || distance > 1280)
                        continue;
                    String key = level.dimension().identifier() + "/" + (cx + dx) + "/" + (cz + dz);
                    if (!records(server).contains(key))
                    {
                        consider(level, cx + dx, cz + dz);
                        return;
                    }
                }
        }
    }

    public static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds)
    {
        if (PLACING.get())
            return false;
        var store = WorldStore.existing(level.getServer());
        if (store == null)
            return false;
        var map = store.section(SECTION);
        for (var key : map.keySet())
        {
            var record = map.getCompoundOrEmpty(key);
            String status = record.getStringOr("state", "");
            if (status.equals("building") && record.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && new HullScan.Bounds(WorldStore.pos(record, "min"), WorldStore.pos(record, "max")).box().intersects(bounds.box()))
                return true;
        }
        return false;
    }

    private static <T> T placing(Supplier<T> action)
    {
        boolean previous = PLACING.get();
        PLACING.set(true);
        try
        {
            return action.get();
        }
        finally
        {
            PLACING.set(previous);
        }
    }

    public static void initialize()
    {
        ServerContext.tick(ServerContext.Phase.TERRAIN, NaturalHabs::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
        {
            var runtime = ServerContext.get(server).existing(KEY);
            if (runtime != null && runtime.active != null)
                runtime.active.release();
        });
    }

    private static void tick(MinecraftServer server)
    {
        var runtime = state(server);
        if (server.getTickCount() < runtime.retry)
            return;
        try
        {
            if (runtime.active == null)
            {
                if (server.getTickCount() % 20 != 0)
                    return;
                var map = records(server);
                for (var key : new TreeSet<>(map.keySet()))
                {
                    var record = map.getCompoundOrEmpty(key);
                    if (record.getIntOr("version", 0) == VERSION && Set.of("planned", "building").contains(record.getStringOr("state", "")))
                    {
                        runtime.active = new Active(server, key, record);
                        NullSpace.LOGGER.info("Planning natural hab {}", key);
                        break;
                    }
                }
                if (runtime.active == null)
                {
                    discover(server);
                    return;
                }
            }
            if (runtime.active.step())
            {
                runtime.active.release();
                runtime.active = null;
            }
        }
        catch (RuntimeException failure)
        {
            NullSpace.LOGGER.error("Natural hab generation paused; its saved construction will retry", failure);
            if (runtime.active != null)
            {
                runtime.active.release();
                runtime.active = null;
            }
            runtime.retry = server.getTickCount() + 1200;
        }
    }

    private static final class Active
    {
        final MinecraftServer server;
        final ServerLevel level;
        final String key;
        final StationSpec spec;
        final CompletableFuture<HabStructure.Build> planning;
        final Set<ChunkPos> held = new LinkedHashSet<>();
        HabStructure.Build build;
        BlockPos origin;
        HullScan.Bounds bounds;
        String id;
        List<Map.Entry<ChunkPos, LinkedHashMap<BlockPos, BlockState>>> chunks;
        int checked;
        SealScan seals;
        /** The chunk being built and the blocks of it still to set; the chunk before it are done. */
        int cursor = -1;
        Iterator<Map.Entry<BlockPos, BlockState>> edits;
        boolean finishing;
        /** The stone that grounds the rooms has been worked out for the chunks still to build. */
        boolean covered;
        /** The plan turned into blocks, worked out on a worker (see compute). */
        CompletableFuture<Prepared> preparing;
        /** The commissioning step, a piece a tick: which piece, and the ids its entities are given in order. */
        int finishPhase;
        List<UUID> finishIds;
        final int[] finishIndex = {0};

        record Prepared(boolean ok, BlockPos origin, HullScan.Bounds bounds, List<Map.Entry<ChunkPos, LinkedHashMap<BlockPos, BlockState>>> chunks, String id)
        {
            static final Prepared FAILED = new Prepared(false, null, null, null, null);
        }

        Active(MinecraftServer server, String key, CompoundTag record)
        {
            this.server = server;
            this.key = key;
            level = WorldStore.level(server, record.getStringOr("dimension", ""));
            spec = StationSpec.read(record.getCompoundOrEmpty("spec"));
            planning = CompletableFuture.supplyAsync(() -> HabStructure.build(HabPlanner.generate(spec)));
        }

        CompoundTag record()
        {
            return records(server).getCompoundOrEmpty(key);
        }

        void release()
        {
            planning.cancel(true);
            releaseTickets();
        }

        void releaseTickets()
        {
            for (var chunk : held)
                level.getChunkSource().removeTicketWithRadius(TICKET, chunk, 0);
            held.clear();
        }

        boolean skip(String why)
        {
            NullSpace.LOGGER.info("Skipping natural hab {}: {}", key, why);
            var record = record();
            int attempt = record.getIntOr("attempt", 0) + 1;
            if (record.getStringOr("state", "").equals("planned") && attempt < HabSites.ATTEMPTS)
            {
                var next = HabSites.site(level.getSeed(), record.getIntOr("cellX", 0), record.getIntOr("cellZ", 0), attempt);
                record.putInt("attempt", attempt);
                record.putInt("x", next.x());
                record.putInt("z", next.z());
                for (var field : List.of("origin", "min", "max", "id"))
                    record.remove(field);
                releaseTickets();
                build = null;
                chunks = null;
                preparing = null;
                checked = 0;
                WorldStore.get(server).requestSave();
                return false;
            }
            record().putString("state", "skipped");
            record().putString("reason", why);
            WorldStore.get(server).requestSave();
            return true;
        }

        int height(int x, int z, Heightmap.Types type)
        {
            var source = level.getChunkSource();
            return source.getGenerator().getBaseHeight(x, z, type, level, source.randomState()) - 1;
        }

        /** Starts working out, on a worker, where the hab stands and every block it sets: a map of hundreds of thousands of blocks is no work for a tick. */
        CompletableFuture<Prepared> startPrepare()
        {
            var record = record();
            int x = record.getIntOr("x", 0) - build.hab().plan().width() / 2;
            int z = record.getIntOr("z", 0) - build.hab().plan().depth() / 2;
            BlockPos saved = record.contains("origin") ? WorldStore.pos(record, "origin") : null;
            var planned = build;
            return CompletableFuture.supplyAsync(() -> compute(planned, x, z, saved));
        }

        Prepared compute(HabStructure.Build build, int x, int z, BlockPos saved)
        {
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            var entrance = build.hab().entry().outer();
            for (int dx : new int[]{entrance.x0(), entrance.x1()})
                for (int dz : new int[]{entrance.z0(), entrance.z1()})
                {
                    int floor = height(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG);
                    if (height(x + dx, z + dz, Heightmap.Types.WORLD_SURFACE_WG) > floor)
                        return Prepared.FAILED;
                    low = Math.min(low, floor);
                    high = Math.max(high, floor);
                }
            if (high - low > 6)
                return Prepared.FAILED;
            var origin = saved != null ? saved : new BlockPos(x, high - build.hab().groundY(), z);
            var all = new LinkedHashMap<BlockPos, BlockState>();
            for (var at : build.carve())
                all.put(origin.offset(at), Blocks.AIR.defaultBlockState());
            build.plan().blocks().forEach((at, block) -> all.put(origin.offset(at), block));
            // Ground just the surface pavilion, without flattening the whole dungeon's footprint.
            int ground = origin.getY() + build.hab().groundY();
            for (int dx = entrance.x0() - 2; dx <= entrance.x1() + 2; dx++)
                for (int dz = entrance.z0() - 2; dz <= entrance.z1() + 2; dz++)
                {
                    int floor = height(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG);
                    for (int y = floor; y <= ground; y++)
                        all.putIfAbsent(new BlockPos(x + dx, y, z + dz), Blocks.STONE.defaultBlockState());
                    if (dx < entrance.x0() || dx > entrance.x1() || dz < entrance.z0() || dz > entrance.z1())
                        for (int y = ground + 1; y <= ground + 3; y++)
                            all.putIfAbsent(new BlockPos(x + dx, y, z + dz), Blocks.AIR.defaultBlockState());
                }
            var min = new BlockPos(all.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow(), all.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow(), all.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow());
            var max = new BlockPos(all.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow(), all.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow(), all.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow());
            if (min.getY() < level.getMinY() || max.getY() > level.getMaxY() || !level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max))
                return Prepared.FAILED;
            var byChunk = new TreeMap<ChunkPos, LinkedHashMap<BlockPos, BlockState>>(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
            all.forEach((at, block) -> byChunk.computeIfAbsent(ChunkPos.containing(at), ignored -> new LinkedHashMap<>()).put(at, block));
            return new Prepared(true, origin, new HullScan.Bounds(min, max), new ArrayList<>(byChunk.entrySet()), HabVessels.idAt(level, origin));
        }

        /** Takes the worker's result: the hab's place, and what it will set, chunk by chunk. False when the site does not suit it. */
        boolean prepare()
        {
            var prepared = preparing.join();
            if (!prepared.ok())
                return false;
            origin = prepared.origin();
            bounds = prepared.bounds();
            chunks = prepared.chunks();
            id = prepared.id();
            var record = record();
            record.putLong("origin", origin.asLong());
            record.putLong("min", bounds.min().asLong());
            record.putLong("max", bounds.max().asLong());
            record.putString("id", id);
            return true;
        }

        boolean step()
        {
            if (build == null)
            {
                if (!planning.isDone())
                    return false;
                build = planning.join();
                preparing = startPrepare();
            }
            if (chunks == null)
            {
                if (!preparing.isDone())
                    return false;
                if (!prepare())
                    return skip("No dry, gentle surface with room below it");
            }
            if (server.getTickCount() % 200 == 0)
                for (var chunk : held)
                    level.getChunkSource().addTicketWithRadius(TICKET, chunk, 0);
            if (checked < chunks.size())
            {
                var pos = chunks.get(checked++).getKey();
                held.add(pos);
                level.getChunkSource().addTicketWithRadius(TICKET, pos, 0);
                var chunk = level.getChunk(pos.x(), pos.z());
                if (record().getStringOr("state", "").equals("planned") && chunk.getInhabitedTime() > 0)
                    return skip("Existing inhabited terrain");
                return false;
            }
            if (record().getStringOr("state", "").equals("planned"))
            {
                for (var pos : held)
                {
                    var chunk = level.getChunk(pos.x(), pos.z());
                    if (chunk.getInhabitedTime() > 0)
                        return skip("Terrain became inhabited before construction");
                }
                if (com.nullandkale.nullspace.gameplay.travel.TransferLocks.areaBusy(level, bounds))
                    return skip("Another construction or transfer reserves this site");
                String emerges = cover(0, true);
                if (emerges != null)
                    return skip(emerges);
                covered = true;
                if (WorldStore.get(server).ships().stream().map(t -> (CompoundTag) t).anyMatch(s -> s.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && WorldStore.bounds(s).box().intersects(bounds.box())) || PadRegistry.read(server).stream().anyMatch(p -> p.dimension().equals(level.dimension().identifier().toString()) && p.bounds().minX() <= bounds.max().getX() && p.bounds().maxX() >= bounds.min().getX() && p.bounds().minZ() <= bounds.max().getZ() && p.bounds().maxZ() >= bounds.min().getZ()))
                    return skip("An existing vessel or pad reserves this site");
                record().putString("state", "building");
                WorldStore.get(server).requestSave();
            }
            else if (!covered)
            {
                // A build taken up after a restart: the stone that grounds the rooms in the terrain is not saved, so it is worked out again for the chunks
                // still to build, over terrain that is as it was. Chunks already built keep theirs, since setting their blocks again never touches it.
                cover(record().getIntOr("next", 0), false);
                covered = true;
            }
            if (finishing)
                return true;
            int next = record().getIntOr("next", 0);
            if (next >= chunks.size())
            {
                // The dungeon's air is found a few milliseconds a tick, before it is registered: registering a large one used to stop the server for ten seconds.
                if (seals == null)
                {
                    var frame = HabVessels.frame(origin, build);
                    seals = new SealScan(level, frame.base(), frame.grid(), frame.bounds());
                }
                if (!seals.step(SCAN_NANOS))
                    return false;
                for (var pos : held)
                    if (!HullEntities.loaded(level, new HullScan.Bounds(new BlockPos(pos.x() * 16, bounds.min().getY(), pos.z() * 16), new BlockPos(pos.x() * 16 + 15, bounds.max().getY(), pos.z() * 16 + 15))))
                        return false;
                if (!finishStep())
                    return false;
                finishing = true;
                return false;
            }
            place(next);
            return false;
        }

        /** True when the world holds, at {@code at}, the block the dungeon sets there: the build has already set it. */
        boolean placed(Map<BlockPos, BlockState> edits, BlockPos at)
        {
            var planned = edits.get(at);
            return planned != null && level.getBlockState(at).equals(planned);
        }

        /**
         * Grounds the dungeon in the terrain over it, for the chunks from {@code from} on: every room must be underground, not just the surface hall's doorway,
         * and shallow terrain cover hides the roofs on gentle slopes (there is no whole-site platform). Returns why the site does not suit, or null; with
         * {@code enforce} false (a build taken up again) nothing is refused.
         */
        String cover(int from, boolean enforce)
        {
            var entrance = build.hab().entry().outer();
            for (int index = Math.min(from, chunks.size()); index < chunks.size(); index++)
            {
                var piece = chunks.get(index);
                // Only the chunk a build stopped in can be part built; every later one stands on the terrain as it was, where the dungeon's own blocks may pass
                // through the ground without having been set.
                boolean partial = !enforce && index == from;
                var ceilings = new HashMap<Long, BlockPos>();
                for (var at : piece.getValue().keySet())
                {
                    int dx = at.getX() - origin.getX(), dz = at.getZ() - origin.getZ();
                    if (dx >= entrance.x0() - 2 && dx <= entrance.x1() + 2 && dz >= entrance.z0() - 2 && dz <= entrance.z1() + 2)
                        continue;
                    long column = new BlockPos(at.getX(), 0, at.getZ()).asLong();
                    ceilings.merge(column, at, (a, b) -> a.getY() > b.getY() ? a : b);
                }
                for (var top : ceilings.values())
                {
                    int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR, top.getX(), top.getZ()) - 1;
                    // The chunk a build stopped in: where the block on top is the one the dungeon sets there, the dungeon set it, and the terrain is found from
                    // the generator instead. Natural ground where a wall will pass is not the wall's block, and stays the ground.
                    if (partial && placed(piece.getValue(), new BlockPos(top.getX(), ground, top.getZ())))
                        ground = height(top.getX(), top.getZ(), Heightmap.Types.OCEAN_FLOOR_WG);
                    if (enforce && top.getY() - ground > MAX_TERRAIN_FILL)
                        return "The underground rooms would emerge through the terrain at " + top + " above " + ground;
                    if (level.getBlockState(top.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                        piece.getValue().putIfAbsent(top.above(), Blocks.STONE.defaultBlockState());
                    if (top.getY() < ground)
                        continue;
                    var cover = level.getBlockState(new BlockPos(top.getX(), ground, top.getZ()));
                    if (partial && placed(piece.getValue(), new BlockPos(top.getX(), ground, top.getZ())) || cover.isAir() || cover.hasBlockEntity() || !cover.getFluidState().isEmpty() || cover.getBlock() instanceof net.minecraft.world.level.block.FallingBlock || cover.is(net.minecraft.tags.BlockTags.LEAVES))
                        cover = Blocks.STONE.defaultBlockState();
                    // Shallow terrain cover hides roofs on gentle slopes; there is no whole-site platform.
                    for (int y = ground; y <= top.getY(); y++)
                        piece.getValue().putIfAbsent(top.atY(y), Blocks.STONE.defaultBlockState());
                    piece.getValue().put(top.above(), cover);
                }
            }
            return null;
        }

        /**
         * Sets blocks for a few milliseconds a tick, chunk by chunk, and keeps count of the chunks that are done. Setting a block that is already right changes
         * nothing, so a build that stopped is taken up a little before where it got to and nothing else need be remembered.
         */
        void place(int next)
        {
            if (cursor < 0)
                cursor = Math.max(0, next - RESUME_REWIND);
            placing(() ->
            {
                long deadline = System.nanoTime() + EDIT_NANOS;
                int placed = 0;
                while (cursor < chunks.size() && placed < MAX_CELLS_PER_TICK)
                {
                    if (edits == null)
                        edits = chunks.get(cursor).getValue().entrySet().iterator();
                    while (edits.hasNext() && placed < MAX_CELLS_PER_TICK)
                    {
                        var edit = edits.next();
                        if (!level.setBlock(edit.getKey(), edit.getValue(), 18) && !level.getBlockState(edit.getKey()).equals(edit.getValue()))
                            throw new IllegalStateException("Cannot place natural hab at " + edit.getKey());
                        placed++;
                        if ((placed & 63) == 0 && System.nanoTime() >= deadline)
                            break;
                    }
                    if (edits.hasNext())
                        break;
                    edits = null;
                    record().putInt("next", ++cursor);
                    WorldStore.get(server).requestSave();
                    if (placed >= 64 && System.nanoTime() >= deadline)
                        break;
                }
                return null;
            });
        }

        /**
         * Commissions the finished dungeon a piece a tick, so that none is a stall of a third of a second: its entities, then its stock and doors, its tree and
         * crew, and last its registration. Each piece is idempotent (entities get the same ids, a vessel is registered over its old record), so one that a
         * crash cut short is simply done again. True when done.
         */
        boolean finishStep()
        {
            switch (finishPhase)
            {
                case 0 ->
                {
                    var crew = HabDepth.plan(build.hab(), origin, spec.seed());
                    int count = build.plan().points().size() + crew.size();
                    finishIds = new ArrayList<>();
                    for (int i = 0; i < count; i++)
                        finishIds.add(UUID.nameUUIDFromBytes((id + "/entity/" + i).getBytes(StandardCharsets.UTF_8)));
                    finishIndex[0] = 0;
                    finishPhase = 1;
                }
                case 1 ->
                {
                    placing(() ->
                    {
                        RoomEntities.spawn(level, origin, build.plan().points(), identity());
                        return null;
                    });
                    finishPhase = 2;
                }
                case 2 ->
                {
                    placing(() ->
                    {
                        HabDepth.stock(level, origin, build.hab(), build.plan().points(), spec.seed(), "Hab supplies");
                        HabDoors.link(level, origin, build);
                        return null;
                    });
                    finishPhase = 3;
                }
                case 3 ->
                {
                    placing(() ->
                    {
                        HabDepth.seedTree(level, origin, build, spec.seed());
                        HabDepth.populate(level, origin, build.hab(), spec.seed(), identity());
                        return null;
                    });
                    finishPhase = 4;
                }
                case 4 ->
                {
                    NullSpace.LOGGER.info("Natural hab {} seals found in {} ms of server time, spread over its last build ticks", key, seals.nanos() / 1_000_000);
                    seals.keep(id);
                    placing(() ->
                    {
                        var vessel = HabVessels.registerDeferred(level, origin, build, spec.maker().name().toLowerCase(Locale.ROOT) + " Hab");
                        vessel.putBoolean("worldGenerated", true);
                        String address = PlanetSurfaces.address(level);
                        vessel.putString("localSpace", address == null ? LocalSpaces.HOME : address);
                        return null;
                    });
                    finishPhase = 5;
                }
                default ->
                {
                    // Built only once the hab's block list is on disk: a built hab that lost it could not find its blocks again after a restart.
                    if (HabVessels.saved(id))
                    {
                        record().putString("state", "built");
                        WorldStore.get(server).requestSave();
                        NullSpace.LOGGER.info("Natural hab placed in {} at {} ({})", level.dimension().identifier(), origin, spec.description());
                        finishPhase = 6;
                    }
                }
            }
            return finishPhase == 6;
        }

        /** Gives each entity its next id, in the order the hab makes them: the same ids on every run, whichever tick makes them. */
        java.util.function.Consumer<net.minecraft.world.entity.Entity> identity()
        {
            return entity -> entity.setUUID(finishIds.get(finishIndex[0]++));
        }
    }

    private NaturalHabs()
    {
    }
}
