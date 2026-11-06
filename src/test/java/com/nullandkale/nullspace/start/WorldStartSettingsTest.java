package com.nullandkale.nullspace.start;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.shipyard.ShipBlueprints;
import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStartSettingsTest {
  @TempDir Path directory;

  @Test
  void absentSettingsKeepVanillaStart() throws Exception {
    assertEquals(
        WorldStartSettings.DEFAULT,
        WorldStartSettings.read(directory.resolve("missing.properties")));
    assertEquals(WorldStartSettings.DEFAULT, WorldStartSettings.from(new Properties()));
  }

  @Test
  void serverOptionEnablesAShipWithoutRequiringItsName() {
    var properties = new Properties();
    properties.setProperty("nullspace-space-start", "true");
    assertEquals(new WorldStartSettings(true, "sparrow"), WorldStartSettings.from(properties));
  }

  @Test
  void eachSelectableShipRoundTripsWithTheWorld() throws Exception {
    for (var ship : ShipBlueprints.PRESETS) {
      var settings = new WorldStartSettings(true, ship.id());
      settings.write(directory);
      assertEquals(settings, WorldStartSettings.read(directory.resolve(WorldStartSettings.FILE)));
    }
  }

  @Test
  void invalidShipConfigurationIsReportedInsteadOfChangingTheChoice() {
    var properties = new Properties();
    properties.setProperty("nullspace-starting-ship", "missing");
    var error =
        assertThrows(IllegalArgumentException.class, () -> WorldStartSettings.from(properties));
    assertTrue(error.getMessage().contains("missing"));
  }
}
