package com.nullandkale.nullspace.gameplay.start;

import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import java.nio.file.*;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorldStartSettingsTest
{
    @TempDir
    Path directory;

    @Test
    void absentSettingsKeepVanillaStart() throws Exception
    {
        assertEquals(WorldStartSettings.DEFAULT, WorldStartSettings.read(directory.resolve("missing.properties")));
        assertEquals(WorldStartSettings.DEFAULT, WorldStartSettings.from(new Properties()));
    }

    @Test
    void serverOptionEnablesAShipWithoutRequiringItsName()
    {
        var properties = new Properties();
        properties.setProperty("nullspace-space-start", "true");
        assertEquals(new WorldStartSettings(true, ShipBlueprints.DEFAULT_STARTER), WorldStartSettings.from(properties));
    }

    @Test
    void eachSelectableShipRoundTripsWithTheWorld() throws Exception
    {
        for (var ship : ShipBlueprints.PRESETS)
        {
            var settings = new WorldStartSettings(true, ship.id());
            settings.write(directory);
            assertEquals(settings, WorldStartSettings.read(directory.resolve(WorldStartSettings.FILE)));
        }
    }

    @Test
    void aCrashStartIsAlwaysAShipStartAndRoundTripsWithTheWorld() throws Exception
    {
        var properties = new Properties();
        properties.setProperty("nullspace-crash-start", "true");
        var settings = WorldStartSettings.from(properties);
        assertTrue(settings.crash() && settings.space(), "A crash start implies a ship start even when the space option is absent");
        settings.write(directory);
        assertEquals(settings, WorldStartSettings.read(directory.resolve(WorldStartSettings.FILE)));
        assertFalse(WorldStartSettings.DEFAULT.crash());
        assertFalse(new WorldStartSettings(true, ShipBlueprints.DEFAULT_STARTER).crash(), "An ordinary ship start does not crash");
    }

    @Test
    void theCrashModeAndPlanetRoundTripAndDefaultToSingleAndRandom() throws Exception
    {
        assertFalse(WorldStartSettings.DEFAULT.multi());
        assertEquals("random", WorldStartSettings.DEFAULT.planet());
        var properties = new Properties();
        properties.setProperty("nullspace-crash-start", "true");
        properties.setProperty("nullspace-crash-mode", "multi");
        properties.setProperty("nullspace-starting-planet", "Lava");
        var settings = WorldStartSettings.from(properties);
        assertTrue(settings.multi() && settings.crash());
        assertEquals("lava", settings.planet());
        settings.write(directory);
        assertEquals(settings, WorldStartSettings.read(directory.resolve(WorldStartSettings.FILE)));
        properties.setProperty("nullspace-starting-planet", "mars");
        assertThrows(IllegalArgumentException.class, () -> WorldStartSettings.from(properties));
    }

    @Test
    void invalidShipConfigurationIsReportedInsteadOfChangingTheChoice()
    {
        var properties = new Properties();
        properties.setProperty("nullspace-starting-ship", "missing");
        var error = assertThrows(IllegalArgumentException.class, () -> WorldStartSettings.from(properties));
        assertTrue(error.getMessage().contains("missing"));
    }
}
