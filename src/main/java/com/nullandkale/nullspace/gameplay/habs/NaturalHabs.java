package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.shipyard.RoomEntities;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
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

/** Natural surface dungeons. Existing inhabited terrain is never retrofitted; unfinished construction resumes from BUILD journals. */
public final class NaturalHabs
{
    public static final String SECTION = "naturalHabs";
    private static final int VERSION = 1, CELLS_PER_TICK = 2048, CHUNKS_PER_BATCH = 4, MAX_TERRAIN_FILL = 8;
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
            if ((status.equals("building") || status.equals("built") && BuildJournal.pending(level.getServer(), record.getStringOr("id", ""))) && record.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && new HullScan.Bounds(WorldStore.pos(record, "min"), WorldStore.pos(record, "max")).box().intersects(bounds.box()))
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
                if (runtime.active.job != null)
                    placing(() ->
                    {
                        runtime.active.job.abort();
                        return null;
                    });
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
        BuildJournal.Job job;
        Iterator<Map.Entry<BlockPos, BlockState>> edits;
        int batchEnd;
        boolean finishing;

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

        boolean prepare()
        {
            build = planning.join();
            var record = record();
            int x = record.getIntOr("x", 0) - build.hab().plan().width() / 2;
            int z = record.getIntOr("z", 0) - build.hab().plan().depth() / 2;
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            var entrance = build.hab().entry().outer();
            for (int dx : new int[]{entrance.x0(), entrance.x1()})
                for (int dz : new int[]{entrance.z0(), entrance.z1()})
                {
                    int floor = height(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG);
                    if (height(x + dx, z + dz, Heightmap.Types.WORLD_SURFACE_WG) > floor)
                        return false;
                    low = Math.min(low, floor);
                    high = Math.max(high, floor);
                }
            if (high - low > 6)
                return false;
            origin = record.contains("origin") ? WorldStore.pos(record, "origin") : new BlockPos(x, high - build.hab().groundY(), z);
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
            bounds = new HullScan.Bounds(min, max);
            if (min.getY() < level.getMinY() || max.getY() > level.getMaxY() || !level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max))
                return false;
            var byChunk = new TreeMap<ChunkPos, LinkedHashMap<BlockPos, BlockState>>(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
            all.forEach((at, block) -> byChunk.computeIfAbsent(ChunkPos.containing(at), ignored -> new LinkedHashMap<>()).put(at, block));
            chunks = new ArrayList<>(byChunk.entrySet());
            id = HabVessels.idAt(level, origin);
            record.putLong("origin", origin.asLong());
            record.putLong("min", min.asLong());
            record.putLong("max", max.asLong());
            record.putString("id", id);
            return true;
        }

        boolean step()
        {
            if (build == null)
            {
                if (!planning.isDone())
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
                // The whole dungeon must be underground, not just its surface hall's doorway.
                var entrance = build.hab().entry().outer();
                for (var piece : chunks)
                {
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
                        if (top.getY() - ground > MAX_TERRAIN_FILL)
                            return skip("The underground rooms would emerge through the terrain at " + top + " above " + ground);
                        if (level.getBlockState(top.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                            piece.getValue().putIfAbsent(top.above(), Blocks.STONE.defaultBlockState());
                        if (top.getY() < ground)
                            continue;
                        var cover = level.getBlockState(new BlockPos(top.getX(), ground, top.getZ()));
                        if (cover.isAir() || cover.hasBlockEntity() || !cover.getFluidState().isEmpty() || cover.getBlock() instanceof net.minecraft.world.level.block.FallingBlock || cover.is(net.minecraft.tags.BlockTags.LEAVES))
                            cover = Blocks.STONE.defaultBlockState();
                        // Shallow terrain cover hides roofs on gentle slopes; there is no whole-site platform.
                        for (int y = ground; y <= top.getY(); y++)
                            piece.getValue().putIfAbsent(top.atY(y), Blocks.STONE.defaultBlockState());
                        piece.getValue().put(top.above(), cover);
                    }
                }
                if (WorldStore.get(server).ships().stream().map(t -> (CompoundTag) t).anyMatch(s -> s.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && WorldStore.bounds(s).box().intersects(bounds.box())) || PadRegistry.read(server).stream().anyMatch(p -> p.dimension().equals(level.dimension().identifier().toString()) && p.bounds().minX() <= bounds.max().getX() && p.bounds().maxX() >= bounds.min().getX() && p.bounds().minZ() <= bounds.max().getZ() && p.bounds().maxZ() >= bounds.min().getZ()))
                    return skip("An existing vessel or pad reserves this site");
                record().putString("state", "building");
                WorldStore.get(server).requestSave();
            }
            if (BuildJournal.pending(server, id))
                return false;
            if (finishing)
                return true;
            int next = record().getIntOr("next", 0);
            if (next >= chunks.size())
            {
                for (var pos : held)
                    if (!HullEntities.loaded(level, new HullScan.Bounds(new BlockPos(pos.x() * 16, bounds.min().getY(), pos.z() * 16), new BlockPos(pos.x() * 16 + 15, bounds.max().getY(), pos.z() * 16 + 15))))
                        return false;
                finish();
                finishing = true;
                return false;
            }
            placing(() ->
            {
                if (job == null)
                {
                    batchEnd = Math.min(next + CHUNKS_PER_BATCH, chunks.size());
                    var regions = new ArrayList<Collection<BlockPos>>();
                    for (int i = next; i < batchEnd; i++)
                        regions.add(chunks.get(i).getValue().keySet());
                    job = BuildJournal.begin(level, "natural-hab", List.of(id), regions.getFirst());
                    for (int i = 1; i < regions.size(); i++)
                        job.include(level, regions.get(i));
                    var batch = new ArrayList<Map.Entry<BlockPos, BlockState>>();
                    for (int i = next; i < batchEnd; i++)
                        batch.addAll(chunks.get(i).getValue().entrySet());
                    edits = batch.iterator();
                }
                job.step(() ->
                {
                    for (int i = 0; i < CELLS_PER_TICK && edits.hasNext(); i++)
                    {
                        var edit = edits.next();
                        if (!level.setBlock(edit.getKey(), edit.getValue(), 18) && !level.getBlockState(edit.getKey()).equals(edit.getValue()))
                            throw new IllegalStateException("Cannot place natural hab at " + edit.getKey());
                    }
                    if (!edits.hasNext())
                        record().putInt("next", batchEnd);
                    return null;
                });
                if (!edits.hasNext())
                {
                    job.commit();
                    job = null;
                }
                return null;
            });
            return false;
        }

        void finish()
        {
            placing(() ->
            {
                var positions = new LinkedHashSet<BlockPos>();
                for (var entry : build.plan().blocks().entrySet())
                    if (entry.getValue().hasBlockEntity())
                        positions.add(origin.offset(entry.getKey()));
                for (var point : build.plan().points())
                {
                    var at = origin.offset(point.pos());
                    positions.add(at);
                    // A painting may select a neighboring anchor; recovery must load its entity chunk too.
                    if (point.name().equals("painting"))
                        for (var side : Direction.Plane.HORIZONTAL)
                        {
                            var neighbor = at.relative(side, 2);
                            int x = neighbor.getX() - origin.getX(), z = neighbor.getZ() - origin.getZ();
                            if (x >= 0 && x < build.hab().plan().width() && z >= 0 && z < build.hab().plan().depth())
                                positions.add(neighbor);
                        }
                }
                var crew = HabDepth.plan(build.hab(), origin, spec.seed());
                crew.forEach(spawn -> positions.add(spawn.at()));
                var tree = build.plan().points("tree").getFirst().pos();
                for (var cell : com.nullandkale.nullspace.gameplay.trees.TreeShape.fruit())
                    positions.add(origin.offset(tree).offset(cell.dx(), cell.dy(), cell.dz()));
                var regions = new LinkedHashMap<ChunkPos, Collection<BlockPos>>();
                for (var at : positions)
                    regions.computeIfAbsent(ChunkPos.containing(at), ignored -> new ArrayList<>()).add(at);
                var pieces = new ArrayList<>(regions.values());
                job = BuildJournal.begin(level, "commission-natural-hab", List.of(id), pieces.getFirst());
                for (int i = 1; i < pieces.size(); i++)
                    job.include(level, pieces.get(i));
                int count = build.plan().points().size() + crew.size();
                var ids = new ArrayList<UUID>();
                for (int i = 0; i < count; i++)
                    ids.add(UUID.nameUUIDFromBytes((id + "/entity/" + i).getBytes(StandardCharsets.UTF_8)));
                job.captureEntityChanges(ids);
                job.step(() ->
                {
                    int[] index = {0};
                    java.util.function.Consumer<net.minecraft.world.entity.Entity> identity = entity -> entity.setUUID(ids.get(index[0]++));
                    RoomEntities.spawn(level, origin, build.plan().points(), identity);
                    HabDepth.stock(level, origin, build.hab(), build.plan().points(), spec.seed(), "Hab supplies");
                    HabDoors.link(level, origin, build);
                    HabDepth.seedTree(level, origin, build, spec.seed());
                    HabDepth.populate(level, origin, build.hab(), spec.seed(), identity);
                    var vessel = HabVessels.register(level, origin, build, spec.maker().name().toLowerCase(Locale.ROOT) + " Hab");
                    vessel.putBoolean("worldGenerated", true);
                    String address = PlanetSurfaces.address(level);
                    vessel.putString("localSpace", address == null ? LocalSpaces.HOME : address);
                    record().putString("state", "built");
                    WorldStore.get(server).requestSave();
                    return null;
                });
                job.commit();
                job = null;
                NullSpace.LOGGER.info("Natural hab placed in {} at {} ({})", level.dimension().identifier(), origin, spec.description());
                return null;
            });
        }
    }

    private NaturalHabs()
    {
    }
}
