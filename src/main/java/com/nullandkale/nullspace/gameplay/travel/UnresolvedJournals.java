package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import java.nio.file.Path;
import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Journals that recovery could not finish yet, and the areas and ships they still cover. Everything listed here stays
 * locked, so nobody can build into, move or recall what the journal may still rewrite, until an operator resolves it.
 */
public final class UnresolvedJournals
{
    public enum State
    {
        /** An in-process BUILD owns its footprint between construction ticks. Never retried as crashed work. */
        BUILDING,
        /** The chunks' saved entities are still loading. Retried until the waiting limit. */
        WAITING,
        /** A dimension the journal needs is not loaded, for example after a mod was removed. Retried on every start. */
        MISSING_DIMENSION,
        /** Recovery failed for a reason that cannot clear up. The file is in quarantine for an operator. */
        SET_ASIDE
    }

    /** The header of a journal, enough to lock its areas and describe it. */
    public record Area(String dimension, HullScan.Bounds bounds)
    {}

    public record Entry(String id, long sequence, String kind, String operation, List<String> ships, String sourceDimension, String targetDimension, BlockPos min, BlockPos max, BlockPos delta, State state, String reason, int sinceTick, Path file, List<Area> regions, HullScan.Bounds targetBounds)
    {
        public Entry(String id, long sequence, String kind, String operation, List<String> ships, String sourceDimension, String targetDimension, BlockPos min, BlockPos max, BlockPos delta, State state, String reason, int sinceTick, Path file)
        {
            this(id, sequence, kind, operation, ships, sourceDimension, targetDimension, min, max, delta, state, reason, sinceTick, file, List.of());
        }

        public Entry(String id, long sequence, String kind, String operation, List<String> ships, String sourceDimension, String targetDimension, BlockPos min, BlockPos max, BlockPos delta, State state, String reason, int sinceTick, Path file, List<Area> regions)
        {
            this(id, sequence, kind, operation, ships, sourceDimension, targetDimension, min, max, delta, state, reason, sinceTick, file, regions, min == null || max == null ? null : new HullScan.Bounds(min.offset(delta), max.offset(delta)));
        }

        Entry with(State state, String reason, Path file)
        {
            return new Entry(id, sequence, kind, operation, ships, sourceDimension, targetDimension, min, max, delta, state, reason, sinceTick, file, regions, targetBounds);
        }

        public boolean hasAreas()
        {
            return min != null && max != null && !sourceDimension.isEmpty();
        }
    }

    private static final ServerContext.Key<Map<String, Entry>> ENTRIES = new ServerContext.Key<>();

    private static Map<String, Entry> entries(MinecraftServer server)
    {
        return ServerContext.get(server).service(ENTRIES, LinkedHashMap::new);
    }

    /** Describes a journal from its header. Missing parts stay empty rather than failing. */
    public static Entry describe(CompoundTag journal, State state, String reason, int tick, Path file)
    {
        boolean located = journal.contains("min") && journal.contains("max");
        var min = located ? BlockPos.of(journal.getLongOr("min", 0)) : null;
        var max = located ? BlockPos.of(journal.getLongOr("max", 0)) : null;
        var delta = BlockPos.of(journal.getLongOr("delta", 0));
        var ships = new ArrayList<String>();
        for (var tag : journal.getListOrEmpty("ships"))
            tag.asString().ifPresent(ships::add);
        var regions = new ArrayList<Area>();
        for (var tag : journal.getListOrEmpty("regions"))
        {
            var region = (CompoundTag) tag;
            regions.add(new Area(region.getStringOr("source", ""), new HullScan.Bounds(BlockPos.of(region.getLongOr("min", 0)), BlockPos.of(region.getLongOr("max", 0)))));
        }
        return new Entry(journal.getStringOr("id", ""), journal.getLongOr("sequence", 0), journal.getStringOr("kind", "MOVE"), journal.getStringOr("operation", ""), List.copyOf(ships), journal.getStringOr("source", ""), journal.getStringOr("target", journal.getStringOr("source", "")), min, max, delta, state, reason, tick, file, List.copyOf(regions), located ? ShipTransform.destinationBounds(journal) : null);
    }

    public static void put(MinecraftServer server, Entry entry)
    {
        entries(server).put(entry.id(), entry);
    }

    public static Entry get(MinecraftServer server, String id)
    {
        return entries(server).get(id);
    }

    public static void remove(MinecraftServer server, String id)
    {
        entries(server).remove(id);
    }

    public static List<Entry> all(MinecraftServer server)
    {
        return List.copyOf(entries(server).values());
    }

    public static boolean any(MinecraftServer server)
    {
        return !entries(server).isEmpty();
    }

    public static boolean areaBusy(ServerLevel level, HullScan.Bounds bounds)
    {
        String dimension = level.dimension().identifier().toString();
        for (var entry : entries(level.getServer()).values())
        {
            // A check of a controller against the store locks its ship, never the block: the terminal must stay usable.
            if (!entry.hasAreas() || entry.kind().equals("CHECK"))
                continue;
            for (var region : entry.regions())
                if (region.dimension().equals(dimension) && region.bounds().box().intersects(bounds.box()))
                    return true;
            if (entry.sourceDimension().equals(dimension) && new HullScan.Bounds(entry.min(), entry.max()).box().intersects(bounds.box()))
                return true;
            if (entry.targetDimension().equals(dimension) && entry.targetBounds().box().intersects(bounds.box()))
                return true;
        }
        return false;
    }

    public static boolean shipBusy(MinecraftServer server, String shipId)
    {
        if (shipId == null || shipId.isEmpty())
            return false;
        for (var entry : entries(server).values())
            if (entry.ships().contains(shipId))
                return true;
        return false;
    }

    public static String describeForOperators(Entry entry)
    {
        return shortId(entry) + " " + entry.kind() + " " + entry.operation() + " ships " + entry.ships() + " " + entry.state() + (entry.reason().isEmpty() ? "" : ": " + entry.reason());
    }

    /** What an operator types to pick an entry: the first eight characters of a journal's id, the whole id of a check. */
    public static String shortId(Entry entry)
    {
        return entry.kind().equals("CHECK") ? entry.id() : entry.id().substring(0, Math.min(8, entry.id().length()));
    }

    private UnresolvedJournals()
    {
    }
}
