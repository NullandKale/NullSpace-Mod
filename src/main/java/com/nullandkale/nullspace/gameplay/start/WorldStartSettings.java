package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Shared, world-scoped creation choices; dedicated servers use the same property names. */
public record WorldStartSettings(boolean space, String ship)
{
    public static final String FILE = "nullspace/world-start.properties";
    public static final WorldStartSettings DEFAULT = new WorldStartSettings(false, "sparrow");

    public WorldStartSettings
    {
        if (ShipBlueprints.PRESETS.stream().noneMatch(p -> p.id().equals(ship)))
            throw new IllegalArgumentException("Unknown starting ship: " + ship);
    }

    public static WorldStartSettings read(Path file) throws IOException
    {
        if (!Files.exists(file))
            return DEFAULT;
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file))
        {
            properties.load(reader);
        }
        return from(properties);
    }

    public static WorldStartSettings from(Properties properties)
    {
        return new WorldStartSettings(Boolean.parseBoolean(properties.getProperty("nullspace-space-start", "false").trim()), properties.getProperty("nullspace-starting-ship", "sparrow").trim());
    }

    public void write(Path worldRoot) throws IOException
    {
        var file = worldRoot.resolve(FILE);
        Files.createDirectories(file.getParent());
        var properties = new Properties();
        properties.setProperty("nullspace-space-start", Boolean.toString(space));
        properties.setProperty("nullspace-starting-ship", ship);
        try (var writer = Files.newBufferedWriter(file))
        {
            properties.store(writer, "NullSpace world start");
        }
    }
}
