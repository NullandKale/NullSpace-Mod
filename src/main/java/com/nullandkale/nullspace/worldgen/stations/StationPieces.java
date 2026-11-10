package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.worldgen.shipgen.StationGenerator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.core.Direction;

import com.google.gson.JsonParser;

/**
 * The pieces a station tile can hold, {@code catalog/station_pieces.json}: rooms, tunnels and
 * domes, each in a few-windows and a many-windows version ({@code <id>_bright}). Every piece is
 * generated: it names the kit that furnishes it ({@link StationGenerator.Kit}), its window level,
 * its role and the sides that have doorway slots in its unturned orientation.
 */
public final class StationPieces
{
    public record Definition(String id, String name, StationLayout.Role role, StationGenerator.Kit kit, int windows, Set<Direction> doors)
    {
        public Definition
        {
            doors = Set.copyOf(doors);
        }

        /** The id without the window suffix: the family a piece belongs to. */
        public String family()
        {
            return id.endsWith("_bright") ? id.substring(0, id.length() - "_bright".length()) : id;
        }
    }

    public static final List<Definition> ALL = load();

    private static List<Definition> load()
    {
        try (var in = StationPieces.class.getResourceAsStream("/data/nullspace/catalog/station_pieces.json"))
        {
            if (in == null)
                throw new IllegalStateException("Missing catalog/station_pieces.json");
            var list = new ArrayList<Definition>();
            for (var element : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray())
            {
                var d = element.getAsJsonObject();
                var doors = EnumSet.noneOf(Direction.class);
                for (var door : d.getAsJsonArray("doors"))
                    doors.add(Direction.valueOf(door.getAsString().toUpperCase(Locale.ROOT)));
                list.add(new Definition(d.get("id").getAsString(), d.get("name").getAsString(), StationLayout.Role.valueOf(d.get("role").getAsString().toUpperCase(Locale.ROOT)), StationGenerator.Kit.valueOf(d.get("kit").getAsString()), d.get("windows").getAsInt(), doors));
            }
            return List.copyOf(list);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    public static Optional<Definition> find(String id)
    {
        return ALL.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public static Definition get(String id)
    {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown station piece " + id));
    }

    /** The plain room for a role (the piece whose id is the role's name). */
    public static Definition forRole(StationLayout.Role role)
    {
        return get(role.name().toLowerCase(Locale.ROOT));
    }

    private StationPieces()
    {
    }
}
