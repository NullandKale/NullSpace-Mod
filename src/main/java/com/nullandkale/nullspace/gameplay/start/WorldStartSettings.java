package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Shared, world-scoped creation choices; dedicated servers use the same property names. {@code space} starts the player aboard a
 * ship in orbit; {@code crash} starts them in the same ship after it has crash-landed on Terra, with its machines broken (a crash
 * start is always a ship start).
 */
public record WorldStartSettings(boolean space, String ship, boolean crash, boolean multi, String planet)
{

    public static final String FILE = "nullspace/world-start.properties";
    /** A server with no start setting, and a world without the settings file: the ordinary Minecraft start. */
    public static final WorldStartSettings DEFAULT = new WorldStartSettings(false, ShipBlueprints.DEFAULT_STARTER);
    /** What Create New World offers first: the tiny starter, crash-landed on Terra, one wreck for everyone. */
    public static final WorldStartSettings NEW_WORLD = new WorldStartSettings(true, ShipBlueprints.DEFAULT_STARTER, true, false, "terra");

    public WorldStartSettings
    {
        if (ShipBlueprints.PRESETS.stream().noneMatch(p -> p.id().equals(ship)))
            throw new IllegalArgumentException("Unknown starting ship: " + ship);
        space = space || crash;
        if (!StartPlanets.valid(planet))
            throw new IllegalArgumentException("Unknown starting planet: " + planet);
    }

    public WorldStartSettings(boolean space, String ship, boolean crash)
    {
        this(space, ship, crash, false, "random");
    }

    public WorldStartSettings(boolean space, String ship)
    {
        this(space, ship, false);
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
        return new WorldStartSettings(Boolean.parseBoolean(properties.getProperty("nullspace-space-start", "false").trim()), properties.getProperty("nullspace-starting-ship", ShipBlueprints.DEFAULT_STARTER).trim(), Boolean.parseBoolean(properties.getProperty("nullspace-crash-start", "false").trim()), properties.getProperty("nullspace-crash-mode", "single").trim().equalsIgnoreCase("multi"), properties.getProperty("nullspace-starting-planet", "random").trim().toLowerCase(java.util.Locale.ROOT));
    }

    public void write(Path worldRoot) throws IOException
    {
        var file = worldRoot.resolve(FILE);
        Files.createDirectories(file.getParent());
        var properties = new Properties();
        properties.setProperty("nullspace-space-start", Boolean.toString(space));
        properties.setProperty("nullspace-starting-ship", ship);
        properties.setProperty("nullspace-crash-start", Boolean.toString(crash));
        properties.setProperty("nullspace-crash-mode", multi ? "multi" : "single");
        properties.setProperty("nullspace-starting-planet", planet);
        try (var writer = Files.newBufferedWriter(file))
        {
            properties.store(writer, "NullSpace world start");
        }
    }
}
