package com.nullandkale.nullspace.gameplay.habs;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipIntegrity;
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

    /** Registers a built hab, its controller the Station Core in its controller room (or the hall's when it has none). */
    public static CompoundTag register(ServerLevel level, BlockPos origin, HabStructure.Build build, String name)
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
        return register(level, origin, build.plan().blocks(), core != null ? core : build.plan().core(), plan.width(), plan.height(), plan.depth(), name);
    }

    /** Registers a station made of {@code blocks} (plan coordinates, standing with their corner at {@code origin}) whose controller is the Station Core at {@code core}. */
    public static CompoundTag register(ServerLevel level, BlockPos origin, Map<BlockPos, BlockState> blocks, BlockPos core, int width, int height, int depth, String name)
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
        // A large station: its air and systems cover the whole hab, up to 512 by 64 by 512 (Atmosphere.Grid). Only a hab taller than 64 blocks (none is
        // planned) is covered from the window of that height round its Station Core.
        var coreAt = origin.offset(core);
        int wy = window(origin.getY(), height, coreAt.getY(), Atmosphere.MAX_HEIGHT);
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
        save(server, id, own);
        store.ships().add(s);
        long started = System.nanoTime();
        // What a station does when its build finishes: its systems are made, its batteries given a charge and its requests set; the fuel is the player's.
        SystemsUpgrade.upgrade(level, s);
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
