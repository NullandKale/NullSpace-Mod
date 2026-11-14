package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.worldgen.condition.DerelictHistory;
import com.nullandkale.nullspace.worldgen.piece.Piece;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Stocks a found vessel: one chest in every room takes that room's derelict table at a tier from the zone's
 * danger and the vessel's history, and the hoard room's chest takes the site's treasure instead. Every other
 * container stays empty. The chests are the {@code roomloot} points the planner marked, one per room.
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

    /** The loot table for a room at a tier, with its power-failure variant where it has one. */
    public static String table(Spot spot, DerelictHistory history, int tier)
    {
        return "derelict/" + spot.key() + (history.dead(spot.use()) ? "_dead" : "") + "_" + tier;
    }

    /** Gives every marked chest its table. Returns how many were stocked. */
    public static int stock(ServerLevel level, BlockPos base, List<Piece.Point> points, String treasure, int danger, long seed, String name)
    {
        var spots = spots(points);
        var hoard = hoard(spots);
        var history = DerelictHistory.of(seed, danger);
        int stocked = 0;
        for (var spot : spots)
        {
            boolean isHoard = spot == hoard;
            String table = isHoard ? treasure : table(spot, history, history.tier(danger, seed, spot.room(), spot.use(), false));
            if (SiteLoot.assign(level, base.offset(spot.pos()), table, seed + stocked, name))
                stocked++;
        }
        return stocked;
    }

    private DerelictLoot()
    {
    }
}
