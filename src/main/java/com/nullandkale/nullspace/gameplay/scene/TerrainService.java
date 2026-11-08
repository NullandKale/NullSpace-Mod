package com.nullandkale.nullspace.gameplay.scene;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.model.space.VisualSnapshot;
import com.nullandkale.nullspace.model.space.VoxelVolume;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.*;

/** Independent section subscriptions. Loading tickets never request block/entity ticking. */
public final class TerrainService
{
    public static final int MAX_SECTIONS = 75, MAX_CACHE = 192;
    private static final TicketType TERRAIN_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.parse("nullspace:terrain_camera"), new TicketType(60, TicketType.FLAG_LOADING));

    public record Section(CompoundTag data) implements CustomPacketPayload
    {
        public static final Type<Section> TYPE = new Type<>(Identifier.parse("nullspace:terrain_section"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Section> CODEC = StreamCodec.of((b, p) -> b.writeNbt(p.data), b -> new Section(b.readNbt()));

        @Override
        public Type<Section> type()
        {
            return TYPE;
        }
    }

    private static final class Entry
    {
        final ServerLevel level;
        final BlockPos base;
        final String id;
        boolean dirty = true;
        int used, captured;
        long revision;
        CompoundTag data;

        Entry(ServerLevel l, BlockPos b)
        {
            level = l;
            base = b;
            id = l.dimension().identifier() + "/" + b.asLong();
        }
    }

    private static final class Viewer
    {
        int expires;
        final Set<String> wanted = new HashSet<>();
        final Map<String, Long> sent = new HashMap<>();
    }

    private static final class State
    {
        long revision;
        final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(192, .75f, true);
        final Map<UUID, Viewer> viewers = new HashMap<>();
    }

    private static final ServerContext.Key<State> KEY = new ServerContext.Key<>();

    private static State state(MinecraftServer s)
    {
        return ServerContext.get(s).service(KEY, State::new);
    }

    public static ListTag collect(ServerPlayer player, BlockPos eye, BlockPos origin)
    {
        var level = player.level();
        var state = state(level.getServer());
        var viewer = state.viewers.computeIfAbsent(player.getUUID(), p -> new Viewer());
        int now = level.getServer().getTickCount();
        if (viewer.expires < now)
            viewer.wanted.clear();
        viewer.expires = now + 40;
        var result = new ListTag();
        int sx = Math.floorDiv(eye.getX(), 16), sy = Math.floorDiv(eye.getY(), 16), sz = Math.floorDiv(eye.getZ(), 16);
        for (int y = sy - 1; y <= sy + 1; y++)
            for (int z = sz - 2; z <= sz + 2; z++)
                for (int x = sx - 2; x <= sx + 2; x++)
                {
                    var base = new BlockPos(x * 16, y * 16, z * 16);
                    if (base.getY() < level.getMinY() || base.getY() > level.getMaxY())
                        continue;
                    String id = level.dimension().identifier() + "/" + base.asLong();
                    var e = state.entries.get(id);
                    if (e == null)
                    {
                        if (state.entries.size() >= MAX_CACHE)
                        {
                            var it = state.entries.entrySet().iterator();
                            it.next();
                            it.remove();
                        }
                        e = new Entry(level, base);
                        state.entries.put(id, e);
                    }
                    e.used = now;
                    viewer.wanted.add(id);
                    level.getChunkSource().addTicketWithRadius(TERRAIN_TICKET, new ChunkPos(x, z), 0);
                    var r = new CompoundTag();
                    r.putString("id", id);
                    r.putString("geometryId", id);
                    r.putLong("base", base.asLong());
                    r.putDouble("x", base.getX() - origin.getX());
                    r.putDouble("y", base.getY() - origin.getY());
                    r.putDouble("z", base.getZ() - origin.getZ());
                    r.putInt("size", 16);
                    result.add(r);
                }
        // A placed monitor may collect two nearby cameras; the aggregate remains bounded.
        if (viewer.wanted.size() > 150)
        {
            viewer.wanted.clear();
            for (var t : result)
                viewer.wanted.add(((CompoundTag) t).getStringOr("id", ""));
        }
        return result;
    }

    public static ListTag entities(ServerLevel level, BlockPos origin, BlockPos eye)
    {
        var result = new ListTag();
        var box = new AABB(eye).inflate(48);
        for (var entity : level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> !(e instanceof ServerPlayer) && e.isAlive()))
        {
            if (result.size() >= 64)
                break;
            var t = new CompoundTag();
            t.putString("id", entity.getUUID().toString());
            t.putString("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            t.putDouble("x", entity.getX() - origin.getX());
            t.putDouble("y", entity.getY() - origin.getY());
            t.putDouble("z", entity.getZ() - origin.getZ());
            t.putFloat("yaw", entity.getYRot());
            t.putFloat("pitch", entity.getXRot());
            result.add(t);
        }
        return result;
    }

    private static CompoundTag capture(Entry e)
    {
        int[] blocks = new int[4096], halo = new int[5832];
        byte[] light = new byte[4096];
        var visuals = new VisualSnapshot.Collector();
        var p = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= 16; y++)
            for (int z = -1; z <= 16; z++)
                for (int x = -1; x <= 16; x++)
                {
                    p.set(e.base.getX() + x, e.base.getY() + y, e.base.getZ() + z);
                    boolean loaded = e.level.hasChunkAt(p);
                    var s = loaded ? e.level.getBlockState(p) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    halo[(x + 1) + (z + 1) * 18 + (y + 1) * 324] = Block.getId(s);
                    if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16)
                        continue;
                    int i = x + z * 16 + y * 256;
                    blocks[i] = Block.getId(s);
                    light[i] = (byte) e.level.getMaxLocalRawBrightness(p);
                    if (s.hasBlockEntity())
                        visuals.add(e.level, p, i);
                }

        var packed = VoxelVolume.pack(blocks);
        var h = VoxelVolume.pack(halo);
        var out = new CompoundTag();
        out.putString("id", e.id);
        out.putLong("revision", e.revision);
        out.putInt("bits", packed.bits());
        out.putIntArray("palette", packed.palette());
        out.putLongArray("words", packed.words());
        out.putInt("haloBits", h.bits());
        out.putIntArray("haloPalette", h.palette());
        out.putLongArray("haloWords", h.words());
        out.putByteArray("light", VoxelVolume.packLight(light));
        out.put("blockEntities", visuals.entities());
        out.putInt("entityBytes", visuals.bytes());
        var biome = e.level.getBiome(e.base.offset(8, 8, 8)).value();
        out.putInt("grass", biome.getGrassColor(e.base.getX(), e.base.getZ()));
        out.putInt("foliage", biome.getFoliageColor());
        out.putInt("water", biome.getWaterColor());
        e.dirty = false;
        e.captured = e.level.getServer().getTickCount();
        if (e.data != null && out.equals(e.data))
            return e.data;
        out.putLong("revision", e.revision = ++state(e.level.getServer()).revision);
        return out;
    }

    public static void changed(ServerLevel level, BlockPos position)
    {
        var s = ServerContext.get(level.getServer()).existing(KEY);
        if (s != null)
            for (var e : s.entries.values())
                if (e.level == level && position.getX() >= e.base.getX() - 1 && position.getX() <= e.base.getX() + 16 && position.getY() >= e.base.getY() - 1 && position.getY() <= e.base.getY() + 16 && position.getZ() >= e.base.getZ() - 1 && position.getZ() <= e.base.getZ() + 16)
                    e.dirty = true;
    }

    public static void initialize()
    {
        PayloadTypeRegistry.clientboundPlay().register(Section.TYPE, Section.CODEC);
        ServerContext.tick(ServerContext.Phase.TERRAIN, server ->
        {
            var s = ServerContext.get(server).existing(KEY);
            if (s == null)
                return;
            int tick = server.getTickCount(), budget = 4;
            var it = s.entries.entrySet().iterator();
            while (it.hasNext())
            {
                var e = it.next().getValue();
                if (tick - e.used > 60)
                {
                    it.remove();
                    continue;
                }
                if (budget > 0 && (e.data == null || e.dirty || tick - e.captured >= 40) && e.level.hasChunkAt(e.base))
                {
                    e.data = capture(e);
                    budget--;
                }
            }
            var viewers = s.viewers.entrySet().iterator();
            while (viewers.hasNext())
            {
                var v = viewers.next();
                var player = server.getPlayerList().getPlayer(v.getKey());
                if (player == null || v.getValue().expires < tick)
                {
                    viewers.remove();
                    continue;
                }
                int bytes = 0, count = 0;
                for (String id : v.getValue().wanted)
                {
                    var e = s.entries.get(id);
                    if (e == null || e.data == null || e.level != player.level() || v.getValue().sent.getOrDefault(id, -1L) == e.revision)
                        continue;
                    int cost = e.data.getLongArray("words").orElseThrow().length * 8 + e.data.getLongArray("haloWords").orElseThrow().length * 8 + e.data.getByteArray("light").orElseThrow().length + e.data.getIntArray("palette").orElseThrow().length * 4 + e.data.getIntArray("haloPalette").orElseThrow().length * 4 + e.data.getIntOr("entityBytes", 0) + 4096;
                    if (count >= 4 || bytes + cost > 131072)
                        break;
                    if (ServerPlayNetworking.canSend(player, Section.TYPE))
                    {
                        ServerPlayNetworking.send(player, new Section(e.data));
                        v.getValue().sent.put(id, e.revision);
                        bytes += cost;
                        count++;
                    }
                }
                if (v.getValue().sent.size() > 256)
                    v.getValue().sent.keySet().retainAll(v.getValue().wanted);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
    }

    private TerrainService()
    {
    }
}
