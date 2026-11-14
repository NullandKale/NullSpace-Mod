package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.worldgen.piece.Piece;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/**
 * The named spots of a site built from kit pieces, saved with the site ({@code sitePoints}) so
 * stocking and threats, which happen later, use the spots the author marked instead of guessing.
 */
public final class SitePoints
{
    public static void write(CompoundTag site, List<Piece.Point> points)
    {
        var list = new ListTag();
        for (var point : points)
        {
            var tag = new CompoundTag();
            tag.putLong("pos", point.pos().asLong());
            tag.putString("name", point.name());
            tag.putString("argument", point.argument());
            list.add(tag);
        }
        site.put("sitePoints", list);
    }

    public static List<Piece.Point> read(CompoundTag site)
    {
        var points = new ArrayList<Piece.Point>();
        for (var entry : site.getListOrEmpty("sitePoints"))
        {
            var tag = (CompoundTag) entry;
            points.add(new Piece.Point(BlockPos.of(tag.getLongOr("pos", 0)), tag.getStringOr("name", ""), tag.getStringOr("argument", "")));
        }
        return points;
    }

    /**
     * Gives every {@code loot} point's container its table (the point's argument, or cargo); the
     * first gets the site's treasure. Returns how many containers were stocked.
     */
    public static int stock(ServerLevel level, BlockPos base, List<Piece.Point> points, String treasure, long seed, String name)
    {
        return SiteLoot.apply(level, assignments(base, points, treasure, seed, name));
    }

    /** Which table each {@code loot} point takes. */
    public static List<SiteLoot.Assignment> assignments(BlockPos base, List<Piece.Point> points, String treasure, long seed, String name)
    {
        var out = new ArrayList<SiteLoot.Assignment>();
        for (var point : points)
        {
            if (!point.name().equals("loot"))
                continue;
            String table = out.isEmpty() ? treasure : point.argument().isEmpty() ? "cargo" : point.argument();
            out.add(new SiteLoot.Assignment(base.offset(point.pos()), table, seed + out.size(), name));
        }
        return out;
    }

    /** World positions of a saved site's {@code mob} points; empty for hand-built sites. */
    public static List<BlockPos> mobSpots(CompoundTag site)
    {
        var base = WorldStore.pos(site, "base");
        return read(site).stream().filter(p -> p.name().equals("mob")).map(p -> base.offset(p.pos())).toList();
    }

    private SitePoints()
    {
    }
}
