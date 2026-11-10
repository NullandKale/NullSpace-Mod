package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYard;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYardService;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Everything aboard has to arrive: launching and landing real ships with animals tied to fence posts,
 * leads held by players, riders, and one of every kind of entity the game can create. Every check
 * runs in the tick of the transfer, so nothing wanders, falls or explodes between the snapshot and
 * the comparison.
 */
public final class EntityTravelGameTest
{
    /** The only entity types vanilla refuses to save. Anything new here needs its own travel rule. */
    public static final Set<String> TRANSIENT = Set.of("minecraft:leash_knot", "minecraft:lightning_bolt", "minecraft:player", "minecraft:fishing_bobber");

    /** The parts of an entity's saved data that players would notice losing. Positions are left out on purpose. */
    public static final List<String> TRAITS = List.of("CustomName", "CustomNameVisible", "Health", "Age", "ForcedAge", "equipment", "Items", "item", "ItemRotation", "variant", "Variant", "Owner", "owner", "Sitting", "VillagerData", "Offers", "Tags", "Pose", "ShowArms", "NoBasePlate", "Small", "Marker", "DisabledSlots", "PersistenceRequired", "LeftHanded", "Fuse", "block_state", "BlockState", "Size", "Temper", "Tame", "Bred", "ChestedHorse", "Saddle", "Invulnerable", "NoGravity", "Silent", "Glowing");

    // ------------------------------------------------------------------ fixtures

    /** A ship on a registered pad, with the chunks of both ends held loaded. */
    public record Bed(ServerLevel level, ServerLevel space, ServerPlayer player, BlockPos helm, String shipId, String padId, List<BlockPos> floor, int orbit)
    {
        public CompoundTag ship()
        {
            return WorldStore.get(level.getServer()).repository().find(shipId);
        }
    }

    public static void registerPad(GameTestHelper ctx, BlockPos base, String name)
    {
        var level = ctx.getLevel();
        var bounds = new PadScanner.Pad(base.getX(), base.getX() + 7, base.getY(), base.getZ(), base.getZ() + 7);
        var file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("nullspace-pads.json");
        try
        {
            var records = java.nio.file.Files.exists(file) ? com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonArray() : new com.google.gson.JsonArray();
            records.asList().removeIf(t -> t.getAsJsonObject().get("name").getAsString().equals(name));
            var entry = new com.google.gson.JsonObject();
            entry.addProperty("id", UUID.randomUUID().toString());
            entry.addProperty("name", name);
            entry.addProperty("dimension", level.dimension().identifier().toString());
            entry.addProperty("computer", base.offset(-1, 1, 3).asLong());
            entry.addProperty("minX", bounds.minX());
            entry.addProperty("maxX", bounds.maxX());
            entry.addProperty("minZ", bounds.minZ());
            entry.addProperty("maxZ", bounds.maxZ());
            entry.addProperty("y", bounds.y());
            records.add(entry);
            java.nio.file.Files.writeString(file, records.toString());
            PadRegistry.reload(level.getServer());
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static int firstFreeSlot(net.minecraft.server.MinecraftServer server)
    {
        int slot = 0;
        while (!TransferLocks.spaceSlotFree(server, slot))
            slot++;
        return slot;
    }

    /** Holds the orbit area of the next few free ship slots loaded, so a launch never waits for entities there. */
    public static void holdOrbit(ServerLevel space, int first, boolean on)
    {
        for (int slot = first; slot < first + 4; slot++)
            hold(space, slot * TravelService.SPACE_SPACING - 2, slot * TravelService.SPACE_SPACING + 33, -2, 33, on);
    }

    public static void hold(ServerLevel level, int minX, int maxX, int minZ, int maxZ, boolean on)
    {
        for (int x = minX >> 4; x <= maxX >> 4; x++)
            for (int z = minZ >> 4; z <= maxZ >> 4; z++)
            {
                level.setChunkForced(x, z, on);
                if (on)
                    level.getChunk(x, z);
            }
    }

    /** Builds the small reference ship (7 x 7) on a pad registered by name; the bed itself is made later, once its entities have loaded. */
    public static java.util.function.Supplier<Bed> referenceBed(GameTestHelper ctx, int slot)
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = ctx.absolutePos(new BlockPos(20480 + slot * 64, 64, 20480));
        var helm = ShipTestFixture.build(level, base);
        String name = "EntityTravel " + UUID.randomUUID().toString().substring(0, 6);
        registerPad(ctx, base, name);
        hold(level, base.getX() - 2, base.getX() + 9, base.getZ() - 2, base.getZ() + 9, true);
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(base.getX() + 4.5, base.getY() + 3, base.getZ() + 3.5);
        int orbit = firstFreeSlot(server);
        holdOrbit(server.getLevel(TravelService.SPACE), orbit, true);
        return () ->
        {
            var ship = com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology.resolve(level, helm);
            var pad = PadRegistry.read(server).stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
            return new Bed(level, server.getLevel(TravelService.SPACE), player, helm, ship.getStringOr("id", ""), pad.id(), floor(level, helm), orbit);
        };
    }

    /** A pad of its own for a shipyard design; the design is delivered when the bed is made, once the area has loaded. */
    public static java.util.function.Supplier<Bed> catalogBed(GameTestHelper ctx, int slot, String design)
    {
        var level = ctx.getLevel();
        var server = level.getServer();
        var base = new BlockPos(310000 + slot * 128, 70, 260000);
        hold(level, base.getX() - 2, base.getX() + 33, base.getZ() - 2, base.getZ() + 33, true);
        try
        {
            ShipYard.buildPad(level, base, 32, 32, "EntityYard " + UUID.randomUUID().toString().substring(0, 8));
        }
        catch (java.io.IOException error)
        {
            throw new java.io.UncheckedIOException(error);
        }
        var beacon = base.offset(-1, 1, 1);
        level.setBlock(beacon, ModBlocks.SHIP_BEACON.defaultBlockState(), 18);
        var player = ctx.makeMockServerPlayerInLevel();
        player.teleportTo(level, base.getX() - 2.5, base.getY() + 1, base.getZ() + 1.5, Set.of(), 0, 0, true);
        int orbit = firstFreeSlot(server);
        holdOrbit(server.getLevel(TravelService.SPACE), orbit, true);
        return () ->
        {
            var ship = ShipYardService.deliver(player, beacon, design);
            var helm = WorldStore.pos(ship, "controller");
            return new Bed(level, server.getLevel(TravelService.SPACE), player, helm, ship.getStringOr("id", ""), ship.getStringOr("homeId", ""), floor(level, helm), orbit);
        };
    }

    /** Every cell a person could stand in, in a fixed order: air with air above, a full block below, and breathable. */
    public static List<BlockPos> floor(ServerLevel level, BlockPos helm)
    {
        var scan = ShipService.inspect(level, helm).scan();
        var cells = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(scan.bounds().min(), scan.bounds().max()))
            if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.below()).isCollisionShapeFullBlock(level, at.below()) && scan.pressure().breathable(at) && scan.pressure().breathable(at.above()))
                cells.add(at.immutable());
        cells.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        return cells;
    }

    /** Cells that do not touch each other, so mobs standing in them cannot push one another out of place. */
    public static List<BlockPos> spread(List<BlockPos> floor)
    {
        var chosen = new ArrayList<BlockPos>();
        var taken = new HashSet<BlockPos>();
        for (var cell : floor)
        {
            if (taken.contains(cell))
                continue;
            chosen.add(cell);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    taken.add(cell.offset(dx, 0, dz));
        }
        return chosen;
    }

    public static <T extends Entity> T spawn(ServerLevel level, EntityType<T> type, BlockPos cell)
    {
        var entity = type.create(level, EntitySpawnReason.COMMAND);
        if (entity == null)
            return null;
        entity.setPos(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
        if (entity instanceof Mob mob)
        {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        level.addFreshEntity(entity);
        return entity;
    }

    public static Entity spawnItem(ServerLevel level, BlockPos cell)
    {
        var drop = new ItemEntity(level, cell.getX() + .5, cell.getY(), cell.getZ() + .5, new ItemStack(Items.DIAMOND, 3));
        drop.setDeltaMovement(0, 0, 0);
        level.addFreshEntity(drop);
        return drop;
    }

    public static LeashFenceKnotEntity knotAt(ServerLevel level, BlockPos fence)
    {
        level.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState(), 18);
        return LeashFenceKnotEntity.getOrCreateKnot(level, fence);
    }

    // ------------------------------------------------------------------ what to compare

    /** What an entity looked like before the trip: its identity, notable saved data, and what it is tied or riding. */
    public record Seen(UUID id, String type, Map<String, Tag> traits, String leash, UUID vehicle, boolean snaps)
    {}

    public static Map<String, Tag> traits(Entity entity)
    {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.level().registryAccess());
        var traits = new TreeMap<String, Tag>();
        if (entity.saveAsPassenger(out))
        {
            var tag = out.buildResult();
            for (var key : TRAITS)
                if (tag.contains(key) && !(key.equals("Health") && entity.getType().getDescriptionId().matches(".*(slime|magma_cube|sulfur_cube)")))
                    traits.put(key, tag.get(key));
        }
        return traits;
    }

    public static String leash(Entity entity, BlockPos base)
    {
        if (!(entity instanceof Leashable leashed) || leashed.getLeashHolder() == null)
            return "";
        var holder = leashed.getLeashHolder();
        return holder instanceof LeashFenceKnotEntity knot ? "fence post at " + knot.getPos().subtract(base).toShortString() : "entity " + holder.getUUID();
    }

    public static Seen see(Entity entity, BlockPos base, boolean snaps)
    {
        return new Seen(entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), traits(entity), leash(entity, base), entity.getVehicle() == null ? null : entity.getVehicle().getUUID(), snaps);
    }

    /** Compares what is now standing in the ship's volume with what was seen before. Returns every difference. */
    public static List<String> compare(String phase, ServerLevel where, ServerLevel elsewhere, CompoundTag ship, List<Seen> seen, int expectedLeads)
    {
        var problems = new ArrayList<String>();
        var base = WorldStore.pos(ship, "base");
        var here = new HashMap<UUID, List<Entity>>();
        for (var entity : HullEntities.inBounds(where, WorldStore.bounds(ship)))
            here.computeIfAbsent(entity.getUUID(), k -> new ArrayList<>()).add(entity);
        int leads = 0;
        for (var item : HullEntities.inBounds(where, WorldStore.bounds(ship)))
            if (item instanceof ItemEntity drop && drop.getItem().is(Items.LEAD))
                leads++;
        for (var s : seen)
        {
            var found = here.getOrDefault(s.id(), List.of());
            if (found.size() != 1)
            {
                problems.add(phase + ": " + s.type() + " " + s.id() + " arrived " + found.size() + " times");
                continue;
            }
            var now = found.getFirst();
            if (elsewhere.getEntity(s.id()) != null)
                problems.add(phase + ": " + s.type() + " " + s.id() + " is also still in the other world");
            var traits = traits(now);
            if (!traits.equals(s.traits()))
                problems.add(phase + ": " + s.type() + " changed: " + diff(s.traits(), traits));
            if (s.snaps())
            {
                if (!leash(now, base).isEmpty())
                    problems.add(phase + ": " + s.type() + " is still tied to something that stayed behind");
            }
            else if (!leash(now, base).equals(s.leash()))
                problems.add(phase + ": " + s.type() + " was tied to [" + s.leash() + "] and is now tied to [" + leash(now, base) + "]");
            UUID vehicle = now.getVehicle() == null ? null : now.getVehicle().getUUID();
            if (!Objects.equals(vehicle, s.vehicle()))
                problems.add(phase + ": " + s.type() + " was riding " + s.vehicle() + " and now rides " + vehicle);
        }
        if (leads != expectedLeads)
            problems.add(phase + ": " + leads + " leads dropped aboard, expected " + expectedLeads);
        return problems;
    }

    public static String diff(Map<String, Tag> before, Map<String, Tag> after)
    {
        var parts = new ArrayList<String>();
        for (var key : before.keySet())
            if (!Objects.equals(before.get(key), after.get(key)))
                parts.add(key + " " + before.get(key) + " -> " + after.get(key));
        for (var key : after.keySet())
            if (!before.containsKey(key))
                parts.add(key + " appeared " + after.get(key));
        return String.join("; ", parts);
    }

    /** Many trips in a row burn the reference ship's coal; a test about blocks should not end because the tank did. */
    public static void refuel(ServerLevel level, CompoundTag ship)
    {
        for (var generator : com.nullandkale.nullspace.gameplay.systems.FuelSystem.generators(level, ship))
            generator.setItem(3, new ItemStack(Items.COAL, 64));
    }

    /** Launches, checks, lands back on the same pad, checks again. Returns every problem found along the way. */
    public static List<String> roundTrip(Bed bed, List<Entity> aboard, Set<UUID> snapping)
    {
        var problems = new ArrayList<String>();
        var server = bed.level().getServer();
        var before = bed.ship();
        var seen = new ArrayList<Seen>();
        // A fence post is rebuilt, not carried, so it is checked through the animals tied to it.
        for (var entity : aboard)
            if (!(entity instanceof LeashFenceKnotEntity))
                seen.add(see(entity, WorldStore.pos(before, "base"), snapping.contains(entity.getUUID())));
        int slot = 0;
        while (!TransferLocks.spaceSlotFree(server, slot))
            slot++;
        hold(bed.space(), slot * TravelService.SPACE_SPACING - 2, slot * TravelService.SPACE_SPACING + 33, -17, 49, true);
        refuel(bed.level(), before);
        var launched = TravelService.transfer(bed.player(), bed.helm(), "", ShipService.preflight(bed.level(), bed.helm()));
        if (launched.outcome() != TravelService.Outcome.COMPLETED)
        {
            problems.add("The ship would not launch: " + launched.message() + " [fuel " + com.nullandkale.nullspace.gameplay.systems.FuelSystem.available(bed.level(), before) + ", gens " + com.nullandkale.nullspace.gameplay.systems.FuelSystem.generators(bed.level(), before).size() + "]");
            return problems;
        }
        var inSpace = bed.ship();
        problems.addAll(compare("In space", bed.space(), bed.level(), inSpace, seen, snapping.size()));
        var seenInSpace = new ArrayList<Seen>();
        for (var entity : HullEntities.inBounds(bed.space(), WorldStore.bounds(inSpace)))
            for (var s : seen)
                if (s.id().equals(entity.getUUID()))
                    seenInSpace.add(see(entity, WorldStore.pos(inSpace, "base"), false));
        var orbitHelm = WorldStore.pos(inSpace, "controller");
        refuel(bed.space(), inSpace);
        var landed = TravelService.transfer(bed.player(), orbitHelm, "id:" + bed.padId(), ShipService.preflight(bed.space(), orbitHelm));
        if (landed.outcome() != TravelService.Outcome.COMPLETED)
        {
            problems.add("The ship would not land: " + landed.message());
            return problems;
        }
        problems.addAll(compare("Back on the pad", bed.level(), bed.space(), bed.ship(), seenInSpace, snapping.size()));
        return problems;
    }

    public static void finish(GameTestHelper ctx, Bed bed, List<String> problems)
    {
        holdOrbit(bed.space(), bed.orbit(), false);
        var level = bed.level();
        for (var entity : HullEntities.inBounds(level, WorldStore.bounds(bed.ship())))
            if (!(entity instanceof ServerPlayer))
                entity.discard();
        level.getServer().getPlayerList().remove(bed.player());
        WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(bed.shipId()));
        ctx.assertTrue(problems.isEmpty(), "Entities travel with the ship:\n" + String.join("\n", problems));
        ctx.succeed();
    }

    // ------------------------------------------------------------------ the cases

    /** The bug report: two pigs on leads tied to a fence post could not travel. */
    @GameTest(maxTicks = 100)
    public void pigsLeashedToAFencePostTravelWithTheShip(GameTestHelper ctx)
    {
        var make = referenceBed(ctx, 0);
        ctx.runAfterDelay(10, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var base = WorldStore.pos(bed.ship(), "base");
            var knot = knotAt(level, base.offset(4, 3, 5));
            var pigA = spawn(level, EntityTypes.PIG, base.offset(3, 3, 4));
            var pigB = spawn(level, EntityTypes.PIG, base.offset(5, 3, 4));
            pigA.setLeashedTo(knot, true);
            pigB.setLeashedTo(knot, true);
            var wolf = spawn(level, EntityTypes.WOLF, base.offset(2, 3, 4));
            finish(ctx, bed, roundTrip(bed, List.of(pigA, pigB, wolf, knot), Set.of()));
        });
    }

    /** Fence posts in several places, a lead held by the player, a lead between animals, and a post left outside the ship. */
    @GameTest(maxTicks = 200)
    public void everyKindOfLeashSurvivesALaunchAndALanding(GameTestHelper ctx)
    {
        var make = catalogBed(ctx, 1, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var cells = spread(bed.floor());
            ctx.assertTrue(cells.size() >= 16, "The ship has room for the test: " + cells.size() + " separate cells");
            bed.player().teleportTo(cells.get(0).getX() + .5, cells.get(0).getY(), cells.get(0).getZ() + .5);
            var aboard = new ArrayList<Entity>();
            var snaps = new HashSet<UUID>();
            // Three animals on one post, two on another, a wolf on a third on a different deck.
            var postA = knotAt(level, cells.get(1));
            var postB = knotAt(level, cells.get(5));
            var postC = knotAt(level, cells.get(cells.size() - 1));
            aboard.addAll(List.of(postA, postB, postC));
            int next = 2;
            for (var type : List.of(EntityTypes.PIG, EntityTypes.COW, EntityTypes.SHEEP))
            {
                var animal = spawn(level, type, cells.get(next++));
                animal.setLeashedTo(postA, true);
                aboard.add(animal);
            }
            next = 6;
            for (var type : List.of(EntityTypes.PIG, EntityTypes.CHICKEN))
            {
                var animal = spawn(level, type, cells.get(next++));
                animal.setLeashedTo(postB, true);
                aboard.add(animal);
            }
            var wolf = spawn(level, EntityTypes.WOLF, cells.get(cells.size() - 2));
            wolf.setLeashedTo(postC, true);
            aboard.add(wolf);
            // A lead held by the player, and a llama led by a horse.
            var cow = spawn(level, EntityTypes.COW, cells.get(9));
            cow.setLeashedTo(bed.player(), true);
            var horse = spawn(level, EntityTypes.HORSE, cells.get(10));
            var llama = spawn(level, EntityTypes.LLAMA, cells.get(11));
            llama.setLeashedTo(horse, true);
            aboard.addAll(List.of(cow, horse, llama));
            // A pig tied to a post outside the hull: that lead has to break and be handed back, not follow the pig.
            var ship = bed.ship();
            var edge = WorldStore.bounds(ship).min();
            var wall = new BlockPos(edge.getX() - 3, edge.getY(), cells.get(12).getZ());
            var outsidePost = knotAt(level, wall);
            var stray = spawn(level, EntityTypes.PIG, cells.get(12));
            stray.setLeashedTo(outsidePost, true);
            aboard.add(stray);
            snaps.add(stray.getUUID());
            var problems = roundTrip(bed, aboard, snaps);
            LeashFenceKnotEntity.getKnot(level, wall).ifPresent(Entity::discard);
            level.setBlock(wall, Blocks.AIR.defaultBlockState(), 18);
            finish(ctx, bed, problems);
        });
    }

    /** A failed move puts the pigs back on their post at the other end of the journal. */
    @GameTest(maxTicks = 120)
    public void pigsOnAFencePostComeBackWhenALaunchIsRolledBack(GameTestHelper ctx)
    {
        var make = referenceBed(ctx, 2);
        ctx.runAfterDelay(10, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var base = WorldStore.pos(bed.ship(), "base");
            var fence = base.offset(4, 3, 5);
            var knot = knotAt(level, fence);
            var pigA = spawn(level, EntityTypes.PIG, base.offset(3, 3, 4));
            var pigB = spawn(level, EntityTypes.PIG, base.offset(5, 3, 4));
            pigA.setLeashedTo(knot, true);
            pigB.setLeashedTo(knot, true);
            var seen = List.of(see(pigA, base, false), see(pigB, base, false));
            rollBack(ctx, bed, fence, seen, List.of("DESTINATION_STAGED", "SOURCE_CLEARED", "PASSENGERS_MOVED"), 0, new ArrayList<>());
        });
    }

    /**
     * Fails the launch at one stage, runs the recovery the travel tick runs after an exception, then waits two
     * ticks: a reloaded animal ties itself back to its fence post on its first tick, as it does after a restart.
     */
    public static void rollBack(GameTestHelper ctx, Bed bed, BlockPos fence, List<Seen> seen, List<String> stages, int index, List<String> problems)
    {
        if (index == stages.size())
        {
            finish(ctx, bed, problems);
            return;
        }
        var level = bed.level();
        var server = level.getServer();
        String stage = stages.get(index);
        TravelService.failureInjector = at ->
        {
            if (at.equals(stage))
                throw new IllegalStateException("injected failure at " + stage);
        };
        try
        {
            TravelService.transfer(bed.player(), bed.helm(), "", ShipService.preflight(level, bed.helm()));
            problems.add("The injected failure at " + stage + " did not stop the launch");
        }
        catch (IllegalStateException expected)
        {
            TransferRecovery.recover(server);
        }
        finally
        {
            TravelService.failureInjector = at ->
            {};
        }
        ctx.runAfterDelay(2, () ->
        {
            problems.addAll(compare("After a failure at " + stage, level, bed.space(), bed.ship(), seen, 0));
            if (LeashFenceKnotEntity.getKnot(level, fence).isEmpty())
                problems.add("After a failure at " + stage + ": the fence post knot is gone");
            rollBack(ctx, bed, fence, seen, stages, index + 1, problems);
        });
    }

    /** Pigs on a post and a wolf travel on every shipyard design: spine, dome and twin hull, small to large. */
    @GameTest(maxTicks = 600)
    public void animalsOnAFencePostTravelOnEveryShipyardDesign(GameTestHelper ctx)
    {
        var sites = new LinkedHashMap<String, java.util.function.Supplier<Bed>>();
        int slot = 10;
        for (var preset : ShipBlueprints.PRESETS)
            sites.put(preset.name(), catalogBed(ctx, slot++, preset.id()));
        ctx.runAfterDelay(120, () -> animalsOnEveryDesign(ctx, sites));
    }

    public static void animalsOnEveryDesign(GameTestHelper ctx, Map<String, java.util.function.Supplier<Bed>> sites)
    {
        var failures = new ArrayList<String>();
        for (var site : sites.entrySet())
        {
            var preset = new Object()
            {
                final String name = site.getKey();
            };
            var bed = site.getValue().get();
            var level = bed.level();
            var cells = spread(bed.floor());
            if (cells.size() < 5)
            {
                failures.add(preset.name + ": only " + cells.size() + " separate cells to stand in");
                continue;
            }
            bed.player().teleportTo(cells.get(0).getX() + .5, cells.get(0).getY(), cells.get(0).getZ() + .5);
            var knot = knotAt(level, cells.get(1));
            var pigA = spawn(level, EntityTypes.PIG, cells.get(2));
            var pigB = spawn(level, EntityTypes.PIG, cells.get(3));
            pigA.setLeashedTo(knot, true);
            pigB.setLeashedTo(knot, true);
            var wolf = spawn(level, EntityTypes.WOLF, cells.get(4));
            for (var problem : roundTrip(bed, List.of(pigA, pigB, wolf, knot), Set.of()))
                failures.add(preset.name + " - " + problem);
            for (var entity : HullEntities.inBounds(level, WorldStore.bounds(bed.ship())))
                if (!(entity instanceof ServerPlayer))
                    entity.discard();
            level.getServer().getPlayerList().remove(bed.player());
            WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(bed.shipId()));
        }
        ctx.assertTrue(failures.isEmpty(), "Every design carries leashed animals:\n" + String.join("\n", failures));
        ctx.succeed();
    }

    /** Cheap and broad: which entity types cannot be written to the transfer journal at all. */
    @GameTest(maxTicks = 100)
    public void onlyTheKnownTransientEntityTypesCannotBeSaved(GameTestHelper ctx)
    {
        var level = ctx.getLevel();
        var unsaveable = new TreeSet<String>();
        var skipped = new TreeSet<String>();
        for (var type : BuiltInRegistries.ENTITY_TYPE)
        {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if (type == EntityTypes.PAINTING || type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME)
                continue;
            Entity entity;
            try
            {
                entity = type.create(level, EntitySpawnReason.COMMAND);
            }
            catch (RuntimeException failure)
            {
                skipped.add(id);
                continue;
            }
            if (entity == null)
            {
                skipped.add(id);
                continue;
            }
            var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            if (!entity.save(out))
                unsaveable.add(id);
        }
        ctx.assertTrue(TRANSIENT.containsAll(unsaveable), "These entity types cannot be saved, so a ship carrying one cannot travel: " + unsaveable + " (known transient: " + TRANSIENT + ")");
        ctx.succeed();
    }

    /** One of every entity type that fits in a cabin, on the largest ship, out and back. */
    @GameTest(maxTicks = 600)
    public void oneOfEveryEntityTypeTravelsOnTheLargestShip(GameTestHelper ctx)
    {
        var make = catalogBed(ctx, 30, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var cells = spread(bed.floor());
            bed.player().teleportTo(cells.get(0).getX() + .5, cells.get(0).getY(), cells.get(0).getZ() + .5);
            var aboard = new ArrayList<Entity>();
            var skipped = new TreeSet<String>();
            int next = 1;
            for (var type : BuiltInRegistries.ENTITY_TYPE)
            {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
                // Hanging decorations need a wall, which the decoration case below gives them; the rest of the exclusions cannot fit or cannot exist here.
                if (TRANSIENT.contains(id) || type == EntityTypes.PAINTING || type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME || type.getWidth() > 2.0F || type.getHeight() > 2.8F)
                {
                    skipped.add(id);
                    continue;
                }
                if (next >= cells.size())
                {
                    skipped.add(id + " (no room)");
                    continue;
                }
                Entity entity;
                try
                {
                    entity = type == EntityTypes.ITEM ? spawnItem(level, cells.get(next)) : spawn(level, type, cells.get(next));
                }
                catch (RuntimeException failure)
                {
                    skipped.add(id + " (" + failure.getClass().getSimpleName() + ")");
                    continue;
                }
                if (entity == null || entity.isRemoved())
                {
                    skipped.add(id);
                    continue;
                }
                next++;
                aboard.add(entity);
            }
            ctx.assertTrue(aboard.size() >= 100, "The sweep covers most of the registry: " + aboard.size() + " types, skipped " + skipped);
            finish(ctx, bed, roundTrip(bed, aboard, Set.of()));
        });
    }
}
