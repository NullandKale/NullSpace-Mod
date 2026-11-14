package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.StationConstruction;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.gameplay.ship.structure.HullScan;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.systems.SystemsUpgrade;
import com.nullandkale.nullspace.gameplay.travel.BuildJournal;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.worldgen.encounters.EncounterSites;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;
import com.nullandkale.nullspace.worldgen.stations.StationPieces;

import java.util.*;

import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Building station rooms: reserving space, paying with kits, the build queue and expansion. */
public final class StationBuilds
{

    static BlockPos reserve(MinecraftServer server)
    {
        return reserve(server, 32, 32);
    }

    static BlockPos reserve(MinecraftServer server, int width, int depth)
    {
        return reserve(server, width, depth, 0).orElseThrow(() -> new IllegalArgumentException("No free space construction sections remain"));
    }

    static Optional<BlockPos> reserve(MinecraftServer server, int width, int depth, int first)
    {
        var level = server.getLevel(TravelService.SPACE);
        if (level == null)
            return Optional.empty();
        int candidates = 0;
        for (int slot = first; slot <= 7000; slot++)
            if (TransferLocks.spaceSlotFree(server, slot))
            {
                if (++candidates > 16)
                    break;
                var base = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
                for (int x = 0; x < width; x += 16)
                    for (int z = 0; z < depth; z += 16)
                        level.getChunk(base.offset(x, 0, z));
                boolean clear = true;
                for (var p : BlockPos.betweenClosed(base, base.offset(width - 1, 31, depth - 1)))
                    if (!level.getBlockState(p).isAir())
                    {
                        clear = false;
                        break;
                    }
                if (clear && level.getEntities(null, new HullScan.Bounds(base, base.offset(width - 1, 31, depth - 1)).box()).isEmpty())
                    return Optional.of(base);
            }
        return Optional.empty();
    }

    static ListTag encode(StationBlueprint.Plan plan)
    {
        var list = new ListTag();
        for (var e : plan.blocks().entrySet())
        {
            var t = new CompoundTag();
            t.putLong("pos", e.getKey().asLong());
            t.put("state", NbtUtils.writeBlockState(e.getValue()));
            list.add(t);
        }
        return list;
    }

    public static CompoundTag queue(MinecraftServer server, String id, String name, String address, Vec3 position, String owner, StationBlueprint.Plan plan, boolean foundation)
    {
        return queue(server, id, name, address, position, owner, plan, foundation, new CompoundTag());
    }

    /** Ambient places use their own storage range and never consume a player's station quota. */
    public static CompoundTag queueAmbient(MinecraftServer server, String id, String name, String address, Vec3 position, StationBlueprint.Plan plan, CompoundTag metadata)
    {
        return queue(server, id, name, address, position, "", plan, false, metadata);
    }

    static CompoundTag queue(MinecraftServer server, String id, String name, String address, Vec3 position, String owner, StationBlueprint.Plan plan, boolean foundation, CompoundTag metadata)
    {
        boolean ambient = metadata.getBooleanOr("worldGenerated", false);
        var store = WorldStore.get(server);
        var existing = store.repository().find(id);
        if (existing != null)
            return existing;
        long count = store.ships().stream().map(t -> (CompoundTag) t).filter(s -> StationAccess.station(s) && s.getStringOr("owner", "").equals(owner)).count();
        if (!ambient && count >= 32)
            throw new IllegalArgumentException("Station quota reached for this owner");
        int width = StationSpace.extent(plan, true), depth = StationSpace.extent(plan, false);
        var base = ambient ? reserve(server, width, depth, 6000).orElse(null) : reserve(server, width, depth);
        if (base == null)
            return null;
        var level = server.getLevel(TravelService.SPACE);
        if (level == null)
            throw new IllegalArgumentException("Space dimension unavailable");
        var s = metadata.copy();
        if (!plan.points().isEmpty())
            com.nullandkale.nullspace.worldgen.encounters.SitePoints.write(s, plan.points());
        s.putString("id", id);
        s.putString("kind", "station");
        s.putString("home", name);
        s.putString("owner", owner);
        s.putString("dimension", TravelService.SPACE.identifier().toString());
        s.putString("localSpace", address);
        s.putString("access", owner.isEmpty() ? "public" : "private");
        s.putLong("base", base.asLong());
        s.putLong("controller", base.offset(plan.core()).asLong());
        s.putInt("width", width);
        s.putInt("depth", depth);
        s.putInt("height", 32);
        s.putDouble("simX", position.x);
        s.putDouble("simY", position.y);
        s.putDouble("simZ", position.z);
        s.put("crew", new ListTag());
        s.put("stationBuild", encode(plan));
        s.putBoolean("foundation", foundation);
        s.putInt("stationVersion", 1);
        s.putBoolean("buildPaid", owner.isEmpty());
        var build = BuildJournal.begin(level, "station-core", List.of(id), plan.blocks().keySet().stream().map(base::offset).toList());
        build.step(() ->
        {
            s.putString("buildJournal", build.fileName());
            store.ships().add(s);
            store.requestSave();
            return null;
        });
        return s;
    }

    static ItemStack kit(ServerPlayer p, Item item)
    {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
        {
            var stack = p.getInventory().getItem(i);
            if (stack.is(item))
                return stack;
        }
        throw new IllegalArgumentException("Carry a crafted " + new ItemStack(item).getHoverName().getString() + " first");
    }

    /** A receipt is stored atomically with player inventory; a redeemed kit cannot create twice. */
    static String receipt(ServerPlayer p, ItemStack stack)
    {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String token = data.getStringOr("stationReceipt", "");
        if (!token.isEmpty() && WorldStore.get(p.level().getServer()).section("stationReceipts").contains(token))
            throw new IllegalArgumentException("That kit was already deployed; its inventory receipt is being settled");
        if (token.isEmpty())
        {
            token = UUID.randomUUID().toString();
            data.putString("stationReceipt", token);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
        p.level().getServer().getPlayerList().saveAll();
        try
        {
            var file = p.level().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(p.getUUID() + ".dat");
            if (!WorldStore.read(file).toString().contains(token))
                throw new IllegalStateException("Could not verify the saved inventory receipt; construction has not been committed");
        }
        catch (java.io.IOException unreadable)
        {
            throw new IllegalStateException("Cannot verify the saved construction inventory", unreadable);
        }
        return token;
    }

    static void redeem(ServerPlayer p, ItemStack stack, String token, int cost)
    {
        var ledger = WorldStore.get(p.level().getServer()).section("stationReceipts");
        if (ledger.contains(token))
            throw new IllegalArgumentException("This kit has already been deployed");
        ledger.putInt(token, p.isCreative() ? 0 : cost);
        for (var tag : WorldStore.get(p.level().getServer()).ships())
        {
            var s = (CompoundTag) tag;
            if (StationService.idOf(s).equals(token) || s.getStringOr("buildReceipt", "").equals(token))
                s.putBoolean("buildPaid", true);
        }
        WorldStore.get(p.level().getServer()).requestSave();
        if (!p.isCreative())
            stack.shrink(cost);
        stack.remove(DataComponents.CUSTOM_DATA);
        p.level().getServer().getPlayerList().saveAll();
    }

    /**
     * Reads {@code <piece> [x z [turns]]} (a piece id from the station catalog, or an old role name)
     * and places it by doorway next to the saved rooms.
     */
    static StationLayout.Room placement(CompoundTag pieces, String[] words)
    {
        String piece = words[0].toLowerCase(Locale.ROOT);
        if (StationPieces.find(piece).isEmpty())
            throw new IllegalArgumentException("Choose a station piece: " + String.join(", ", StationPieces.ALL.stream().map(StationPieces.Definition::id).toList()) + "; optional tile x z (0..7) and turns (0..3)");
        if (words.length != 1 && words.length != 3 && words.length != 4)
            throw new IllegalArgumentException("Use piece x z [turns], for example tunnel_straight 1 0");
        int x = 0, z = 0;
        Integer turns = null;
        try
        {
            if (words.length >= 3)
            {
                x = Integer.parseInt(words[1]);
                z = Integer.parseInt(words[2]);
            }
            if (words.length == 4)
                turns = Integer.parseInt(words[3]);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Use piece x z [turns] with whole numbers");
        }
        var existing = new ArrayList<StationLayout.Room>();
        for (var key : pieces.keySet())
            existing.add(StationLayout.Room.decode(key, pieces.getStringOr(key, "")));
        return StationLayout.place(existing, piece, x, z, turns);
    }

    public static void buildRoom(ServerPlayer p, CompoundTag s, String argument, boolean generate)
    {
        StationAccess.requireBuilder(p, s);
        if (!StationAccess.station(s) || !StationAccess.ready(s) || s.contains("dock") || TransferLocks.busy(p.level().getServer(), s))
            throw new IllegalArgumentException("Finish construction or travel before adding a module");
        var stack = kit(p, ModBlocks.STATION_MODULE);
        String token = receipt(p, stack);
        String[] words = argument.strip().split(" +");
        StationBlueprint.Plan plan;
        var pieces = s.getCompoundOrEmpty("roomPieces").copy();
        var additions = new ArrayList<StationLayout.Room>();
        if (generate)
        {
            if (!p.isCreative() && stack.getCount() < (s.getIntOr("width", 32) > 32 ? 28 : 4))
                throw new IllegalArgumentException("Carry enough module kits: four for a starter or 28 for a full-size ring");
            if (!pieces.isEmpty())
                throw new IllegalArgumentException("Generated assembly requires an empty construction section; add individual room kits to" + " expand");
            var layout = s.getIntOr("width", 32) > 32 ? StationLayout.harbor() : StationLayout.generate(s.getStringOr("id", "").hashCode());
            plan = StationBlueprint.build(layout);
            additions.addAll(layout.rooms());
        }
        else
        {
            var room = placement(pieces, words);
            additions.add(room);
            plan = StationBlueprint.build(new StationLayout.Layout(List.of(room), List.of()));
        }
        for (var room : additions)
            if ((room.x() + 1) * 16 > s.getIntOr("width", 32) || (room.z() + 1) * 16 > s.getIntOr("depth", 32))
                throw new IllegalArgumentException("Expand the station build area at its core before building this tile");
        var out = new LinkedHashMap<>(plan.blocks());
        var base = WorldStore.pos(s, "base");
        var oldCore = WorldStore.pos(s, "controller").subtract(base);
        // A new arrival room brings its own core; the station keeps the core it already has.
        if (out.getOrDefault(plan.core(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()).is(ModBlocks.STATION_CORE))
            out.remove(plan.core());
        out.put(oldCore, ModBlocks.STATION_CORE.defaultBlockState());
        var seamExpected = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (var room : additions)
            for (var key : pieces.keySet())
            {
                var previous = StationLayout.Room.decode(key, pieces.getStringOr(key, ""));
                if (!StationLayout.joined(previous, room))
                    continue;
                var expected = StationBlueprint.build(new StationLayout.Layout(List.of(previous), List.of())).blocks();
                for (var cell : StationBlueprint.seam(previous, room))
                    seamExpected.put(cell, expected.get(cell));
                StationBlueprint.connect(out, previous, room);
            }
        var dockingProblem = StationConstruction.constructionProblem(p.level(), s, out);
        if (dockingProblem.isPresent())
        {
            var problem = dockingProblem.get();
            ShipDiagnostics.highlight(p, List.of(problem.position()));
            throw ShipMessages.blockProblem(p.level(), problem.position(), problem.message());
        }
        var foundation = s.getBooleanOr("foundation", false) ? StationBlueprint.foundation().blocks() : Map.<BlockPos, net.minecraft.world.level.block.state.BlockState>of();
        for (var e : out.entrySet())
        {
            var before = p.level().getBlockState(base.offset(e.getKey()));
            if (!before.isAir() && !before.equals(e.getValue()) && !before.equals(foundation.get(e.getKey())) && !before.equals(seamExpected.get(e.getKey())) && !e.getKey().equals(oldCore))
                throw new IllegalArgumentException("Module is obstructed at " + base.offset(e.getKey()).toShortString() + "; existing construction is preserved");
            if (!e.getValue().isAir() && !before.equals(e.getValue()) && !p.level().getEntities(null, new AABB(base.offset(e.getKey()))).isEmpty())
                throw new IllegalArgumentException("Move occupants out of the module footprint first");
        }
        var build = BuildJournal.begin(p.level(), "station-room", List.of(s.getStringOr("id", "")), out.keySet().stream().map(base::offset).toList());
        build.step(() ->
        {
            s.putString("buildJournal", build.fileName());
            s.put("stationBuild", encode(new StationBlueprint.Plan(out, oldCore, plan.docks())));
            s.putString("buildReceipt", token);
            s.put("piecesBefore", pieces.copy());
            for (var room : additions)
                pieces.putString(room.x() + "_" + room.z(), room.encode());
            s.put("roomPieces", pieces);
            s.putBoolean("buildPaid", false);
            s.putBoolean("foundation", false);
            WorldStore.get(p.level().getServer()).requestSave();
            redeem(p, stack, token, generate ? additions.size() : 1);
            return null;
        });
    }

    public static void expand(ServerPlayer player, CompoundTag station)
    {
        StationAccess.requireBuilder(player, station);
        if (!StationAccess.station(station) || !StationAccess.ready(station) || TransferLocks.busy(player.level().getServer(), station))
            throw new IllegalArgumentException("Finish station construction and travel before expanding");
        if (station.getIntOr("width", 32) == 128 && station.getIntOr("depth", 32) == 128)
            return;
        var level = player.level();
        var base = WorldStore.pos(station, "base");
        var oldBounds = WorldStore.bounds(station);
        var bounds = new HullScan.Bounds(base, base.offset(127, 31, 127));
        for (var tag : WorldStore.get(level.getServer()).ships())
        {
            var other = (CompoundTag) tag;
            if (other != station && other.getStringOr("dimension", "").equals(station.getStringOr("dimension", "")) && WorldStore.bounds(other).box().intersects(bounds.box()))
                throw new IllegalArgumentException("Station expansion overlaps another registered vessel");
        }
        for (int x = base.getX() >> 4; x <= bounds.max().getX() >> 4; x++)
            for (int z = base.getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
                level.getChunk(x, z);
        for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
            if (!oldBounds.contains(at) && !level.getBlockState(at).isAir())
                throw new IllegalArgumentException("Expansion is obstructed at " + at.toShortString() + "; existing blocks are preserved");
        if (level.getEntities(null, bounds.box()).stream().anyMatch(e -> !oldBounds.box().contains(e.position())))
            throw new IllegalArgumentException("Move occupants out of the new station build area before expanding");
        com.nullandkale.nullspace.gameplay.travel.RecordJournal.run(level.getServer(), "expand-station", () ->
        {
            SystemsUpgrade.resizeStation(level, station, 128, 128);
            return null;
        });
    }

    public static void tickBuilds(MinecraftServer server)
    {
        var progress = ServerContext.get(server).service(StationService.PROGRESS, HashMap::new);
        var store = WorldStore.get(server);
        // Explicit player work goes first; construction is confined to its reserved station area.
        for (var tag : store.ships().stream().sorted(Comparator.comparing(t -> ((CompoundTag) t).getBooleanOr("worldGenerated", false))).toList())
        {
            var s = (CompoundTag) tag;
            if (!s.contains("stationBuild") || !s.getBooleanOr("buildPaid", true))
                continue;
            var level = WorldStore.level(server, s.getStringOr("dimension", ""));
            StationSpace.chunks(level, s);
            if (!s.contains("buildJournal"))
            {
                if (TransferLocks.busy(server, s))
                    continue;
                var base = WorldStore.pos(s, "base");
                var positions = s.getListOrEmpty("stationBuild").stream().map(t -> base.offset(WorldStore.pos((CompoundTag) t, "pos"))).toList();
                var migrated = BuildJournal.begin(level, "station-legacy-build", List.of(s.getStringOr("id", "")), positions);
                migrated.step(() ->
                {
                    s.putString("buildJournal", migrated.fileName());
                    return null;
                });
            }
            var build = BuildJournal.resume(level, s.getStringOr("buildJournal", ""));
            build.step(() ->
            {
                var base = WorldStore.pos(s, "base");
                var blocks = s.getListOrEmpty("stationBuild");
                String id = s.getStringOr("id", "");
                int index = progress.getOrDefault(id, 0);
                if (s.getBooleanOr("stationRegeneration", false))
                {
                    var occupants = level.getEntities(null, WorldStore.bounds(s).box());
                    if (occupants.stream().anyMatch(e -> e instanceof ServerPlayer))
                        return null;
                    if (index == 0)
                        for (var entity : occupants)
                            entity.discard();
                }

                StationService.PLACING.set(true);
                try
                {
                    int end = Math.min(blocks.size(), index + 384);
                    for (; index < end; index++)
                    {
                        var e = blocks.getCompound(index).orElseThrow();
                        var at = base.offset(WorldStore.pos(e, "pos"));
                        var state = NbtUtils.readBlockState(level.registryAccess().lookupOrThrow(Registries.BLOCK), e.getCompoundOrEmpty("state"));
                        if (s.getBooleanOr("stationRegeneration", false) && state.isAir() && level.getBlockEntity(at) instanceof net.minecraft.world.Container container)
                            container.clearContent();
                        level.setBlock(at, state, 18);
                    }
                }
                finally
                {
                    StationService.PLACING.set(false);
                }
                progress.put(id, index);
                if (index < blocks.size())
                    return null;
                int salvageGenerators = s.contains("encounterKind") ? (int) blocks.stream().filter(t -> level.getBlockState(base.offset(WorldStore.pos((CompoundTag) t, "pos"))).is(ModBlocks.GENERATOR)).count() : 0;
                int salvageFuel = 8;
                for (var entry : blocks)
                {
                    var block = (CompoundTag) entry;
                    var position = base.offset(WorldStore.pos(block, "pos"));
                    if (s.contains("encounterKind") && level.getBlockState(position).is(ModBlocks.GENERATOR))
                    {
                        int share = salvageGenerators == 0 ? 0 : (salvageFuel + salvageGenerators - 1) / salvageGenerators;
                        FuelSystem.provision(level, position, share);
                        salvageFuel -= share;
                        salvageGenerators--;
                    }
                    else if (!s.contains("encounterKind"))
                        FuelSystem.provision(level, position, 64);
                }
                if (!ShipSystems.enabled(s))
                    SystemsUpgrade.upgrade(level, s);
                if (!s.getBooleanOr("stationInitialized", false))
                {
                    for (var device : s.getCompoundOrEmpty("systems").getListOrEmpty("devices"))
                    {
                        var d = (CompoundTag) device;
                        if (d.getStringOr("type", "").equals("nullspace:battery"))
                            d.putInt("energy", 1200);
                    }
                    // Designed rooms keep their containers empty (only a salvage site marks some to stock); their frames, stands, paintings and animals appear once, here.
                    if (s.getBooleanOr("generatedRooms", false))
                        com.nullandkale.nullspace.gameplay.shipyard.RoomEntities.spawn(level, base, com.nullandkale.nullspace.worldgen.encounters.SitePoints.read(s));
                    if (s.contains("encounterKind"))
                        EncounterSites.stock(level, s);
                    else if (!s.getBooleanOr("generatedRooms", false) && s.getStringOr("owner", "").isEmpty())
                    {
                        for (var at : BlockPos.betweenClosed(WorldStore.bounds(s).min(), WorldStore.bounds(s).max()))
                            if ((level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity || level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity) && level.getBlockEntity(at) instanceof net.minecraft.world.Container container)
                            {
                                container.clearContent();
                                container.setItem(0, new ItemStack(Items.QUARTZ_BLOCK, 32));
                                container.setItem(1, new ItemStack(Items.IRON_INGOT, 16));
                                container.setChanged();
                                break;
                            }
                    }
                    s.putBoolean("stationInitialized", true);
                }
                s.getCompoundOrEmpty("systems").putIntArray("requests", new int[]{1, SpatialAir.SUPPLY_POWER, 1, 0, 2, 0});
                ShipSystems.changed(level, base, false);
                s.remove("stationBuild");
                s.remove("buildJournal");
                s.remove("piecesBefore");
                s.remove("stationRegeneration");
                com.nullandkale.nullspace.gameplay.ship.ShipIntegrity.stamp(level, WorldStore.pos(s, "controller"), id);
                progress.remove(id);
                if (s.getBooleanOr("foundation", false))
                    StationConstruction.connectConstruction(server, s);
                else
                    StationConstruction.commissionConstruction(server, s);
                return null;
            });
            if (!s.contains("stationBuild"))
                build.commit();
            return;
        }
    }

    private StationBuilds()
    {
    }
}
