package com.nullandkale.nullspace.worldgen.piece;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import com.google.gson.*;

/**
 * One manufacturer's piece set, {@code data/nullspace/kit/<id>.json}:
 *
 * <pre>
 * { "name": "Rivet Works",
 *   "pieces": [ { "template": "kit/rivet/nose_a", "role": "nose", "weight": 2 }, ... ],
 *   "materials": { "minecraft:white_concrete": "minecraft:stone_bricks" } }
 * </pre>
 *
 * Materials swap blocks in every piece, keeping shared properties (facing, half, shape), so pieces
 * authored in neutral colours can serve several makers.
 */
public record Kit(String id, String name, List<Entry> pieces, Map<Block, Block> materials)
{
    /** A piece with the role it plays in an archetype and its relative chance of being picked. */
    public record Entry(Piece piece, String role, int weight)
    {}

    public Kit
    {
        pieces = List.copyOf(pieces);
        materials = Map.copyOf(materials);
    }

    public List<Entry> role(String role)
    {
        return pieces.stream().filter(e -> e.role().equals(role)).toList();
    }

    /** {@code state} with this kit's material swap applied. */
    public BlockState material(BlockState state)
    {
        var target = materials.get(state.getBlock());
        if (target == null)
            return state;
        var swapped = target.defaultBlockState();
        for (Property<?> property : state.getProperties())
            swapped = copy(state, swapped, property);
        return swapped;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> property)
    {
        return to.hasProperty(property) ? to.setValue(property, from.getValue(property)) : to;
    }

    public static Kit load(String id)
    {
        try (var in = Kit.class.getResourceAsStream("/data/nullspace/kit/" + id + ".json"))
        {
            if (in == null)
                throw new IllegalArgumentException("No kit " + id);
            return parse(id, JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    public static Kit parse(String id, JsonObject json)
    {
        var entries = new ArrayList<Entry>();
        for (var element : json.getAsJsonArray("pieces"))
        {
            var p = element.getAsJsonObject();
            entries.add(new Entry(PieceLibrary.piece(p.get("template").getAsString()), p.get("role").getAsString(), p.has("weight") ? p.get("weight").getAsInt() : 1));
        }
        var materials = new LinkedHashMap<Block, Block>();
        if (json.has("materials"))
            for (var e : json.getAsJsonObject("materials").entrySet())
                materials.put(block(e.getKey()), block(e.getValue().getAsString()));
        return new Kit(id, json.has("name") ? json.get("name").getAsString() : id, entries, materials);
    }

    private static Block block(String id)
    {
        var key = Identifier.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(key))
            throw new IllegalArgumentException("Unknown block in kit materials: " + id);
        return BuiltInRegistries.BLOCK.getValue(key);
    }
}
