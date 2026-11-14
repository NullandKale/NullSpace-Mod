package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.worldgen.condition.DerelictHistory;
import com.nullandkale.nullspace.worldgen.piece.Piece;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Stocks a found vessel: one chest in every room takes that room's derelict table, and the hoard room's chest takes the
 * site's treasure instead, the one place the top-end items live. Every other container stays empty. The chests are the
 * {@code roomloot} points the planner marked, one per room. Below danger 3 every room gives the zone's own tier of loot;
 * at danger 3 four or five of the best rooms (fewer in a small vessel) give the high tier and the rest the medium one.
 */
public final class DerelictLoot
{
    /** A room's loot chest: where it is, the room's id and use, and which variant of the room it is. */
    public record Spot(BlockPos pos, String room, String use, int variant)
    {
        /** The table key: stores, growing bays and gardens have one table per variant. */
        public String key()
        {
            return switch (use)
            {
                case "stores", "growing_bay", "garden" -> use + "_" + variant;
                default -> use;
            };
        }
    }

    public static boolean has(List<Piece.Point> points)
    {
        return points.stream().anyMatch(p -> p.name().equals("roomloot"));
    }

    /** The marked chests, in the order the planner listed the rooms. */
    public static List<Spot> spots(List<Piece.Point> points)
    {
        var out = new ArrayList<Spot>();
        for (var point : points)
        {
            if (!point.name().equals("roomloot"))
                continue;
            var parts = point.argument().split(" ");
            out.add(new Spot(point.pos(), parts[0], parts[1], parts.length > 2 ? Integer.parseInt(parts[2]) : 0));
        }
        return out;
    }

    /**
     * The hoard room: a cargo hold if there is one, else a workshop, foundry or lab, else a store or living room, and
     * only then the first room. The galleys, gardens, bridge and engineering keep their own food, plants and fuel.
     */
    public static Spot hoard(List<Spot> spots)
    {
        for (var uses : List.of(Set.of("cargo_hold"), Set.of("workshop", "foundry", "lab"), Set.of("stores", "receiving_bay", "maintenance", "commons", "cabin", "bunk_room", "observation", "archive", "armory", "operations", "infirmary", "brig", "lookout", "eva_prep")))
            for (var spot : spots)
                if (uses.contains(spot.use()))
                    return spot;
        return spots.isEmpty() ? null : spots.getFirst();
    }

    /** The rooms a danger 3 vessel favors for its high tier, best first: where good stock would plausibly be kept. */
    private static final List<String> HIGH_ROOMS = List.of("armory", "foundry", "workshop", "lab", "receiving_bay", "cargo_hold", "engineering", "archive", "cabin", "infirmary", "brig", "operations", "bridge", "maintenance");

    /**
     * The loot tier of each room other than the hoard. Danger 0 to 2: the danger itself, the same everywhere. Danger 3:
     * four or five rooms (at most half the rooms) at tier 3, chosen from the favored uses first and then by seed, and
     * every other room at tier 2.
     */
    public static Map<Spot, Integer> tiers(List<Spot> spots, Spot hoard, int danger, long seed)
    {
        var tiers = new LinkedHashMap<Spot, Integer>();
        var rooms = spots.stream().filter(s -> s != hoard).toList();
        int level = Math.max(0, Math.min(3, danger));
        for (var spot : rooms)
            tiers.put(spot, Math.min(level, 2));
        if (level < 3)
            return tiers;
        int high = Math.min(4 + (int) (scatter(seed ^ 0x7E57L) >>> 40 & 1), rooms.size() / 2);
        var ranked = new ArrayList<>(rooms);
        ranked.sort(Comparator.comparingInt((Spot s) -> favor(s.use())).thenComparingLong(s -> scatter(seed * 31 + s.room().hashCode())));
        for (var spot : ranked.subList(0, high))
            tiers.put(spot, 3);
        return tiers;
    }

    /** Spreads nearby seeds apart; a plain Random gives similar first values to similar seeds. */
    private static long scatter(long value)
    {
        long z = value * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int favor(String use)
    {
        int at = HIGH_ROOMS.indexOf(use);
        return at < 0 ? HIGH_ROOMS.size() : at;
    }

    /** The loot table for a room at a tier, with its power-failure variant where it has one. */
    public static String table(Spot spot, DerelictHistory history, int tier)
    {
        return "derelict/" + spot.key() + (history.dead(spot.use()) ? "_dead" : "") + "_" + tier;
    }

    /** Which table each marked chest takes, with the roll seeds the game uses. */
    public static List<SiteLoot.Assignment> assignments(BlockPos base, List<Piece.Point> points, String treasure, int danger, long seed, String name)
    {
        var spots = spots(points);
        var hoard = hoard(spots);
        var history = DerelictHistory.of(seed, danger);
        var tiers = tiers(spots, hoard, danger, seed);
        var out = new ArrayList<SiteLoot.Assignment>();
        for (var spot : spots)
        {
            String table = spot == hoard ? treasure : table(spot, history, tiers.get(spot));
            out.add(new SiteLoot.Assignment(base.offset(spot.pos()), table, seed + out.size(), name));
        }
        return out;
    }

    /** Gives every marked chest its table. Returns how many were stocked. */
    public static int stock(ServerLevel level, BlockPos base, List<Piece.Point> points, String treasure, int danger, long seed, String name)
    {
        return SiteLoot.apply(level, assignments(base, points, treasure, danger, seed, name));
    }

    private DerelictLoot()
    {
    }
}
