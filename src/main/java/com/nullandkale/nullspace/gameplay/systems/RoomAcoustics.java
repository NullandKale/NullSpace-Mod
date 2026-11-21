package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.model.air.Atmosphere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;

/**
 * How a sound carries between the rooms of a hull, from the air system's rooms: an open door joins two spaces into one room, a closed door or a wall keeps them
 * apart. Two rooms are neighbours across a closed door (a {@link #DOOR} step) or across a single wall block (a {@link #WALL} step), and a sound's cost is its
 * cheapest path between the source's room and the listener's. The graph is worked out once for each version of the rooms and kept until they change.
 */
public final class RoomAcoustics
{
    public static final int DOOR = 1, WALL = 3, UNREACHABLE = Integer.MAX_VALUE;

    private record Graph(List<int[]>[] edges)
    {}

    private static final Map<Atmosphere.Topology, Graph> GRAPHS = Collections.synchronizedMap(new WeakHashMap<>());

    /** The cost of a sound's way from {@code source} to {@code listener} in one hull: 0 in the same room (or a hull without rooms), else the sum of its steps. */
    public static int cost(ServerLevel level, CompoundTag ship, BlockPos source, BlockPos listener)
    {
        return cost(level, ship, source, listener, true);
    }

    /**
     * As {@link #cost(ServerLevel, CompoundTag, BlockPos, BlockPos)}; with {@code rebuild} false, rooms whose hull just changed are used as they were, never
     * worked out again: a vanilla sound can be played in the middle of a block change, where the hull must not be scanned.
     */
    public static int cost(ServerLevel level, CompoundTag ship, BlockPos source, BlockPos listener, boolean rebuild)
    {
        if (!ShipSystems.enabled(ship))
            return 0;
        var r = ShipSystems.runtime(level, ship);
        if (r.dirty && rebuild)
            ShipSystems.rebuild(level, ship, r, false);
        var topology = r.atmosphere;
        if (topology == null)
            return 0;
        var grid = ShipSystems.grid(ship);
        var base = WorldStore.pos(ship, "base");
        int from = room(topology, grid, source.subtract(base)), to = room(topology, grid, listener.subtract(base));
        if (from == to)
            return 0;
        if (from < 0 || to < 0)
            return WALL;
        var graph = GRAPHS.computeIfAbsent(topology, t -> graph(level, base, grid, t));
        var best = new int[graph.edges.length];
        Arrays.fill(best, UNREACHABLE);
        best[from] = 0;
        var queue = new PriorityQueue<int[]>((a, b) -> Integer.compare(a[1], b[1]));
        queue.add(new int[]{from, 0});
        while (!queue.isEmpty())
        {
            var next = queue.poll();
            if (next[0] == to)
                return next[1];
            if (next[1] > best[next[0]])
                continue;
            for (var edge : graph.edges[next[0]])
                if (next[1] + edge[1] < best[edge[0]])
                {
                    best[edge[0]] = next[1] + edge[1];
                    queue.add(new int[]{edge[0], best[edge[0]]});
                }
        }
        return UNREACHABLE;
    }

    /** The room of a cell, or of the first neighbouring air cell for a block that is not air itself (a Synth block, a closed door, a wall torch). */
    private static int room(Atmosphere.Topology topology, Atmosphere.Grid grid, BlockPos local)
    {
        int x = local.getX(), y = local.getY(), z = local.getZ();
        if (grid.contains(x, y, z) && topology.roomAt()[grid.index(x, y, z)] >= 0)
            return topology.roomAt()[grid.index(x, y, z)];
        int[][] around = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
        for (var d : around)
            if (grid.contains(x + d[0], y + d[1], z + d[2]) && topology.roomAt()[grid.index(x + d[0], y + d[1], z + d[2])] >= 0)
                return topology.roomAt()[grid.index(x + d[0], y + d[1], z + d[2])];
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Graph graph(ServerLevel level, BlockPos base, Atmosphere.Grid grid, Atmosphere.Topology topology)
    {
        int rooms = topology.rooms().size();
        var costs = new java.util.HashMap<Long, Integer>();
        var roomAt = topology.roomAt();
        int[][] around = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        var touching = new int[6];
        for (int cell = 0; cell < roomAt.length; cell++)
        {
            if (roomAt[cell] >= 0)
                continue;
            int x = grid.x(cell), y = grid.y(cell), z = grid.z(cell), n = 0;
            for (var d : around)
                if (grid.contains(x + d[0], y + d[1], z + d[2]))
                {
                    int room = roomAt[grid.index(x + d[0], y + d[1], z + d[2])];
                    if (room >= 0)
                        touching[n++] = room;
                }
            if (n < 2)
                continue;
            Integer cost = null;
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++)
                    if (touching[i] != touching[j])
                    {
                        if (cost == null)
                        {
                            var block = level.getBlockState(base.offset(x, y, z)).getBlock();
                            cost = block instanceof DoorBlock || block instanceof TrapDoorBlock ? DOOR : WALL;
                        }
                        long key = Math.min(touching[i], touching[j]) * (long) rooms + Math.max(touching[i], touching[j]);
                        costs.merge(key, cost, Math::min);
                    }
        }
        var edges = (List<int[]>[]) new List[rooms];
        for (int i = 0; i < rooms; i++)
            edges[i] = new ArrayList<>();
        costs.forEach((key, cost) ->
        {
            int a = (int) (key / rooms), b = (int) (key % rooms);
            edges[a].add(new int[]{b, cost});
            edges[b].add(new int[]{a, cost});
        });
        return new Graph(edges);
    }

    private RoomAcoustics()
    {
    }
}
