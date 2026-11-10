package com.nullandkale.nullspace.gameplay.travel;

import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.HullEntities;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;

import java.nio.file.*;
import java.util.*;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/**
 * The transfer journal after a crash: replaying or rolling back a move and putting players where
 * they belong.
 */
public final class TransferRecovery
{

    /** A journaled entity and every rider saved inside it. */
    static Set<UUID> journaled(CompoundTag entry)
    {
        var ids = new HashSet<UUID>();
        ids.add(UUID.fromString(entry.getStringOr("id", "")));
        for (Tag rider : entry.getListOrEmpty("riders"))
            ids.add(UUID.fromString(rider.asString().orElse("")));
        return ids;
    }

    static void checkpoint(Path file, CompoundTag journal, String stage)
    {
        journal.putString("stage", stage);
        WorldStore.write(file, journal);
        TravelService.failureInjector.accept(stage);
    }

    static void safeOfflineArrival(ServerLevel source, HullScan.Result scan, BlockPos base, CompoundTag member)
    {
        var feet = BlockPos.containing(base.getX() + member.getDoubleOr("x", 0), base.getY() + member.getDoubleOr("y", 0), base.getZ() + member.getDoubleOr("z", 0));
        if (scan.pressure().breathable(feet) && scan.pressure().breathable(feet.above()) && scan.blocks().contains(feet.below()) && source.getBlockState(feet.below()).isCollisionShapeFullBlock(source, feet.below()))
            return;
        // An offline passenger's old room may be in the abandoned component. Reconnect in the
        // validated cabin, never at an offset that has become empty space in the departing ship.
        var safe = scan.boarding().subtract(base);
        member.putDouble("x", safe.getX() + .5);
        member.putDouble("y", safe.getY());
        member.putDouble("z", safe.getZ() + .5);
    }

    public static void place(ServerLevel level, StructureTemplate template, BlockPos min)
    {
        TransferReplay.place(level, template, min);
    }

    public static void clear(ServerLevel level, BlockPos min, BlockPos max)
    {
        TransferReplay.clear(level, min, max);
    }

    /** Servers with a journal that could not be replayed yet because the chunks' saved entities were still loading. */
    private static final Set<MinecraftServer> WAITING = Collections.newSetFromMap(new WeakHashMap<>());

    static
    {
        // Entities load from disk on level ticks, so a journal that has to wait is retried every tick until it can run.
        ServerTickEvents.END_SERVER_TICK.register(server ->
        {
            if (WAITING.contains(server) && !replay(server))
                WAITING.remove(server);
        });
    }

    /** Asks for the chunks around a hull and says whether their saved entities are loaded yet. */
    private static boolean entitiesReady(ServerLevel level, HullScan.Bounds bounds)
    {
        for (int x = (bounds.min().getX() - 2) >> 4; x <= (bounds.max().getX() + 2) >> 4; x++)
            for (int z = (bounds.min().getZ() - 2) >> 4; z <= (bounds.max().getZ() + 2) >> 4; z++)
                level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(x, z), 2);
        return HullEntities.loaded(level, bounds);
    }

    public static void recover(MinecraftServer server)
    {
        if (replay(server))
            WAITING.add(server);
    }

    /** Replays every unfinished journal that can run now. Returns true when some journal has to wait for its entities. */
    private static boolean replay(MinecraftServer server)
    {
        boolean waiting = false;
        var store = WorldStore.get(server);
        try (var files = Files.list(store.directory.resolve("transfers")))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".nbt")).sorted().toList())
            {
                var journal = WorldStore.read(file);
                if (journal.getIntOr("version", 0) != 1)
                    throw new IllegalStateException("Unsupported transfer journal");
                if (journal.getStringOr("stage", "").equals("COMMITTED") || journal.getStringOr("stage", "").equals("ROLLED_BACK"))
                    continue;
                var source = WorldStore.level(server, journal.getStringOr("source", ""));
                var target = WorldStore.level(server, journal.getStringOr("target", ""));
                BlockPos min = WorldStore.pos(journal, "min"), max = WorldStore.pos(journal, "max"), delta = WorldStore.pos(journal, "delta");
                // Entities load from disk after their chunk does. Restoring a passenger before that finishes leaves it
                // unregistered, so a journal waits (and is retried every tick) until both ends of the move are ready.
                if (!entitiesReady(source, new HullScan.Bounds(min, max)) | !entitiesReady(target, new HullScan.Bounds(min.offset(delta), max.offset(delta))))
                {
                    waiting = true;
                    continue;
                }
                store.replace(journal.getCompoundOrEmpty("before").copy(), ShipRepository.Mutation.RECOVERY);
                for (Tag tag : journal.getListOrEmpty("entities"))
                    for (UUID moved : journaled((CompoundTag) tag))
                        authorityStore(store, moved, source.dimension().identifier().toString(), target.dimension().identifier().toString(), min.offset(delta), max.offset(delta));
                clear(target, min.offset(delta), max.offset(delta));
                var template = new StructureTemplate();
                template.load(source.registryAccess().lookupOrThrow(Registries.BLOCK), journal.getCompoundOrEmpty("template"));
                place(source, template, min);
                for (Tag tag : journal.getListOrEmpty("entities"))
                {
                    var entry = (CompoundTag) tag;
                    UUID id = UUID.fromString(entry.getStringOr("id", ""));
                    var ids = journaled(entry);
                    for (var old : HullEntities.inBounds(source, new HullScan.Bounds(min, max)))
                        if (ids.contains(old.getUUID()))
                            old.discard();
                    for (var old : HullEntities.inBounds(target, new HullScan.Bounds(min.offset(delta), max.offset(delta))))
                        if (ids.contains(old.getUUID()))
                            old.discard();
                    var entity = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, source.registryAccess(), entry.getCompoundOrEmpty("data")), source, EntitySpawnReason.LOAD, e -> e);
                    if (entity == null)
                        throw new IllegalStateException("Could not restore passenger " + id);
                    source.addFreshEntityWithPassengers(entity);
                    if (entity.isRemoved() || source.getEntity(entity.getUUID()) != entity)
                        throw new IllegalStateException("Could not establish authoritative passenger " + id + " (" + entity.getType() + ", removed " + entity.isRemoved() + ", reason " + entity.getRemovalReason() + ", the level holds " + source.getEntity(entity.getUUID()) + ", entities loaded here " + HullEntities.loaded(source, new HullScan.Bounds(min, max)) + ")");
                }
                // A fence-post knot is never journaled: an animal leaves one at the destination when it is tied there. With the
                // animals gone it would only block the next ship, so it goes with them.
                for (var stray : HullEntities.inBounds(target, new HullScan.Bounds(min.offset(delta), max.offset(delta))))
                    if (stray instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity)
                        stray.discard();
                for (Tag tag : journal.getListOrEmpty("players"))
                {
                    var p = (CompoundTag) tag;
                    targetStore(store, UUID.fromString(p.getStringOr("id", "")), source.dimension().identifier().toString(), p.getDoubleOr("x", 0), p.getDoubleOr("y", 0), p.getDoubleOr("z", 0), p.getFloatOr("yaw", 0), p.getFloatOr("pitch", 0));
                }
                store.save();
                for (ServerPlayer player : server.getPlayerList().getPlayers())
                    resolveArrival(player);
                server.saveEverything(true, true, true);
                journal.putString("stage", "ROLLED_BACK");
                WorldStore.write(file, journal);
            }
        }
        catch (java.io.IOException error)
        {
            throw new IllegalStateException("Cannot recover transfer journal", error);
        }
        return waiting;
    }

    public static void resolveArrival(ServerPlayer player)
    {
        var store = WorldStore.get(player.level().getServer());
        for (Tag item : store.list("targets"))
        {
            var target = (CompoundTag) item;
            if (!target.getStringOr("id", "").equals(player.getUUID().toString()))
                continue;
            var level = WorldStore.level(player.level().getServer(), target.getStringOr("dimension", ""));
            player.teleport(new TeleportTransition(level, new Vec3(target.getDoubleOr("x", 0), target.getDoubleOr("y", 0), target.getDoubleOr("z", 0)), Vec3.ZERO, target.getFloatOr("yaw", 0), target.getFloatOr("pitch", 0), TeleportTransition.PLACE_PORTAL_TICKET));
            clearTarget(player);
            break;
        }
    }

    static void clearTarget(ServerPlayer player)
    {
        var store = WorldStore.get(player.level().getServer());
        store.list("targets").removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(player.getUUID().toString()));
        store.save();
    }

    static void targetStore(WorldStore store, UUID id, String dimension, double x, double y, double z, float yaw, float pitch)
    {
        var targets = store.list("targets");
        targets.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id.toString()));
        var target = new CompoundTag();
        target.putString("id", id.toString());
        target.putString("dimension", dimension);
        target.putDouble("x", x);
        target.putDouble("y", y);
        target.putDouble("z", z);
        target.putFloat("yaw", yaw);
        target.putFloat("pitch", pitch);
        targets.add(target);
    }

    static void authorityStore(WorldStore store, UUID id, String dimension, String obsolete, BlockPos min, BlockPos max)
    {
        var root = new CompoundTag();
        root.put("entityAuthority", store.list("entityAuthority"));
        authority(root, id, dimension, obsolete, min, max);
    }

    static void authority(CompoundTag root, UUID id, String dimension, String obsolete, BlockPos min, BlockPos max)
    {
        if (!root.contains("entityAuthority"))
            root.put("entityAuthority", new ListTag());
        var list = root.getListOrEmpty("entityAuthority");
        list.removeIf(t -> ((CompoundTag) t).getStringOr("id", "").equals(id.toString()));
        var tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putString("dimension", dimension);
        tag.putString("obsoleteDimension", obsolete);
        tag.putLong("min", min.asLong());
        tag.putLong("max", max.asLong());
        list.add(tag);
    }

    private TransferRecovery()
    {
    }
}
