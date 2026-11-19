package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipIntegrity;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.SealScan;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.worldgen.stations.HabStructure;
import com.nullandkale.nullspace.worldgen.stations.StationPlan.Use;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A placed hab or gallery station as a registered station vessel, so that its Station Core is the real thing: it shows the hab's power, fuel, air,
 * leaks and doors, opens the systems console, and the hab's machines run on the ship systems like a station's. The vessel is registered the way a
 * station that has just been built is (see {@code StationBuilds}), except that it is not given free fuel.
 *
 * <p>It is a large station: its air grid is the hab's whole footprint (up to 512 by 512), kept compressed in the save.
 *
 * <p>A vessel's blocks are found by flooding outward from its controller through every solid block in its bounds. A hab is buried, so that would take
 * in all the rock round it; a hab vessel is therefore told which blocks are its own, saved beside the world so that it survives a restart
 * ({@link #discover}).
 */
public final class HabVessels
{
    private static final Map<String, long[]> OWN = new ConcurrentHashMap<>();
    /** The writes of hab block lists that were handed to a worker, until they are done (see {@link #saved}). */
    private static final Map<String, java.util.concurrent.CompletableFuture<Void>> SAVING = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(task ->
    {
        var thread = new Thread(task, "nullspace-hab-save");
        thread.setDaemon(true);
        return thread;
    });

    /** True for a vessel that is a placed hab (or a gallery station) and knows its own blocks. */
    public static boolean is(CompoundTag ship)
    {
        return ship.getBooleanOr("hab", false);
    }

    /** The id of the hab vessel standing with its plan corner at {@code origin}. */
    public static String idAt(ServerLevel level, BlockPos origin)
    {
        return UUID.nameUUIDFromBytes(("nullspace:hab:" + level.dimension().identifier() + ":" + origin.asLong()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Where a hab's vessel stands: the low corner and size of its air grid, and the box that holds its blocks. */
    public record Frame(BlockPos base, Atmosphere.Grid grid, HullScan.Bounds bounds)
    {}

    /** The Station Core of a built hab, in its controller room (or the hall's when it has none), in plan coordinates. */
    private static BlockPos core(HabStructure.Build build)
    {
        var plan = build.hab().plan();
        BlockPos core = null;
        for (var room : plan.rooms(Use.CONTROLLER_ROOM))
        {
            var i = room.interior();
            for (var e : build.plan().blocks().entrySet())
            {
                var at = e.getKey();
                if (e.getValue().is(ModBlocks.STATION_CORE) && at.getX() >= i.x0() && at.getX() <= i.x1() && at.getY() >= i.y0() && at.getY() <= i.y1() && at.getZ() >= i.z0() && at.getZ() <= i.z1())
                    core = at;
            }
        }
        return core != null ? core : build.plan().core();
    }

    /** The frame the vessel of a hab built with its plan corner at {@code origin} will have, known before it is registered. */
    public static Frame frame(BlockPos origin, HabStructure.Build build)
    {
        var plan = build.hab().plan();
        return frame(origin, core(build), plan.width(), plan.height(), plan.depth());
    }

    private static Frame frame(BlockPos origin, BlockPos core, int width, int height, int depth)
    {
        // A large station: its air and systems cover the whole hab, up to 512 by 64 by 512 (Atmosphere.Grid). Only a hab taller than 64 blocks (none is
        // planned) is covered from the window of that height round its Station Core.
        int wy = window(origin.getY(), height, origin.offset(core).getY(), Atmosphere.MAX_HEIGHT);
        var base = new BlockPos(origin.getX(), wy, origin.getZ());
        int vesselHeight = Math.clamp(height, 32, Atmosphere.MAX_HEIGHT);
        return new Frame(base, new Atmosphere.Grid(width, vesselHeight, depth), new HullScan.Bounds(base, base.offset(width - 1, vesselHeight - 1, depth - 1)));
    }

    /** Registers a built hab, its controller the Station Core in its controller room (or the hall's when it has none). */
    public static CompoundTag register(ServerLevel level, BlockPos origin, HabStructure.Build build, String name)
    {
        var plan = build.hab().plan();
        return register(level, origin, build.plan().blocks(), core(build), plan.width(), plan.height(), plan.depth(), name, false);
    }

    /**
     * Registers a built hab like {@link #register}, but hands the writing of its block list to a worker, since compressing hundreds of thousands of blocks is no
     * work for a tick. The hab is not safe against a crash until {@link #saved} says so: the block list is what finds its blocks again after a restart.
     */
    public static CompoundTag registerDeferred(ServerLevel level, BlockPos origin, HabStructure.Build build, String name)
    {
        var plan = build.hab().plan();
        return register(level, origin, build.plan().blocks(), core(build), plan.width(), plan.height(), plan.depth(), name, true);
    }

    /** True once the block list of the hab registered with {@link #registerDeferred} is on disk; throws what stopped it, if it failed. */
    public static boolean saved(String id)
    {
        var writing = SAVING.get(id);
        if (writing == null)
            return true;
        if (!writing.isDone())
            return false;
        SAVING.remove(id);
        writing.join();
        return true;
    }

    /** Registers a station made of {@code blocks} (plan coordinates, standing with their corner at {@code origin}) whose controller is the Station Core at {@code core}. */
    public static CompoundTag register(ServerLevel level, BlockPos origin, Map<BlockPos, BlockState> blocks, BlockPos core, int width, int height, int depth, String name)
    {
        return register(level, origin, blocks, core, width, height, depth, name, false);
    }

    private static CompoundTag register(ServerLevel level, BlockPos origin, Map<BlockPos, BlockState> blocks, BlockPos core, int width, int height, int depth, String name, boolean deferSave)
    {
        var server = level.getServer();
        var store = WorldStore.get(server);
        String id = idAt(level, origin);
        forget(server, id);
        var s = new CompoundTag();
        s.putString("id", id);
        s.putString("kind", "station");
        s.putString("home", name);
        s.putString("owner", "");
        s.putString("dimension", level.dimension().identifier().toString());
        s.putString("localSpace", "hab:" + id);
        s.putString("access", "public");
        // Anyone may use and edit it, as with the derelict stations.
        s.putBoolean("salvage", true);
        s.putBoolean("hab", true);
        var coreAt = origin.offset(core);
        int wy = frame(origin, core, width, height, depth).base().getY();
        var base = new BlockPos(origin.getX(), wy, origin.getZ());
        s.putLong("base", base.asLong());
        s.putLong("controller", coreAt.asLong());
        s.putInt("width", width);
        s.putInt("depth", depth);
        s.putInt("height", Math.clamp(height, 32, Atmosphere.MAX_HEIGHT));
        s.putDouble("simX", 0);
        s.putDouble("simY", 0);
        s.putDouble("simZ", 0);
        s.put("crew", new ListTag());
        s.putBoolean("foundation", false);
        s.putInt("stationVersion", 1);
        s.putBoolean("buildPaid", true);
        s.putBoolean("stationInitialized", true);
        s.putBoolean("generatedRooms", true);
        var list = new java.util.ArrayList<Long>();
        for (var e : blocks.entrySet())
        {
            var at = origin.offset(e.getKey());
            if (!e.getValue().isAir() && at.getY() >= wy && at.getY() < wy + Atmosphere.MAX_HEIGHT)
                list.add(at.asLong());
        }
        var own = list.stream().mapToLong(Long::longValue).toArray();
        OWN.put(id, own);
        if (deferSave)
            SAVING.put(id, java.util.concurrent.CompletableFuture.runAsync(() -> save(server, id, own), IO));
        else
            save(server, id, own);
        store.ships().add(s);
        long started = System.nanoTime();
        // What a station does when its build finishes: its systems are made, its batteries given a charge and its requests set; the fuel is the player's.
        try
        {
            SystemsUpgrade.upgrade(level, s);
        }
        finally
        {
            // A scan kept for this hab is taken by its first rebuild; one it did not take is not left holding the hab's seals.
            SealScan.discard(id);
        }
        for (var device : s.getCompoundOrEmpty("systems").getListOrEmpty("devices"))
        {
            var d = (CompoundTag) device;
            if (d.getStringOr("type", "").equals("nullspace:battery"))
                d.putInt("energy", 1200);
        }
        s.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, com.nullandkale.nullspace.model.air.SpatialAir.SUPPLY_POWER, 1, 0, 2, 0});
        ShipIntegrity.stamp(level, coreAt, id);
        store.requestSave();
        NullSpace.LOGGER.info("HAB VESSEL {} x {} x {} registered in {} ms", width, s.getIntOr("height", 32), depth, (System.nanoTime() - started) / 1_000_000);
        return s;
    }

    /** The low corner of a window {@code size} long that holds {@code around}, inside the span of {@code length} that starts at {@code start}. */
    private static int window(int start, int length, int around, int size)
    {
        if (length <= size)
            return start;
        return Math.max(start, Math.min(start + length - size, around - size / 2));
    }

    /** The hab vessel's blocks that are still there: the hab's own, found from the list kept for it, never the rock round it. */
    public static Set<BlockPos> discover(ServerLevel level, CompoundTag ship)
    {
        var own = OWN.computeIfAbsent(ship.getStringOr("id", ""), id -> load(level.getServer(), id));
        // (Not Set.copyOf: an immutable set probes linearly, and block positions hash so badly that building one of a hab's two hundred thousand blocks takes half a minute.)
        var found = new HashSet<BlockPos>(own.length * 2);
        for (long packed : own)
        {
            var at = BlockPos.of(packed);
            if (level.hasChunkAt(at) && !level.getBlockState(at).isAir())
                found.add(at);
        }
        return java.util.Collections.unmodifiableSet(found);
    }

    /** The positions of a hab vessel's own blocks: those in loaded chunks that are still there, and the rest as they were saved. */
    public static it.unimi.dsi.fastutil.longs.LongOpenHashSet members(ServerLevel level, CompoundTag ship)
    {
        var own = OWN.computeIfAbsent(ship.getStringOr("id", ""), id -> load(level.getServer(), id));
        var set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(own.length * 2);
        var at = new BlockPos.MutableBlockPos();
        for (long packed : own)
        {
            at.set(packed);
            if (!level.hasChunkAt(at) || !level.getBlockState(at).isAir())
                set.add(packed);
        }
        return set;
    }

    /** A set of block positions over a set of packed ones, without copying them: a hab has hundreds of thousands. */
    public static Set<BlockPos> view(it.unimi.dsi.fastutil.longs.LongOpenHashSet packed)
    {
        return new java.util.AbstractSet<>()
        {
            @Override
            public boolean contains(Object o)
            {
                return o instanceof BlockPos p && packed.contains(p.asLong());
            }

            @Override
            public int size()
            {
                return packed.size();
            }

            @Override
            public java.util.Iterator<BlockPos> iterator()
            {
                var it = packed.iterator();
                return new java.util.Iterator<>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return it.hasNext();
                    }

                    @Override
                    public BlockPos next()
                    {
                        return BlockPos.of(it.nextLong());
                    }
                };
            }
        };
    }

    /** Takes a hab vessel out of the world's records (an older one in the same place, when a gallery is rebuilt). */
    private static void forget(MinecraftServer server, String id)
    {
        var ships = WorldStore.get(server).ships();
        for (int i = ships.size() - 1; i >= 0; i--)
            if (ships.get(i) instanceof CompoundTag t && t.getStringOr("id", "").equals(id))
                ships.remove(i);
        OWN.remove(id);
    }

    private static Path file(MinecraftServer server, String id)
    {
        return server.getWorldPath(LevelResource.ROOT).resolve("nullspace").resolve("habs").resolve(id + ".bin");
    }

    private static void save(MinecraftServer server, String id, long[] own)
    {
        var path = file(server, id);
        try
        {
            Files.createDirectories(path.getParent());
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path))))
            {
                out.writeInt(own.length);
                for (long packed : own)
                    out.writeLong(packed);
            }
        }
        catch (IOException failure)
        {
            NullSpace.LOGGER.error("Could not save the blocks of hab {}", id, failure);
        }
    }

    private static long[] load(MinecraftServer server, String id)
    {
        var path = file(server, id);
        if (!Files.exists(path))
            return new long[0];
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(path))))
        {
            var own = new long[in.readInt()];
            for (int i = 0; i < own.length; i++)
                own[i] = in.readLong();
            return own;
        }
        catch (IOException failure)
        {
            NullSpace.LOGGER.error("Could not read the blocks of hab {}", id, failure);
            return new long[0];
        }
    }

    private HabVessels()
    {
    }
}
