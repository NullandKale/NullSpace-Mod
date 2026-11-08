package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.gameplay.crew.LifeSupport;
import com.nullandkale.nullspace.ui.apps.StationCoreTerminal;
import com.nullandkale.nullspace.ui.terminal.ControllerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalInput;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;

import java.util.*;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StationExpansionTest
{
    @Test
    void stationTetherAllowsWalkingFarFromCoreButBoundsEva()
    {
        var station = new net.minecraft.nbt.CompoundTag();
        station.putString("kind", "station");
        station.putInt("width", 128);
        station.putInt("depth", 128);
        station.putInt("height", 32);
        station.putLong("base", net.minecraft.core.BlockPos.ZERO.asLong());
        station.putLong("controller", new net.minecraft.core.BlockPos(5, 3, 5).asLong());
        assertEquals(0, LifeSupport.tetherDistance(station, new Vec3(126, 3, 88)));
        assertEquals(73, LifeSupport.tetherDistance(station, new Vec3(201, 3, 88)));
    }

    @Test
    void fullStationIsAConnectedRingWithEightSeparatedBerths()
    {
        var layout = StationLayout.harbor();
        assertEquals(28, layout.rooms().size());
        assertEquals(28, layout.joints().size());
        for (var room : layout.rooms())
        {
            assertTrue(room.origin().getX() + 16 <= 128);
            assertTrue(room.origin().getZ() + 16 <= 128);
        }
    }

    @Test
    void sectionPickerAddressesRoomsBeyondOriginalFourTiles()
    {
        var terminal = new StationCoreTerminal("Station", List.of(new TerminalPackets.Destination("station_area", "4", "STATION_AREA")));
        terminal.input(new TerminalInput.Pointer(25, 4));
        terminal.input(new TerminalInput.Pointer(4, 12));
        assertEquals(new ControllerTerminal.Action("station_module", "ARRIVAL 2 0"), terminal.pollAction());
        terminal.acknowledged();
        terminal.input(new TerminalInput.Pointer(43, 12));
        assertEquals(new ControllerTerminal.Action("station_generate", ""), terminal.pollAction());
        assertTrue(terminal.display().row(12).contains("28 kits"));
    }

    @Test
    void oldStationCanRequestExpansionWithoutChangingNameOrRoom()
    {
        var terminal = new StationCoreTerminal("Old station", List.of());
        terminal.input(new TerminalInput.Pointer(50, 4));
        assertEquals(new ControllerTerminal.Action("station_expand", ""), terminal.pollAction());
        assertTrue(terminal.display().row(3).contains("Old station"));
    }
}
