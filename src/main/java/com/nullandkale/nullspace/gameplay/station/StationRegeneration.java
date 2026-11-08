package com.nullandkale.nullspace.gameplay.station;

import com.nullandkale.nullspace.NullSpace;
import com.nullandkale.nullspace.core.save.ShipRepository;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
import com.nullandkale.nullspace.worldgen.stations.ManufacturerStation;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;
import com.nullandkale.nullspace.worldgen.stations.StationLayout;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import com.google.gson.*;

/** Explicit, backed-up, world-local rebuild requests. Normal station visits never rebuild. */
public final class StationRegeneration
{
    public static Path request(MinecraftServer server)
    {
        return server.getWorldPath(LevelResource.ROOT).resolve("nullspace/station-regeneration-request.json");
    }

    public static String sha(Path file) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** Persist the ordinary replayable construction jobs before touching station blocks. */
    public static int applyPending(MinecraftServer server) throws Exception
    {
        var file = request(server);
        if (!Files.exists(file))
            return 0;
        if (Files.size(file) > 65536)
            throw new IllegalArgumentException("Station regeneration request is too large");
        var request = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (request.get("version").getAsInt() != 1)
            throw new IllegalArgumentException("Unknown regeneration request version");
        String job = UUID.fromString(request.get("id").getAsString()).toString();
        var ids = new LinkedHashSet<String>();
        for (var id : request.getAsJsonArray("stations"))
        {
            if (!ids.add(UUID.fromString(id.getAsString()).toString()))
                throw new IllegalArgumentException("Duplicate station in regeneration request");
        }
        if (ids.isEmpty() || ids.size() > 32)
            throw new IllegalArgumentException("Choose 1..32 stations to regenerate");
        var store = WorldStore.get(server);
        var stations = store.ships().stream().map(t -> (CompoundTag) t).filter(s -> ids.contains(s.getStringOr("id", ""))).toList();
        if (stations.size() != ids.size() || stations.stream().anyMatch(s -> !StationAccess.station(s)))
            throw new IllegalArgumentException("A requested station is missing or is a ship");
        var archive = file.resolveSibling("station-regeneration-queued-" + job + ".json");
        if (stations.stream().allMatch(s -> s.getStringOr("stationRegenerationRequest", "").equals(job)))
        {
            Files.move(file, archive, StandardCopyOption.REPLACE_EXISTING);
            return 0;
        }
        String expected = request.get("sourceSha256").getAsString();
        var source = file.resolveSibling("ships.nbt");
        var backup = Path.of(request.get("backup").getAsString()).resolve("nullspace/ships.nbt");
        if (!expected.matches("[a-f0-9]{64}") || !sha(source).equals(expected) || !Files.exists(backup) || !sha(backup).equals(expected))
            throw new IllegalArgumentException("World or backup changed since regeneration was requested; no rebuild was queued");
        if (TransferLocks.active(server) || NavigationService.pending(server))
            throw new IllegalArgumentException("Finish travel before station regeneration");
        for (var s : stations)
        {
            if (!StationAccess.ready(s) || !s.getListOrEmpty("crew").isEmpty() || !s.getCompoundOrEmpty("dockLinks").isEmpty() || s.contains("dock"))
                throw new IllegalArgumentException("Return crew and undock visiting ships before regenerating " + s.getStringOr("home", "station"));
            for (var tag : store.ships())
            {
                var dock = ((CompoundTag) tag).getCompoundOrEmpty("dock");
                if (ids.contains(dock.getStringOr("target", "")) || ids.contains(dock.getStringOr("source", "")))
                    throw new IllegalArgumentException("A docking maneuver still refers to a requested station");
            }
        }
        var before = store.snapshot();
        var next = before.copy();
        for (var original : stations)
        {
            var s = next.getListOrEmpty("ships").stream().map(t -> (CompoundTag) t).filter(t -> t.getStringOr("id", "").equals(original.getStringOr("id", ""))).findFirst().orElseThrow();
            var level = WorldStore.level(server, s.getStringOr("dimension", ""));
            StationSpace.chunks(level, s);
            var base = WorldStore.pos(s, "base");
            var bounds = WorldStore.bounds(s);
            if (s.getIntOr("width", 32) < 32 || s.getIntOr("depth", 32) < 32)
                throw new IllegalArgumentException("Station build area is smaller than the current starter template");
            boolean showcase = s.getStringOr("template", "").equals("manufacturer_showcase_v1");
            var layout = s.getIntOr("width", 32) >= 128 && s.getIntOr("depth", 32) >= 128 ? StationLayout.harbor() : StationLayout.waypoint();
            var plan = showcase ? ManufacturerStation.build() : StationBlueprint.build(layout);
            var clear = new LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
            for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
                if (!level.getBlockState(at).isAir())
                    clear.put(at.subtract(base), Blocks.AIR.defaultBlockState());
            var blocks = StationBuilds.encode(new StationBlueprint.Plan(clear, plan.core(), List.of()));
            blocks.addAll(StationBuilds.encode(plan));
            for (String key : List.of("systems", "doorNodes", "dockLinks", "piecesBefore", "constructionShip", "constructionSourcePort", "stationInitialized", "flightStatus"))
                s.remove(key);
            s.putLong("controller", base.offset(plan.core()).asLong());
            s.putBoolean("foundation", false);
            s.putBoolean("buildPaid", true);
            s.putBoolean("stationRegeneration", true);
            s.putString("stationRegenerationRequest", job);
            s.put("stationBuild", blocks);
            var pieces = new CompoundTag();
            if (!showcase)
                for (var room : layout.rooms())
                    pieces.putString(room.x() + "_" + room.z(), room.role().name());
            s.put("roomPieces", pieces);
        }
        try
        {
            store.replace(next, ShipRepository.Mutation.RECOVERY);
            store.save();
        }
        catch (RuntimeException error)
        {
            store.replace(before, ShipRepository.Mutation.RECOVERY);
            throw error;
        }
        Files.move(file, archive, StandardCopyOption.REPLACE_EXISTING);
        NullSpace.LOGGER.info("Queued regeneration of {} stations; backup: {}", stations.size(), request.get("backup").getAsString());
        return stations.size();
    }

    private StationRegeneration()
    {
    }
}
