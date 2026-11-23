package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.Jumps;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.ownership.CrewRelease;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.galaxy.GalaxyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.storage.LevelResource;

/** New arrivals are commissioned after login registration, never inside the JOIN callback. */
public final class SpaceStart
{
    private static final ServerContext.Key<WorldStartSettings> SETTINGS = new ServerContext.Key<>();
    private static final ServerContext.Key<Map<UUID, Integer>> ARRIVALS = new ServerContext.Key<>();
    /** New arrivals whose cover has not gone up yet, with the tick they joined. */
    private static final ServerContext.Key<Map<UUID, Integer>> UNCOVERED = new ServerContext.Key<>();
    /** How long a start waits for the client to take the cover before building anyway. */
    private static final int COVER_WAIT = 100;
    private static final ServerContext.Key<Map<UUID, Integer>> FAILURES = new ServerContext.Key<>();
    private static final String SUPPLIES = "nullspace_start_supplies";

    public static void initialize()
    {
        StartPackets.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
        {
            var file = server.isDedicatedServer() ? server.getServerDirectory().resolve("server.properties") : server.getWorldPath(LevelResource.ROOT).resolve(WorldStartSettings.FILE);
            try
            {
                ServerContext.get(server).put(SETTINGS, WorldStartSettings.read(file));
            }
            catch (IOException | IllegalArgumentException failure)
            {
                // Invalid configuration must not silently start players with a different ship.
                throw new IllegalStateException("Cannot read NullSpace start settings from " + file, failure);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
        {
            var player = handler.player;
            var settings = settings(server);
            if (!settings.space())
                return;
            if (!firstArrival(player))
                return;
            ServerContext.get(server).service(ARRIVALS, HashMap::new).put(player.getUUID(), 0);
            // The client registers the mod's play channels just after joining, so the cover goes up from the tick, before anything is built.
            ServerContext.get(server).service(UNCOVERED, HashMap::new).put(player.getUUID(), server.getTickCount());
        });
        ServerContext.tick(ServerContext.Phase.SESSIONS, server ->
        {
            var waiting = ServerContext.get(server).existing(ARRIVALS);
            if (waiting == null)
                return;
            for (var id : List.copyOf(waiting.keySet()))
            {
                var player = server.getPlayerList().getPlayer(id);
                if (player == null)
                {
                    waiting.remove(id);
                    continue;
                }
                if (waiting.get(id) > server.getTickCount())
                    continue;
                var uncovered = ServerContext.get(server).service(UNCOVERED, HashMap::new);
                if (uncovered.containsKey(id))
                {
                    boolean ready = StartPackets.canSend(player);
                    if (!ready && server.getTickCount() - uncovered.get(id) < COVER_WAIT)
                        continue;
                    uncovered.remove(id);
                    StartPackets.send(player, false, settings(server).crash() ? "Your ship is coming down..." : "Preparing your ship...");
                    // Packets arrive in order: the cover is up before the teleport that follows.
                }
                try
                {
                    start(player, settings(server));
                    waiting.remove(id);
                    StartPackets.send(player, true, "");
                }
                catch (RuntimeException failure)
                {
                    var failures = ServerContext.get(server).service(FAILURES, HashMap::new);
                    if (failures.merge(id, 1, Integer::sum) >= 3 && settings(server).crash())
                    {
                        // Joining must never fail: after three tries, the player is put on the surface with their supplies.
                        try
                        {
                            CrashStart.fallback(player, settings(server), failure);
                            waiting.remove(id);
                            StartPackets.send(player, true, "");
                            continue;
                        }
                        catch (RuntimeException last)
                        {
                            NullSpace.LOGGER.error("The crash start fallback failed for {}", id, last);
                        }
                    }
                    waiting.put(id, server.getTickCount() + 200);
                    NullSpace.LOGGER.error("Space start failed for {}; the saved starting ship will be retried", id, failure);
                    player.sendSystemMessage(Component.literal("Your starting ship could not finish setting up. The server will retry. See" + " the server log for details."));
                    StartPackets.send(player, false, "Your starting ship could not finish setting up. Retrying in 10 seconds...");
                }
            }
        });
    }

    public static WorldStartSettings settings(MinecraftServer server)
    {
        var settings = ServerContext.get(server).existing(SETTINGS);
        return settings == null ? WorldStartSettings.DEFAULT : settings;
    }

    public static boolean firstArrival(ServerPlayer player)
    {
        var server = player.level().getServer();
        var previous = WorldStore.get(server).section("spaceStarts").getCompoundOrEmpty(player.getUUID().toString());
        if (previous.getBooleanOr("completed", false))
            return false;
        if (!previous.isEmpty())
            return true; // A saved construction intent can resume after login.
        // A player with saved data (or its backup) has played this world before.
        var data = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        return !Files.exists(data.resolve(player.getUUID() + ".dat")) && !Files.exists(data.resolve(player.getUUID() + ".dat_old"));
    }

    /** Stable random choice per new player and world; only landable planets, never moons. */
    public static String planet(MinecraftServer server, UUID player)
    {
        // P9: start in the home system, over a planet you can land on.
        var planets = StarSystems.galaxy(server).place(GalaxyPlan.HOME) instanceof GalaxyPlan.StarSystem sol ? sol.planets().stream().filter(GalaxyPlan.Body::landable).toList() : List.<GalaxyPlan.Body>of();
        if (planets.isEmpty())
            throw new IllegalStateException("No starting planets are available");
        var random = new Random(server.overworld().getSeed() ^ player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 17));
        return planets.get(random.nextInt(planets.size())).id();
    }

    public static CompoundTag start(ServerPlayer player, WorldStartSettings settings)
    {
        if (!settings.space())
            return null;
        var server = player.level().getServer();
        var store = WorldStore.get(server);
        var starts = store.section("spaceStarts");
        String key = player.getUUID().toString();
        var record = starts.getCompoundOrEmpty(key);
        if (settings.crash() || record.getBooleanOr("crash", false))
            return CrashStart.start(player, settings);
        if (record.getBooleanOr("completed", false))
            return store.repository().find(record.getStringOr("ship", ""));
        var level = server.getLevel(TravelService.SPACE);
        if (level == null)
            throw new IllegalStateException("The space dimension is unavailable");
        if (record.isEmpty())
        {
            var before = store.snapshot();
            record.putString("ship", UUID.randomUUID().toString());
            record.putString("preset", settings.ship());
            record.putString("planet", orbitPlanet(server, player.getUUID(), settings));
            starts.put(key, record);
            store.requestSave();
            com.nullandkale.nullspace.gameplay.travel.RecordJournal.commit(server, "prepare-space-start", before);
        }
        PlanetSurfaces.ensureLanding(server, record.getStringOr("planet", LocalSpaces.HOME));
        var preset = ShipBlueprints.get(record.getStringOr("preset", "rivet_bolt"));
        var ship = store.repository().find(record.getStringOr("ship", ""));
        com.nullandkale.nullspace.gameplay.travel.BuildJournal.Job construction = null;
        if (ship == null)
        {
            BlockPos base = null;
            for (int slot = 0; slot <= 7000 && base == null; slot++)
            {
                if (!TransferLocks.spaceSlotFree(server, slot))
                    continue;
                var candidate = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
                for (int x = 0; x < 32; x += 16)
                    for (int z = 0; z < 32; z += 16)
                        level.getChunk(candidate.offset(x, 0, z));
                boolean clear = true;
                for (var at : BlockPos.betweenClosed(candidate, candidate.offset(31, 31, 31)))
                    if (!level.getBlockState(at).isAir())
                    {
                        clear = false;
                        break;
                    }
                if (clear && level.getEntities(null, new HullScan.Bounds(candidate, candidate.offset(31, 31, 31)).box()).isEmpty())
                    base = candidate;
            }
            if (base == null)
                throw new IllegalStateException("No clear starting ship region remains");
            ship = new CompoundTag();
            ship.putString("id", record.getStringOr("ship", ""));
            ship.putString("dimension", TravelService.SPACE.identifier().toString());
            ship.putLong("base", base.asLong());
            ship.putLong("controller", base.offset(preset.helm()).asLong());
            ship.putInt("width", 32);
            ship.putInt("height", 32);
            ship.putInt("depth", 32);
            ship.putString("blueprint", preset.id());
            ship.putString("name", preset.name());
            ship.putString("owner", key);
            ship.putString("localSpace", record.getStringOr("planet", LocalSpaces.HOME));
            ship.put("crew", new ListTag());
            var arrival = Jumps.arrivalOrigin(server, ship);
            ship.putDouble("simX", arrival.x);
            ship.putDouble("simY", arrival.y);
            ship.putDouble("simZ", arrival.z);
            construction = com.nullandkale.nullspace.gameplay.travel.BuildJournal.begin(level, "starting-ship", List.of(ship.getStringOr("id", "")), new HullScan.Bounds(base, base.offset(31, 31, 31)));
            var allocated = ship;
            var prepared = construction;
            construction.step(() ->
            {
                allocated.putString("buildJournal", prepared.fileName());
                store.ships().add(allocated);
                store.requestSave();
                return null;
            });
        }
        if (construction == null)
            construction = com.nullandkale.nullspace.gameplay.travel.BuildJournal.begin(level, "starting-ship-migration", List.of(ship.getStringOr("id", "")), WorldStore.bounds(ship));
        var startedShip = ship;
        var at = construction.step(() -> buildHull(player, level, store, startedShip, preset, key));
        construction.commitNow();
        var controller = WorldStore.pos(ship, "controller");
        var facing = level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING);
        player.teleportTo(level, at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), facing.getOpposite().toYRot(), 0, true);
        giveSupplies(player);
        player.setAirSupply(player.getMaxAirSupply());
        server.getPlayerList().saveAll();
        var beforeCompletion = store.snapshot();
        record.putBoolean("completed", true);
        store.requestSave();
        com.nullandkale.nullspace.gameplay.travel.RecordJournal.commit(server, "complete-space-start", beforeCompletion);
        player.sendSystemMessage(Component.literal("Welcome aboard " + preset.name() + ". You are above " + LocalSpaces.get(server, LocalSpaces.address(ship)).name() + ". Use the Ship Controller to explore. Fuel is in the generator; food is in your" + " inventory."));
        return ship;
    }

    /** The starting supplies, once: a tag stops a second grant. */
    public static void giveSupplies(ServerPlayer player)
    {
        if (!player.entityTags().contains(SUPPLIES))
        {
            player.getInventory().add(new ItemStack(Items.BREAD, 16));
            player.getInventory().add(new ItemStack(Items.COAL, 16));
            player.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
            player.getInventory().add(new ItemStack(Items.IRON_AXE));
            player.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModBlocks.HELMET));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModBlocks.CHEST));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModBlocks.LEGS));
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModBlocks.BOOTS));
            FieldGuide.supply(player);
            player.addTag(SUPPLIES);
        }
    }

    /**
     * What a crash survivor has: food, the suit and the field guide, and nothing else. No tools, no fuel and no stone: the
     * ship's wrecks and furnishings are the first materials, and a tool is the first thing to make.
     */
    public static void giveCrashSupplies(ServerPlayer player)
    {
        if (!player.entityTags().contains(SUPPLIES))
        {
            player.getInventory().add(new ItemStack(Items.BREAD, 16));
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModBlocks.HELMET));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModBlocks.CHEST));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModBlocks.LEGS));
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModBlocks.BOOTS));
            FieldGuide.supply(player);
            player.addTag(SUPPLIES);
        }
    }

    /** The planet an orbit start is over: the chosen one, or the existing draw from the home system when the choice is random. */
    private static String orbitPlanet(MinecraftServer server, UUID player, WorldStartSettings settings)
    {
        if (settings.planet().equals("random"))
            return planet(server, player);
        return StartPlanets.resolve(StarSystems.galaxy(server), settings.planet(), server.overworld().getSeed() ^ player.getMostSignificantBits()).id();
    }

    private static BlockPos buildHull(ServerPlayer player, net.minecraft.server.level.ServerLevel level, WorldStore store, CompoundTag ship, ShipBlueprints.Preset preset, String key)
    {
        var base = WorldStore.pos(ship, "base");
        for (int x = 0; x < 32; x += 16)
            for (int z = 0; z < 32; z += 16)
                level.getChunk(base.offset(x, 0, z));
        var plan = ShipBlueprints.blocks(preset);
        for (var entry : plan.entrySet())
        {
            var at = base.offset(entry.getKey());
            var existing = level.getBlockState(at);
            if (!existing.isAir() && !existing.equals(entry.getValue()))
                throw new IllegalStateException("Starting ship construction is obstructed at " + at.toShortString());
            if (existing.isAir() && !level.setBlock(at, entry.getValue(), 18))
                throw new IllegalStateException("Cannot place starting ship block at " + at.toShortString());
        }
        for (var entry : plan.entrySet())
            if (entry.getValue().is(ModBlocks.GENERATOR))
                FuelSystem.provision(level, base.offset(entry.getKey()), 32);
        if (preset.planned())
        {
            ship.putBoolean("generatedRooms", true);
            if (!ship.getBooleanOr("roomEntities", false))
            {
                com.nullandkale.nullspace.gameplay.shipyard.RoomEntities.spawn(level, base, ShipBlueprints.points(preset));
                ship.putBoolean("roomEntities", true);
            }
        }
        SystemsUpgrade.upgrade(level, ship);
        ship.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
        var controller = WorldStore.pos(ship, "controller");
        var facing = level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING);
        var at = controller.relative(facing);
        if (!ShipAir.breathable(level, ship, at) || !ShipAir.breathable(level, ship, at.above()))
            throw new IllegalStateException("Starting ship does not have a breathable cabin");
        CrewRelease.authorize(ship, player);
        var member = new CompoundTag();
        member.putString("id", key);
        member.putDouble("x", at.getX() - base.getX() + .5);
        member.putDouble("y", at.getY() - base.getY());
        member.putDouble("z", at.getZ() - base.getZ() + .5);
        var crew = ship.getListOrEmpty("crew");
        crew.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(key));
        crew.add(member);
        ship.put("crew", crew);
        store.requestSave();
        ship.remove("buildJournal");
        com.nullandkale.nullspace.gameplay.ship.ShipIntegrity.stamp(level, WorldStore.pos(ship, "controller"), ship.getStringOr("id", ""));
        return at;
    }

    private SpaceStart()
    {
    }
}
