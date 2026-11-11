package com.nullandkale.nullspace.gameplay.ship;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.ControllerBlockEntity;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.UnresolvedJournals;

import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Checks the ship store against the world whenever a chunk loads. Every ship controller carries its ship's id in its
 * block entity (a few bytes, never ship data). The store's records are checked against the controllers in the chunk,
 * and the controllers against the records, in both directions:
 *
 * <ul>
 * <li>a controller that carries a different ship's id than the record that places a controller there;
 * <li>a controller that carries an id, while the store records that ship somewhere else or not at all. That is how a
 * store that is older than the world looks.
 * </ul>
 *
 * A disagreement does not stop the server. It marks the ship as needing attention: the ship cannot travel, be recalled
 * or be stored, operators are told, and {@code /nullspace recovery} lists it. A missing controller is never flagged,
 * because a destroyed controller is an ordinary state of a damaged ship. Existing ships get their id stamped on the
 * first load where record and controller agree.
 */
public final class ShipIntegrity
{
    public static final String CHECK = "CHECK";

    /** Controller chunk to the records that place a controller there, rebuilt when the store changes. A stale index only delays a check. */
    private record Index(long generation, int size, Map<String, Map<Long, List<CompoundTag>>> byChunk, Map<String, CompoundTag> byId)
    {}

    private static final ServerContext.Key<Index> INDEX = new ServerContext.Key<>();

    /** A chunk that loaded and is waiting to be checked. */
    private record Loaded(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int x, int z)
    {}

    private static final ServerContext.Key<java.util.Set<Loaded>> QUEUE = new ServerContext.Key<>();

    public static void initialize()
    {
        // The callback runs inside the chunk pipeline, where an exception is swallowed and leaves the chunk half loaded.
        // It only notes the chunk; the check runs from the server tick, where a failure is logged and harmless.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> ServerContext.get(level.getServer()).service(QUEUE, java.util.LinkedHashSet::new).add(new Loaded(level.dimension(), chunk.getPos().x(), chunk.getPos().z())));
        ServerContext.tick(ServerContext.Phase.FIXTURES, server ->
        {
            checkQueued(server);
            if (server.getTickCount() % 200 == 0)
                stampLoaded(server);
        });
    }

    /** Checks the chunks that loaded since the last tick, a bounded number each tick. */
    private static void checkQueued(MinecraftServer server)
    {
        var queue = ServerContext.get(server).existing(QUEUE);
        if (queue == null || queue.isEmpty())
            return;
        var iterator = queue.iterator();
        for (int budget = 32; budget > 0 && iterator.hasNext(); budget--)
        {
            var loaded = iterator.next();
            iterator.remove();
            var level = server.getLevel(loaded.dimension());
            var chunk = level == null ? null : level.getChunkSource().getChunkNow(loaded.x(), loaded.z());
            if (chunk == null)
                continue;
            try
            {
                check(level, chunk);
            }
            catch (RuntimeException failure)
            {
                NullSpace.LOGGER.error("The ship integrity check of chunk {}, {} failed; it is skipped", loaded.x(), loaded.z(), failure);
            }
        }
    }

    private static Index index(MinecraftServer server, WorldStore store)
    {
        var context = ServerContext.get(server);
        var existing = context.existing(INDEX);
        var ships = store.ships();
        long generation = store.repository().generation();
        if (existing != null && existing.generation() == generation && existing.size() == ships.size())
            return existing;
        var byChunk = new HashMap<String, Map<Long, List<CompoundTag>>>();
        var byId = new HashMap<String, CompoundTag>();
        for (var tag : ships)
        {
            var ship = (CompoundTag) tag;
            byId.put(ship.getStringOr("id", ""), ship);
            if (!ship.contains("controller") || ship.getStringOr("kind", "").equals("debris"))
                continue;
            var controller = BlockPos.of(ship.getLongOr("controller", 0));
            byChunk.computeIfAbsent(ship.getStringOr("dimension", ""), d -> new HashMap<>()).computeIfAbsent(new ChunkPos(controller.getX() >> 4, controller.getZ() >> 4).pack(), c -> new ArrayList<>()).add(ship);
        }
        var built = new Index(generation, ships.size(), byChunk, byId);
        context.put(INDEX, built);
        return built;
    }

    private static boolean isController(net.minecraft.world.level.block.state.BlockState state)
    {
        return state.is(ModBlocks.COMPUTER) || state.is(ModBlocks.STATION_CORE);
    }

    static void check(ServerLevel level, LevelChunk chunk)
    {
        var server = level.getServer();
        // While a move or a recovery edits blocks, the store and the chunks are legitimately out of step.
        if (com.nullandkale.nullspace.gameplay.travel.TravelService.worldMutation())
            return;
        var store = WorldStore.get(server);
        var index = index(server, store);
        String dimension = level.dimension().identifier().toString();
        var here = chunk.getPos();
        for (var ship : index.byChunk().getOrDefault(dimension, Map.of()).getOrDefault(here.pack(), List.of()))
        {
            // A ship that is moving, or that an unresolved journal still covers, is legitimately out of step.
            if (TransferLocks.busy(server, ship))
                continue;
            var pos = BlockPos.of(ship.getLongOr("controller", 0));
            if (!isController(chunk.getBlockState(pos)) || !(chunk.getBlockEntity(pos) instanceof ControllerBlockEntity controller))
                continue;
            String id = ship.getStringOr("id", "");
            if (controller.ship.isEmpty())
            {
                controller.ship = id;
                controller.setChanged();
            }
            else if (!controller.ship.equals(id) && !index.byId().containsKey(controller.ship))
            {
                // The stamp belongs to a ship the store does not have: the record that stands here is the authority.
                NullSpace.LOGGER.info("Controller at {} carried the id of ship {}, which the store no longer records; it now carries {}", pos.toShortString(), controller.ship, id);
                controller.ship = id;
                controller.setChanged();
            }
            else if (!controller.ship.equals(id))
                flag(server, List.of(id, controller.ship), level, pos, "the controller at " + pos.toShortString() + " carries ship " + controller.ship + " but the store records ship " + id + " there");
        }
        for (var entry : chunk.getBlockEntities().entrySet())
        {
            if (!(entry.getValue() instanceof ControllerBlockEntity controller) || controller.ship.isEmpty())
                continue;
            var pos = entry.getKey();
            var record = index.byId().get(controller.ship);
            if (record != null && TransferLocks.busy(server, record) || TransferLocks.areaBusy(level, new com.nullandkale.nullspace.gameplay.ship.structure.HullScan.Bounds(pos, pos)))
                continue;
            if (record == null)
                flag(server, List.of(controller.ship), level, pos, "the controller at " + pos.toShortString() + " belongs to ship " + controller.ship + ", which the store does not record");
            else if (!record.getStringOr("dimension", "").equals(dimension) || record.getLongOr("controller", 0) != pos.asLong())
                flag(server, List.of(controller.ship, record.getStringOr("id", "")), level, pos, "the controller at " + pos.toShortString() + " in " + dimension + " carries ship " + controller.ship + ", which the store records in " + record.getStringOr("dimension", "?") + " at " + BlockPos.of(record.getLongOr("controller", 0)).toShortString());
        }
    }

    /** Ships created after their chunk loaded get their id stamped here. */
    private static void stampLoaded(MinecraftServer server)
    {
        var store = WorldStore.get(server);
        for (var tag : store.ships())
        {
            var ship = (CompoundTag) tag;
            if (!ship.contains("controller") || ship.getStringOr("kind", "").equals("debris"))
                continue;
            var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(ship.getStringOr("dimension", ""))));
            var pos = BlockPos.of(ship.getLongOr("controller", 0));
            if (level == null || !level.hasChunkAt(pos) || !isController(level.getBlockState(pos)))
                continue;
            if (level.getBlockEntity(pos) instanceof ControllerBlockEntity controller && controller.ship.isEmpty())
            {
                controller.ship = ship.getStringOr("id", "");
                controller.setChanged();
            }
        }
    }

    private static void flag(MinecraftServer server, List<String> shipIds, ServerLevel level, BlockPos pos, String reason)
    {
        String id = "check-" + Long.toString(pos.asLong(), 36);
        if (UnresolvedJournals.get(server, id) != null)
            return;
        String dimension = level.dimension().identifier().toString();
        NullSpace.LOGGER.error("Ships {} need attention: {}", shipIds, reason);
        UnresolvedJournals.put(server, new UnresolvedJournals.Entry(id, 0, CHECK, "controller", List.copyOf(shipIds), dimension, dimension, pos, pos, BlockPos.ZERO, UnresolvedJournals.State.SET_ASIDE, reason, server.getTickCount(), null));
        for (var player : server.getPlayerList().getPlayers())
            if (player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Ships " + shipIds + " need attention: " + reason + ". Run /nullspace recovery list"));
    }

    /** A ship was registered or its controller changed: the controller now carries this ship's id, replacing any stale stamp. */
    public static void stamp(ServerLevel level, BlockPos controller, String shipId)
    {
        if (level.hasChunkAt(controller) && level.getBlockEntity(controller) instanceof ControllerBlockEntity block && !block.ship.equals(shipId))
        {
            block.ship = shipId;
            block.setChanged();
        }
    }

    /** An operator accepts the store as it is: the stale controller stamp is cleared, so the check passes. */
    public static void accept(MinecraftServer server, UnresolvedJournals.Entry entry)
    {
        var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(entry.sourceDimension())));
        if (level != null && entry.min() != null && level.hasChunkAt(entry.min()) && level.getBlockEntity(entry.min()) instanceof ControllerBlockEntity controller && entry.ships().contains(controller.ship))
        {
            controller.ship = "";
            controller.setChanged();
        }
        UnresolvedJournals.remove(server, entry.id());
    }

    private ShipIntegrity()
    {
    }
}
