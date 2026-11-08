package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.air.SpatialAir;

import java.util.*;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/** Sparse flow samples, not a copy of the pressure field; visuals never drive simulation. */
public final class AirEffects
{
    public record FlowPacket(String dimension, BlockPos base, int[] values) implements CustomPacketPayload
    {

        public static final Type<FlowPacket> TYPE = new Type<>(Identifier.parse("nullspace:air_flow"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FlowPacket> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUtf(p.dimension, 128);
            b.writeBlockPos(p.base);
            b.writeVarInt(p.values.length);
            for (int n : p.values)
                b.writeVarInt(n);
        }, b ->
        {
            String d = b.readUtf(128);
            var p = b.readBlockPos();
            int count = b.readVarInt();
            if (count < 0 || count > 192 || count % 6 != 0)
                throw new IllegalArgumentException("Invalid flow packet");
            int[] a = new int[count];
            for (int i = 0; i < count; i++)
                a[i] = b.readVarInt();
            return new FlowPacket(d, p, a);
        });

        @Override
        public Type<FlowPacket> type()
        {
            return TYPE;
        }
    }

    public static void initialize()
    {
        PayloadTypeRegistry.clientboundPlay().register(FlowPacket.TYPE, FlowPacket.CODEC);
    }

    public static void send(ServerLevel level, BlockPos base, List<SpatialAir.Flow> flows)
    {
        var selected = flows.stream().sorted(Comparator.comparingInt(SpatialAir.Flow::amount).reversed()).limit(32).toList();
        int[] data = new int[selected.size() * 6];
        int i = 0;
        for (var f : selected)
        {
            data[i++] = f.cell();
            data[i++] = f.dx();
            data[i++] = f.dy();
            data[i++] = f.dz();
            data[i++] = f.amount();
            data[i++] = f.exterior() ? 1 : 0;
        }
        var packet = new FlowPacket(level.dimension().identifier().toString(), base, data);
        for (var p : level.players())
            if (p.distanceToSqr(base.getX() + 16, base.getY() + 16, base.getZ() + 16) < 10000 && ServerPlayNetworking.canSend(p, FlowPacket.TYPE))
                ServerPlayNetworking.send(p, packet);
    }

    public static void send(ServerLevel level, BlockPos base, List<SpatialAir.Flow> flows, Atmosphere.Grid grid)
    {
        var sections = new HashMap<BlockPos, List<SpatialAir.Flow>>();
        for (var flow : flows)
        {
            int x = grid.x(flow.cell()), y = grid.y(flow.cell()), z = grid.z(flow.cell());
            var offset = new BlockPos(x / 32 * 32, y / 32 * 32, z / 32 * 32);
            sections.computeIfAbsent(offset, k -> new ArrayList<>()).add(new SpatialAir.Flow(Atmosphere.index(x % 32, y % 32, z % 32), flow.dx(), flow.dy(), flow.dz(), flow.amount(), flow.exterior()));
        }
        sections.forEach((offset, samples) -> send(level, base.offset(offset), samples));
    }

    private AirEffects()
    {
    }
}
