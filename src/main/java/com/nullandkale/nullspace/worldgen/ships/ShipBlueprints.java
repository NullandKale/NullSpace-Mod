package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;
import com.nullandkale.nullspace.worldgen.blueprint.ShipRooms;
import com.nullandkale.nullspace.worldgen.piece.PieceLibrary;
import com.nullandkale.nullspace.worldgen.shipgen.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.level.block.state.BlockState;

import com.google.gson.*;

/**
 * The shipyard designs. Each is a template, {@code structure/ship/<id>.nbt}, edited in game with the
 * authoring tools, plus an entry in {@code catalog/ships.json} with its name, maker, role and room
 * landmarks. Size comes from the template and the helm is its computer.
 */
public final class ShipBlueprints
{
    public record Preset(String id, String name, int width, int height, int depth, int decks, String description, String color, boolean armed, boolean cargo, String manufacturer, String role)
    {
        public String size()
        {
            return width + " x " + height + " x " + depth;
        }

        public BlockPos helm()
        {
            return HELMS.computeIfAbsent(id, key ->
            {
                var made = GENERATED.get(id);
                if (made != null && PieceLibrary.size(template()).isEmpty())
                    return made.helm();
                var computers = blocks(this).entrySet().stream().filter(e -> e.getValue().is(ModBlocks.COMPUTER)).map(Map.Entry::getKey).toList();
                if (computers.size() != 1)
                    throw new IllegalStateException(id + " needs exactly one computer for its helm; found " + computers.size());
                return computers.getFirst();
            });
        }

        public String template()
        {
            return "ship/" + id;
        }
    }

    private static final Map<String, BlockPos> HELMS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Map<String, List<ShipRooms.Room>> ROOMS = new LinkedHashMap<>();

    /** Designs built by the ship generator; a template saved in game under the same id replaces one. */
    private static final Map<String, ShipGenerator.Ship> GENERATED = new HashMap<>();

    private static final Map<String, Map<BlockPos, BlockState>> BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static final List<Preset> PRESETS = load();

    private static List<Preset> load()
    {
        JsonArray designs;
        try (var in = ShipBlueprints.class.getResourceAsStream("/data/nullspace/catalog/ships.json"))
        {
            if (in == null)
                throw new IllegalStateException("Missing catalog/ships.json");
            designs = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        var presets = new ArrayList<Preset>();
        for (var element : designs)
        {
            var d = element.getAsJsonObject();
            String id = d.get("id").getAsString();
            if (!id.matches("[a-z_]{1,32}") || presets.stream().anyMatch(p -> p.id().equals(id)))
                throw new IllegalStateException("Invalid or duplicate ship design id " + id);
            ShipGenerator.Ship made = null;
            if (d.has("generate"))
            {
                var g = d.getAsJsonObject("generate");
                made = ShipGenerator.build(new Spec(Spec.Maker.valueOf(g.get("maker").getAsString()), Spec.Shape.valueOf(g.get("shape").getAsString()), Spec.Tier.valueOf(g.get("tier").getAsString()), Spec.Role.valueOf(g.get("role").getAsString()), g.get("seed").getAsLong()));
                GENERATED.put(id, made);
            }
            var generated = made;
            var size = PieceLibrary.size("ship/" + id).orElseGet(() ->
            {
                if (generated == null)
                    throw new IllegalStateException("Ship design " + id + " has no template structure/ship/" + id + ".nbt and no generator spec");
                return new Vec3i(generated.width(), generated.height(), generated.depth());
            });
            int decks = d.has("decks") ? d.get("decks").getAsInt() : generated.decks();
            presets.add(new Preset(id, d.get("name").getAsString(), size.getX(), size.getY(), size.getZ(), decks, d.get("description").getAsString(), d.get("color").getAsString(), d.get("armed").getAsBoolean(), d.get("cargo").getAsBoolean(), d.get("manufacturer").getAsString(), d.get("role").getAsString()));
            var rooms = new ArrayList<ShipRooms.Room>();
            if (generated != null)
                for (var landmark : generated.rooms())
                    rooms.add(new ShipRooms.Room(landmark.name(), use(landmark.use()), landmark.walk(), landmark.supplies()));
            else
                for (var r : d.getAsJsonArray("rooms"))
                {
                    var room = r.getAsJsonObject();
                    rooms.add(new ShipRooms.Room(room.get("name").getAsString(), ShipRooms.Use.valueOf(room.get("use").getAsString().toUpperCase(Locale.ROOT)), pos(room.getAsJsonArray("walk")), room.has("supplies") ? pos(room.getAsJsonArray("supplies")) : null));
                }
            ROOMS.put(id, List.copyOf(rooms));
        }
        return List.copyOf(presets);
    }

    private static ShipRooms.Use use(RoomUse use)
    {
        return switch (use)
        {
            case BRIDGE -> ShipRooms.Use.BRIDGE;
            case ENGINEERING -> ShipRooms.Use.ENGINEERING;
            case CARGO, STORAGE, MINING, ENTRY -> ShipRooms.Use.CARGO;
            case CRAFTING, SMELTING, ENCHANTING, BREWING -> ShipRooms.Use.WORKSHOP;
            case GALLEY, HYDROPONICS -> ShipRooms.Use.GALLEY;
            case LAB, VIEWING -> ShipRooms.Use.LAB;
            default -> ShipRooms.Use.CREW;
        };
    }

    private static BlockPos pos(JsonArray xyz)
    {
        return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
    }

    /** Room landmarks (walking spot, supply container) for a design; empty for unknown ids. */
    public static List<ShipRooms.Room> rooms(String id)
    {
        return ROOMS.getOrDefault(id, List.of());
    }

    /** Air request (power per Air Supply) for a delivered ship: normal output. */
    public static int airDemand(Preset preset)
    {
        return SpatialAir.SUPPLY_POWER;
    }

    /** Designs that were retired when ships became generated, and what replaces each, so old saves, settings and servers keep working. */
    private static final Map<String, String> RETIRED = Map.of("sparrow", "rivet_bolt", "skylark", "halcyon_swift", "mule", "rivet_hauler", "wayfarer", "rivet_ranger", "aegis", "bastion_ward", "atlas", "rivet_ranger");

    public static Preset get(String id)
    {
        var wanted = id == null ? null : RETIRED.getOrDefault(id, id);
        return PRESETS.stream().filter(p -> p.id().equals(wanted)).findFirst().orElseThrow(() -> new IllegalArgumentException("Choose a ship from the beacon catalog."));
    }

    /** Sizes are width/height/depth; never rotate a hull or silently use a different pad layer. */
    public static BlockPos origin(Preset ship, PadScanner.Pad pad, int height)
    {
        if (ship.width() > pad.width() || ship.depth() > pad.depth() || ship.height() > height)
            throw new IllegalArgumentException(ship.name() + " needs " + ship.width() + " x " + ship.depth() + " floor space and " + ship.height() + " blocks of height. This pad has " + pad.width() + " x " + pad.depth() + " and " + height + " blocks of height.");
        return new BlockPos(pad.minX() + (pad.width() - ship.width()) / 2, pad.y() + 1, pad.minZ() + (pad.depth() - ship.depth()) / 2);
    }

    /**
     * The design's blocks, without air, with facing and connections settled (see
     * BlueprintOrientation) in case an in-game edit left a block facing a wall.
     */
    public static Map<BlockPos, BlockState> blocks(Preset p)
    {
        var made = GENERATED.get(p.id());
        if (made != null && PieceLibrary.size(p.template()).isEmpty())
            return BLOCKS.computeIfAbsent(p.id(), id ->
            {
                var out = new LinkedHashMap<BlockPos, BlockState>(ShipBlocks.blocks(made));
                BlueprintOrientation.fix(out);
                return Collections.unmodifiableMap(out);
            });
        var template = PieceLibrary.template(p.template()).orElseThrow(() -> new IllegalStateException("Missing template for " + p.id()));
        var out = new LinkedHashMap<BlockPos, BlockState>();
        template.blocks().forEach((at, state) ->
        {
            if (!state.isAir())
                out.put(at, state);
        });
        BlueprintOrientation.fix(out);
        return Collections.unmodifiableMap(out);
    }

    /** Forgets cached helms after a design's template is edited in game. */
    public static void invalidate()
    {
        HELMS.clear();
        BLOCKS.clear();
    }

    private ShipBlueprints()
    {
    }
}
