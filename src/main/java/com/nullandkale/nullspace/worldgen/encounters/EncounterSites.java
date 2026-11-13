package com.nullandkale.nullspace.worldgen.encounters;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.station.StationAccess;
import com.nullandkale.nullspace.gameplay.station.StationBuilds;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.EncounterKind;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.blueprint.ShipRooms;
import com.nullandkale.nullspace.worldgen.blueprint.VesselTemplate;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import com.nullandkale.nullspace.worldgen.stations.ExistingStationPlans;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Uncrewed, salvageable places. A saved identity owns real blocks and finite supplies. */
public final class EncounterSites
{
    private static final ServerContext.Key<Map<String, Integer>> RETRY = new ServerContext.Key<>();

    public static StationBlueprint.Plan plan(int kind, long seed)
    {
        EncounterKind.saved(kind);
        if (kind == EncounterKind.ABANDONED_STATION.id)
            return ExistingStationPlans.build(ExistingStationPlans.encounter(seed));
        if (kind == 0)
            return growing(seed);
        if (kind == 3)
            return cargoPod(seed);
        String design = design(kind, seed);
        var preset = ShipBlueprints.get(design);
        var blocks = new LinkedHashMap<>(ShipBlueprints.blocks(preset));
        var controller = preset.helm();
        blocks.put(controller, ModBlocks.STATION_CORE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, blocks.get(controller).getValue(HorizontalDirectionalBlock.FACING)));
        if (kind == 1)
        {
            // A blocked interior cargo door is repairable with an ordinary pickaxe. The dock stays
            // intact.
            blocks.put(new BlockPos(12, 1, 10), Blocks.COBBLESTONE.defaultBlockState());
            blocks.put(new BlockPos(12, 2, 10), Blocks.COBBLESTONE.defaultBlockState());
        }
        return new StationBlueprint.Plan(blocks, controller, docks(blocks));
    }

    public static String design(int kind, long seed)
    {
        if (kind == 0 || kind == 3 || kind == EncounterKind.ABANDONED_STATION.id)
            return "";
        var designs = kind == 1 ? List.of("rivet_hauler", "halcyon_courier", "bastion_tender") : List.of("rivet_bolt", "halcyon_swift", "bastion_ward", "rivet_ranger", "halcyon_horizon", "bastion_pioneer");
        designs = designs.stream().filter(id -> !ShipBlueprints.get(id).tiny()).toList();
        return designs.get((int) Math.floorMod(seed, designs.size()));
    }

    private static List<BlockPos> docks(Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks)
    {
        return blocks.entrySet().stream().filter(e -> e.getValue().is(ModBlocks.DOCKING_BASE)).map(Map.Entry::getKey).toList();
    }

    private static StationBlueprint.Plan growing(long seed)
    {
        var t = new VesselTemplate();
        var footprint = new LinkedHashSet<BlockPos>();
        VesselTemplate.rectangle(footprint, 2, 2, 18, 22);
        var hull = switch ((int) Math.floorMod(seed, 3))
        {
            case 0 -> Blocks.STONE_BRICKS;
            case 1 -> Blocks.QUARTZ_BLOCK;
            default -> Blocks.POLISHED_DEEPSLATE;
        };
        t.shell(footprint, 0, 7, hull, Blocks.POLISHED_ANDESITE);
        t.bulkhead(9, 3, 17, 0, 7, 10, hull);
        t.dock(new BlockPos(10, 1, 2), Direction.NORTH, hull);
        var core = new BlockPos(4, 1, 5);
        t.machine(core, ModBlocks.STATION_CORE, Direction.EAST);
        t.machine(new BlockPos(4, 1, 6), ModBlocks.GENERATOR, Direction.EAST);
        t.machine(new BlockPos(4, 1, 7), ModBlocks.BATTERY, Direction.EAST);
        t.machine(new BlockPos(5, 1, 3), ModBlocks.AIR_SUPPLY, Direction.SOUTH);
        t.machine(new BlockPos(5, 1, 21), ModBlocks.AIR_SUPPLY, Direction.NORTH);
        t.put(15, 1, 5, Blocks.BARREL);
        t.put(15, 1, 6, Blocks.CRAFTING_TABLE);
        t.put(15, 1, 7, Blocks.FURNACE);
        for (int x : new int[]{5, 6, 7, 13, 14, 15})
        {
            for (int z = 12; z <= 19; z++)
            {
                t.state(new BlockPos(x, 0, z), Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7));
                t.state(new BlockPos(x, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
        }
        for (int x : new int[]{4, 16})
            for (int z = 12; z <= 19; z++)
                t.put(x, 0, z, Blocks.WATER);
        // Water is inside an intact pressure hull, with a solid bottom below it.
        // Raise the complete plan by one block to leave room for that bottom.
        t.light(10, 6, 5);
        t.light(7, 6, 15);
        t.light(13, 6, 15);
        var blocks = new LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        t.finish(21, 8, 25).forEach((p, state) -> blocks.put(p.above(), state));
        for (int x = 2; x <= 18; x++)
            for (int z = 2; z <= 22; z++)
                blocks.put(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE.defaultBlockState());
        return new StationBlueprint.Plan(blocks, core.above(), docks(blocks));
    }

    /** Barrels in the drifting cargo pod; the first holds the site's treasure. */
    public static final List<BlockPos> POD_CRATES = List.of(new BlockPos(11, 1, 4), new BlockPos(11, 1, 5), new BlockPos(11, 1, 7), new BlockPos(11, 1, 8), new BlockPos(10, 1, 8), new BlockPos(11, 2, 8));

    /** A small sealed cargo pod, adrift with no power or air: one dock, a core and a few crates. */
    private static StationBlueprint.Plan cargoPod(long seed)
    {
        var t = new VesselTemplate();
        var footprint = new LinkedHashSet<BlockPos>();
        VesselTemplate.rectangle(footprint, 2, 2, 12, 10);
        var hull = switch ((int) Math.floorMod(seed, 3))
        {
            case 0 -> Blocks.DYED_TERRACOTTA.orange();
            case 1 -> Blocks.DYED_TERRACOTTA.lightGray();
            default -> Blocks.DYED_TERRACOTTA.cyan();
        };
        t.shell(footprint, 0, 6, hull, Blocks.SMOOTH_STONE);
        t.dock(new BlockPos(7, 1, 2), Direction.NORTH, hull);
        var core = new BlockPos(3, 1, 6);
        t.machine(core, ModBlocks.STATION_CORE, Direction.EAST);
        for (var crate : POD_CRATES)
            t.put(crate.getX(), crate.getY(), crate.getZ(), Blocks.BARREL);
        t.light(7, 5, 6);
        // A mast with a red beacon so the pod reads as cargo from outside.
        t.put(7, 6, 8, Blocks.END_ROD);
        t.put(7, 7, 8, Blocks.REDSTONE_LAMP);
        var blocks = new LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        t.finish(15, 9, 13).forEach(blocks::put);
        return new StationBlueprint.Plan(blocks, core, docks(blocks));
    }

    /**
     * Journey signals vanish: once no ship's journey refers to a signal and no other ship is in its
     * region, its site is disposed of and the region forgotten. A claimed site is a player's ship and
     * keeps its region. FTL corridors left behind by finished journeys are forgotten the same way.
     */
    public static void vanish(MinecraftServer server)
    {
        var store = WorldStore.get(server);
        var referenced = new HashSet<String>();
        var present = new HashMap<String, Set<String>>();
        for (var tag : store.ships())
        {
            var ship = (CompoundTag) tag;
            var journey = ship.getCompoundOrEmpty("journey");
            if (!journey.isEmpty())
            {
                referenced.add(journey.getStringOr("encounter", ""));
                referenced.add(journey.getStringOr("corridor", ""));
            }
            if (ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()))
                present.computeIfAbsent(LocalSpaces.address(ship), k -> new HashSet<>()).add(ship.getStringOr("id", ""));
        }
        var sites = store.section("encounters");
        var regions = store.section("journeyRegions");
        boolean changed = false;
        for (var id : List.copyOf(sites.keySet()))
        {
            if (referenced.contains(id))
                continue;
            var site = sites.getCompoundOrEmpty(id);
            String vesselId = site.getStringOr("vessel", "");
            var others = new HashSet<>(present.getOrDefault(id, Set.of()));
            others.remove(vesselId);
            if (!others.isEmpty())
                continue;
            var vessel = store.repository().find(vesselId);
            if (vessel != null)
            {
                if (!vessel.getStringOr("owner", "").isEmpty() || !StationAccess.ready(vessel))
                    continue;
                SpacePopulation.dispose(server, vessel);
            }
            sites.remove(id);
            regions.remove(id);
            changed = true;
            NullSpace.LOGGER.info("Signal {} faded", id);
        }
        for (var id : List.copyOf(regions.keySet()))
            if (!referenced.contains(id) && !sites.contains(id) && !present.containsKey(id))
            {
                regions.remove(id);
                changed = true;
            }
        if (changed)
            store.requestSave();
    }

    public static void tick(MinecraftServer server)
    {
        if (server.getTickCount() % 20 != 0 || StationAccess.building(server))
            return;
        vanish(server);
        var store = WorldStore.get(server);
        var retry = ServerContext.get(server).service(RETRY, HashMap::new);
        for (var tag : List.copyOf(store.ships()))
        {
            var traveller = (CompoundTag) tag;
            if (!JourneyService.contact(traveller))
                continue;
            String address = traveller.getCompoundOrEmpty("journey").getStringOr("encounter", "");
            var site = store.section("encounters").getCompoundOrEmpty(address);
            if (!site.getStringOr("state", "").equals("planned") || retry.getOrDefault(address, 0) > server.getTickCount())
                continue;
            try
            {
                String id = UUID.nameUUIDFromBytes(("nullspace:" + address).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                var metadata = new CompoundTag();
                metadata.putBoolean("worldGenerated", true);
                metadata.putBoolean("salvage", true);
                metadata.putInt("encounterKind", site.getIntOr("kind", 0));
                metadata.putLong("encounterSeed", site.getLongOr("seed", 1));
                metadata.putInt("encounterDanger", site.getIntOr("danger", 0));
                metadata.putString("encounterDesign", design(site.getIntOr("kind", 0), site.getLongOr("seed", 1)));
                StationBlueprint.Plan blueprint;
                if (site.getIntOr("kind", 0) == EncounterKind.ABANDONED_STATION.id)
                {
                    var spec = site.contains("stationSpec") ? StationSpec.read(site.getCompoundOrEmpty("stationSpec")) : ExistingStationPlans.encounter(site.getLongOr("seed", 1));
                    blueprint = ExistingStationPlans.build(spec);
                    metadata.put("stationSpec", spec.save());
                    metadata.put("roomPieces", StationLayout.pieces(StationLayout.generate(spec.seed())));
                }
                else
                    blueprint = plan(site.getIntOr("kind", 0), site.getLongOr("seed", 1));
                var vessel = StationBuilds.queueAmbient(server, id, site.getStringOr("name", "Signal"), address, new Vec3(0, 0, 600), blueprint, metadata);
                if (vessel == null)
                {
                    retry.put(address, server.getTickCount() + 1200);
                    NullSpace.LOGGER.warn("No clear storage for encounter {}; retrying in 60 seconds", address);
                    return;
                }
                site.putString("vessel", id);
                site.putString("state", "placed");
                store.requestSave();
            }
            catch (RuntimeException failure)
            {
                retry.put(address, server.getTickCount() + 1200);
                NullSpace.LOGGER.error("Encounter construction failed for {}; saved plan retained, retrying in 60 seconds", address, failure);
            }
            return;
        }
    }

    /**
     * Called only during inaccessible initial construction, before the ready marker is saved. Each
     * supply container gets its room's loot table; one gets the site's treasure, which scales with
     * the zone's danger. Loot rolls when a player first opens it.
     */
    public static void stock(ServerLevel level, CompoundTag ship)
    {
        if (ship.getBooleanOr("encounterStocked", false))
            return;
        int kind = ship.getIntOr("encounterKind", 0);
        var base = WorldStore.pos(ship, "base");
        long seed = ship.getLongOr("encounterSeed", 1) ^ ship.getLongOr("resets", 0) * 0x9e3779b97f4a7c15L;
        String treasure = SiteLoot.treasure(level.getServer(), LocalSpaces.address(ship));
        var points = SitePoints.read(ship);
        if (!points.isEmpty())
            SitePoints.stock(level, base, points, treasure, seed, ship.getStringOr("name", "Salvage") + " supplies");
        else if (kind == 3)
        {
            int index = 0;
            for (var crate : POD_CRATES)
                SiteLoot.assign(level, base.offset(crate), index == 0 ? treasure : "cargo", seed + index++, "Drifting cargo");
        }
        else if (kind == 0)
        {
            SiteLoot.assign(level, base.offset(15, 2, 5), treasure.equals("treasure_0") ? "garden" : treasure, seed, "Growing station supplies");
        }
        else
        {
            var rooms = ShipRooms.rooms(ship.getStringOr("encounterDesign", design(kind, seed)));
            var supplied = rooms.stream().filter(r -> r.supplies() != null).toList();
            var hoard = supplied.stream().filter(r -> r.use() == ShipRooms.Use.CARGO).findFirst().or(() -> supplied.stream().filter(r -> r.use() == ShipRooms.Use.WORKSHOP || r.use() == ShipRooms.Use.LAB).findFirst()).or(() -> supplied.stream().findFirst()).orElse(null);
            int index = 0;
            for (var room : supplied)
            {
                String table = room == hoard ? treasure : switch (room.use())
                {
                    case ENGINEERING -> "engineering";
                    case GALLEY -> "galley";
                    case CARGO -> "cargo";
                    case WORKSHOP, LAB -> "workshop";
                    default -> "crew";
                };
                SiteLoot.assign(level, base.offset(room.supplies()), table, seed + index++, room.name() + " supplies");
            }
        }
        ship.putBoolean("encounterStocked", true);
    }

    private EncounterSites()
    {
    }
}
