package com.nullandkale.nullspace;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.content.machine.ControllerBlockEntity;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.VersionCommand;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.dev.Authoring;
import com.nullandkale.nullspace.dev.PlaytestWorld;
import com.nullandkale.nullspace.dev.ShipLab;
import com.nullandkale.nullspace.gameplay.combat.ShipCombat;
import com.nullandkale.nullspace.gameplay.computer.ComputerService;
import com.nullandkale.nullspace.gameplay.crew.LifeSupport;
import com.nullandkale.nullspace.gameplay.crew.PlayerRescue;
import com.nullandkale.nullspace.gameplay.crew.ShipRespawns;
import com.nullandkale.nullspace.gameplay.docking.DockingService;
import com.nullandkale.nullspace.gameplay.navigation.CourseService;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.PlanetSurfaces;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.StarSystems;
import com.nullandkale.nullspace.gameplay.ownership.ShipStorage;
import com.nullandkale.nullspace.gameplay.ownership.ShipTitles;
import com.nullandkale.nullspace.gameplay.pads.BuildPreviews;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.scene.TerrainService;
import com.nullandkale.nullspace.gameplay.ship.ShipDiagnostics;
import com.nullandkale.nullspace.gameplay.ship.ShipIntegrity;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.shipyard.RandomYard;
import com.nullandkale.nullspace.gameplay.shipyard.RoomGallery;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYard;
import com.nullandkale.nullspace.gameplay.shipyard.ShipYardService;
import com.nullandkale.nullspace.gameplay.shipyard.StationGallery;
import com.nullandkale.nullspace.gameplay.start.SpaceStart;
import com.nullandkale.nullspace.gameplay.station.StationService;
import com.nullandkale.nullspace.gameplay.systems.AirEffects;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.worldgen.encounters.SiteThreats;
import com.nullandkale.nullspace.worldgen.encounters.SpacePopulation;
import com.nullandkale.nullspace.worldgen.sites.TerraHarbor;
import com.nullandkale.nullspace.worldgen.sites.VillagePorts;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NullSpace implements ModInitializer
{
    public static final String MOD_ID = "nullspace";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize()
    {
        LOGGER.info("Beginning NullSpace Initialization");
        VersionCommand.initialize();
        ComputerTerminal.initialize();
        ShipControl.initialize();
        PadComputers.initialize();
        BuildPreviews.initialize();
        ModBlocks.initialize();
        ControllerBlockEntity.initialize();
        SiteThreats.initialize();
        VillagePorts.initialize();
        MachineMenus.initialize();
        FieldGuide.initialize();
        ComputerService.initialize();
        ShipService.initialize();
        ShipDiagnostics.initialize();
        ShipIntegrity.initialize();
        TravelService.initialize();
        ShipStorage.initialize();
        PlayerRescue.initialize();
        ShipRespawns.initialize();
        ShipTitles.initialize();
        TerraHarbor.initialize();
        AirEffects.initialize();
        ShipSystems.initialize();
        SceneService.initialize();
        TerrainService.initialize();
        ShipCombat.initialize();
        NavigationService.initialize();
        StarSystems.initialize();
        Authoring.initialize();
        StationService.initialize();
        DockingService.initialize();
        CourseService.initialize();
        SpacePopulation.initialize();
        PlanetSurfaces.initialize();
        LifeSupport.initialize();
        PlaytestWorld.initialize();
        ShipLab.initialize();
        ShipYardService.initialize();
        ShipYard.initialize();
        RandomYard.initialize();
        RoomGallery.initialize();
        StationGallery.initialize();
        SpaceStart.initialize();
        ServerContext.initialize();
        LOGGER.info("NullSpace Initialized");
    }
}
