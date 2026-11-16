package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.ownership.CrewRelease;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.RecordJournal;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The crash-landing start. The starting ship is built where it crashed, on a pad-shaped site on the chosen planet, and wrecked
 * by {@link CrashDamage}; the player starts inside it. Nothing is landed or transferred, so nothing waits on a pad, a journal
 * or a chunk, and joining a server never fails for want of a place: a site is always found and made level.
 *
 * <p>Two modes. In a <b>single crash</b> the first new player causes the one crash and every later new player wakes up at that
 * ship, in the closest breathable cell to it. In a <b>multi-crash</b> every new player crashes on their own, each at the nearest
 * free site to the planet's landing spot. The Pad Computer is always broken and no pad is ever registered for a crash.
 */
public final class CrashStart
{
    /** The world's one crash, in a single-crash world: its planet, ship and how many have joined. */
    private static CompoundTag world(WorldStore store)
    {
        return store.section("crashWorld");
    }

    private static CompoundTag record(WorldStore store, String key)
    {
        return store.section("spaceStarts").getCompoundOrEmpty(key);
    }

    /** Starts the player. Idempotent: every step is repeated safely, and a finished start is never awarded again. */
    public static CompoundTag start(ServerPlayer player, WorldStartSettings settings)
    {
        var server = player.level().getServer();
        var store = WorldStore.get(server);
        var starts = store.section("spaceStarts");
        String key = player.getUUID().toString();
        var existing = starts.getCompoundOrEmpty(key);
        if (existing.getBooleanOr("completed", false))
            return store.repository().find(existing.getStringOr("ship", ""));
        var galaxy = StarSystems.galaxy(server);
        if (existing.isEmpty())
        {
            var before = store.snapshot();
            var record = new CompoundTag();
            record.putBoolean("crash", true);
            record.putString("preset", settings.ship());
            var world = world(store);
            if (settings.multi())
            {
                // One specific planet is fixed when the world is first used, so it never changes; random is each player's own.
                String planet;
                if (settings.planet().equals("random"))
                    planet = StartPlanets.random(galaxy, server.overworld().getSeed() ^ player.getUUID().getMostSignificantBits() ^ Long.rotateLeft(player.getUUID().getLeastSignificantBits(), 17)).id();
                else
                {
                    if (!world.contains("planet"))
                        world.putString("planet", StartPlanets.resolve(galaxy, settings.planet(), server.overworld().getSeed()).id());
                    planet = world.getStringOr("planet", LocalSpaces.HOME);
                }
                record.putString("planet", planet);
                record.putString("ship", UUID.randomUUID().toString());
            }
            else
            {
                if (!world.contains("ship"))
                {
                    world.putString("planet", StartPlanets.resolve(galaxy, settings.planet(), server.overworld().getSeed()).id());
                    world.putString("ship", UUID.randomUUID().toString());
                    world.putString("preset", settings.ship());
                }
                record.putString("planet", world.getStringOr("planet", LocalSpaces.HOME));
                record.putString("ship", world.getStringOr("ship", ""));
                record.putString("preset", world.getStringOr("preset", settings.ship()));
            }
            starts.put(key, record);
            store.requestSave();
            RecordJournal.commit(server, "prepare-crash-start", before);
        }
        var record = record(store, key);
        String planet = record.getStringOr("planet", LocalSpaces.HOME);
        var levelKey = PlanetSurfaces.dimension(server, planet);
        var level = levelKey == null ? server.overworld() : server.getLevel(levelKey);
        if (level == null)
            level = server.overworld();
        String shipId = record.getStringOr("ship", "");
        var preset = ShipBlueprints.get(record.getStringOr("preset", "rivet_bolt"));
        boolean single = !settings.multi() && world(store).getStringOr("ship", "").equals(shipId);
        var site = CrashSites.allocate(server, level, planet, shipId);
        var ship = store.repository().find(shipId);
        long seed = server.overworld().getSeed();
        if (ship == null)
        {
            CrashSites.prepare(level, site, seed);
            CrashSites.explode(level, site, seed);
            ship = build(player, level, store, site, shipId, preset, key, planet);
        }
        boolean vacuum = !PlanetSurfaces.breathable(level);
        CrashDamage.Report report = null;
        if (!world(store).getBooleanOr("damaged:" + shipId, false))
        {
            report = CrashDamage.apply(level, ship, seed, vacuum);
            NullSpace.LOGGER.info("Crash start for ship {} on {}: {}", shipId, planet, report);
            hangBuckets(level, ship);
            var before = store.snapshot();
            world(store).putBoolean("damaged:" + shipId, true);
            store.requestSave();
            RecordJournal.commit(server, "crash-start-damaged", before);
        }
        boolean first = !ship.getStringOr("owner", "").isEmpty() && ship.getStringOr("owner", "").equals(key) || single && ship.getStringOr("owner", "").equals(key);
        var focus = report != null ? report : null;
        place(player, level, store, ship, site, vacuum, first, focus);
        SpaceStart.giveCrashSupplies(player);
        if (vacuum)
            player.getInventory().add(new ItemStack(ModBlocks.AIR_CANISTER));
        unlockRecipes(player);
        player.setAirSupply(player.getMaxAirSupply());
        server.getPlayerList().saveAll();
        var beforeCompletion = store.snapshot();
        record(store, key).putBoolean("completed", true);
        store.requestSave();
        RecordJournal.commit(server, "complete-crash-start", beforeCompletion);
        player.sendSystemMessage(Component.literal("Your ship crash-landed on " + LocalSpaces.get(server, planet).name() + ". Replace its burnt-out machines; the recipes are in your recipe book."));
        return store.repository().find(shipId);
    }

    /** The starting ship, built on the site's floor and registered, with its machines provisioned and its crew. */
    private static CompoundTag build(ServerPlayer player, ServerLevel level, WorldStore store, CrashSites.Site site, String shipId, ShipBlueprints.Preset preset, String key, String planet)
    {
        var plan = ShipBlueprints.blocks(preset);
        // Centre the hull on the pad's floor rather than leaving it in a corner.
        int lowX = Integer.MAX_VALUE, highX = Integer.MIN_VALUE, lowZ = Integer.MAX_VALUE, highZ = Integer.MIN_VALUE;
        for (var offset : plan.keySet())
        {
            lowX = Math.min(lowX, offset.getX());
            highX = Math.max(highX, offset.getX());
            lowZ = Math.min(lowZ, offset.getZ());
            highZ = Math.max(highZ, offset.getZ());
        }
        var base = site.base().offset(Math.max(0, (32 - (highX - lowX + 1)) / 2 - lowX), 0, Math.max(0, (32 - (highZ - lowZ + 1)) / 2 - lowZ));
        var ship = new CompoundTag();
        ship.putString("id", shipId);
        ship.putString("dimension", level.dimension().identifier().toString());
        ship.putLong("base", base.asLong());
        ship.putLong("controller", base.offset(preset.helm()).asLong());
        ship.putInt("width", 32);
        ship.putInt("height", 32);
        ship.putInt("depth", 32);
        ship.putString("blueprint", preset.id());
        ship.putString("name", preset.name());
        ship.putString("owner", key);
        ship.putString("localSpace", planet);
        ship.put("crew", new ListTag());
        var construction = BuildJournal.begin(level, "crash-ship", List.of(shipId), new HullScan.Bounds(base, base.offset(31, 31, 31)));
        var prepared = construction;
        var allocated = ship;
        construction.step(() ->
        {
            allocated.putString("buildJournal", prepared.fileName());
            store.ships().add(allocated);
            store.requestSave();
            return null;
        });
        construction.step(() ->
        {
            for (var entry : plan.entrySet())
            {
                var at = base.offset(entry.getKey());
                if (!level.setBlock(at, entry.getValue(), 18) && !level.getBlockState(at).equals(entry.getValue()))
                    throw new IllegalStateException("Cannot place crashed ship block at " + at.toShortString());
            }
            for (var entry : plan.entrySet())
                if (entry.getValue().is(ModBlocks.GENERATOR))
                    FuelSystem.provision(level, base.offset(entry.getKey()), 32);
            if (preset.planned())
            {
                allocated.putBoolean("generatedRooms", true);
                com.nullandkale.nullspace.gameplay.shipyard.RoomEntities.spawn(level, base, ShipBlueprints.points(preset));
                allocated.putBoolean("roomEntities", true);
            }
            SystemsUpgrade.upgrade(level, allocated);
            allocated.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, ShipBlueprints.airDemand(preset), 1, 3, 2, 0});
            CrewRelease.authorize(allocated, player);
            allocated.remove("buildJournal");
            store.requestSave();
            return null;
        });
        construction.commitNow();
        var landed = store.repository().find(shipId);
        com.nullandkale.nullspace.gameplay.ship.ShipIntegrity.stamp(level, WorldStore.pos(landed, "controller"), shipId);
        return landed;
    }

    /**
     * Puts the player in the ship, facing the damage, and adds them to the crew. The first player stands in front of the helm. A later
     * player of a single crash stands in the closest breathable cell to the ship; if there is none (no air outside and the ship's
     * air is gone) they stand at the ship's pad, outside, which is all that can be done and is never an error.
     */
    private static void place(ServerPlayer player, ServerLevel level, WorldStore store, CompoundTag ship, CrashSites.Site site, boolean vacuum, boolean first, CrashDamage.Report report)
    {
        var controller = WorldStore.pos(ship, "controller");
        var facing = level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING);
        var helm = controller.relative(facing);
        BlockPos at = helm;
        if (!first)
        {
            var found = breathableNear(level, ship, helm, vacuum, site, store);
            at = found;
        }
        var look = nearestDamage(ship, at, level, report);
        float yaw = facing.getOpposite().toYRot();
        if (look != null)
        {
            double dx = look.getX() + .5 - (at.getX() + .5), dz = look.getZ() + .5 - (at.getZ() + .5);
            if (dx * dx + dz * dz > .25)
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        player.teleportTo(level, at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), yaw, 8, true);
        var base = WorldStore.pos(ship, "base");
        String key = player.getUUID().toString();
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
        var world = world(store);
        world.putInt("joined", world.getIntOr("joined", 0) + 1);
    }

    /** The closest wreck or tear to the cell, so the player wakes facing the damage rather than a wall. */
    private static BlockPos nearestDamage(CompoundTag ship, BlockPos from, ServerLevel level, CrashDamage.Report report)
    {
        var points = new ArrayList<BlockPos>();
        if (report != null)
        {
            points.addAll(report.broken());
            points.addAll(report.woundCenters());
        }
        else
        {
            for (var at : BlockPos.betweenClosed(WorldStore.bounds(ship).min(), WorldStore.bounds(ship).max()))
                if (com.nullandkale.nullspace.content.BrokenMachines.broken(level.getBlockState(at)))
                    points.add(at.immutable());
        }
        return points.stream().min(Comparator.comparingDouble(p -> p.distSqr(from))).orElse(null);
    }

    /** A standing cell with two free blocks above it and breathable air, nearest the helm; failing that, a cell beside the ship's pad. */
    private static BlockPos breathableNear(ServerLevel level, CompoundTag ship, BlockPos helm, boolean vacuum, CrashSites.Site site, WorldStore store)
    {
        var bounds = WorldStore.bounds(ship);
        var cells = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
        {
            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir() || level.getBlockState(at.below()).isAir())
                continue;
            if (ShipAir.breathable(level, ship, at.above()))
                cells.add(at.immutable());
        }
        cells.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(helm)).thenComparingLong(BlockPos::asLong));
        if (!cells.isEmpty())
        {
            int joined = world(store).getIntOr("joined", 0);
            return cells.get(Math.min(cells.size() - 1, joined % Math.min(cells.size(), 6)));
        }
        // No air zone: stand at the pad, just outside the ring, on the ground.
        int x = site.minX() - 3, z = site.minZ() + 16;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, Math.max(y, site.y() + 1), z);
    }

    /**
     * A bucket of water, a bucket of lava, a sapling and a block of dirt hang in item frames at eye height on the cabin wall nearest
     * the helm, in a row. Water running onto lava makes cobblestone, and a sapling planted in the dirt grows wood, so every planet has
     * stone and wood to start with, whatever its ground is made of (a lava world is basalt, an ice world is ice). Whatever the cabin
     * has no wall for is dropped at the helm instead.
     */
    /** Marks the kit's frames among the ship's own furnishings (a farm room has frames of dirt and water too). */
    public static final String KIT_FRAME = "nullspace_crash_kit";

    static void hangBuckets(ServerLevel level, CompoundTag ship)
    {
        var controller = WorldStore.pos(ship, "controller");
        var helm = controller.relative(level.getBlockState(controller).getValue(HorizontalDirectionalBlock.FACING));
        var bounds = WorldStore.bounds(ship);
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        var spots = new ArrayList<BlockPos>();
        var faces = new HashMap<BlockPos, net.minecraft.core.Direction>();
        seen.add(helm);
        queue.add(helm);
        while (!queue.isEmpty() && seen.size() < 1500)
        {
            var at = queue.poll();
            for (var side : net.minecraft.core.Direction.values())
            {
                var next = at.relative(side);
                if (bounds.contains(next) && level.getBlockState(next).isAir() && seen.add(next))
                    queue.add(next);
            }
            // Eye height: the second block above a floor you can stand on, against a wall that can hold the frame.
            if (!level.getBlockState(at.below()).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at).isAir() && !nearWay(level, at.above()) && ShipAir.breathable(level, ship, at.above()))
            {
                var frameCell = at.above();
                for (var wall : net.minecraft.core.Direction.Plane.HORIZONTAL)
                {
                    var behind = frameCell.relative(wall);
                    if (level.getBlockState(behind).isFaceSturdy(level, behind, wall.getOpposite()) && level.getEntitiesOfClass(net.minecraft.world.entity.decoration.HangingEntity.class, new net.minecraft.world.phys.AABB(frameCell)).isEmpty())
                    {
                        spots.add(frameCell);
                        faces.put(frameCell, wall.getOpposite());
                        break;
                    }
                }
            }
        }
        spots.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(helm)).thenComparingLong(BlockPos::asLong));
        var items = List.of(new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET), new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET), new ItemStack(net.minecraft.world.item.Items.OAK_SAPLING), new ItemStack(net.minecraft.world.item.Items.DIRT));
        // A row along one wall if there is room: start at the nearest spot and add spots next to the ones chosen, then fill from the nearest.
        var chosen = new ArrayList<BlockPos>();
        if (!spots.isEmpty())
            chosen.add(spots.getFirst());
        boolean grew = true;
        while (chosen.size() < items.size() && grew)
        {
            grew = false;
            for (var spot : spots)
                if (chosen.size() < items.size() && !chosen.contains(spot) && chosen.stream().anyMatch(c -> faces.get(c) == faces.get(spot) && c.distManhattan(spot) == 1))
                {
                    chosen.add(spot);
                    grew = true;
                }
        }
        for (var spot : spots)
            if (chosen.size() < items.size() && !chosen.contains(spot))
                chosen.add(spot);
        // Sorted along the wall, so the row reads water, lava, sapling, dirt from one end.
        chosen.sort(Comparator.comparingInt((BlockPos p) -> p.getX() + p.getZ()).thenComparingInt(BlockPos::getY));
        for (int i = 0; i < items.size(); i++)
        {
            if (i >= chosen.size())
            {
                level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, helm.getX() + .5, helm.getY() + .5, helm.getZ() + .5, items.get(i)));
                continue;
            }
            var frame = new net.minecraft.world.entity.decoration.ItemFrame(level, chosen.get(i), faces.get(chosen.get(i)));
            frame.setItem(items.get(i));
            frame.addTag(KIT_FRAME);
            level.addFreshEntity(frame);
        }
    }

    /** True when a door or a docking base is within a block of the cell sideways, or at its height: a frame there would be in the way. */
    private static boolean nearWay(ServerLevel level, BlockPos cell)
    {
        for (var near : BlockPos.betweenClosed(cell.offset(-1, -2, -1), cell.offset(1, 1, 1)))
        {
            var state = level.getBlockState(near);
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || state.is(com.nullandkale.nullspace.content.ModBlocks.DOCKING_BASE) || state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock)
                return true;
        }
        return false;
    }

    /** Every NullSpace recipe is in the player's recipe book, so the repairs can be crafted from the start. */
    static void unlockRecipes(ServerPlayer player)
    {
        var recipes = player.level().getServer().getRecipeManager().getRecipes().stream().filter(r -> r.id().identifier().getNamespace().equals("nullspace")).toList();
        player.awardRecipes(recipes);
    }

    /**
     * The last resort, when starting properly has failed repeatedly for a reason nobody foresaw: the player is put on the planet's
     * surface at the landing spot with their supplies, so joining a server never leaves anyone stuck or kicked. The failure is in the log.
     */
    public static void fallback(ServerPlayer player, WorldStartSettings settings, RuntimeException cause)
    {
        var server = player.level().getServer();
        var store = WorldStore.get(server);
        String key = player.getUUID().toString();
        NullSpace.LOGGER.error("Crash start failed for {}; placing the player on the surface instead", key, cause);
        var record = record(store, key);
        var level = server.overworld();
        var levelKey = PlanetSurfaces.dimension(server, record.getStringOr("planet", LocalSpaces.HOME));
        if (levelKey != null && server.getLevel(levelKey) != null)
            level = server.getLevel(levelKey);
        var corner = level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD) ? level.getRespawnData().pos() : PlanetSurfaces.landingSite(level);
        level.getChunk(corner.getX() >> 4, corner.getZ() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, corner.getX(), corner.getZ());
        player.teleportTo(level, corner.getX() + .5, y, corner.getZ() + .5, Set.of(), 0, 0, true);
        SpaceStart.giveCrashSupplies(player);
        if (!PlanetSurfaces.breathable(level))
            player.getInventory().add(new ItemStack(ModBlocks.AIR_CANISTER));
        unlockRecipes(player);
        var before = store.snapshot();
        record.putBoolean("completed", true);
        record.putBoolean("degraded", true);
        store.requestSave();
        RecordJournal.commit(server, "crash-start-fallback", before);
        player.sendSystemMessage(Component.literal("Your ship could not be set up. You are on the surface with your supplies; tell the server operator."));
    }

    private CrashStart()
    {
    }
}
