package com.nullandkale.nullspace.core.save;

import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import com.google.gson.*;

/** One cached registration document per server. External changes use an explicit reload. */
public final class PadRepository
{
    private static final ServerContext.Key<PadRepository> KEY = new ServerContext.Key<>();
    private final Path path;
    private List<PadRegistry.Pad> pads = List.of();
    private Map<String, PadRegistry.Pad> ids = Map.of();
    private Map<String, List<PadRegistry.Pad>> dimensions = Map.of();

    private record Controller(String dimension, long position)
    {}

    private Map<Controller, PadRegistry.Pad> controllers = Map.of();

    public PadRegistry.Pad controller(String dimension, BlockPos position)
    {
        return controllers.get(new Controller(dimension, position.asLong()));
    }

    private long reads, revision;

    public static PadRepository get(MinecraftServer server)
    {
        return ServerContext.get(server).service(KEY, () -> new PadRepository(server));
    }

    private PadRepository(MinecraftServer server)
    {
        path = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-pads.json");
        try
        {
            read();
        }
        catch (IOException | RuntimeException error)
        {
            loadPrevious(error);
        }
    }

    public List<PadRegistry.Pad> all()
    {
        return pads;
    }

    public List<PadRegistry.Pad> dimension(String id)
    {
        return dimensions.getOrDefault(id, List.of());
    }

    public PadRegistry.Pad id(String id)
    {
        return ids.get(id);
    }

    public long reads()
    {
        return reads;
    }

    public long revision()
    {
        return revision;
    }

    private Path previous()
    {
        return path.resolveSibling("nullspace-pads.previous.json");
    }

    /** Opens the registry the way a server start does: a registry that cannot be read is replaced by the previous copy. */
    public static PadRepository reopen(MinecraftServer server)
    {
        return new PadRepository(server);
    }

    /**
     * Reads the registry again after an operator edited it. A registry that cannot be read is an error and changes
     * nothing: the server keeps the pads it is running with.
     */
    public void reload()
    {
        try
        {
            read();
        }
        catch (IOException | RuntimeException error)
        {
            throw new IllegalStateException("Cannot read pad registry", error);
        }
    }

    private void read() throws IOException
    {
        reads++;
        // A registry that was never written is empty. One that is missing while its previous copy exists was lost mid-write.
        if (!Files.exists(path) && Files.exists(previous()))
            throw new IOException("nullspace-pads.json is missing but its previous copy exists");
        index(Files.exists(path) ? parse(path) : List.of());
    }

    private boolean readable(Path file)
    {
        try
        {
            parse(file);
            return true;
        }
        catch (IOException | RuntimeException error)
        {
            return false;
        }
    }

    /** The pads in a registry file. */
    private List<PadRegistry.Pad> parse(Path file) throws IOException
    {
        var parsed = new ArrayList<PadRegistry.Pad>();
        for (var element : JsonParser.parseString(Files.readString(file)).getAsJsonArray())
        {
            var v = element.getAsJsonObject();
            parsed.add(new PadRegistry.Pad(v.get("id").getAsString(), v.get("name").getAsString(), v.get("dimension").getAsString(), BlockPos.of(v.get("computer").getAsLong()), new PadScanner.Pad(v.get("minX").getAsInt(), v.get("maxX").getAsInt(), v.get("y").getAsInt(), v.get("minZ").getAsInt(), v.get("maxZ").getAsInt())));
        }
        return parsed;
    }

    /**
     * The registry cannot be read. It is kept under another name, never deleted, and the previous copy (one registration
     * behind) is loaded and written back as the registry, so players only re-register what the lost write held.
     */
    private void loadPrevious(Exception error)
    {
        if (!Files.exists(previous()))
            throw new IllegalStateException("Cannot read pad registry", error);
        try
        {
            var recovered = parse(previous());
            index(recovered);
            if (Files.exists(path))
            {
                var kept = path.resolveSibling("nullspace-pads.json.damaged-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                Files.move(path, kept, StandardCopyOption.REPLACE_EXISTING);
                com.nullandkale.nullspace.NullSpace.LOGGER.error("The pad registry could not be read ({}). It was kept as {} and the previous copy was loaded.", error.toString(), kept.getFileName());
            }
            replace(recovered);
        }
        catch (IOException | RuntimeException second)
        {
            throw new IllegalStateException("Cannot read the pad registry or its previous copy", error);
        }
    }

    private void index(List<PadRegistry.Pad> next)
    {
        var byId = new HashMap<String, PadRegistry.Pad>();
        var byDimension = new HashMap<String, List<PadRegistry.Pad>>();
        var byController = new HashMap<Controller, PadRegistry.Pad>();
        for (var pad : next)
        {
            if (byId.put(pad.id(), pad) != null || pad.id().isBlank() || pad.dimension().isBlank() || byController.put(new Controller(pad.dimension(), pad.computer().asLong()), pad) != null || pad.bounds().width() < 1 || pad.bounds().width() > 32 || pad.bounds().depth() < 1 || pad.bounds().depth() > 32)
                throw new IllegalArgumentException("Invalid pad registration");
            byDimension.computeIfAbsent(pad.dimension(), k -> new ArrayList<>()).add(pad);
        }
        byDimension.replaceAll((k, v) -> List.copyOf(v));
        pads = List.copyOf(next);
        ids = Map.copyOf(byId);
        dimensions = Map.copyOf(byDimension);
        controllers = Map.copyOf(byController);
        revision++;
    }

    public void replace(List<PadRegistry.Pad> next) throws IOException
    {
        var json = new JsonArray();
        for (var pad : next)
        {
            var v = new JsonObject();
            v.addProperty("id", pad.id());
            v.addProperty("name", pad.name());
            v.addProperty("dimension", pad.dimension());
            v.addProperty("computer", pad.computer().asLong());
            var b = pad.bounds();
            v.addProperty("minX", b.minX());
            v.addProperty("maxX", b.maxX());
            v.addProperty("y", b.y());
            v.addProperty("minZ", b.minZ());
            v.addProperty("maxZ", b.maxZ());
            json.add(v);
        }
        // Validate before touching the durable document, but publish only after promotion.
        var oldPads = pads;
        var oldIds = ids;
        var oldDimensions = dimensions;
        var oldControllers = controllers;
        long oldRevision = revision;
        try
        {
            index(next);
        }
        finally
        {
            pads = oldPads;
            ids = oldIds;
            dimensions = oldDimensions;
            controllers = oldControllers;
            revision = oldRevision;
        }
        var temporary = path.resolveSibling("nullspace-pads.json.tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        // The registry being replaced stays as the previous copy, so a damaged new one can be recovered from it.
        // Only a registry that can be read: a damaged one must never replace the good previous copy.
        if (Files.exists(path) && readable(path))
            Files.copy(path, previous(), StandardCopyOption.REPLACE_EXISTING);
        try
        {
            AtomicFiles.promote(temporary, path);
        }
        catch (AtomicMoveNotSupportedException fallback)
        {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        index(next);
    }
}
