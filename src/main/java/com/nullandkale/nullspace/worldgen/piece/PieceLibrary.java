package com.nullandkale.nullspace.worldgen.piece;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * Loads templates bundled with the mod, {@code data/nullspace/structure/<kind>/<name>.nbt}, by the
 * id {@code <kind>/<name>}. Lookup order: the authoring folder of a development run
 * ({@code -Dnullspace.authoringDir}), then templates saved in the running world, then the copy
 * bundled in the JAR, so an in-game edit shows without a rebuild.
 */
public final class PieceLibrary
{
    private static final Map<String, Optional<CompoundTag>> FILES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<TemplateCodec.Template>> TEMPLATES = new ConcurrentHashMap<>();
    private static final Map<String, Piece> PIECES = new ConcurrentHashMap<>();
    private static volatile java.util.function.Function<String, Optional<CompoundTag>> world = id -> Optional.empty();

    /** Templates saved in the running world (the server's structure manager); reset when it stops. */
    public static void worldSource(java.util.function.Function<String, Optional<CompoundTag>> source)
    {
        world = source == null ? id -> Optional.empty() : source;
        invalidate();
    }

    public static Optional<TemplateCodec.Template> template(String id)
    {
        return TEMPLATES.computeIfAbsent(id, key -> file(key).map(TemplateCodec::read));
    }

    /** A template's size, read without resolving its blocks (works before registries exist). */
    public static Optional<Vec3i> size(String id)
    {
        return file(id).map(TemplateCodec::size);
    }

    public static boolean exists(String id)
    {
        return template(id).isPresent();
    }

    public static Piece piece(String id)
    {
        return PIECES.computeIfAbsent(id, key -> Piece.of(key, template(key).orElseThrow(() -> new IllegalArgumentException("No template " + key))));
    }

    /** Forgets loaded templates; called after a template is saved in game. */
    public static void invalidate()
    {
        FILES.clear();
        TEMPLATES.clear();
        PIECES.clear();
    }

    /** The file a template is saved to in the authoring folder, or empty outside development. */
    public static Optional<Path> authoringFile(String id)
    {
        String dir = System.getProperty("nullspace.authoringDir");
        if (dir == null || dir.isBlank())
            return Optional.empty();
        return Optional.of(Path.of(dir, "data", "nullspace", "structure", id + ".nbt"));
    }

    static void checkId(String id)
    {
        if (!id.matches("[a-z0-9_]{1,32}(/[a-z0-9_]{1,48})+"))
            throw new IllegalArgumentException("Template ids are kind/name in lowercase letters, digits and _: " + id);
    }

    private static Optional<CompoundTag> file(String id)
    {
        return FILES.computeIfAbsent(id, PieceLibrary::load);
    }

    private static Optional<CompoundTag> load(String id)
    {
        checkId(id);
        try
        {
            var file = authoringFile(id);
            if (file.isPresent() && Files.isRegularFile(file.get()))
                try (var in = Files.newInputStream(file.get()))
                {
                    return Optional.of(TemplateCodec.readFile(in));
                }
            var saved = world.apply(id);
            if (saved.isPresent())
                return saved;
            try (var in = PieceLibrary.class.getResourceAsStream("/data/nullspace/structure/" + id + ".nbt"))
            {
                return in == null ? Optional.empty() : Optional.of(TemplateCodec.readFile(in));
            }
        }
        catch (IOException failure)
        {
            throw new UncheckedIOException("Could not read template " + id, failure);
        }
    }

    private PieceLibrary()
    {
    }
}
