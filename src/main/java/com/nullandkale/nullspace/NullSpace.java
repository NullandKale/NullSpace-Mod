package com.nullandkale.nullspace;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NullSpace implements ModInitializer {
  public static final String MOD_ID = "nullspace";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitialize() {
    com.nullandkale.nullspace.terminal.ComputerTerminal.initialize();
    com.nullandkale.nullspace.pad.PadComputers.initialize();
    com.nullandkale.nullspace.pad.PreviewService.initialize();
    com.nullandkale.nullspace.ship.ShipBlocks.initialize();
    com.nullandkale.nullspace.guide.FieldGuide.initialize();
    com.nullandkale.nullspace.computer.ComputerService.initialize();
    com.nullandkale.nullspace.ship.ShipService.initialize();
    com.nullandkale.nullspace.ship.ShipDiagnostics.initialize();
    com.nullandkale.nullspace.ship.TravelService.initialize();
    com.nullandkale.nullspace.systems.AirEffects.initialize();
    com.nullandkale.nullspace.systems.ShipSystems.initialize();
    com.nullandkale.nullspace.scene.SceneService.initialize();
    com.nullandkale.nullspace.scene.TerrainService.initialize();
    com.nullandkale.nullspace.scene.ShipCombat.initialize();
    com.nullandkale.nullspace.navigation.NavigationService.initialize();
    com.nullandkale.nullspace.navigation.PlanetSurfaces.initialize();
    com.nullandkale.nullspace.ship.SpaceSurvival.initialize();
    com.nullandkale.nullspace.dev.PlaytestWorld.initialize();
    com.nullandkale.nullspace.dev.ShipLab.initialize();
    com.nullandkale.nullspace.ship.ShipStore.initialize();
    com.nullandkale.nullspace.shipyard.ShipYardService.initialize();
    com.nullandkale.nullspace.shipyard.ShipYard.initialize();
    com.nullandkale.nullspace.server.ServerContext.initialize();
    LOGGER.info("NullSpace initialized");
  }
}
