package com.nullandkale.nullspace.worldgen.ships;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;
import com.nullandkale.nullspace.worldgen.blueprint.ShipRooms;
import com.nullandkale.nullspace.worldgen.piece.Piece;
import com.nullandkale.nullspace.worldgen.piece.PieceLibrary;
import com.nullandkale.nullspace.worldgen.shipgen.*;
import com.nullandkale.nullspace.worldgen.stations.ShipSpec;
import com.nullandkale.nullspace.worldgen.stations.ShipStructure;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.level.block.state.BlockState;

import com.google.gson.*;

/**
 * Catalog designs backed by generated plans or authored templates. An authored
 * {@code structure/ship/<id>.nbt} overrides the generator for that design.
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
                    return made.data().helm();
                var computers = blocks(this).entrySet().stream().filter(e -> e.getValue().is(ModBlocks.COMPUTER)).map(Map.Entry::getKey).toList();
                if (computers.size() != 1)
                    throw new IllegalStateException(id + " needs exactly one computer for its helm; found " + computers.size());
                return computers.getFirst();
            });
        }

        /** True for a ship planned from its rooms: the room designs, their entities and a power plant that serves doors before air. */
        public boolean planned()
        {
            var made = GENERATED.get(id);
            return made != null && made.planned && PieceLibrary.size(template()).isEmpty();
        }

        public String template()
        {
            return "ship/" + id;
        }

        public boolean tiny()
        {
            return TINY.contains(id);
        }
    }

    private static final Map<String, BlockPos> HELMS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Map<String, List<ShipRooms.Room>> ROOMS = new LinkedHashMap<>();

    /** Designs built by the ship generator; a template saved in game under the same id replaces one. */
    private static final Map<String, Made> GENERATED = new HashMap<>();

    /** A generated design's blocks, helm, size, room landmarks and the points its entities are spawned from. */
    private static final class Made
    {
        final int width, height, depth, decks;
        final boolean planned;
        private final java.util.function.Supplier<Data> source;
        private Data data;

        Made(int width, int height, int depth, int decks, boolean planned, java.util.function.Supplier<Data> source)
        {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.decks = decks;
            this.planned = planned;
            this.source = source;
        }

        /** The blocks and landmarks need the game's block registry, so they are built on first use, not when the catalog loads. */
        synchronized Data data()
        {
            if (data == null)
                data = source.get();
            return data;
        }
    }

    private record Data(Map<BlockPos, BlockState> blocks, BlockPos helm, List<ShipRooms.Room> rooms, List<Piece.Point> points)
    {}

    private static final Set<String> TINY = new HashSet<>();
    public static final String DEFAULT_STARTER = "rivet_spark";

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
            Made made = null;
            if (d.has("generate"))
            {
                var g = d.getAsJsonObject("generate");
                var maker = Spec.Maker.valueOf(g.get("maker").getAsString());
                var role = Spec.Role.valueOf(g.get("role").getAsString());
                long seed = g.get("seed").getAsLong();
                if (d.has("tiny") && d.get("tiny").getAsBoolean())
                {
                    if (!g.has("shape") || !g.get("shape").getAsString().equals("SPINE") || role != Spec.Role.SCOUT)
                        throw new IllegalStateException("Tiny designs use the two-room small scout program: " + id);
                    TINY.add(id);
                    var tiny = TinyShipGenerator.build(maker, seed);
                    made = new Made(tiny.width(), tiny.height(), tiny.depth(), tiny.decks(), false, () ->
                    {
                        var landmarks = new ArrayList<ShipRooms.Room>();
                        for (var landmark : tiny.rooms())
                            landmarks.add(new ShipRooms.Room(landmark.name(), use(landmark.use()), landmark.walk(), landmark.supplies()));
                        var blocks = new LinkedHashMap<BlockPos, BlockState>(ShipBlocks.blocks(tiny));
                        BlueprintOrientation.fix(blocks);
                        return new Data(Collections.unmodifiableMap(blocks), tiny.helm(), List.copyOf(landmarks), List.of());
                    });
                }
                else
                {
                    var ship = new ShipSpec(maker, ShipSpec.Size.valueOf(g.get("size").getAsString()), role, seed);
                    // The plan is pure geometry, so the catalog knows each ship's size and decks without touching blocks.
                    var plan = com.nullandkale.nullspace.worldgen.stations.ShipPlanner.generate(ship);
                    made = new Made(plan.width(), plan.height(), plan.depth() + com.nullandkale.nullspace.worldgen.stations.ShipPlanner.RAMP, plan.levels(), true, () ->
                    {
                        var built = ShipStructure.build(ship);
                        var landmarks = new ArrayList<ShipRooms.Room>();
                        for (var landmark : built.rooms())
                            landmarks.add(new ShipRooms.Room(landmark.name(), station(landmark.use()), landmark.walk(), landmark.supplies()));
                        var blocks = new LinkedHashMap<BlockPos, BlockState>(built.blueprint().blocks());
                        BlueprintOrientation.fix(blocks);
                        return new Data(Collections.unmodifiableMap(blocks), built.helm(), List.copyOf(landmarks), List.copyOf(built.blueprint().points()));
                    });
                }
                GENERATED.put(id, made);
            }
            var generated = made;
            var size = PieceLibrary.size("ship/" + id).orElseGet(() ->
            {
                if (generated == null)
                    throw new IllegalStateException("Ship design " + id + " has no template structure/ship/" + id + ".nbt and no generator spec");
                return new Vec3i(generated.width, generated.height, generated.depth);
            });
            int decks = d.has("decks") ? d.get("decks").getAsInt() : generated.decks;
            presets.add(new Preset(id, d.get("name").getAsString(), size.getX(), size.getY(), size.getZ(), decks, d.get("description").getAsString(), d.get("color").getAsString(), d.get("armed").getAsBoolean(), d.get("cargo").getAsBoolean(), d.get("manufacturer").getAsString(), d.get("role").getAsString()));
            if (generated == null)
            {
                var rooms = new ArrayList<ShipRooms.Room>();
                for (var r : d.getAsJsonArray("rooms"))
                {
                    var room = r.getAsJsonObject();
                    rooms.add(new ShipRooms.Room(room.get("name").getAsString(), ShipRooms.Use.valueOf(room.get("use").getAsString().toUpperCase(Locale.ROOT)), pos(room.getAsJsonArray("walk")), room.has("supplies") ? pos(room.getAsJsonArray("supplies")) : null));
                }
                ROOMS.put(id, List.copyOf(rooms));
            }
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

    private static ShipRooms.Use station(com.nullandkale.nullspace.worldgen.stations.StationPlan.Use use)
    {
        return switch (use)
        {
            case BRIDGE -> ShipRooms.Use.BRIDGE;
            case ENGINEERING -> ShipRooms.Use.ENGINEERING;
            case CARGO_HOLD, STORES, RECEIVING_BAY, MAINTENANCE, EVA_PREP -> ShipRooms.Use.CARGO;
            case WORKSHOP, FOUNDRY, ARMORY -> ShipRooms.Use.WORKSHOP;
            case KITCHEN, GROWING_BAY, GARDEN -> ShipRooms.Use.GALLEY;
            case LAB, OBSERVATION, ARCHIVE, LOOKOUT, OPERATIONS -> ShipRooms.Use.LAB;
            case INFIRMARY -> ShipRooms.Use.INFIRMARY;
            default -> ShipRooms.Use.CREW;
        };
    }

    /** The entity points (frames, stands, paintings, animals) of a planned design, in ship coordinates; empty for the rest. */
    public static List<Piece.Point> points(Preset preset)
    {
        var made = GENERATED.get(preset.id());
        return made != null && preset.planned() ? made.data().points() : List.of();
    }

    private static BlockPos pos(JsonArray xyz)
    {
        return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
    }

    /** Room landmarks (walking spot, supply container) for a design; empty for unknown ids. */
    public static List<ShipRooms.Room> rooms(String id)
    {
        var made = GENERATED.get(id);
        return made != null ? made.data().rooms() : ROOMS.getOrDefault(id, List.of());
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
            return made.data().blocks();
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
