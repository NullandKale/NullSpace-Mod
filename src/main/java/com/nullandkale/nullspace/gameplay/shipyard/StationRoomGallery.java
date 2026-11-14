package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.*;

import java.nio.file.Files;
import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The station room gallery: one of every station room type, in the small and large variants, on a
 * walkway, in each maker, so rooms are judged side by side. Each room is behind a lobby with a
 * pressure-plated door and a label. The ship room gallery ({@link RoomGallery}) shows the ship rooms.
 */
public final class StationRoomGallery
{
    /** Outer room sizes: a small room has a 7 by 6 interior, a large one 15 by 14. */
    private static final int SMALL_W = 9, SMALL_D = 8, LARGE_W = 17, LARGE_D = 16;
    private static final int STATION_PITCH_X = 26, STATION_PITCH_Z = 28, PER_ROW = 6, GAP = 12, MARGIN = 10;

    /** One room to place: its blocks and entity points in their own coordinates, and its label. */
    private record Entry(String title, String subtitle, Map<BlockPos, BlockState> blocks, List<Piece.Point> points, int entryX)
    {
        BlockPos min()
        {
            int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
            for (var at : blocks.keySet())
            {
                x = Math.min(x, at.getX());
                y = Math.min(y, at.getY());
                z = Math.min(z, at.getZ());
            }
            return new BlockPos(x, y, z);
        }

        int height()
        {
            return blocks.keySet().stream().mapToInt(at -> at.getY()).max().orElse(0) - min().getY() + 1;
        }
    }

    /** Where the visitor should stand, and how many entities the gallery placed. */
    public record Placed(BlockPos start, int entities)
    {}

    /** A block of entries laid out in rows. */
    private record Section(String name, List<Entry> entries, int pitchX, int pitchZ)
    {
        int rows()
        {
            return (entries.size() + PER_ROW - 1) / PER_ROW;
        }
    }

    /** Places the station room gallery for the given makers and returns where to stand and what was placed. */
    public static Placed build(MinecraftServer server, List<Maker> makers) throws java.io.IOException
    {
        return place(server.overworld(), sections(makers));
    }

    /** The number of rooms the gallery holds for these makers. */
    public static int rooms(List<Maker> makers)
    {
        return sections(makers).stream().mapToInt(section -> section.entries().size()).sum();
    }

    // ---- What goes in the gallery ----

    private static List<Section> sections(List<Maker> makers)
    {
        var out = new ArrayList<Section>();
        for (var maker : makers)
        {
            var entries = new ArrayList<Entry>();
            for (var use : StationPlan.Use.values())
            {
                if (!StationRooms.furnishes(use))
                    continue;
                for (int variant = 0; variant < StationRooms.variants(use); variant++)
                    for (boolean large : new boolean[]{false, true})
                        entries.add(stationRoom(maker, use, variant, large));
            }
            out.add(new Section(maker.label + ": station rooms, small then large", entries, STATION_PITCH_X, STATION_PITCH_Z));
        }
        return out;
    }

    private static Entry stationRoom(Maker maker, StationPlan.Use use, int variant, boolean large)
    {
        var spec = new StationSpec(maker, StationSpec.Purpose.HARBOR, StationSpec.Size.STANDARD, StationSpec.Condition.OPERATIONAL, 1000L + use.ordinal() * 17L + variant);
        int w = large ? LARGE_W : SMALL_W, d = large ? LARGE_D : SMALL_D;
        var blueprint = StationStructure.room(StationRooms.single(spec, use, w, d), variant);
        String name = use.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        String variantName = StationRooms.variantName(use, variant);
        return new Entry(name + (variantName.isEmpty() ? "" : " (" + variantName + ")"), (large ? "large" : "small") + ", " + maker.name().toLowerCase(Locale.ROOT), blueprint.blocks(), blueprint.points(), w / 2);
    }

    // ---- Placing ----

    /** Lays every section out on a walkway in clear sky above the terrain and returns where the visitor should stand. */
    private static Placed place(ServerLevel level, List<Section> sections) throws java.io.IOException
    {
        var server = level.getServer();
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-station-room-gallery.nbt");
        int count = Files.exists(marker) ? Math.addExact(WorldStore.read(marker).getIntOr("count", 0), 1) : 1;
        int width = 0, depth = MARGIN, height = 0;
        for (var s : sections)
        {
            width = Math.max(width, Math.min(PER_ROW, s.entries().size()) * s.pitchX());
            depth += s.rows() * s.pitchZ() + GAP;
            for (var e : s.entries())
                height = Math.max(height, e.height());
        }
        width += 2 * MARGIN;
        int x0 = 3200, z0 = Math.addExact(40000, Math.multiplyExact(count - 1, 1200));
        for (int cx = (x0 - MARGIN) >> 4; cx <= (x0 + width + MARGIN) >> 4; cx++)
            for (int cz = (z0 - MARGIN) >> 4; cz <= (z0 + depth + MARGIN) >> 4; cz++)
                level.getChunk(cx, cz);
        int surface = level.getMinY();
        for (int dx = 0; dx < width; dx += 4)
            for (int dz = 0; dz < depth; dz += 4)
                surface = Math.max(surface, level.getHeight(Heightmap.Types.WORLD_SURFACE, x0 + dx, z0 + dz));
        int y = surface + 12;
        if (y + height + 8 >= level.getMaxY())
            throw new IllegalArgumentException("No room above terrain for the room gallery");
        var low = new BlockPos(x0, y, z0);
        var high = low.offset(width - 1, height + 4, depth - 1);
        if (!level.getWorldBorder().isWithinBounds(low) || !level.getWorldBorder().isWithinBounds(high))
            throw new IllegalArgumentException("The room gallery is outside the world border");
        for (var at : BlockPos.betweenClosed(low, high))
            if (!level.getBlockState(at).isAir())
                throw new IllegalArgumentException("The room gallery space is occupied at " + at.toShortString());
        // The walkway: every cell at floor level, so the rooms stand on it and the gaps are paths.
        var floor = Blocks.CONCRETE.lightGray().defaultBlockState();
        var lamp = Blocks.SEA_LANTERN.defaultBlockState();
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                level.setBlock(low.offset(dx, 0, dz), dx % 6 == 3 && dz % 6 == 3 ? lamp : floor, 18);
        BlockPos start = null;
        int rowZ = MARGIN;
        int entities = 0;
        for (var section : sections)
        {
            // The section's title on the walkway before its first row.
            ShipGallery.label(level, low.offset(MARGIN + 1, 1, rowZ - 4), DyeColor.BLUE, section.name().length() > 15 ? section.name().substring(0, 15) : section.name(), section.name().length() > 15 ? section.name().substring(15, Math.min(30, section.name().length())) : "", "", "");
            for (int i = 0; i < section.entries().size(); i++)
            {
                var entry = section.entries().get(i);
                int col = i % PER_ROW, row = i / PER_ROW;
                var min = entry.min();
                var origin = low.offset(MARGIN + col * section.pitchX() - min.getX(), -min.getY(), rowZ + row * section.pitchZ() - min.getZ());
                entry.blocks().forEach((at, state) -> level.setBlock(origin.offset(at), state, 18));
                var sign = origin.offset(entry.entryX() + 3, 1 + min.getY(), min.getZ() - 1);
                ShipGallery.label(level, sign, DyeColor.BLACK, truncate(entry.title()), truncate(entry.subtitle()), "Row " + (row + 1) + ", col " + (col + 1), "No. " + (i + 1) + " of " + section.entries().size());
                entities += RoomEntities.spawn(level, origin, entry.points());
                if (start == null)
                    start = origin.offset(entry.entryX(), 1 + min.getY(), min.getZ() - 3);
            }
            rowZ += section.rows() * section.pitchZ() + GAP;
        }
        var data = new CompoundTag();
        data.putInt("count", count);
        data.putLong("origin", low.asLong());
        WorldStore.write(marker, data);
        return new Placed(start == null ? low.offset(2, 1, 2) : start, entities);
    }

    private static String truncate(String text)
    {
        return text.length() <= 15 ? text : text.substring(0, 15);
    }

    private StationRoomGallery()
    {
    }
}
