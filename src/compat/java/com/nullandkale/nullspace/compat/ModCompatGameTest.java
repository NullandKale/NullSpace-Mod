package com.nullandkale.nullspace.compat;

import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.travel.EntityTravelGameTest;
import com.nullandkale.nullspace.gameplay.travel.TravelService;

import java.nio.file.Files;
import java.util.*;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Does the ship still travel when another mod's content is aboard? Registry driven, so a new mod needs no
 * test code: drop its jar in and every block, fluid, block entity and entity type it registers is put on a ship
 * and flown out and back. Everything that is plainly just a block is checked in bulk; the time goes to the
 * things that could be strange: block entities, fluids, plants that need support, and entities.
 *
 * <p>Without other mods installed there is nothing to test and every case passes at once. With them, each
 * anomaly is reported by name. Anomalies a person has looked at and accepted go in
 * {@code src/gametest/resources/compat/known-issues.json}, so a new problem fails the run and an old one does not.
 */
public final class ModCompatGameTest
{
    /** What the game and its own test mod register; everything else is somebody else's content. */
    private static boolean ours(String namespace)
    {
        return namespace.equals("minecraft") || namespace.equals("nullspace") || namespace.equals("nullspace-test") || namespace.startsWith("fabric") || namespace.equals("c");
    }

    /** The mod namespaces under test: the property, or whatever else is registered. */
    static Set<String> namespaces()
    {
        var wanted = System.getProperty("nullspace.compat.namespaces", "");
        var found = new TreeSet<String>();
        if (!wanted.isBlank())
            found.addAll(Arrays.asList(wanted.split(",")));
        else
            for (var id : BuiltInRegistries.BLOCK.keySet())
                if (!ours(id.getNamespace()))
                    found.add(id.getNamespace());
        return found;
    }

    private static Set<String> known(String category)
    {
        try (var in = ModCompatGameTest.class.getResourceAsStream("/compat/known-issues.json"))
        {
            var accepted = new HashSet<String>();
            if (in == null)
                return accepted;
            var root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has(category))
                for (var element : root.getAsJsonArray(category))
                    accepted.add(element.getAsString());
            return accepted;
        }
        catch (java.io.IOException failure)
        {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** Writes the full report where the runner can find it, and fails on anything nobody has accepted. */
    static void conclude(GameTestHelper ctx, String name, List<String> notes, Map<String, String> anomalies)
    {
        var accepted = known(name);
        var unexpected = new TreeMap<String, String>();
        anomalies.forEach((id, why) ->
        {
            if (!accepted.contains(id))
                unexpected.put(id, why);
        });
        var report = new ArrayList<String>(notes);
        report.add(anomalies.size() + " anomalies, " + unexpected.size() + " not yet accepted");
        unexpected.forEach((id, why) -> report.add("  " + id + ": " + why));
        try
        {
            Files.writeString(java.nio.file.Path.of("compat-" + name + ".txt"), String.join("\n", report) + "\n");
        }
        catch (java.io.IOException ignored)
        {
        }
        System.out.println("MOD COMPAT " + name + "\n" + String.join("\n", report));
        ctx.assertTrue(unexpected.isEmpty(), "Content from other mods that stops or changes a ship (" + name + "):\n" + String.join("\n", report));
        ctx.succeed();
    }

    /** The exception and the frames that matter: where it was thrown, and the first calls into this game or another mod. */
    static String trace(Throwable failure)
    {
        var text = new StringBuilder(failure.toString().replaceAll("\\s+", " "));
        int shown = 0;
        for (var frame : failure.getStackTrace())
        {
            text.append(" <- ").append(frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            if (++shown >= 9)
                break;
        }
        return text.toString();
    }

    /** Blocks a mod itself crashes on when they are not set up (a documented upstream fault), left out of the sweeps. */
    static Set<String> skipped()
    {
        return known("unplaceable");
    }

    static boolean skip(BlockState state)
    {
        return skipped().contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    /**
     * Puts a block down the way a player does: the block, then the "placed by" step that mods use to set a block entity up
     * (assigning an id, registering with a network). Beds and the like place a second block, so they are put down raw.
     * Returns what went wrong, or null.
     */
    static String place(ServerLevel level, BlockPos pos, BlockState state, net.minecraft.server.level.ServerPlayer player)
    {
        level.setBlock(pos, state, 18);
        if (!state.hasBlockEntity() || state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
            return null;
        try
        {
            state.getBlock().setPlacedBy(level, pos, state, player, new net.minecraft.world.item.ItemStack(state.getBlock()));
        }
        catch (RuntimeException failure)
        {
            return "placing it as a player does threw " + failure;
        }
        return null;
    }

    // ------------------------------------------------------------------ entities

    /** One of every entity type another mod adds that fits in a cabin, out and back, with boats given a rider. */
    @GameTest(maxTicks = 600)
    public void everyEntityTypeFromOtherModsTravels(GameTestHelper ctx)
    {
        var spaces = namespaces();
        if (spaces.isEmpty())
        {
            ctx.succeed();
            return;
        }
        var make = EntityTravelGameTest.catalogBed(ctx, 40, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var cells = EntityTravelGameTest.spread(bed.floor());
            bed.player().teleportTo(cells.get(0).getX() + .5, cells.get(0).getY(), cells.get(0).getZ() + .5);
            var anomalies = new TreeMap<String, String>();
            var notes = new ArrayList<String>();
            var aboard = new ArrayList<Entity>();
            int next = 1, tested = 0, tooBig = 0;
            for (var type : BuiltInRegistries.ENTITY_TYPE)
            {
                var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (!spaces.contains(key.getNamespace()))
                    continue;
                String id = key.toString();
                if (type.getWidth() > 2.0F || type.getHeight() > 2.8F)
                {
                    tooBig++;
                    continue;
                }
                if (next >= cells.size())
                {
                    anomalies.put(id, "NO ROOM in the test ship; add a second ship");
                    continue;
                }
                Entity entity;
                try
                {
                    entity = EntityTravelGameTest.spawn(level, type, cells.get(next));
                }
                catch (RuntimeException failure)
                {
                    anomalies.put(id, "CANNOT BE CREATED: " + failure);
                    continue;
                }
                if (entity == null || entity.isRemoved())
                {
                    anomalies.put(id, "CANNOT BE CREATED: the type returned nothing");
                    continue;
                }
                next++;
                tested++;
                if (!type.canSerialize())
                    anomalies.put(id, "NOT SAVED: the mod marks this type no-save, so it cannot be carried");
                if (entity instanceof net.minecraft.world.entity.vehicle.ContainerEntity cargo)
                    ContainerFill.fill(cargo, level, spaces);
                if (entity instanceof Boat boat && next < cells.size())
                {
                    var rider = EntityTravelGameTest.spawn(level, EntityTypes.PIG, cells.get(next++));
                    rider.startRiding(boat, true, false);
                    aboard.add(rider);
                }
                aboard.add(entity);
            }
            notes.add(spaces + ": " + tested + " entity types tested, " + tooBig + " too big for a cabin");
            // A no-save type cannot be in the journal, so it is not expected back; everything else must come back.
            aboard.removeIf(e -> !e.getType().canSerialize());
            if (!aboard.isEmpty())
            {
                var problems = EntityTravelGameTest.roundTrip(bed, aboard, Set.of());
                for (var problem : problems)
                    anomalies.put(problem.length() > 90 ? problem.substring(0, 90) : problem, problem);
            }
            for (var entity : HullEntitiesProxy.inShip(bed))
                if (!(entity instanceof net.minecraft.server.level.ServerPlayer))
                    entity.discard();
            level.getServer().getPlayerList().remove(bed.player());
            WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(bed.shipId()));
            conclude(ctx, "entities", notes, anomalies);
        });
    }

    // ------------------------------------------------------------------ blocks

    /** The default state, plus each property turned once, so the shapes a mod's block really takes are covered. */
    static List<BlockState> statesToTry(BlockState base)
    {
        var states = new LinkedHashSet<BlockState>();
        states.add(base);
        int extra = 0;
        for (Property<?> property : base.getProperties())
        {
            if (extra >= 6)
                break;
            states.add(base.cycle(property));
            extra++;
        }
        return List.copyOf(states);
    }

    /** Anything that a ship has no way to treat as an ordinary block. */
    static boolean interesting(BlockState state)
    {
        return state.hasBlockEntity() || state.getBlock() instanceof EntityBlock || !state.getFluidState().isEmpty() || state.isRandomlyTicking() || state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock || state.getProperties().stream().anyMatch(p -> p.getName().equals("half") || p.getName().equals("part") || p.getName().equals("waterlogged") || p.getName().equals("distance") || p.getName().equals("persistent"));
    }

    /**
     * Cells that can hold a test block without cutting the way from the player to anywhere else on the ship, farthest
     * first, so a ship's own walkways stay open and its pressure and exit checks mean what they usually do.
     */
    static List<BlockPos> spareCells(List<BlockPos> floor, BlockPos start)
    {
        var all = new HashSet<>(floor);
        var distance = new HashMap<BlockPos, Integer>();
        var queue = new ArrayDeque<BlockPos>(List.of(start));
        distance.put(start, 0);
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        var next = at.offset(dx, dy, dz);
                        if ((dx == 0) == (dz == 0) || !all.contains(next) || distance.containsKey(next))
                            continue;
                        distance.put(next, distance.get(at) + 1);
                        queue.add(next);
                    }
        }
        var ordered = new ArrayList<>(distance.keySet());
        ordered.sort(Comparator.<BlockPos>comparingInt(p -> -distance.get(p)).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        var removed = new HashSet<BlockPos>();
        var spare = new ArrayList<BlockPos>();
        for (var candidate : ordered)
        {
            if (candidate.equals(start))
                continue;
            removed.add(candidate);
            if (connected(all, removed, start, distance.size() - removed.size()))
                spare.add(candidate);
            else
                removed.remove(candidate);
        }
        return spare;
    }

    private static boolean connected(Set<BlockPos> all, Set<BlockPos> removed, BlockPos start, int expected)
    {
        var seen = new HashSet<BlockPos>(List.of(start));
        var queue = new ArrayDeque<BlockPos>(List.of(start));
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        var next = at.offset(dx, dy, dz);
                        if ((dx == 0) == (dz == 0) || !all.contains(next) || removed.contains(next) || !seen.add(next))
                            continue;
                        queue.add(next);
                    }
        }
        return seen.size() >= expected;
    }

    record Placed(BlockPos cell, BlockState state, BlockState original)
    {
        Placed(BlockPos cell, BlockState state)
        {
            this(cell, state, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    /** Every block another mod adds, in batches on a ship's floor: launched, landed, and compared state for state and block entity for block entity. */
    @GameTest(maxTicks = 2400)
    public void everyBlockFromOtherModsTravels(GameTestHelper ctx)
    {
        var spaces = namespaces();
        if (spaces.isEmpty())
        {
            ctx.succeed();
            return;
        }
        var make = EntityTravelGameTest.catalogBed(ctx, 50, "rivet_ranger");
        ctx.runAfterDelay(30, () ->
        {
            var bed = make.get();
            var level = bed.level();
            var start = bed.floor().getFirst();
            bed.player().teleportTo(start.getX() + .5, start.getY(), start.getZ() + .5);
            var cells = spareCells(bed.floor(), start);
            var anomalies = new TreeMap<String, String>();
            var notes = new ArrayList<String>();
            var states = new ArrayList<BlockState>();
            var doors = new ArrayList<BlockState>();
            int blocks = 0, interesting = 0;
            for (var block : BuiltInRegistries.BLOCK)
            {
                var key = BuiltInRegistries.BLOCK.getKey(block);
                if (!spaces.contains(key.getNamespace()))
                    continue;
                if (skipped().contains(key.toString()))
                    continue;
                blocks++;
                // A door means nothing without its frame, so doors are swapped into the ship's real doorways below.
                if (block instanceof net.minecraft.world.level.block.DoorBlock)
                {
                    doors.add(block.defaultBlockState());
                    continue;
                }
                for (var state : statesToTry(block.defaultBlockState()))
                {
                    states.add(state);
                    if (interesting(state))
                        interesting++;
                }
            }
            notes.add(spaces + ": " + blocks + " blocks, " + states.size() + " states to try (" + interesting + " could be strange), " + cells.size() + " free cells per batch");
            ctx.assertTrue(cells.size() >= 20, "The test ship has room: " + cells.size() + " cells");
            int offset = 0, batches = 0;
            while (offset < states.size())
            {
                var batch = new ArrayList<Placed>();
                for (int i = 0; i < cells.size() && offset < states.size(); i++)
                    batch.add(new Placed(cells.get(i), states.get(offset++)));
                batches++;
                runBatch(bed, batch, anomalies);
            }
            notes.add(batches + " batches, " + doors.size() + " door types swapped into doorways");
            runDoors(bed, doors, anomalies);
            level.getServer().getPlayerList().remove(bed.player());
            WorldStore.get(level.getServer()).ships().removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(bed.shipId()));
            conclude(ctx, "blocks", notes, anomalies);
        });
    }

    /** Each modded door goes where one of the ship's own doors is, so the frame, the seal and the way out are all real. */
    private static void runDoors(EntityTravelGameTest.Bed bed, List<BlockState> doors, Map<String, String> anomalies)
    {
        var level = bed.level();
        var scan = ShipService.inspect(level, bed.helm()).scan();
        var slots = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(scan.bounds().min(), scan.bounds().max()))
        {
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && state.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER && !level.getBlockState(at.below()).is(com.nullandkale.nullspace.content.ModBlocks.DOCKING_BASE))
                slots.add(at.immutable());
        }
        if (slots.isEmpty() || doors.isEmpty())
            return;
        for (int offset = 0; offset < doors.size(); offset += slots.size())
        {
            var batch = new ArrayList<Placed>();
            for (int i = 0; i < slots.size() && offset + i < doors.size(); i++)
            {
                var slot = slots.get(i);
                var old = level.getBlockState(slot);
                var modded = doors.get(offset + i);
                var lower = modded.setValue(net.minecraft.world.level.block.DoorBlock.FACING, old.getValue(net.minecraft.world.level.block.DoorBlock.FACING)).setValue(net.minecraft.world.level.block.DoorBlock.HINGE, old.getValue(net.minecraft.world.level.block.DoorBlock.HINGE)).setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false);
                batch.add(new Placed(slot, lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), old));
                batch.add(new Placed(slot.above(), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), level.getBlockState(slot.above())));
            }
            runBatch(bed, batch, anomalies);
        }
    }

    static String describe(BlockState state)
    {
        String text = state.toString();
        int bracket = text.indexOf('[');
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + (bracket < 0 ? "" : text.substring(bracket));
    }

    static CompoundTag data(ServerLevel level, BlockPos at)
    {
        var entity = level.getBlockEntity(at);
        return entity == null ? null : entity.saveWithoutMetadata(level.registryAccess());
    }

    /** Places a batch, flies the ship out and back, and notes each block that was refused or came back different. */
    static void runBatch(EntityTravelGameTest.Bed bed, List<Placed> batch, Map<String, String> anomalies)
    {
        runBatch(bed, batch, anomalies, null);
    }

    static void runBatch(EntityTravelGameTest.Bed bed, List<Placed> batch, Map<String, String> anomalies, java.util.function.Consumer<List<Placed>> afterPlace)
    {
        try
        {
            runBatchChecked(bed, batch, anomalies, afterPlace);
        }
        catch (RuntimeException failure)
        {
            anomalies.put("(batch " + describe(batch.getFirst().state()) + " ...) THREW", trace(failure));
            clear(bed.level(), batch);
        }
    }

    private static void runBatchChecked(EntityTravelGameTest.Bed bed, List<Placed> batch, Map<String, String> anomalies, java.util.function.Consumer<List<Placed>> afterPlace)
    {
        var level = bed.level();
        var space = bed.space();
        var server = level.getServer();
        var base = WorldStore.pos(bed.ship(), "base");
        var before = new LinkedHashMap<BlockPos, CompoundTag>();
        var active = new ArrayList<Placed>(batch);
        for (var placed : active)
        {
            var problem = place(level, placed.cell(), placed.state(), bed.player());
            if (problem != null)
                anomalies.put(describe(placed.state()), problem);
        }
        if (afterPlace != null)
            afterPlace.accept(active);
        // A block that the ship refuses is a block that stalls a player: note it, take it out, and try the rest.
        for (int attempt = 0; attempt <= batch.size(); attempt++)
        {
            try
            {
                ShipService.preflight(level, bed.helm());
                break;
            }
            catch (HullScan.Failure refusal)
            {
                var culprit = active.stream().filter(p -> p.cell().equals(refusal.location) || refusal.trace.contains(p.cell())).findFirst();
                if (culprit.isEmpty())
                {
                    anomalies.put("(ship)", "The test ship itself was refused, so the rest of this batch is untested: " + refusal.getMessage().replace('\n', ' '));
                    clear(level, batch);
                    return;
                }
                anomalies.put(describe(culprit.get().state()), "REFUSED: " + refusal.getMessage().replace('\n', ' '));
                level.setBlock(culprit.get().cell(), culprit.get().original(), 18);
                active.remove(culprit.get());
            }
        }
        var expected = new LinkedHashMap<BlockPos, BlockState>();
        for (var placed : active)
        {
            expected.put(placed.cell().subtract(base), level.getBlockState(placed.cell()));
            before.put(placed.cell().subtract(base), data(level, placed.cell()));
        }
        int slot = EntityTravelGameTest.firstFreeSlot(server);
        EntityTravelGameTest.hold(space, slot * TravelService.SPACE_SPACING - 2, slot * TravelService.SPACE_SPACING + 33, -2, 33, true);
        var launched = TravelService.transfer(bed.player(), bed.helm(), "", ShipService.preflight(level, bed.helm()));
        if (launched.outcome() != TravelService.Outcome.COMPLETED)
        {
            anomalies.put("(batch " + describe(batch.getFirst().state()) + " ...)", "The ship would not launch with this batch: " + launched.message().replace('\n', ' '));
            clear(level, batch);
            return;
        }
        var ship = bed.ship();
        check("In space", space, WorldStore.pos(ship, "base"), expected, before, anomalies);
        var orbitHelm = WorldStore.pos(ship, "controller");
        var landed = TravelService.transfer(bed.player(), orbitHelm, "id:" + bed.padId(), ShipService.preflight(space, orbitHelm));
        if (landed.outcome() != TravelService.Outcome.COMPLETED)
        {
            anomalies.put("(batch landing " + describe(batch.getFirst().state()) + " ...)", "The ship would not land with this batch: " + landed.message().replace('\n', ' '));
            return;
        }
        check("Back on the pad", level, WorldStore.pos(bed.ship(), "base"), expected, before, anomalies);
        clear(level, batch);
    }

    static void clear(ServerLevel level, List<Placed> batch)
    {
        for (var placed : batch)
        {
            level.removeBlockEntity(placed.cell());
            level.setBlock(placed.cell(), placed.original(), 18);
        }
    }

    static void check(String phase, ServerLevel where, BlockPos base, Map<BlockPos, BlockState> expected, Map<BlockPos, CompoundTag> before, Map<String, String> anomalies)
    {
        for (var entry : expected.entrySet())
        {
            var at = base.offset(entry.getKey());
            var now = where.getBlockState(at);
            if (!now.equals(entry.getValue()))
            {
                anomalies.put(describe(entry.getValue()), phase + ": CHANGED to " + describe(now) + " (was " + entry.getValue() + ", now " + now + ")");
                continue;
            }
            var was = before.get(entry.getKey());
            var is = data(where, at);
            if (was == null != (is == null))
                anomalies.put(describe(entry.getValue()), phase + ": BLOCK ENTITY " + (is == null ? "LOST" : "APPEARED"));
            else if (was != null && !was.equals(is))
                anomalies.put(describe(entry.getValue()), phase + ": BLOCK ENTITY DATA CHANGED " + was + " -> " + is);
        }
    }

    /** The entities standing in a bed's ship, wherever it now is. */
    private static final class HullEntitiesProxy
    {
        static List<Entity> inShip(EntityTravelGameTest.Bed bed)
        {
            return com.nullandkale.nullspace.gameplay.ship.structure.HullEntities.inBounds(bed.level(), WorldStore.bounds(bed.ship()));
        }
    }
}
