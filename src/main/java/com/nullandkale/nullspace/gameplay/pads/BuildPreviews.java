package com.nullandkale.nullspace.gameplay.pads;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;

/** Viewer-owned, bounded build sensors. Inspection never changes blocks or loads remote terrain. */
public final class BuildPreviews
{
    public static final int LIFETIME = 600, MAX_BLOCKS = 32 * 32 * 32;

    public record Preview(UUID id, BlockPos controller, String dimension, boolean ship, int remaining, long[] positions) implements CustomPacketPayload
    {
        public Preview
        {
            if (remaining < 0 || remaining > LIFETIME || positions.length > MAX_BLOCKS)
                throw new IllegalArgumentException("Invalid preview bounds");
            positions = positions.clone();
        }

        public static final Type<Preview> TYPE = new Type<>(Identifier.parse("nullspace:build_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Preview> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUUID(p.id);
            b.writeBlockPos(p.controller);
            b.writeUtf(p.dimension, 128);
            b.writeBoolean(p.ship);
            b.writeVarInt(p.remaining);
            b.writeVarInt(p.positions.length);
            for (long pos : p.positions)
                b.writeLong(pos);
        }, b ->
        {
            var id = b.readUUID();
            var controller = b.readBlockPos();
            var dimension = b.readUtf(128);
            boolean ship = b.readBoolean();
            int remaining = b.readVarInt(), count = b.readVarInt();
            if (count < 0 || count > MAX_BLOCKS)
                throw new IllegalArgumentException("Too many preview blocks");
            var positions = new long[count];
            for (int i = 0; i < count; i++)
                positions[i] = b.readLong();
            return new Preview(id, controller, dimension, ship, remaining, positions);
        });

        @Override
        public Type<Preview> type()
        {
            return TYPE;
        }
    }

    private static final class Active
    {
        final UUID id = UUID.randomUUID();
        final BlockPos controller;
        final String dimension;
        final boolean ship;
        final int expires;
        PadScanner.Pad pad;
        List<BlockPos> points;
        boolean dirty;

        Active(ServerPlayer p, BlockPos controller, boolean ship, PadScanner.Pad pad, List<BlockPos> points)
        {
            this.controller = controller.immutable();
            dimension = p.level().dimension().identifier().toString();
            this.ship = ship;
            this.pad = pad;
            this.points = points;
            expires = p.level().getServer().getTickCount() + LIFETIME;
        }
    }

    private static final ServerContext.Key<Map<UUID, Map<Boolean, Active>>> KEY = new ServerContext.Key<>();

    private static Map<UUID, Map<Boolean, Active>> state(MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, HashMap::new);
    }

    public static boolean enabled(ServerPlayer p, BlockPos controller, boolean ship)
    {
        var a = state(p.level().getServer()).getOrDefault(p.getUUID(), Map.of()).get(ship);
        return a != null && a.controller.equals(controller) && a.expires > p.level().getServer().getTickCount();
    }

    public static List<BlockPos> padPoints(PadScanner.Pad pad)
    {
        var points = new ArrayList<BlockPos>();
        for (int x = pad.minX() - 1; x <= pad.maxX() + 1; x++)
            for (int z = pad.minZ() - 1; z <= pad.maxZ() + 1; z++)
                points.add(new BlockPos(x, pad.y(), z));
        return List.copyOf(points);
    }

    public static List<BlockPos> shipPoints(ServerLevel level, PadScanner.Pad pad)
    {
        int height = PadRegistry.buildHeight(level, pad);
        if (height == 0)
            throw new IllegalArgumentException("There is no ship clearance above this pad. Move the pad above it or leave more vertical" + " room.");
        var bounds = new HullScan.Bounds(new BlockPos(pad.minX(), pad.y() + 1, pad.minZ()), new BlockPos(pad.maxX(), pad.y() + height, pad.maxZ()));
        var queue = new ArrayDeque<BlockPos>();
        for (var pos : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            if (!level.hasChunkAt(pos))
                throw new IllegalArgumentException("The ship area is still loading. Wait a moment and enable the preview again.");
            if (ModBlocks.navigation(level.getBlockState(pos)))
                queue.add(pos.immutable());
        }
        if (queue.isEmpty())
            throw new IllegalArgumentException("Place a helm or ship computer above this pad, connected to the hull, then enable Ship" + " preview.");
        var points = new HashSet<BlockPos>();
        while (!queue.isEmpty())
        {
            var pos = queue.removeFirst();
            if (!bounds.contains(pos) || points.contains(pos))
                continue;
            var b = level.getBlockState(pos);
            if (b.isAir())
                continue;
            points.add(pos);
            for (var d : Direction.values())
                queue.add(pos.relative(d));
        }
        return points.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
    }

    private static void send(ServerPlayer p, Active a, int remaining)
    {
        ServerPlayNetworking.send(p, new Preview(a.id, a.controller, a.dimension, a.ship, remaining, remaining == 0 ? new long[0] : a.points.stream().mapToLong(BlockPos::asLong).toArray()));
    }

    /** The player is alive and free, and the pad computer is loaded, still there, in reach and theirs to use. */
    private static boolean canReach(ServerPlayer p, BlockPos controller)
    {
        if (!p.isAlive() || TransferLocks.busy(p) || TransferLocks.locked(p.level(), controller) || !p.level().hasChunkAt(controller))
            return false;
        return p.level().getBlockState(controller).is(PadComputers.BLOCK) && p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(controller)) <= 64 && p.mayInteract(p.level(), controller);
    }

    /** The pad computer is loaded, still there, and within the distance a preview stays on. */
    private static boolean computerInView(ServerPlayer p, BlockPos controller)
    {
        if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(controller)) > 160 * 160 || !p.level().hasChunkAt(controller))
            return false;
        return p.level().getBlockState(controller).is(PadComputers.BLOCK);
    }

    public static String toggle(ServerPlayer p, BlockPos controller, boolean ship)
    {
        if (!canReach(p, controller))
            throw new IllegalArgumentException("Move within 8 blocks of the pad computer to change its preview.");
        var previews = state(p.level().getServer()).computeIfAbsent(p.getUUID(), id -> new HashMap<>());
        var previous = previews.get(ship);
        if (previous != null && previous.controller.equals(controller))
        {
            previews.remove(ship);
            send(p, previous, 0);
            return (ship ? "Ship" : "Pad") + " preview OFF.";
        }
        var scanned = PadComputers.inspect(p, controller);
        if (!scanned.valid())
            throw new IllegalArgumentException(scanned.message());
        var pad = scanned.pad();
        var points = ship ? shipPoints(p.level(), pad) : padPoints(pad);
        var active = new Active(p, controller, ship, pad, points);
        previews.put(ship, active);
        send(p, active, LIFETIME);
        return (ship ? "Ship" : "Pad") + " preview ON: " + points.size() + " blocks. It fades out over 30 seconds; select again to turn it off.";
    }

    public static void registrationsChanged(ServerLevel level)
    {
        var viewers = ServerContext.get(level.getServer()).existing(KEY);
        if (viewers != null)
            for (var modes : viewers.values())
                for (var a : modes.values())
                    if (a.dimension.equals(level.dimension().identifier().toString()))
                        a.dirty = true;
    }

    public static void changed(ServerLevel level, BlockPos pos)
    {
        var viewers = ServerContext.get(level.getServer()).existing(KEY);
        if (viewers == null || viewers.isEmpty())
            return;
        for (var modes : viewers.values())
            for (var a : modes.values())
                if (a.dimension.equals(level.dimension().identifier().toString()))
                {
                    var b = a.pad;
                    if (pos.equals(a.controller) || pos.getX() >= b.minX() - 1 && pos.getX() <= b.maxX() + 1 && pos.getZ() >= b.minZ() - 1 && pos.getZ() <= b.maxZ() + 1 && pos.getY() >= b.y() && pos.getY() <= b.y() + 32)
                        a.dirty = true;
                }
    }

    private static void tick(MinecraftServer server)
    {
        var viewers = state(server);
        for (var viewer : List.copyOf(viewers.entrySet()))
        {
            var p = server.getPlayerList().getPlayer(viewer.getKey());
            if (p == null)
            {
                viewers.remove(viewer.getKey());
                continue;
            }
            for (var entry : List.copyOf(viewer.getValue().entrySet()))
            {
                var a = entry.getValue();
                boolean stop = server.getTickCount() >= a.expires || !p.isAlive() || !a.dimension.equals(p.level().dimension().identifier().toString()) || !computerInView(p, a.controller);
                if (!stop && a.dirty && server.getTickCount() % 10 == 0)
                {
                    try
                    {
                        var scanned = PadComputers.inspect(p, a.controller);
                        if (!scanned.valid())
                            stop = true;
                        else
                        {
                            a.pad = scanned.pad();
                            var next = a.ship ? shipPoints(p.level(), a.pad) : padPoints(a.pad);
                            if (!next.equals(a.points))
                            {
                                a.points = next;
                                send(p, a, a.expires - server.getTickCount());
                            }
                            a.dirty = false;
                        }
                    }
                    catch (IllegalArgumentException invalid)
                    {
                        stop = true;
                    }
                }
                if (stop)
                {
                    viewer.getValue().remove(entry.getKey());
                    send(p, a, 0);
                }
            }
            if (viewer.getValue().isEmpty())
                viewers.remove(viewer.getKey());
        }
    }

    public static void initialize()
    {
        PayloadTypeRegistry.clientboundPlay().register(Preview.TYPE, Preview.CODEC);
        ServerContext.tick(ServerContext.Phase.SESSIONS, BuildPreviews::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> state(server).remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerContext.get(server).remove(KEY));
    }

    private BuildPreviews()
    {
    }
}
