package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.*;
import com.nullandkale.nullspace.gameplay.pads.*;
import com.nullandkale.nullspace.gameplay.ship.ShipIntegrity;
import com.nullandkale.nullspace.gameplay.ship.structure.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;

/** Operator rescue from a MOVE's saved hull. The rescue itself is a BUILD, including entity authority and arrivals. */
public final class JournalRestore
{
    private record Location(ServerLevel level, BlockPos min, BlockPos max, Set<BlockPos> positions)
    {}

    public static String restore(MinecraftServer server, UnresolvedJournals.Entry entry, String destination) throws IOException
    {
        if (entry.file() == null || entry.file().toString().endsWith(".restoring"))
            throw new IllegalArgumentException("Choose an unresolved MOVE journal; finish any restoration already in progress first");
        var original = WorldStore.read(entry.file());
        if (original.getIntOr("version", 0) != Journals.VERSION || !original.getStringOr("kind", "").equals("MOVE"))
            throw new IllegalArgumentException("Restore requires a readable MOVE journal in this version's format; use export for other formats");
        var shipChanges = StoreRecords.decode(original.getListOrEmpty("records")).stream().filter(e -> e.top().equals("ships") && e.kind() == StoreRecords.Kind.KEYED && (e.before() instanceof CompoundTag || e.after() instanceof CompoundTag)).toList();
        if (shipChanges.size() != 1)
            throw new IllegalArgumentException("This journal contains multiple ships or no complete ship record; export it for individual recovery");
        var change = shipChanges.getFirst();
        var ship = ((CompoundTag) (change.before() != null ? change.before() : change.after())).copy();
        String id = ship.getStringOr("id", "");
        var sourceMin = WorldStore.pos(original, "min");
        var sourceMax = WorldStore.pos(original, "max");
        var originalDelta = WorldStore.pos(original, "delta");
        var savedBase = WorldStore.pos(ship, "base");
        var sourceBase = original.contains("transform") ? ShipTransform.fromJournal(original).source() : change.before() != null ? savedBase : savedBase.subtract(originalDelta);
        var localMin = sourceMin.subtract(sourceBase);
        var localMax = sourceMax.subtract(sourceBase);
        var originals = new ArrayList<Location>();
        for (String side : List.of("source", "target"))
        {
            var level = server.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, net.minecraft.resources.Identifier.parse(original.getStringOr(side, ""))));
            if (level == null)
                continue;
            var offset = side.equals("source") ? BlockPos.ZERO : originalDelta;
            var positions = new HashSet<BlockPos>();
            TransferReplay.positions(original.getCompoundOrEmpty("template"), sourceMin).forEach(p -> positions.add(side.equals("source") ? p : ShipTransform.fromJournal(original).apply(p)));
            var bounds = side.equals("source") ? new HullScan.Bounds(sourceMin, sourceMax) : ShipTransform.destinationBounds(original);
            load(level, bounds);
            HullEntities.requireLoaded(level, bounds);
            originals.add(new Location(level, bounds.min(), bounds.max(), positions));
        }
        var store = WorldStore.get(server);
        UnresolvedJournals.remove(server, entry.id());
        BuildJournal.Job build = null;
        Path held = null;
        boolean completed = false;
        try
        {
            ServerLevel target;
            BlockPos base;
            PadRegistry.Pad pad = null;
            if (destination.equalsIgnoreCase("space"))
            {
                target = server.getLevel(TravelService.SPACE);
                if (target == null)
                    throw new IllegalArgumentException("The space dimension is unavailable");
                base = null;
                for (int slot = 0; slot <= 7000 && base == null; slot++)
                    if (TransferLocks.spaceSlotFree(server, slot))
                    {
                        var candidate = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
                        if (clear(target, new HullScan.Bounds(candidate.offset(localMin), candidate.offset(localMax))))
                            base = candidate;
                    }
                if (base == null)
                    throw new IllegalArgumentException("No clear space slot remains");
            }
            else
            {
                var pads = PadRegistry.read(server).stream().filter(p -> destination.equals("id:" + p.id()) || p.name().equalsIgnoreCase(destination)).toList();
                if (pads.size() != 1)
                    throw new IllegalArgumentException("Choose one registered pad by name or id:<pad-id>, or space");
                pad = pads.getFirst();
                target = WorldStore.level(server, pad.dimension());
                base = new BlockPos(pad.bounds().minX(), pad.bounds().y() + 1, pad.bounds().minZ());
                var bounds = new HullScan.Bounds(base.offset(localMin), base.offset(localMax));
                if (localMin.getX() < 0 || localMin.getY() < 0 || localMin.getZ() < 0 || localMax.getX() >= pad.bounds().width() || localMax.getZ() >= pad.bounds().depth() || localMax.getY() >= PadRegistry.buildHeight(target, pad.bounds()))
                    throw new IllegalArgumentException("The saved hull does not fit this pad");
                load(target, bounds);
                PadRegistry.validate(target, pad);
                if (!clear(target, bounds))
                    throw new IllegalArgumentException("The rescue pad is occupied or reserved");
            }
            var targetMin = base.offset(localMin);
            var targetMax = base.offset(localMax);
            var targetBounds = new HullScan.Bounds(targetMin, targetMax);
            HullEntities.requireLoaded(target, targetBounds);
            var shift = targetMin.subtract(sourceMin);
            var affected = new LinkedHashSet<UUID>();
            for (var tag : original.getListOrEmpty("entities"))
                affected.addAll(TransferRecovery.journaled((CompoundTag) tag));
            for (var level : server.getAllLevels())
                for (var uuid : affected)
                {
                    var entity = level.getEntity(uuid);
                    if (entity != null && originals.stream().noneMatch(location -> location.level() == level && new HullScan.Bounds(location.min(), location.max()).box().intersects(entity.getBoundingBox())))
                        throw new IllegalArgumentException("A saved passenger already exists outside this journal's original areas");
                }
            var positions = TransferReplay.positions(original.getCompoundOrEmpty("template"), targetMin);
            if (positions.isEmpty())
                throw new IllegalArgumentException("The saved hull is empty");
            Files.createDirectories(Journals.quarantine(store.directory));
            held = Journals.quarantine(store.directory).resolve(entry.file().getFileName() + ".restoring");
            Files.move(entry.file(), held);
            build = BuildJournal.begin(target, "restore-ship", List.of(id), positions);
            build.annotate("rescuedOriginal", StringTag.valueOf(entry.file().getFileName().toString()));
            for (var location : originals)
                build.include(location.level(), location.positions());
            build.captureEntityChanges(affected);
            var restoredBase = base;
            var landing = pad;
            build.step(() ->
            {
                for (var location : originals)
                    TransferReplay.clear(location.level(), location.positions());
                for (var level : server.getAllLevels())
                    for (var uuid : affected)
                    {
                        var entity = level.getEntity(uuid);
                        if (entity != null)
                            entity.discard();
                    }
                var template = new StructureTemplate();
                var saved = BlockSupport.destinationTemplate(original.getCompoundOrEmpty("template"), TransferReplay.positions(original.getCompoundOrEmpty("template"), sourceMin), shift);
                template.load(target.registryAccess().lookupOrThrow(Registries.BLOCK), saved);
                TransferRecovery.place(target, template, targetMin);
                JournalTicks.restore(target, original.getCompoundOrEmpty("ticks"), shift);
                var controllerLocal = WorldStore.pos(ship, "controller").subtract(savedBase);
                ship.putString("dimension", target.dimension().identifier().toString());
                ship.putLong("base", restoredBase.asLong());
                ship.putLong("controller", restoredBase.offset(controllerLocal).asLong());
                ship.remove("stored");
                ship.remove("dock");
                ship.remove("dockLinks");
                ship.remove("course");
                ship.remove("journey");
                if (landing != null)
                {
                    var address = com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces.address(target);
                    if (address != null)
                        ship.putString("localSpace", address);
                    ship.putString("home", landing.name());
                    ship.putString("homeId", landing.id());
                    ship.putInt("width", landing.bounds().width());
                    ship.putInt("depth", landing.bounds().depth());
                    ship.putInt("height", PadRegistry.buildHeight(target, landing.bounds()));
                }
                RotatedShipState.ship(ship, new ShipTransform(savedBase, restoredBase, 0, 0, 0));
                store.repository().apply(List.of(new StoreRecords.Entry("ships", StoreRecords.Kind.KEYED, id, store.repository().find(id), ship)), true, ShipRepository.Mutation.RECOVERY);
                ShipIntegrity.stamp(target, WorldStore.pos(ship, "controller"), id);
                for (var uuid : affected)
                    for (var location : originals)
                        TransferRecovery.authorityStore(store, uuid, target.dimension().identifier().toString(), location.level().dimension().identifier().toString(), location.min(), location.max());
                for (var tag : original.getListOrEmpty("entities"))
                {
                    var passenger = (CompoundTag) tag;
                    var entity = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, target.registryAccess(), relocated(passenger.getCompoundOrEmpty("data"), shift)), target, EntitySpawnReason.LOAD, e -> e);
                    if (entity == null)
                        throw new IllegalStateException("A saved passenger could not be restored");
                    target.addFreshEntityWithPassengers(entity);
                    if (entity.isRemoved() || target.getEntity(entity.getUUID()) != entity)
                        throw new IllegalStateException("A rescued passenger could not be registered");
                }
                for (var tag : original.getListOrEmpty("players"))
                {
                    var player = (CompoundTag) tag;
                    TransferRecovery.targetStore(store, UUID.fromString(player.getStringOr("id", "")), target.dimension().identifier().toString(), player.getDoubleOr("x", 0) + shift.getX(), player.getDoubleOr("y", 0) + shift.getY(), player.getDoubleOr("z", 0) + shift.getZ(), player.getFloatOr("yaw", 0), player.getFloatOr("pitch", 0));
                }
                store.requestSave();
                return null;
            });
            build.commitNow();
            var marker = new CompoundTag();
            marker.putString("rescuedOriginal", entry.file().getFileName().toString());
            finishOriginal(server, marker, true);
            completed = true;
            for (var player : server.getPlayerList().getPlayers())
                TransferRecovery.resolveArrival(player);
            return "Restored " + id + " at " + destination;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            if (build != null)
                build.abort();
            if (held != null && Files.exists(held))
            {
                var marker = new CompoundTag();
                marker.putString("rescuedOriginal", entry.file().getFileName().toString());
                finishOriginal(server, marker, false);
            }
            throw failure;
        }
        finally
        {
            if (!completed && Files.exists(entry.file()))
                UnresolvedJournals.put(server, entry);
        }
    }

    private static void load(ServerLevel level, HullScan.Bounds bounds)
    {
        for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
            for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new net.minecraft.world.level.ChunkPos(x, z), 2);
        for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
            for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                level.getChunk(x, z);
    }

    /** Relocate before loading, so attachments and delayed leash data agree with the entity's new position. */
    private static CompoundTag relocated(CompoundTag original, BlockPos shift)
    {
        var data = original.copy();
        var saved = data.getListOrEmpty("Pos");
        if (saved.size() != 3)
            throw new IllegalArgumentException("A saved passenger has no valid position");
        var position = new ListTag();
        position.add(DoubleTag.valueOf(saved.getDoubleOr(0, 0) + shift.getX()));
        position.add(DoubleTag.valueOf(saved.getDoubleOr(1, 0) + shift.getY()));
        position.add(DoubleTag.valueOf(saved.getDoubleOr(2, 0) + shift.getZ()));
        data.put("Pos", position);
        for (String key : List.of("block_pos", "leash"))
            data.getIntArray(key).ifPresent(at ->
            {
                if (at.length != 3)
                    throw new IllegalArgumentException("A saved passenger has an invalid attachment");
                data.putIntArray(key, new int[]{at[0] + shift.getX(), at[1] + shift.getY(), at[2] + shift.getZ()});
            });
        var passengers = new ListTag();
        for (var passenger : data.getListOrEmpty("Passengers"))
            passengers.add(relocated((CompoundTag) passenger, shift));
        if (data.contains("Passengers"))
            data.put("Passengers", passengers);
        return data;
    }

    private static boolean clear(ServerLevel level, HullScan.Bounds bounds)
    {
        load(level, bounds);
        if (bounds.min().getY() < level.getMinY() || bounds.max().getY() >= level.getMaxY() || !level.getWorldBorder().isWithinBounds(bounds.min()) || !level.getWorldBorder().isWithinBounds(bounds.max()) || TransferLocks.areaBusy(level, bounds))
            return false;
        for (var tag : WorldStore.get(level.getServer()).ships())
        {
            var ship = (CompoundTag) tag;
            if (ship.getStringOr("dimension", "").equals(level.dimension().identifier().toString()) && WorldStore.bounds(ship).box().intersects(bounds.box()))
                return false;
        }
        for (var pos : BlockPos.betweenClosed(bounds.min(), bounds.max()))
            if (!level.getBlockState(pos).isAir())
                return false;
        return HullEntities.inBounds(level, bounds).isEmpty();
    }

    static void finishOriginal(MinecraftServer server, CompoundTag journal, boolean committed) throws IOException
    {
        String name = journal.getStringOr("rescuedOriginal", "");
        if (name.isEmpty() || !Path.of(name).getFileName().toString().equals(name) || !name.endsWith(".nbt"))
            throw new IOException("Invalid original rescue journal name");
        var folder = Journals.quarantine(WorldStore.get(server).directory);
        var held = folder.resolve(name + ".restoring");
        var destination = folder.resolve(committed ? name + ".restored" : name);
        if (Files.exists(held))
            Files.move(held, destination, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(destination))
        {
            var original = WorldStore.read(destination);
            String id = original.getStringOr("id", "");
            if (committed)
                UnresolvedJournals.remove(server, id);
            else
                UnresolvedJournals.put(server, UnresolvedJournals.describe(original, UnresolvedJournals.State.SET_ASIDE, "Restoration rolled back; the original journal is preserved", server.getTickCount(), destination));
        }
    }

    private JournalRestore()
    {
    }
}
